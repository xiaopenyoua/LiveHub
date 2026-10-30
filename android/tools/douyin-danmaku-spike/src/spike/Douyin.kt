// 抖音弹幕 spike 的网络部分：解析房间上下文 → 拼 WS URL → 连上去把帧解出来。
//
// 参数集与解包顺序**参照 DTV Mobile**（MIT, Copyright 2025 c.zeong）
//   DouyinDanmakuClientAndroid.kt / DouyinProtoLite.kt
// 但**没有**照搬它的重连/Flow 结构：这里是一次性探针，只要「握手 101 + 一条真弹幕」。
package spike

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

object Douyin {

    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    private const val WEB = "https://live.douyin.com"

    val WS_BASES = listOf(
        "wss://webcast5-ws-web-hl.douyin.com/webcast/im/push/v2/?",
        "wss://webcast3-ws-web-lq.douyin.com/webcast/im/push/v2/?",
        "wss://webcast5-ws-web-lf.douyin.com/webcast/im/push/v2/?",
    )

    // 这几个常量必须和签名时用的完全一致，否则 sign.js 算出来的是「另一个请求」的签名。
    private const val VERSION_CODE = "180800"
    private const val WEBCAST_SDK_VERSION = "1.3.0"
    private const val UPDATE_VERSION_CODE = "1.3.0"

    /** 参与 md5 的 13 个 key，顺序即拼接顺序（DTV / pure_live 一致）。 */
    private val SIGN_KEYS = listOf(
        "live_id", "aid", "version_code", "webcast_sdk_version", "room_id", "sub_room_id",
        "sub_channel_id", "did_rule", "user_unique_id", "device_platform", "device_type",
        "ac", "identity",
    )

    data class Ctx(val webRid: String, val roomId: String, val userUniqueId: String, val cookie: String)

    // ---------------------------------------------------------------- 房间上下文

    /**
     * 房间上下文 = cookie + `user_unique_id` + room_id。
     *
     * 正常路径是 GET `live.douyin.com/{web_rid}`，从内嵌 JSON 里拿 uid 和 cookie；
     * room_id 用 `"roomId":"…","web_rid":"…"` 相邻配对（和 backend 的 `detail()` 同一个取法）。
     *
     * **但这条路径会被反爬打断**：同一 IP 短时间内多抓几次房间页，返回的就不再是房间页，
     * 而是一个 6KB 的「验证码中间页」（`<title>验证码中间页</title>`），里面既没有 uid
     * 也没有 cookie。这不是我们的代码坏了，是页面级限流，实测 2026-10-29 触发过。
     * 所以这里要有第二条路，并且**打印用了哪条路**——不然读日志的人会以为 uid 还来自页面。
     *
     * 第二条路：cookie 从**首页** `https://live.douyin.com/` 取（首页不拦），
     * uid 用调用方给的、或直接生成一个 19 位数字。实测随机 uid 照样 101，
     * 说明服务端并**不拿 uid 去核对页面**，它只是用来拼 md5 和 query（两边一致即可）。
     */
    fun resolveCtx(http: OkHttpClient, webRid: String, roomIdArg: String?, uidArg: String? = null): Ctx {
        val page = get(http, "$WEB/$webRid")
        val html = page.second.replace("\\", "")
        val captcha = html.contains("验证码中间页")
        val fromPage = Regex("\"roomId\":\"(\\d{15,20})\",\"web_rid\":\"(\\d+)\"")
            .find(html)?.groupValues?.get(1).orEmpty()
        val uidFromPage = Regex("\"user_unique_id\":\"(\\d+)\"").find(html)?.groupValues?.get(1).orEmpty()

        // 房间页拦了就换首页拿 cookie：区别只在 Referer 和路径，cookie 是一套。
        val cookieSource = if (captcha || page.second.isBlank()) get(http, "$WEB/") else page
        val cookie = cookieSource.third

        val uid = uidArg?.takeIf { it.isNotBlank() }
            ?: uidFromPage.takeIf { it.isNotBlank() }
            ?: randomUserId()
        val uidSource = when {
            uid == uidArg -> "传入"
            uid == uidFromPage -> "页面"
            else -> "随机生成"
        }
        val roomId = roomIdArg?.takeIf { it.isNotBlank() } ?: fromPage

        println(
            "  resolve: 房间页 http=${page.first} ${html.length}B" +
                (if (captcha) "（**验证码中间页**，房间页这条道被限流了）" else "") +
                "  cookie 来源=${if (cookieSource === page) "房间页" else "首页"}" +
                "（${cookie.substringBefore("=")}…）" +
                "  room_id=${roomId}（${if (roomId == roomIdArg) "传入" else "页面配对"}）" +
                "  pageRoomId=${fromPage.ifBlank { "无" }}  uid=$uid($uidSource)"
        )
        require(roomId.isNotBlank()) { "拿不到 room_id：页面里没有 roomId/web_rid 配对，也没传参" }
        return Ctx(webRid, roomId, uid, cookie)
    }

    private fun randomUserId(): String =
        (1..18).joinToString("") { "${('0'..'9').random()}" }.let { "${('1'..'9').random()}$it" }

    /** GET 一次，返回 (code, body, cookie 头)。cookie 取**全部** Set-Cookie 的 name=value，
     *  不只第一条：浏览器发 WS 时整条 Cookie 头都会带过去，只留 ttwid 是我们自己猜的裁剪。 */
    private fun get(http: OkHttpClient, url: String): Triple<Int, String, String> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", WEB)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        http.newCall(req).execute().use { resp ->
            val cookie = resp.headers("Set-Cookie")
                .mapNotNull { raw -> raw.substringBefore(";").trim().ifBlank { null } }
                .distinctBy { it.substringBefore("=") }
                .joinToString("; ")
            return Triple(resp.code, resp.body?.string().orEmpty(), cookie)
        }
    }

    // ---------------------------------------------------------------- 拼 URL + 签名

    /**
     * 按 DTV 的参数顺序拼 query，然后把 `signature` 拼在最后。
     * 参与签名的 13 个 key 全部来自这张 map，**不是**从 URL 里回读——
     * 回读会引入「URL 编码后又被解码」的差异，那是签名对不上的常见原因。
     */
    fun buildWsUrl(base: String, ctx: Ctx, signature: String, encodeSig: Boolean = true): String {
        val uaTail = UA.removePrefix("Mozilla/")
        val params = linkedMapOf(
            "app_name" to "douyin_web",
            "version_code" to VERSION_CODE,
            "webcast_sdk_version" to WEBCAST_SDK_VERSION,
            "update_version_code" to UPDATE_VERSION_CODE,
            "compress" to "gzip",
            "cursor" to "h-1_t-${System.currentTimeMillis()}_r-1_d-1_u-1",
            "host" to "https://live.douyin.com",
            "aid" to "6383",
            "live_id" to "1",
            "did_rule" to "3",
            "debug" to "false",
            "maxCacheMessageNumber" to "20",
            "endpoint" to "live_pc",
            "support_wrds" to "1",
            "im_path" to "/webcast/im/fetch/",
            "user_unique_id" to ctx.userUniqueId,
            "device_platform" to "web",
            "cookie_enabled" to "true",
            "screen_width" to "1920",
            "screen_height" to "1080",
            "browser_language" to "zh-CN",
            "browser_platform" to "Win32",
            "browser_name" to "Mozilla",
            "browser_version" to uaTail,
            "browser_online" to "true",
            "tz_name" to "Asia/Shanghai",
            "identity" to "audience",
            "room_id" to ctx.roomId,
            "heartbeatDuration" to "0",
        )
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        // 签名字符串里有 + / =，按规范必须编码；但「服务端其实要原样」这种假设也值得打一次，
        // 因为 200 和 101 只有一次握手的差别，猜错只会多花 3 秒。
        val sigPart = if (encodeSig) enc(signature) else signature
        return base + query + "&signature=" + sigPart
    }

    /** md5 的原文 = 13 个 key 按固定顺序拼 `k=v,`。缺的 key 用空串，**不许跳过**。 */
    fun signStub(ctx: Ctx): String {
        val values = mapOf(
            "live_id" to "1",
            "aid" to "6383",
            "version_code" to VERSION_CODE,
            "webcast_sdk_version" to WEBCAST_SDK_VERSION,
            "room_id" to ctx.roomId,
            "sub_room_id" to "",
            "sub_channel_id" to "",
            "did_rule" to "3",
            "user_unique_id" to ctx.userUniqueId,
            "device_platform" to "web",
            "device_type" to "",
            "ac" to "",
            "identity" to "audience",
        )
        return SIGN_KEYS.joinToString(",") { "$it=${values[it].orEmpty()}" }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------------------------------------------------------- 连接 + 解包

    class Probe(
        val handshakeCode: Int,
        val handshakeDetail: String,
        val frames: Int,
        val msgFrames: Int,
        val gzipFailures: Int,
        val acks: Int,
        val methods: Map<String, Int>,
        val chats: List<Proto.Chat>,
        val elapsedMs: Long,
    )

    fun probe(
        wsUrl: String,
        ctx: Ctx,
        wantChats: Int = 3,
        timeoutMs: Long = 30_000,
    ): Probe {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        val t0 = System.currentTimeMillis()
        val done = CountDownLatch(1)
        val frames = AtomicInteger()
        val msgFrames = AtomicInteger()
        val gzipFail = AtomicInteger()
        val acks = AtomicInteger()
        val methods = Collections.synchronizedMap(mutableMapOf<String, Int>())
        val chats = Collections.synchronizedList(mutableListOf<Proto.Chat>())
        var code = -1
        var detail = ""
        // 探针成功之后是我们自己 cancel() 的，OkHttp 会回调 onFailure("Socket closed")。
        // 那不是观测到的事实，是收尾噪声——会把「握手失败」四个字打在 101 后面，读日志的人会以为连接挂了。
        var tearingDown = false

        val req = Request.Builder()
            .url(wsUrl)
            .header("accept", "application/json, text/plain, */*")
            .header("accept-language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("cache-control", "no-cache")
            .header("pragma", "no-cache")
            .header("User-Agent", UA)
            .header("Cookie", ctx.cookie)
            .header("Origin", WEB)
            .header("Referer", "${WEB}/${ctx.webRid}")
            .build()

        val hb = Proto.encodePushFrame("hb", 0L, ByteArray(0))

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                code = response.code
                detail = "server=${response.header("server")} upgrade=${response.header("upgrade")}"
                println("  WS 已建立：HTTP ${response.code}  $detail")
                Thread {
                    runCatching {
                        while (!Thread.currentThread().isInterrupted && done.count > 0) {
                            webSocket.send(hb.toByteString())
                            Thread.sleep(10_000)
                        }
                    }
                }.also { it.isDaemon = true }.start()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val n = frames.incrementAndGet()
                val frame = Proto.decodePushFrame(bytes.toByteArray())
                if (frame == null) {
                    println("  #$n 帧：PushFrame 解不出 payloadType，跳过（${bytes.size}B）")
                    return
                }
                if (frame.payloadType != "msg") {
                    if (n <= 5) println("  #$n 帧：payloadType=${frame.payloadType}（非 msg，跳过 ${bytes.size}B）")
                    return
                }
                msgFrames.incrementAndGet()
                val raw = frame.payload
                val unzipped = runCatching {
                    GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
                }.getOrElse {
                    gzipFail.incrementAndGet()
                    println("  #$n 帧：gunzip 失败（${raw.size}B）— ${it.message?.take(80)}")
                    return
                }
                val resp = Proto.decodeResponse(unzipped)
                if (n <= 5 || resp.messages.isNotEmpty()) {
                    println(
                        "  #$n 帧：msg ${raw.size}B → gunzip ${unzipped.size}B，" +
                            "message=${resp.messages.size} needAck=${resp.needAck} " +
                            "internalExt=${resp.internalExt.take(18)}"
                    )
                }
                if (resp.needAck) {
                    val ack = Proto.encodePushFrame("ack", frame.logId, resp.internalExt.toByteArray())
                    webSocket.send(ack.toByteString())
                    acks.incrementAndGet()
                }
                for (m in resp.messages) {
                    methods.merge(m.method, 1, Int::plus)
                    if (m.method != "WebcastChatMessage") continue
                    Proto.decodeChat(m.payload)?.let { chats.add(it) }
                }
                if (chats.size >= wantChats) done.countDown()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                detail = "closing code=$code reason=$reason"
                println("  WS closing：code=$code reason=$reason")
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                detail = "closed code=$code reason=$reason"
                println("  WS closed：code=$code reason=$reason")
                done.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (tearingDown) return   // 见 tearingDown 的注释
                code = response?.code ?: -1
                val peek = runCatching {
                    response?.peekBody(2048)?.string()?.replace("\n", " ")?.take(300).orEmpty()
                }.getOrDefault("")
                detail = "throwable=${t.message} respCode=${response?.code} " +
                    "server=${response?.header("server")} ct=${response?.header("content-type")} body=$peek"
                println("  WS 握手失败：${t.message}")
                println("  ↳ 响应：code=${response?.code} server=${response?.header("server")} body=${peek.ifBlank { "（空）" }}")
                done.countDown()
            }
        }

        val socket = client.newWebSocket(req, listener)
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        tearingDown = true
        runCatching { socket.cancel() }
        client.dispatcher.executorService.shutdownNow()

        return Probe(
            handshakeCode = code,
            handshakeDetail = detail,
            frames = frames.get(),
            msgFrames = msgFrames.get(),
            gzipFailures = gzipFail.get(),
            acks = acks.get(),
            methods = methods.toMap(),
            chats = chats.toList(),
            elapsedMs = System.currentTimeMillis() - t0,
        )
    }
}
