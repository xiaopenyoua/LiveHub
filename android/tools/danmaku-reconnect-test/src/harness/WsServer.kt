// 本地极简 WebSocket 服务端：只做三件事——握手 101、按剧本写二进制帧、按剧本掐断。
// 不实现掩码解码（客户端发来的 loginreq/joingroup/注册组一律读掉不解析），
// 因为这件夹具要验的是「客户端断开后怎么重连」，不是服务端的健壮性。
package harness

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread

private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

private fun acceptKey(key: String): String =
    Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray())
    )

private fun readHeaders(input: InputStream): Map<String, String> {
    val sb = StringBuilder()
    while (!sb.endsWith("\r\n\r\n")) {
        val b = input.read()
        if (b < 0) break
        sb.append(b.toChar())
    }
    return sb.lines().filter { it.contains(":") }
        .associate { it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim() }
}

/** 服务端 → 客户端：不掩码，FIN=1。 */
private fun writeFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
    val head = ByteArray(if (payload.size < 126) 2 else 4)
    head[0] = (0x80 or opcode).toByte()
    when {
        payload.size < 126 -> head[1] = payload.size.toByte()
        else -> {
            head[1] = 126
            head[2] = ((payload.size ushr 8) and 0xFF).toByte()
            head[3] = (payload.size and 0xFF).toByte()
        }
    }
    out.write(head)
    out.write(payload)
    out.flush()
}

/** 一帧要发的东西：相对握手完成第几毫秒、载荷、日志里怎么称呼它。 */
data class Out(val at: Long, val payload: ByteArray, val label: String)

/**
 * 一条连接的服务端剧本。
 * - [outs]        发哪些帧、什么时候发
 * - [cutAt]       第几毫秒掐断（null = 一直挂着）
 * - [style]       "graceful" = 发 close 帧（服务端正常关闭，会走 onClosing→echo→onClosed）
 *                 "abrupt"   = setSoLinger(0)+close（RST，等价断网，只会走 onFailure）
 *                 "refuse"   = 连握手都不回（服务器在但拒绝服务）
 * - [stopAccept]  掐断后把监听也关掉，后续重连一律 ECONNREFUSED
 */
data class Plan(
    val outs: List<Out> = emptyList(),
    val cutAt: Long? = null,
    val style: String = "graceful",
    val stopAccept: Boolean = false
)

class FakeServer(private val plans: List<Plan>) {

    private val ss = ServerSocket(0)
    val port: Int get() = ss.localPort
    @Volatile private var stopped = false
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
    private val threads = Collections.synchronizedList(mutableListOf<Thread>())

    fun start() {
        val acceptor = thread(isDaemon = true, name = "ws-accept") {
            var idx = 0
            while (!stopped) {
                val s = try {
                    ss.accept()
                } catch (e: Exception) {
                    return@thread
                }
                val plan = plans.getOrNull(idx)
                Timeline.mark("server", "accept #${idx + 1}" + if (plan == null) "（剧本已用尽→只握手" else "")
                idx++
                val t = thread(isDaemon = true, name = "ws-conn-$idx") {
                    serve(s, plan ?: Plan(style = "refuse"), idx)
                }
                threads.add(t)
            }
        }
        threads.add(acceptor)
    }

    fun shutdown() {
        stopped = true
        runCatching { ss.close() }
        synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
        // 关键：把还挂在 Thread.sleep 里的连接线程打断。否则上一组用例的线程会在
        // 下一组的 Timeline 里留下一行时间错位的 mark（用例之间是同一个 Timeline 对象），
        // 读流水的人会以为掐断发生在不该发生的位置。
        synchronized(threads) { threads.toList() }.forEach { it.interrupt() }
    }

    private fun serve(socket: Socket, plan: Plan, id: Int) {
        sockets.add(socket)
        val t0 = System.currentTimeMillis()
        try {
            val input = socket.getInputStream()
            val out = socket.getOutputStream()

            if (plan.style == "refuse") {
                socket.close()
                return
            }

            val headers = readHeaders(input)
            out.write(
                ("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ${acceptKey(headers["sec-websocket-key"] ?: "")}\r\n\r\n")
                    .toByteArray()
            )
            out.flush()
            Timeline.mark("server", "101 Switching Protocols")

            // 读掉客户端发来的握手后数据。EOF 有两种来路，措辞必须分开——
            // S5 全靠这条流水判「谁先关的」，一律写成「对端已关闭」会把服务端自己掐断写成客户端的锅。
            val cutByUs = java.util.concurrent.atomic.AtomicBoolean(false)
            thread(isDaemon = true) {
                runCatching { while (input.read() >= 0) Unit }
                Timeline.mark(
                    "server",
                    if (cutByUs.get()) "conn #$id 读循环结束（我方刚掐断，这是被关后的 EOF）"
                    else "conn #$id 对端已关闭（read EOF，客户端先关的）"
                )
            }

            plan.outs.forEach { o ->
                val wait = o.at - (System.currentTimeMillis() - t0)
                if (wait > 0) Thread.sleep(wait)
                writeFrame(out, 0x2, o.payload)
                Timeline.mark("server", "→ ${o.label}（${o.payload.size}B）")
            }

            if (plan.cutAt != null) {
                val wait = plan.cutAt - (System.currentTimeMillis() - t0)
                if (wait > 0) Thread.sleep(wait)
                cutByUs.set(true)
                when (plan.style) {
                    "graceful" -> {
                        val sent = runCatching { writeFrame(out, 0x8, byteArrayOf(0x03, 0xe8.toByte())) }.isSuccess
                        Timeline.mark(
                            "server",
                            "掐断 conn #$id：close 帧 1000（服务端正常关闭" +
                                if (sent) "）" else "，对端已先关，写入被忽略）"
                        )
                        Thread.sleep(120)
                        runCatching { socket.close() }
                    }
                    else -> {
                        Timeline.mark("server", "掐断 conn #$id：setSoLinger(0)+close（RST，等价断网）")
                        runCatching { socket.setSoLinger(true, 0) }
                        runCatching { socket.close() }
                    }
                }
            } else {
                Thread.sleep(120_000)
            }
        } catch (e: Exception) {
            // 对端关闭或剧本结束，属正常
        } finally {
            if (plan.stopAccept) {
                stopped = true
                runCatching { ss.close() }
                Timeline.mark("server", "监听已关闭（后续重连全部被拒）")
            }
            runCatching { socket.close() }
        }
    }
}
