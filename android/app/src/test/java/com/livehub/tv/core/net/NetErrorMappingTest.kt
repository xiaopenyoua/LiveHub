package com.livehub.tv.core.net

import com.livehub.tv.core.model.SiteError
import com.livehub.tv.core.model.SiteException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

/**
 * **被测对象**：`Net` 里那张「HTTP → [SiteError]」表（`docs/04` §3.3.1 第二节）与
 * 「IO 异常 → kind + detail 前缀」的收口逻辑。
 *
 * 为什么必须用真 HTTP 服务端而不是 mock 掉 OkHttp：这张表的三条关键行为是
 * - `404` 归 `HTTP_4XX`（**不是**「房间下播」，下播那时代码是 404）；
 * - `403 + 风控词` 归 `RATE_LIMITED`（退避有效、原地重试无效），不带风控词的 403 才是 `SIGN_INVALID`；
 * - `200` 但 body 不是 JSON 归 `PARSE_BROKEN`（**200 不等于成功**：哔哩 -352、抖音 10011 都是 200）。
 *
 * 这三条都是「状态码 + 响应体」共同决定的，把 OkHttp 换掉就测不到。所以这里起真服务端、
 * 真走一次 socket，只断言 `kind` 和 `detail`。
 *
 * 有两个坑是**离线造不出来**的，只在这个文件里用真异常实例直接喂给 [Net.ioError]：
 * - `SSLException`：需要一个会讲 TLS 的服务端（自签证书要 keytool，平台相关）；
 * - `SocketTimeoutException`：真造要 12s 读超时，测试里白等。
 * 它们验的是**同一个函数**的「类 → kind」，不是另一条路径；`catch (e: IOException)` 有没有
 * 真的接住，由 [disconnectAtStartStillMapsToTimeout] 端到端证明。
 */
class NetErrorMappingTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        println("== 被测对象：Net 的 HTTP→kind 表 + IO 异常归类（docs/04 §3.3.1 第二节）==")
        server = MockWebServer()
        server.start()
        Net.onConnectivityCheck { true }
    }

    @After
    fun tearDown() {
        Net.onConnectivityCheck { true }
        server.shutdown()
    }

    private fun url(path: String = "/x"): String = server.url(path).toString()

    /** 发一次请求，把抛出的 `SiteException` 拿回来；没抛就是 bug，直接失败 */
    private fun call(block: suspend () -> Unit): SiteException = runBlocking {
        try {
            block()
            throw AssertionError("应该抛 SiteException，但正常返回了")
        } catch (e: SiteException) {
            e
        }
    }

    private fun enqueue(code: Int, body: String = ""): String {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        return url()
    }

    // ---------------------------------------------------------------- 1. 无网短路

    @Test
    fun offlineShortCircuitsBeforeAnyByte() {
        Net.onConnectivityCheck { false }
        val before = Net.requestCount
        val e = call { Net.get(url("/never")) }
        assertEquals(SiteError.NET_OFFLINE, e.kind)
        // 没网是**用户在飞行模式**，不是我们的故障：report 必须为 false，否则它会成为 Top1 告警
        assertTrue("NET_OFFLINE 不该上报（怪用户，不怪我们）", !e.kind.report)
        assertTrue("NET_TIMEOUT 才该上报", SiteError.NET_TIMEOUT.report)
        assertEquals("网络未连接", e.detail)
        assertEquals("无网时一个字节都不该发出去", 0, server.requestCount)
        // Net 被调用了 1 次（进了函数），但请求没发出去——这两件事不能混
        assertEquals(1L, Net.requestCount - before)
    }

    // ---------------------------------------------------------------- 2. 401 / 403

    @Test
    fun unauthorizedIsSignInvalid() {
        val e = call { Net.getJson(enqueue(401)) }
        assertEquals(SiteError.SIGN_INVALID, e.kind)
        assertTrue("detail 必须带状态码，否则诊断页看不出是哪一档: ${e.detail}", e.detail!!.startsWith("HTTP 401"))
    }

    @Test
    fun forbiddenWithoutRiskWordsIsSignInvalid() {
        val e = call { Net.getJson(enqueue(403, "forbidden")) }
        assertEquals(SiteError.SIGN_INVALID, e.kind)
        assertTrue(e.detail!!.startsWith("HTTP 403"))
    }

    @Test
    fun forbiddenWithRiskWordsIsRateLimited() {
        // 哔哩的风控码 -352：同一个 403，动作完全相反（退避重试有用 vs 需要登录）
        val e = call { Net.getJson(enqueue(403, """{"code":-352,"message":"请求过于频繁"}""")) }
        assertEquals(SiteError.RATE_LIMITED, e.kind)
        assertTrue("detail 要留证据，不然分不清是哪种 403: ${e.detail}", e.detail!!.contains("-352"))
    }

    // ---------------------------------------------------------------- 3. 429 / 404 / 其他 4xx

    @Test
    fun tooManyRequestsIsRateLimited() {
        val e = call { Net.getJson(enqueue(429)) }
        assertEquals(SiteError.RATE_LIMITED, e.kind)
        assertTrue(e.detail!!.startsWith("HTTP 429"))
    }

    @Test
    fun notFoundIsHttp4xxNotRoomGone() {
        val e = call { Net.getJson(enqueue(404)) }
        // 404 是「域名/路径改了」，不是「房间下播」；下播那时代码往往是 200 + status=4
        assertEquals(SiteError.HTTP_4XX, e.kind)
        assertTrue(e.detail!!.startsWith("HTTP 404"))
    }

    @Test
    fun teapotIsHttp4xx() {
        assertEquals(SiteError.HTTP_4XX, call { Net.getJson(enqueue(418)) }.kind)
    }

    // ---------------------------------------------------------------- 4. 5xx

    @Test
    fun serverErrorIsHttp5xx() {
        val e = call { Net.getJson(enqueue(503)) }
        assertEquals(SiteError.HTTP_5XX, e.kind)
        assertTrue(e.detail!!.startsWith("HTTP 503"))
    }

    // ---------------------------------------------------------------- 5. 200 不等于成功

    @Test
    fun htmlBodyIsParseBroken() {
        val e = call { Net.getJson(enqueue(200, "<html>nope</html>")) }
        assertEquals(SiteError.PARSE_BROKEN, e.kind)
        assertTrue("detail 要说明是「返回体不是 JSON」: ${e.detail}", e.detail!!.contains("不是 JSON"))
    }

    @Test
    fun emptyBodyIsParseBroken() {
        assertEquals(SiteError.PARSE_BROKEN, call { Net.getJson(enqueue(200, "")) }.kind)
    }

    @Test
    fun noContentIsParseBroken() {
        // 204 看着像成功，但没有 body —— 当成功返回会让上游拿到一个空 JSONObject
        assertEquals(SiteError.PARSE_BROKEN, call { Net.getJson(enqueue(204, "")) }.kind)
    }

    // ---------------------------------------------------------------- 6. IO 异常（端到端）

    @Test
    fun disconnectAtStartStillMapsToTimeout() {
        // 这一条专门证明 `catch (e: IOException) → ioError()` **没有落空**：
        // 换异常类型时 catch 静默失效，编译通过、无 warning，只有这里会红。
        // 排 3 条：`retryOnConnectionFailure(true)` 会让 OkHttp 自己再试一次，
        // 队列空了 MockWebServer 会阻塞等下一个响应（测试就挂死了）。
        repeat(3) {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        }
        val e = call { Net.get(url()) }
        assertEquals(SiteError.NET_TIMEOUT, e.kind)
        assertTrue("detail 要留异常类名: ${e.detail}", e.detail!!.contains("Exception"))
        assertTrue("不是 TLS 失败，不许带 TLS 前缀", !e.detail!!.startsWith("TLS"))
    }

    @Test
    fun dnsFailureIsTimeout() {
        val e = call { Net.get("http://no-such-host.invalid/x") }
        assertEquals(SiteError.NET_TIMEOUT, e.kind)
        assertTrue("detail 不该为空（诊断页要能看出是哪一类）: ${e.detail}", !e.detail.isNullOrBlank())
    }

    // ---------------------------------------------------------------- 7. IO 异常（类 → kind，离线造不出来的那两类）

    @Test
    fun tlsHandshakeIsTimeoutWithTlsPrefix() {
        // **TLS 不单列第 13 个码**，靠 detail 的 `TLS:` 前缀区分（API 21 的 TLS 栈是已知坑）。
        // 只断 kind 的话，这个设计等于没验——所以前缀是断言的一部分。
        val e = Net.ioError("https://x/y", SSLHandshakeException("Chain validation failed"))
        assertEquals(SiteError.NET_TIMEOUT, e.kind)
        assertEquals("TLS: SSLHandshakeException: Chain validation failed", e.detail)
        assertTrue(e.detail!!.startsWith("TLS"))
    }

    @Test
    fun socketTimeoutIsTimeoutWithoutTlsPrefix() {
        val e = Net.ioError("http://x/y", SocketTimeoutException("Read timed out"))
        assertEquals(SiteError.NET_TIMEOUT, e.kind)
        assertTrue(e.detail!!.startsWith("SocketTimeoutException"))
        assertTrue("超时不是 TLS，前缀不能撞上", !e.detail!!.startsWith("TLS"))
    }

    @Test
    fun connectionResetIsTimeout() {
        val e = Net.ioError("http://x/y", ConnectException("Connection refused"))
        assertEquals(SiteError.NET_TIMEOUT, e.kind)
        assertTrue(e.detail!!.startsWith("ConnectException"))
    }

    // ---------------------------------------------------------------- 8. 表本身

    @Test
    fun tableHasExactlyThirteenCodes() {
        assertEquals(13, SiteError.entries.size)
    }
}
