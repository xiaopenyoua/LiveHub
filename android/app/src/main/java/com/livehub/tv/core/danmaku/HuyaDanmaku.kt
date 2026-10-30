package com.livehub.tv.core.danmaku

import android.util.Log
import com.livehub.tv.core.danmaku.tars.TarsReader
import com.livehub.tv.core.danmaku.tars.TarsWriter
import com.livehub.tv.core.model.Platform
import com.livehub.tv.core.model.logFields
import com.livehub.tv.core.net.Net
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 虎牙弹幕。
 *
 * ## 链路
 * 1. 用 `mp.huya.com/cache.php?m=Live&do=profileRoom&roomid={房间号}&showSecret=1`
 *    换房间信息：`data.liveStatus` 判断是否在播，`data.stream.baseSteamInfoList[0]`
 *    里的 `lChannelId` 才是弹幕要的 topSid（**不是房间号**，例如房间 660000 的
 *    topSid 是 2272316519），`lSubChannelId` 是子频道，`profileInfo.uid` 是主播 uid。
 * 2. 连 `wss://wsapi.huya.com`（二进制帧，不用 text）。
 * 3. onOpen 立刻发两条：命令 16「注册组」+ 命令 20「心跳」，之后每 60s 一条心跳。
 * 4. 收包是 Tars 信封：字段 0 是信封类型，字段 1 是包体。类型 7 = 单条推送，
 *    类型 22 = 打包推送（包体是 groupId + list<推送项>）。
 *
 * ## 推送 uri（都用真机抓包验证过字段位置）
 * - 1400 聊天：`HYMessage{ 0: HYSender{0: uid, 2: nick}, 3: content, 6: HYBulletFormat{0: fontColor} }`
 * - 8006 人气值：包体是单个 int（`iAttendeeCount`）
 * - 8001 主播变更：包体是单个 int（当前主播 uid）。**为 0 或等于本房间主播 uid
 *   就是「直播已结束」**——这是虎牙下播的实际信号，文档里说的「靠心跳超时判断」
 *   并不成立，心跳超时只说明网络断了。
 *
 * ## 故意没做的两件事（都是有证据但字段不全，猜错会在屏幕上显示错内容）
 * - **礼物（uri 6501）**：包体里能确定读到礼物名（字段 20，实测 '虎粮'），但发送者
 *   昵称有两个候选字段（5 和 6，同一条包里是两个不同的人名），数量字段也没解出来。
 *   猜错会显示「某某送出 X」，送错人比不显示更糟，所以这一版不播报礼物。
 * - **在线人数**：8006 的数值能解出来，但 [DanmakuEvent] 没有「在线人数」这个分支，
 *   塞进 [DanmakuEvent.System] 会变成每 30 秒一条提示把弹幕区刷满。只写日志，
 *   等契约里加了 `Online(count)` 再接。
 */
class HuyaDanmaku : DanmakuClient {

    override val platform = Platform.HUYA

    override fun stream(roomId: String): Flow<DanmakuEvent> = channelFlow {
        // 房间号不对就没必要退避重试五轮再告诉用户：直接失败更快。
        // 允许传 "660000" 这种纯数字，也允许传带数字的整串（如粘贴的 URL）。
        if (roomId.none { it.isDigit() }) {
            trySend(DanmakuEvent.Failed("虎牙房间号无效"))
            return@channelFlow
        }

        // ★ 断线策略与 DouyuDanmaku / BilibiliDanmaku 是**同一套**，改一处必须同步另两处：
        //   连续失败计数 → 连上即 attempt=0 / announced=false
        //   → 第 2 次失败且距上次成功 ≥3s 才提示一次
        //   → 到上限发 Failed 收尾。重连过程本身不发任何事件。
        // 重连必须留在本次 collect 里，绝不做共享热流：用户「关弹幕→开弹幕」
        // 是电视上唯一可靠的自助恢复手段，被 shareIn 一挡就废了。
        var attempt = 0
        // 每次「中断」只提示一次，避免正常自动重连时闪一条假的「已断开」
        var announced = false
        var everUp = false
        var lastOkAt = 0L
        // 跨线程只走原子量：onUp 跑在 OkHttp 的线程上，attempt/announced/everUp/lastOkAt
        // 只在协程线程上改。直接在这里写 attempt=0 会有可见性问题（抖动几次就误判上限）。
        val upAt = AtomicLong(0L)
        val connectedSent = AtomicBoolean(false)

        while (isActive) {
            val room = try {
                resolve(roomId)
            } catch (e: Exception) {
                // 这一处是「catch 之后立刻降级成文案」的形状，`${e.message}` 在这里代价最高：
                // `resolve` 自己一个 `throw` 都没有，异常只可能来自 `Net.getJson`，
                // 而下面紧接着把房间压成 null、一路走到 `Failed("虎牙房间信息获取失败…")`
                // ——归责码在用户界面上是零，这句日志是它**唯一**的出口。
                Log.w(TAG, "room info failed: ${e.logFields}")
                null
            }

            if (room == null) {
                if (attempt >= MAX_RETRY) {
                    trySend(DanmakuEvent.Failed("虎牙房间信息获取失败，重新开关弹幕可恢复"))
                    return@channelFlow
                }
                delay(backoff(attempt++))
                continue
            }
            // 开播状态要在建连之前判，否则未开播的房间会白连一次再被踢
            if (!room.living) {
                // 下播不是连接问题，别把用户往「开关弹幕」上引
                trySend(DanmakuEvent.System("直播已结束"))
                return@channelFlow
            }

            when (val outcome = holdConnection(this@channelFlow, room) {
                upAt.set(System.currentTimeMillis())
                // Connected 只在**本次 collect 第一次**连上时发一次。
                // 重连成功不能再发，否则画面上会凭空多一行「已连接」。
                if (connectedSent.compareAndSet(false, true)) trySend(DanmakuEvent.Connected)
            }) {
                Outcome.ENDED -> {
                    trySend(DanmakuEvent.System("直播已结束"))
                    return@channelFlow
                }
                Outcome.CLOSED, Outcome.FAILED -> {
                    // 本次 attempt 里连上过就把退避清零：正常涨落不该把「5 次配额」
                    // 用光，否则一次长连接 + 几次抖动之后会话就永久废了。
                    if (upAt.get() > lastOkAt) {
                        attempt = 0
                        announced = false
                        everUp = true
                        lastOkAt = upAt.get()
                    }
                    if (attempt >= MAX_RETRY) {
                        trySend(DanmakuEvent.Failed("虎牙弹幕连接失败，重新开关弹幕可恢复"))
                        return@channelFlow
                    }
                    // 服务端正常关闭 / 网络断：先悄悄重连一次。
                    // 重连也不成（说明不是一次抖动）才提示——「已断开」这条
                    // 提示的有效期只有 8 秒，若每次抖动都发，用户会看到
                    // 「弹幕连接已断开」和正常滚动的弹幕同时出现。
                    if (everUp && attempt >= 1 && !announced &&
                        System.currentTimeMillis() - lastOkAt >= ANNOUNCE_AFTER_MS
                    ) {
                        announced = true
                        trySend(DanmakuEvent.System("弹幕连接已断开，重新开关弹幕可恢复"))
                    }
                    Log.w(TAG, "reconnect #${attempt + 1} after $outcome")
                    delay(backoff(attempt++))
                }
            }
        }
    }

    // ---- 房间信息

    private suspend fun resolve(roomId: String): Room? {
        val rid = roomId.trim().takeWhile { it.isDigit() }.ifEmpty { return null }
        val json = Net.getJson(
            "https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=$rid&showSecret=1",
            referer = "https://www.huya.com/$rid"
        )
        val data = json.optJSONObject("data") ?: return null
        val living = data.optString("liveStatus") == "ON"
        val presenterUid = data.optJSONObject("profileInfo")?.optLong("uid") ?: 0L

        var topSid = 0L
        var subSid = 0L
        val base = data.optJSONObject("stream")?.optJSONArray("baseSteamInfoList")
        if (base != null && base.length() > 0) {
            val first = base.optJSONObject(0)
            topSid = first?.optLong("lChannelId") ?: 0L
            subSid = first?.optLong("lSubChannelId") ?: 0L
        }
        // 兜底顺序照真实数据的重要性排：频道号 -> 主播 uid -> 房间号
        if (topSid <= 0L) topSid = presenterUid
        if (topSid <= 0L) topSid = rid.toLong()
        if (subSid <= 0L) subSid = topSid
        if (topSid <= 0L) return null

        Log.i(TAG, "room=$rid live=$living topSid=$topSid subSid=$subSid presenter=$presenterUid")
        return Room(topSid = topSid, subSid = subSid, living = living, presenterUid = presenterUid)
    }

    // ---- 连接

    /**
     * 建一次连，挂到对端关闭为止。返回「为什么结束」，由调用方决定要不要重连。
     * 所有发送/接收都在 OkHttp 的线程上，往外用 [scope] 的 trySend（线程安全）。
     */
    private suspend fun holdConnection(
        scope: ProducerScope<DanmakuEvent>,
        room: Room,
        onConnected: () -> Unit
    ): Outcome {
        val done = CompletableDeferred<Outcome>()
        val emit: (DanmakuEvent) -> Unit = { scope.trySend(it) }

        // 最近一次收到任何帧的时刻。健康连接每 60s 会有一条心跳回执，
        // 所以「长时间一帧都没有」本身就是掉线信号——见下面的看门狗。
        val lastFrameAt = AtomicLong(System.currentTimeMillis())

        val request = Request.Builder()
            .url(WS_URL)
            .header("User-Agent", Net.UA_PC)
            .header("Origin", "https://www.huya.com")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 顺序要紧：先注册组，再心跳。不发注册组的话服务器一条弹幕都不推。
                webSocket.send(registerGroup(room.topSid).toByteString())
                webSocket.send(HEARTBEAT.toByteString())
                onConnected()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                lastFrameAt.set(System.currentTimeMillis())
                try {
                    if (decode(bytes.toByteArray(), room, emit)) {
                        Log.i(TAG, "presenter gone, stop")
                        webSocket.close(1000, "ended")
                        done.complete(Outcome.ENDED)
                    }
                } catch (e: Exception) {
                    // 单条包坏掉不该拖垮连接，记一笔继续收。
                    //
                    // 这里**刻意只打 message、不走 logFields**，理由是结构性的、不是「这一处不重要」：
                    // `decode` 是纯 Tars 解析，全链路不碰 `Net`，`e` 只可能是越界/下标一类，
                    // `e as? SiteException` 恒为 null —— **这个 e 不可能是 SiteException**，
                    // 所以根本没有 kind 可丢。走 logFields 只会每收一个坏包输出一次 `kind=-`，
                    // 把真正带归责的那几行冲淡。
                    Log.w(TAG, "decode: ${e.message}")
                }
            }

            // 虎牙只发二进制；text 帧落到这里说明协议变了，没必要求救
            override fun onMessage(webSocket: WebSocket, text: String) {
                lastFrameAt.set(System.currentTimeMillis())
                Log.w(TAG, "unexpected text frame: ${text.take(64)}")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // http 单独一格、不拼进 detail：它是「谁关的」的第一手判据，
                // 而 detail 是「服务端说了什么」，两者会互相覆盖（5xx 时 detail 可能只有半句，
                // 403 时 detail 里又已经含 HTTP 码，response 还可能整个为 null）。
                Log.w(TAG, "ws failure: ${t.logFields} http=${response?.code}")
                done.complete(Outcome.FAILED)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(Outcome.CLOSED)
            }
        }

        val ws = Net.client.newWebSocket(request, listener)

        val heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)

                // send 返回 false = 出站队列已关，连接已经死了，但 OkHttp 未必回调
                // onFailure。不在这里收尾，flow 会一直挂着、一条事件都不发，
                // 用户看到的是「弹幕区永远没人说话」——最糟的那种静默失败。
                if (!ws.send(HEARTBEAT.toByteString())) {
                    Log.w(TAG, "heartbeat send rejected, treat as dead")
                    done.complete(Outcome.FAILED)
                    return@launch
                }

                // 看门狗：健康连接每个心跳周期都有回执，连续两个半周期一帧都没有
                // = 半开连接（TCP 没断但对面不再说话），按掉线处理触发重连。
                val silent = System.currentTimeMillis() - lastFrameAt.get()
                if (silent > SILENCE_LIMIT_MS) {
                    Log.w(TAG, "silent ${silent}ms, treat as dead")
                    done.complete(Outcome.FAILED)
                    return@launch
                }
            }
        }

        return try {
            done.await()
        } finally {
            heartbeat.cancel()
            ws.close(1000, "bye")
        }
    }

    // ---- 解码

    /** @return true 表示「直播已结束」，调用方要断开。 */
    private fun decode(data: ByteArray, room: Room, emit: (DanmakuEvent) -> Unit): Boolean {
        val envelope = TarsReader.of(data)
        val body = envelope.bytes(1) ?: return false
        return when (envelope.int(0)) {
            // 7 = HYPushMessage{ 0: pushType, 1: uri, 2: msg, 3: protocolType }
            7 -> {
                val push = TarsReader.of(body)
                onPush(push.int(1), push.bytes(2), room, emit)
            }
            // 22 = HYPushMessageV2{ 0: groupId, 1: list<HYMessageItem{0: uri, 1: msg, 2: messageId}> }
            22 -> {
                val packed = TarsReader.of(body)
                for (item in packed.structs(1)) {
                    if (onPush(item.int(0), item.bytes(1), room, emit)) return true
                }
                false
            }
            else -> false
        }
    }

    private fun onPush(
        uri: Int,
        msg: ByteArray?,
        room: Room,
        emit: (DanmakuEvent) -> Unit
    ): Boolean {
        if (msg == null) return false
        return when (uri) {
            URI_CHAT -> {
                val chat = parseChat(msg)
                if (chat != null) emit(chat)
                false
            }

            URI_ATTENDEE -> {
                // 契约里没有「在线人数」分支，见类注释；这里只记日志
                val count = TarsReader.of(msg).int(0)
                Log.d(TAG, "attendee=$count")
                false
            }

            URI_PRESENTER_CHANGE -> {
                val presenter = TarsReader.of(msg).long(0)
                // 0 或本房间主播本人 = 主播已走
                presenter <= 0L || (room.presenterUid > 0L && presenter == room.presenterUid)
            }

            else -> {
                Log.v(TAG, "skip uri=$uri len=${msg.size}")
                false
            }
        }
    }

    private fun parseChat(msg: ByteArray): DanmakuEvent.Chat? {
        val t = TarsReader.of(msg)
        val text = t.string(3)
        if (text.isEmpty()) return null
        val nick = t.struct(0)?.string(2).orEmpty()
        val fontColor = t.struct(6)?.int(0) ?: 0
        return DanmakuEvent.Chat(
            user = nick,
            text = text,
            // fontColor <= 0 表示「跟随主题」，即默认白字（实测大量消息就是 -1）
            color = if (fontColor <= 0) WHITE else OPAQUE or (fontColor.toLong() and 0xFFFFFFL)
        )
    }

    // ---- 编码

    /** 命令 16：`EWSCmdC2S_RegisterGroupReq{ 0: cmd=16, 1: 组列表结构 }`。 */
    private fun registerGroup(topSid: Long): ByteArray {
        val group = TarsWriter()
            // 组名只认 STRING4 的写法，见 Tars.kt 里坑 1 的说明
            .stringList(0, listOf("live:$topSid", "chat:$topSid"))
            .string(1, "")
        return TarsWriter()
            .int(0, CMD_REGISTER_GROUP)
            .bytes(1, group.toByteArray())
            .toByteArray()
    }

    private enum class Outcome { CLOSED, FAILED, ENDED }

    private data class Room(
        val topSid: Long,
        val subSid: Long,
        val living: Boolean,
        val presenterUid: Long
    )

    private fun backoff(attempt: Int): Long =
        (1_000L shl attempt.coerceIn(0, 4)).coerceAtMost(16_000L)

    private companion object {
        const val TAG = "HuyaDanmaku"
        const val WS_URL = "wss://wsapi.huya.com"

        const val CMD_REGISTER_GROUP = 16L
        const val CMD_HEARTBEAT = 20L

        const val URI_ATTENDEE = 8006
        const val URI_PRESENTER_CHANGE = 8001
        const val URI_CHAT = 1400

        const val MAX_RETRY = 5
        const val HEARTBEAT_MS = 60_000L

        /** 中断多久还没恢复才值得打扰用户（三个平台同一口径） */
        const val ANNOUNCE_AFTER_MS = 3_000L

        /** 两个半心跳周期没有任何帧就按掉线处理（健康连接每 60s 有一条回执）。 */
        const val SILENCE_LIMIT_MS = HEARTBEAT_MS * 5 / 2

        const val WHITE = 0xFFFFFFFFL
        const val OPAQUE = 0xFF000000L

        /** 命令 20 心跳：`{0: 20, 1: 空 byte[]}`，空数组要写成 ZERO_TAG 长度。 */
        val HEARTBEAT: ByteArray = TarsWriter()
            .int(0, CMD_HEARTBEAT)
            .bytes(1, ByteArray(0))
            .toByteArray()
    }
}
