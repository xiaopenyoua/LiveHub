package com.livehub.tv.core.danmaku

import android.util.Log
import com.livehub.tv.core.model.Platform
import com.livehub.tv.core.model.SiteError
import com.livehub.tv.core.model.SiteException
import com.livehub.tv.core.model.logFields
import com.livehub.tv.core.net.Net
import kotlinx.coroutines.CancellationException
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Inflater

/**
 * 哔哩哔哩弹幕。
 *
 * 协议（web 端，与 blivedm 一致）：
 * 1. room_init 拿真实房间号（短号要转）
 * 2. 取 token 与弹幕服务器列表：优先免签的 room/v1/Danmu/getConf，
 *    退化到 getDanmuInfo（该接口已上 wbi，未登录返回 -352）
 * 3. wss://{host}/sub 连上后发认证包 op=7
 * 4. 包头 16 字节大端：包长 / 头长(16) / 协议版本 / 操作码 / 序号
 * 5. 心跳 op=2 每 30 秒
 *
 * 这里主动把 protover 设成 2（zlib）而不是 3（brotli）：Android 没有内置 brotli 解压，
 * 引一个 so 库不值得，而服务端对 2 一直保持支持。
 */
class BilibiliDanmaku : DanmakuClient {

    override val platform = Platform.BILIBILI

    override fun stream(roomId: String): Flow<DanmakuEvent> = channelFlow {
        // ★ 断线策略与 HuyaDanmaku / DouyuDanmaku 是**同一套**，改一处必须同步另两处：
        //   连续失败计数 → 连上即 attempt=0 / announced=false
        //   → 第 2 次失败且距上次成功 ≥3s 才提示一次
        //   → 到上限发 Failed 收尾。重连过程本身不发任何事件。
        // 重连必须留在本次 collect 里，绝不做共享热流：用户「关弹幕→开弹幕」
        // 是电视上唯一可靠的自助恢复手段，被 shareIn 一挡就废了。
        var attempt = 0
        var announced = false
        var everUp = false
        var lastOkAt = 0L
        var session: Session? = null
        // 跨线程只走原子量：onUp 跑在 OkHttp 的线程上，上面的计数只在协程线程上改。
        val upAt = AtomicLong(0L)
        val connectedSent = AtomicBoolean(false)

        while (isActive) {
            // token 由 prepare() 拿，不是一次性凭证，重连复用同一份即可
            // （和虎牙不同：虎牙每次重连都要重新查一次房间信息换 topSid）。
            val prepared = session ?: try {
                prepare(roomId).also { session = it }
            } catch (e: CancellationException) {
                // 协程被取消（用户切台/关弹幕）时 Net 里抛的是 CancellationException。
                // 不 rethrow 就会被下面当成「准备失败」，白退避一轮再被 delay 抛出来——
                // 结果一样但日志里会多一条假的「prepare failed」。
                throw e
            } catch (e: Exception) {
                // 用 logFields 而不是 "${e.message}"：后者的实际行为是「有 detail 就丢
                // 归责码、没 detail 才有」。完整的结构性论证（Net 的四个抛出点
                // detail 都不可能为空）在 SiteError.kt 的 `logFields` KDoc 里，只写一份。
                Log.w(TAG, "prepare failed: ${e.logFields}")
                null
            }

            if (prepared == null) {
                if (attempt >= MAX_RETRY) {
                    // 与另两处一样用 trySend：这里已经是终态，通道万一已被下游关掉
                    // （用户切台/关弹幕）不该再抛 CancellationException 冒到上层。
                    trySend(DanmakuEvent.Failed("哔哩弹幕准备失败，重新开关弹幕可恢复"))
                    return@channelFlow
                }
                delay(backoff(attempt++))
                continue
            }

            when (val outcome = holdConnection(this@channelFlow, prepared) {
                upAt.set(System.currentTimeMillis())
                // Connected 只在**本次 collect 第一次**连上时发一次。
                // 重连成功不能再发，否则画面上会凭空多一行「已连接」。
                if (connectedSent.compareAndSet(false, true)) trySend(DanmakuEvent.Connected)
            }) {
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
                        trySend(DanmakuEvent.Failed("哔哩弹幕连接失败，重新开关弹幕可恢复"))
                        return@channelFlow
                    }
                    // 只有「连上过又被断开」才值得说「已断开」；一次都没连上属于连接失败，
                    // 那是另一件事，别用同一句话把人往开关上引。
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

    /**
     * 建一次连，挂到对端关闭为止。与 HuyaDanmaku.holdConnection 同构。
     *
     * OkHttp 的实测回调契约（跑真实 okhttp 4.12 验证过，不是看文档）：
     * 对端优雅关闭 → 只有 onClosing，**不会有 onClosed**（除非我们回声一个 close）；
     * 对端异常断开 → 只有 onFailure；在 onFailure 里再 close() 不会再补任何回调。
     * 所以 onClosing 才是「服务端关了连接」的真实信号，onClosed 只当兜底。
     */
    private suspend fun holdConnection(
        scope: ProducerScope<DanmakuEvent>,
        session: Session,
        onUp: () -> Unit
    ): Outcome {
        val done = CompletableDeferred<Outcome>()

        val ws = Net.client.newWebSocket(
            Request.Builder()
                .url(session.wsUrl)
                .header("User-Agent", Net.UA_PC)
                .header("Origin", "https://live.bilibili.com")
                .build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send((authPacket(session)).toByteString())
                    onUp()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    parseFrames(bytes.toByteArray()) { ev -> scope.trySend(ev) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // 这一处尤其贵，因为 `t` 很可能就是我们自己抛的 SiteException：
                    // okhttp 4.12 的 `RealWebSocket$connect$1.onResponse` 异常表是
                    // `168..241 → 244`（覆盖 onOpen + **loopReader**），244 处直接调
                    // `failWebSocket(e, null)`，而 `failWebSocket` 是 `aload_1` 原对象
                    // 传给 `onFailure`——不包装、不改类型。也就是说 parseFrames 里抛什么，
                    // 这里就收到什么；只打 message 等于把归责码当场扔掉。
                    // 那个异常表只在 `pingInterval == 0` 时成立（默认值），见 logFields 的用法说明。
                    Log.w(TAG, "ws failure: ${t.logFields}")
                    done.complete(Outcome.FAILED)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "closing by server: $code $reason")
                    // 回声一个 close 完成握手（这是 onClosed 会来的前提，实测）
                    webSocket.close(1000, null)
                    done.complete(Outcome.CLOSED)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "closed: $code $reason")
                    done.complete(Outcome.CLOSED)
                }
            }
        )

        val heartbeat = scope.launch {
            delay(1_000)
            while (isActive) {
                // send 返回 false = 出站队列已关，连接已死但 OkHttp 未必回调 onFailure。
                // 不做「静默看门狗」：哔哩的心跳回执（OP_HEARTBEAT_REPLY）我没有抓包证据，
                // 靠无帧时长判断可能误杀冷清房间，宁可少判一种。
                if (!ws.send((packet(OP_HEARTBEAT, "[object Object]".toByteArray())).toByteString())) {
                    Log.w(TAG, "heartbeat send rejected, treat as dead")
                    done.complete(Outcome.FAILED)
                    return@launch
                }
                delay(HEARTBEAT_MS)
            }
        }

        return try {
            done.await()
        } finally {
            heartbeat.cancel()
            ws.close(1000, "bye")
        }
    }

    private enum class Outcome { CLOSED, FAILED }

    /**
     * 「响应里没有 data / 没有 token」的**控制流哨兵**，不是给用户看的错误。
     *
     * 刻意**不带 message**：带了也一定被 [fetchByDanmuInfo] 的 `getOrElse` 覆盖成另一句，
     * 于是那句话只能进日志、且永远进不去——但读代码的人会以为它在描述用户看到的东西。
     * 这类「写下来只为被覆盖」的文案，比没有更贵。归责与原始异常都从 `cause` 带走。
     */
    private class ConfMissing : Exception()


    private fun backoff(attempt: Int): Long =
        (1_000L shl attempt.coerceIn(0, 4)).coerceAtMost(16_000L)

    private data class Session(val wsUrl: String, val roomId: Long, val token: String, val buvid: String)

    private suspend fun prepare(roomId: String): Session {
        val init = Net.getJson(
            "https://api.live.bilibili.com/room/v1/Room/room_init?id=$roomId",
            referer = "https://live.bilibili.com/"
        )
        // 三个判据分开，不许压成一个。
        //
        // 1. `code` **缺失** ≠ `code` 非 0：后者是「这个房间现在不可用」（平台的确定结论，
        //    不怪我们、不该报警），前者是「响应结构变了」（怪我们、必须报警）。给
        //    `optInt("code", -1)` 一个默认值就把这两种归责压成一种——本轮反复踩的就是这个。
        // 2. 走到 prepare() 这一步，`roomId` **已经是取流层解析出来的数字**了，所以
        //    `room_init` 非 0 落 ROOM_NOT_FOUND（「这个号不存在」），**不是** ID_UNRESOLVED
        //    （那个码的定义是「没能把用户给的东西变成房间标识」，属取流层 resolver）。
        //    用错码的代价不是文案：诊断页会把同一个用户错误记两笔，让「id 解析器坏了」
        //    这个指标虚高。
        // 3. **不许拿输入值兜底当真实房间号**（原来的 `?: roomId.toLongOrNull() ?: 0L`）。
        //    data 缺失时那个兜底会让我们去 getConf(输入值)，必然拿不到 token，最后报成
        //    DANMAKU_PROTOCOL(report=true)——归责从「房间不存在」被推成「我们的协议坏了」；
        //    而且只要输入是数字 realId 就永远非 0，下面那个守卫永远不亮灯。那是假数据，不是降级。
        val initCode = if (init.has("code")) init.optInt("code") else null
        val realId = init.optJSONObject("data")?.optLong("room_id", 0L) ?: 0L
        if (initCode == null) throw SiteException(
            SiteError.PARSE_BROKEN,
            detail = "room_init 响应无 code 字段：${init.keys().asSequence().toList()}"
        )
        if (initCode != 0) throw SiteException(
            SiteError.ROOM_NOT_FOUND,
            detail = "room_init code=$initCode（房间 $roomId 不可用）"
        )
        if (realId <= 0L) throw SiteException(
            SiteError.PARSE_BROKEN,
            detail = "room_init code=0 但 data.room_id 缺失或为 0"
        )

        val buvid = runCatching {
            Net.getJson("https://api.bilibili.com/x/frontend/finger/spi")
                .optJSONObject("data")?.optString("b_3").orEmpty()
        }.getOrDefault("")
        if (buvid.isNotEmpty()) Net.setCookie("api.live.bilibili.com", "buvid3", buvid)

        // 两条路取 token + 弹幕机列表，实测（2026-10）：
        //   getConf      免签名，仍返回 token 与 host_server_list —— 首选
        //   getDanmuInfo 已上 wbi 风控，未登录直接 {"code":-352}，只作兜底
        // getDanmuInfo 想救活就得算 w_rid（nav 取 img_key/sub_key 再 md5 拼 query），
        // 那是 40 行只为兜底，不值得；等哪天 getConf 也挂了再说。
        val conf = fetchByConf(realId) ?: fetchByDanmuInfo(realId, buvid)
        return Session("wss://${conf.second}/sub", realId, conf.first, buvid)
    }

    /** 旧版免签接口。返回 (token, host:port) */
    private suspend fun fetchByConf(realId: Long): Pair<String, String>? = runCatching {
        val res = Net.getJson(
            "https://api.live.bilibili.com/room/v1/Danmu/getConf?room_id=$realId&platform=pc&player=web",
            referer = "https://live.bilibili.com/$realId"
        )
        if (res.optInt("code") != 0) return@runCatching null
        val data = res.optJSONObject("data") ?: return@runCatching null
        val token = data.optString("token")
        if (token.isEmpty()) return@runCatching null
        token to pickHost(data.optJSONArray("host_server_list"))
    }.getOrNull()

    /** wbi 风控后的接口，未登录大概率 -352，仅兜底 */
    private suspend fun fetchByDanmuInfo(realId: Long, buvid: String): Pair<String, String> = runCatching {
        val info = Net.getJson(
            "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=$realId&type=0",
            headers = mapOf("Cookie" to "buvid3=$buvid"),
            referer = "https://live.bilibili.com/$realId"
        )
        // **这不是错误，是控制流哨兵。** 原来的 `IllegalStateException("getDanmuInfo 无数据")`
        // 的 message 会被下面的 getOrElse 立刻覆盖成另一句话——也就是说那是一句
        // 「只能进日志、且一定进不去」的死文案，读的人却会以为它在描述用户看到的东西。
        // 一个不携带文案的私有类型就不会有人再误会它。原始信息由 cause 带出去，不会丢。
        val data = info.optJSONObject("data") ?: throw ConfMissing()
        val token = data.optString("token")
        // token 为空与 data 缺失是同一个后果（sub 包里的 key 是空串，服务端必然拒），
        // 所以同归一处：`getConf` 与 `getDanmuInfo` **均未取到 token/host**。
        if (token.isEmpty()) throw ConfMissing()
        token to pickHost(data.optJSONArray("host_list"))
    }.getOrElse { e ->
        // SiteException 原样上抛，不重包一层：诊断页要看到最初那个 url 与 detail
        // （Net 现在只抛 SiteException，rethrow 它才能保住 HTTP_4XX / RATE_LIMITED 的归责）。
        if (e is SiteException) throw e
        // 选 DANMAKU_PROTOCOL 而不是 SIGN_INVALID：这一个失败**不影响画面**，
        // 只是没弹幕。retryable=true 与后台确实在按 3s/6s/12s 重连一致；
        // SIGN_INVALID 是「平台改版、整条取流线可能都坏了」的最高优先信号，
        // 把「没有弹幕服务器地址」记成它会污染那条最早、最干净的告警线。
        // report=true 仍然成立：getConf 与 getDanmuInfo 同时不可用，多半是我们该看的东西。
        throw SiteException(
            SiteError.DANMAKU_PROTOCOL,
            detail = "getConf 与 getDanmuInfo 均未取到 token/host",
            cause = e
        )
    }

    private fun pickHost(arr: JSONArray?): String {
        if (arr == null || arr.length() == 0) return "broadcastlv.chat.bilibili.com:443"
        val first = arr.optJSONObject(0)
        val h = first?.optString("host") ?: "broadcastlv.chat.bilibili.com"
        val port = first?.optInt("wss_port", 443) ?: 443
        return if (port == 443) h else "$h:$port"
    }

    private fun authPacket(s: Session): ByteArray {
        val json = JSONObject().apply {
            put("uid", 0)
            put("roomid", s.roomId)
            put("protover", 2)
            put("buvid", s.buvid)
            put("platform", "web")
            put("type", 2)
            put("key", s.token)
        }
        return packet(OP_AUTH, json.toString().toByteArray())
    }

    private fun packet(op: Int, body: ByteArray): ByteArray {
        val total = 16 + body.size
        return ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            .putInt(total)
            .putShort(16)
            .putShort(1)
            .putInt(op)
            .putInt(1)
            .put(body)
            .array()
    }

    private fun parseFrames(buf: ByteArray, emit: (DanmakuEvent) -> Unit) {
        var offset = 0
        while (offset + 16 <= buf.size) {
            val bb = ByteBuffer.wrap(buf, offset, buf.size - offset).order(ByteOrder.BIG_ENDIAN)
            val total = bb.int
            val headerLen = bb.short.toInt()
            val protoVer = bb.short.toInt()
            val op = bb.int
            bb.int // seq
            if (total <= 0 || offset + total > buf.size) break
            val bodyLen = total - headerLen
            if (bodyLen > 0) {
                val body = ByteArray(bodyLen)
                System.arraycopy(buf, offset + headerLen, body, 0, bodyLen)
                when {
                    protoVer == 2 -> runCatching { inflate(body) }.getOrNull()
                        ?.let { parseFrames(it, emit) }

                    protoVer == 0 || protoVer == 1 -> if (op == OP_NOTIFY) parseNotify(body, emit)
                    else -> Unit
                }
            }
            // 认证回执不再发 Connected：Connected 统一由 holdConnection 的 onUp
            // 在**本次 collect 第一次**连上时发一次（三个平台同一套），
            // 这里再发一次会在重连时凭空多出一行「已连接」。
            if (op == OP_AUTH_REPLY) Log.i(TAG, "auth reply ok")
            offset += total
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val chunk = ByteArray(8 * 1024)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n == 0) break
                out.write(chunk, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun parseNotify(body: ByteArray, emit: (DanmakuEvent) -> Unit) {
        val json = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() ?: return
        when (json.optString("cmd").substringBefore(':')) {
            "DANMU_MSG" -> {
                val info = json.optJSONArray("info") ?: return
                val text = info.optString(1)
                val user = info.optJSONArray(2)?.optString(1).orEmpty()
                val colorArr = info.optJSONArray(0)
                val color = colorArr?.optLong(3, 0xFFFFFFL) ?: 0xFFFFFFL
                if (text.isNotBlank()) {
                    emit(
                        DanmakuEvent.Chat(
                            user = user,
                            text = text,
                            color = 0xFF000000L or color
                        )
                    )
                }
            }

            "SEND_GIFT" -> {
                val d = json.optJSONObject("data") ?: return
                emit(
                    DanmakuEvent.Gift(
                        user = d.optString("uname"),
                        giftName = d.optString("giftName"),
                        count = d.optInt("num", 1)
                    )
                )
            }

            "SUPER_CHAT_MESSAGE" -> {
                val d = json.optJSONObject("data") ?: return
                val user = d.optJSONObject("user_info")?.optString("uname").orEmpty()
                val msg = d.optString("message")
                if (msg.isNotBlank()) emit(DanmakuEvent.Chat(user, "【醒目留言】$msg", 0xFFFFD700L))
            }

            "WATCHED_CHANGE" -> {
                val n = json.optJSONObject("data")?.optInt("num", 0) ?: 0
                if (n > 0) emit(DanmakuEvent.System("${n} 人看过"))
            }
        }
    }

    private companion object {
        const val TAG = "BiliDanmaku"
        const val OP_HEARTBEAT = 2
        const val OP_NOTIFY = 5
        const val OP_AUTH = 7
        const val OP_AUTH_REPLY = 8

        /** 与 HuyaDanmaku / DouyuDanmaku 保持一致的重连参数 */
        const val MAX_RETRY = 5
        const val HEARTBEAT_MS = 30_000L
        const val ANNOUNCE_AFTER_MS = 3_000L
    }
}
