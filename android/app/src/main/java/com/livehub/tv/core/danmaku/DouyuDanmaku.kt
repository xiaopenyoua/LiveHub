package com.livehub.tv.core.danmaku

import android.util.Log
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 斗鱼弹幕。
 *
 * 协议是斗鱼自有的 STT（Serrialized Type Transfer），与 web 端一致：
 * 包结构 = [4B 长度小端][4B 长度小端][4B 类型=689][正文][\0]
 * 正文是 `key@=value/` 拼接，`/` 与 `@` 在值里分别被转义成 `@S@` 与 `@A@`。
 *
 * 连接后必须发两条：loginreq 与 joingroup（gid=-9999 表示公共弹幕大区），
 * 否则服务器收包但不推该房间的聊天。
 */
class DouyuDanmaku : DanmakuClient {

    override val platform = Platform.DOUYU

    override fun stream(roomId: String): Flow<DanmakuEvent> = channelFlow {
        // ★ 断线策略与 HuyaDanmaku / BilibiliDanmaku 是**同一套**，改一处必须同步另两处：
        //   连续失败计数 → 连上即 attempt=0 / announced=false
        //   → 第 2 次失败且距上次成功 ≥3s 才提示一次
        //   → 到上限发 Failed 收尾。重连过程本身不发任何事件。
        // 重连必须留在本次 collect 里，绝不做共享热流：用户「关弹幕→开弹幕」
        // 是电视上唯一可靠的自助恢复手段，被 shareIn 一挡就废了。
        var attempt = 0
        var announced = false
        var everUp = false
        var lastOkAt = 0L
        // 跨线程只走原子量：onUp 跑在 OkHttp 的线程上，上面的计数只在协程线程上改。
        val upAt = AtomicLong(0L)
        val connectedSent = AtomicBoolean(false)

        while (isActive) {
            when (val outcome = holdConnection(this@channelFlow, roomId) {
                upAt.set(System.currentTimeMillis())
                // Connected 只在**本次 collect 第一次**连上时发一次。
                // 重连成功不能再发，否则画面上会凭空多一行「已连接」。
                if (connectedSent.compareAndSet(false, true)) trySend(DanmakuEvent.Connected)
            }) {
                // 斗鱼没有「直播已结束」这个连接期终态：下播信号来自弹幕流里的
                // rss 消息（见 parseBody），是普通事件，不是连接结束。
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
                        trySend(DanmakuEvent.Failed("斗鱼弹幕连接失败，重新开关弹幕可恢复"))
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
        roomId: String,
        onUp: () -> Unit
    ): Outcome {
        val done = CompletableDeferred<Outcome>()

        val ws = Net.client.newWebSocket(
            Request.Builder()
                .url(WS_URL)
                .header("User-Agent", Net.UA_PC)
                .header("Origin", "https://www.douyu.com")
                .build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // 斗鱼的 loginreq 没有一次性 token，重连直接重发即可（与虎牙不同，
                    // 虎牙要先查一次房间信息换 topSid，这里房间号就够）。
                    webSocket.send((pack("type@=loginreq/roomid@=$roomId/")).toByteString())
                    webSocket.send((pack("type@=joingroup/rid@=$roomId/gid@=-9999/")).toByteString())
                    onUp()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    decode(bytes.toByteArray()) { ev -> scope.trySend(ev) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // 用 logFields：okhttp 4.12 会把 onMessage 里抛出的原对象直接**不包装**
                    // 交给这里（`failWebSocket` 是 `aload_1`），所以 `t` 可能就带着归责码。
                    // 论证只在 SiteError.kt 的 `logFields` KDoc 里写一份，不在这里复制。
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
                // 不做「静默看门狗」：斗鱼的 mrkl 没有对应的回执帧（无抓包证据），
                // 靠无帧时长判断会误杀冷清房间。
                if (!ws.send((pack("type@=mrkl/")).toByteString())) {
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

    private fun backoff(attempt: Int): Long =
        (1_000L shl attempt.coerceIn(0, 4)).coerceAtMost(16_000L)

    private fun pack(body: String): ByteArray {
        val payload = body.toByteArray(Charsets.UTF_8)
        val len = payload.size + 9 // 正文 + \0 + 8 字节长度字段
        val buf = ByteBuffer.allocate(12 + payload.size + 1).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(len)
        buf.putInt(len)
        buf.putInt(689)
        buf.put(payload)
        buf.put(0)
        return buf.array()
    }

    private fun decode(data: ByteArray, emit: (DanmakuEvent) -> Unit) {
        var offset = 0
        while (offset + 12 <= data.size) {
            val bb = ByteBuffer.wrap(data, offset, data.size - offset).order(ByteOrder.LITTLE_ENDIAN)
            val len = bb.int
            if (len <= 8 || offset + 4 + len > data.size) break
            bb.int // 重复长度
            bb.int // 类型 689
            val bodyLen = len - 8
            if (bodyLen <= 0) break
            val body = String(data, offset + 12, bodyLen - 1, Charsets.UTF_8)
            parseBody(body, emit)
            offset += 4 + len
        }
    }

    private fun parseBody(raw: String, emit: (DanmakuEvent) -> Unit) {
        val text = raw
            .replace("@S@", "\u0001")
            .replace("@A@", "@")
            .replace("\u0001", "/")
        val map = HashMap<String, String>(12)
        text.split('/').forEach { kv ->
            val i = kv.indexOf("@=")
            if (i > 0) map[kv.substring(0, i)] = kv.substring(i + 2)
        }
        when (map["type"]) {
            "chatmsg" -> {
                // if=1 才是真实观众发言，其余是系统/机器人
                if (map["if"] != "1") return
                val txt = map["txt"] ?: return
                val nn = map["nn"] ?: ""
                val col = map["col"]?.toLongOrNull() ?: 0L
                emit(DanmakuEvent.Chat(user = nn, text = txt, color = colToArgb(col)))
            }

            "dgb" -> {
                emit(
                    DanmakuEvent.Gift(
                        user = map["nn"] ?: "",
                        giftName = map["gfn"] ?: "礼物",
                        count = map["gfcnt"]?.toIntOrNull() ?: 1
                    )
                )
            }

            "rss" -> {
                if (map["ss"] == "0") emit(DanmakuEvent.System("直播已结束"))
            }

            "uenter" -> {
                // 进场消息太密，不播报到弹幕轨道
            }
        }
    }

    /** 斗鱼的 col 是 0 表示默认白字，非 0 是 RGB 整数 */
    private fun colToArgb(col: Long): Long =
        if (col == 0L) 0xFFFFFFFFL else 0xFF000000L or (col and 0xFFFFFFL)

    private companion object {
        const val TAG = "DouyuDanmaku"
        const val WS_URL = "wss://danmuproxy.douyu.com:8506"

        /** 与 HuyaDanmaku / BilibiliDanmaku 保持一致的重连参数 */
        const val MAX_RETRY = 5
        const val HEARTBEAT_MS = 45_000L
        const val ANNOUNCE_AFTER_MS = 3_000L
    }
}
