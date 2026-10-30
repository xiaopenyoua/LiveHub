package com.livehub.tv.core.net

import com.livehub.tv.core.model.SiteError
import com.livehub.tv.core.model.SiteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 全局 HTTP 客户端，同时是**错误码的唯一收口点**。
 *
 * 设计取舍：
 * 1. 只用 OkHttp + org.json，不引 Gson/Moshi —— 四家接口返回结构互不相同，
 *    手写解析比反射序列化更容易在接口改版时定位问题。
 * 2. Cookie 自己管（内存 CookieJar），哔哩和抖音都靠 cookie 决定返回什么清晰度。
 * 3. 所有请求走 IO 线程，调用方是 suspend，可以直接在协程里用。
 * 4. **HTTP 状态码只在这里判一次**（[httpError]）。四家 site 不许再出现 `resp.code`，
 *    否则同一个 403 在四个文件里会有四种归类，诊断页的统计直接作废。
 * 5. 抛出的都是 [SiteException]，带 `kind`；字符串异常在这里绝迹（§3.3.1 验收第 1 条）。
 */
object Net {

    const val UA_PC =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    const val UA_TV =
        "Mozilla/5.0 (Linux; Android 12; SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = cookieStore.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { c ->
                list.removeAll { it.name == c.name }
                list.add(c)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookieStore[url.host]?.filter { it.matches(url) } ?: emptyList()
    }

    /**
     * 普通 HTTP 客户端。**不要把 `pingInterval` 改成非 0。**
     *
     * 弹幕层「哪些异常能到 `onFailure`」的结论全部依赖这个值是 0：`RealWebSocket$connect$1.onResponse`
     * 里只有 `pingInterval == 0` 才走同步 `loopReader()`，那条异常表覆盖
     * `initReaderAndWriter + onOpen + loopReader` 并把**原 throwable** 交给 `onFailure`；
     * 一旦非 0 就换到带超时的那条读路径，兜底位置跟着变，`core/danmaku` 里的归责结论当场作废。
     *
     * 这条防线放在 builder 上，不放在弹幕层：兜底位置是这个**值**的推论，不是某个使用者的属性。
     * 加一行心跳看起来很正面，review 最容易放过的就是这种「更稳」的改动。
     * [streamClient] 由 `client.newBuilder()` 派生，所以这条约束对它同样成立。
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }

    /** 流媒体播放器用的客户端：读超时放宽，避免长连接被掐 */
    val streamClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun cookieHeader(host: String): String =
        cookieStore[host]?.joinToString("; ") { "${it.name}=${it.value}" } ?: ""

    fun setCookie(host: String, name: String, value: String) {
        val url = HttpUrl.Builder().scheme("https").host(host).build()
        val cookie = Cookie.Builder()
            .name(name).value(value).domain(host).path("/").build()
        cookieJar.saveFromResponse(url, listOf(cookie))
    }

    // ------------------------------------------------ 无网短路（可注入，默认关）

    /**
     * 连通性探针，返回 `true` = 有网。默认 `null`：判断不了就不判断，**不假装有网也不假装没网**。
     *
     * 为什么是个钩子而不是直接读 `ConnectivityManager`：`Net` 是纯 OkHttp + org.json，
     * 没有也不该有 `Context`。装探针是 App 层的事（`LiveHubApp.onCreate` 里一行），
     * 这样 `Net` 仍然可以在一台没有 Android 的机器上编译和跑单测。
     *
     * 装了之后的唯一作用：**省掉一次 8 秒连接超时**。[SiteError.NET_OFFLINE] 的
     * `report = false`，因为「用户开了飞行模式」不是我们的故障，不该进错误码 Top3。
     */
    @Volatile
    private var connectivity: (() -> Boolean)? = null

    /**
     * 探针结果的缓存。**不能每个请求都真去查一遍**：`ConnectivityManager.getNetworkCapabilities`
     * 在低端盒子上是跨进程调用，首页四家并发就是四次。
     *
     * 两道保鲜：TTL（[ONLINE_TTL_MS]）兜住「监听漏了事件」，以及 App 层的网络变化回调
     * 调 [invalidateConnectivity] 立即作废。宁可多问一次，也不要在 WiFi 恢复后还短路一屏错误。
     */
    @Volatile
    private var onlineCache: Boolean? = null

    @Volatile
    private var onlineCachedAtMs = 0L

    private const val ONLINE_TTL_MS = 2_000L

    fun onConnectivityCheck(probe: () -> Boolean) {
        connectivity = probe
        invalidateConnectivity()
    }

    /** 网络状态变了（连上/断开/切换）时由 App 层调用，下一次请求重新问探针 */
    fun invalidateConnectivity() {
        onlineCache = null
        onlineCachedAtMs = 0L
    }

    /** 探针自己抛异常（厂商 ROM 上见过）一律当有网：查不到不等于没网 */
    private fun isOnline(): Boolean {
        val probe = connectivity ?: return true
        val cached = onlineCache
        val now = System.nanoTime() / 1_000_000L
        if (cached != null && now - onlineCachedAtMs in 0 until ONLINE_TTL_MS) return cached
        val value = try {
            probe()
        } catch (e: Throwable) {
            true
        }
        onlineCache = value
        onlineCachedAtMs = System.nanoTime() / 1_000_000L
        return value
    }

    /** 请求前的闸门：明知没网就别花 8 秒去证明它 */
    private fun ensureOnline(url: String) {
        if (!isOnline()) {
            throw SiteException(SiteError.NET_OFFLINE, url, "网络未连接")
        }
    }

    // ------------------------------------------------ 可观测：请求计数

    private val requestCounter = java.util.concurrent.atomic.AtomicLong()

    /**
     * 进程内「[Net] 被调用了几次」（含被无网闸门拦下的、被聚合层超时裁掉后仍走了的）。
     *
     * 存在的理由有两条，都不是为了好看：
     * 1. 诊断页要回答「今天抖音打了几次」，而不是靠猜；
     * 2. **验收重试策略只能数这个数**。「虎牙非 2xx 重试一次」这类逻辑一旦 `catch` 落空，
     *    编译通过、没有 warning、日志也看不出来——只有请求次数会从 2 变 1。
     */
    val requestCount: Long get() = requestCounter.get()

    // ------------------------------------------------ HTTP → kind（唯一收口点）

    /** 风控词：403 的 body 里出现这些，说明是「请求太频繁」而不是「没权限」，两者动作相反 */
    private val RISK_WORDS = listOf("-352", "risk", "frequen")

    /**
     * 状态码 → [SiteError]。这张表对应 `docs/04` §3.3.1 第二节的 13 行，改之前先改文档，
     * 改之后跑 MockWebServer 那 13 条断言。
     *
     * 两条容易写错的：
     * - **404 归 [SiteError.HTTP_4XX]，不是「房间下播」**。下播由业务字段判（`status != 2`），
     *   那时候 HTTP 往往是 200。把 404 当下播会让诊断页永远看不到「域名/路径改了」。
     * - **403 + 风控词归 [SiteError.RATE_LIMITED]**，退避有效、原地重试无效；
     *   不带风控词的 403 才是 [SiteError.SIGN_INVALID]。
     */
    private fun httpError(url: String, resp: Response, body: String): SiteException {
        val code = resp.code
        val kind = when {
            code == 429 -> SiteError.RATE_LIMITED
            code == 403 && RISK_WORDS.any { body.contains(it, ignoreCase = true) } -> SiteError.RATE_LIMITED
            code == 401 || code == 403 -> SiteError.SIGN_INVALID
            code in 400..499 -> SiteError.HTTP_4XX
            code in 500..599 -> SiteError.HTTP_5XX
            // 3xx 落到这里说明 OkHttp 跟随失败（比如失效短链落到首页），
            // 1xx/204 这种也不该被当成成功体返回。兜底归 PARSE_BROKEN，**不静默吞掉**
            else -> SiteError.PARSE_BROKEN
        }
        val snippet = body.trim().take(120).replace('\n', ' ')
        val detail = "HTTP $code" + if (snippet.isEmpty()) "" else " body=$snippet"
        return SiteException(kind, url, detail)
    }

    /**
     * IO 异常 → [SiteError]。**四类全部归 [SiteError.NET_TIMEOUT]**，不单列 DNS/连接重置/TLS：
     * 用户在这些情况下的可做动作完全一样（检查网络、再试一次），分成四个码只会让诊断页
     * 出现四个各 1 次的码，看不出「今天网络整体不好」。TLS 靠 `detail` 的 `TLS:` 前缀区分
     * （机型相关，API 21 的 TLS 栈是已知坑）。
     *
     * `internal` 而不是 `private`：`SSLException` / `SocketTimeoutException` 这几类
     * **离线造不出来**（要一个会讲 TLS 的服务端），只能拿真异常实例直接喂给这个函数验
     * 「类 → kind + detail 前缀」。不改结构、只为可测。
     */
    internal fun ioError(url: String, e: IOException): SiteException {
        val detail = when (e) {
            is SSLException -> "TLS: ${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            else -> "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
        }
        return SiteException(SiteError.NET_TIMEOUT, url, detail, e)
    }

    /** 200 但 body 不是 JSON：**「200 不等于成功」**（哔哩 -352、抖音 Request params error 都是 200） */
    private fun parseError(url: String, e: JSONException): SiteException =
        SiteException(SiteError.PARSE_BROKEN, url, "返回体不是 JSON（200 不等于成功）: ${e.message.orEmpty()}", e)

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null
    ): String = getBody(url, headers, referer).text

    /**
     * 同 [get]，但把响应体的**真实字节数**一起带出来。
     *
     * 存在的理由只有一条：**`String.length` 是 UTF-16 字符数，不是字节数**。中文页面上
     * 一个汉字算 1 个 `length`、3 个字节，于是「1354 字节的错误页」在代码里是 1090 左右的
     * 数字。凡是拿 `text.length` 当体积判据、又在 `detail` 里写成 `bytes=` 的地方，都是
     * 一把单位错的尺子：下一个人 `curl -s … | wc -c` 对不上，就会怀疑记录本身错了。
     *
     * 所以体积要么按字节（这里），要么老实用 `chars=` 说清是字符数。不允许含糊。
     */
    suspend fun getBody(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null
    ): NetBody = withContext(Dispatchers.IO) {
        requestCounter.incrementAndGet()
        ensureOnline(url)
        val builder = Request.Builder().url(url).get()
            .header("User-Agent", headers["User-Agent"] ?: UA_PC)
        headers.forEach { (k, v) -> if (k != "User-Agent") builder.header(k, v) }
        referer?.let { builder.header("Referer", it) }
        try {
            client.newCall(builder.build()).execute().use { resp ->
                // 先读 contentType 再读 bytes()：bytes() 会把 body 关掉
                val charset = resp.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val raw = resp.body?.bytes() ?: ByteArray(0)
                val text = String(raw, charset)
                if (!resp.isSuccessful) throw httpError(url, resp, text)
                NetBody(text, raw.size)
            }
        } catch (e: IOException) {
            throw ioError(url, e)
        }
    }

    suspend fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null
    ): JSONObject = try {
        JSONObject(get(url, headers, referer))
    } catch (e: JSONException) {
        throw parseError(url, e)
    }

    suspend fun postForm(
        url: String,
        form: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        referer: String? = null
    ): String = withContext(Dispatchers.IO) {
        requestCounter.incrementAndGet()
        ensureOnline(url)
        val body = form.entries.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
            .toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
        val builder = Request.Builder().url(url).post(body)
            .header("User-Agent", headers["User-Agent"] ?: UA_PC)
        headers.forEach { (k, v) -> if (k != "User-Agent") builder.header(k, v) }
        referer?.let { builder.header("Referer", it) }
        try {
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw httpError(url, resp, text)
                text
            }
        } catch (e: IOException) {
            throw ioError(url, e)
        }
    }

    suspend fun postJsonForm(
        url: String,
        form: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        referer: String? = null
    ): JSONObject = try {
        JSONObject(postForm(url, form, headers, referer))
    } catch (e: JSONException) {
        throw parseError(url, e)
    }
}

/**
 * 一次 200 响应的两个面：解出来的 [text] 和它在网线上的 [bytes]。
 *
 * 这两个数**不是同一个数**：UTF-8 下中文一字 3 字节，`text.length` 只算 1。
 * 体积判据（「这是 1.3KB 的错误页还是 119KB 的数据」）必须用 [bytes]，
 * 否则判据会随响应里中文的多少漂移。
 *
 * [size] 是给 `detail` 用的诊断串，两个数一起写：看见 `bytes≠chars` 就知道
 * 这段响应里有非 ASCII 内容，不用再回去猜。
 */
data class NetBody(val text: String, val bytes: Int) {
    val size: String get() = "bytes=$bytes chars=${text.length}"
}
