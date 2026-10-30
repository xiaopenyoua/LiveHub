package com.livehub.tv.core.site

import com.livehub.tv.core.model.SiteError
import com.livehub.tv.core.model.SiteException
import com.livehub.tv.core.net.Net
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **被测对象**：`DouyinSite.resolveRoomId` 的入参形状规则（`douyinIdShape`）。
 *
 * 这组断言存在的唯一理由，是它踩过一次并且**编译、运行、肉眼 review 全都看不出来**：
 * 守卫曾被写成「不是 19 位就抛」，于是 4–12 位的 `web_rid` 在本地被判死——而 web_rid
 * （实测有 4 位的）是抖音在这个 App 里**唯一的常规入口**（`capabilities.idInput`
 * 承诺了它）。真机敲一个 `8888` 才暴露。
 *
 * 所以这里不验「抛对了码」就完事，**必须同时验「发了几次请求」**：
 * - 19 位 → 直连 `reflow`，**不许**先去页面；
 * - 4–12 位 → 必须去页面配对，**不许**本地拒绝；
 * - 13–18 位 / 空 / 超长 → `ID_UNRESOLVED` 且 **0 次请求**（连 `Net` 都不进）。
 *
 * 全程把连通性探针设成 false：所有请求都在闸门处短路，**测试不访问公网**。
 * 于是「走到哪条路」靠 `SiteException.url` 判断（reflow 的 url 与页面 url 长得完全不一样），
 * 「发了几次」靠 `Net.requestCount` —— 探针返回 false 时 `Net` 照样计数，但一个字节都不会出去。
 */
class DouyinIdShapeTest {

    private val douyin = DouyinSite()

    @Before
    fun setUp() {
        println("== 被测对象：DouyinSite.resolveRoomId 的形状规则 + 请求次数 ==")
        Net.onConnectivityCheck { false }
    }

    @After
    fun tearDown() {
        Net.onConnectivityCheck { true }
    }

    /** 返回 (抛出的异常, 期间 Net 被调用了几次) */
    private fun call(id: String): Pair<SiteException, Long> {
        val before = Net.requestCount
        val err = runBlocking {
            try {
                douyin.detail(id)
                throw AssertionError("「$id」应该抛 SiteException，但正常返回了")
            } catch (e: SiteException) {
                e
            }
        }
        return err to (Net.requestCount - before)
    }

    // ---------------------------------------------------------------- 19 位

    @Test
    fun nineteenDigitsGoStraightToReflow() {
        val (e, calls) = call("7026727478496348968")
        assertEquals(1L, calls)
        assertTrue("19 位应该直接打 reflow: ${e.url}", e.url!!.contains("reflow/info"))
        assertFalse("19 位不该先去页面配对: ${e.url}", e.url!!.contains("live.douyin.com/70"))
        assertEquals(SiteError.NET_OFFLINE, e.kind)
    }

    // ---------------------------------------------------------------- 4–12 位：web_rid，唯一的常规入口

    @Test
    fun fourDigitWebRidGoesToPageAndIsNotRejectedLocally() {
        val (e, calls) = call("8888")
        assertEquals("web_rid 必须去取页面（1 次），不许在本地判死", 1L, calls)
        assertTrue("应该落在直播间页上: ${e.url}", e.url!!.contains("live.douyin.com/8888"))
        // 关键：**不是** ID_UNRESOLVED。判死的话这里会拿到「房间号位数不对」
        assertEquals(SiteError.NET_OFFLINE, e.kind)
    }

    @Test
    fun twelveDigitWebRidAlsoGoesToPage() {
        val (e, calls) = call("715855351148")
        assertEquals(1L, calls)
        assertTrue(e.url!!.contains("live.douyin.com/715855351148"))
    }

    // ---------------------------------------------------------------- 字母短码

    @Test
    fun shortLinkLettersGoToLandingPage() {
        val (e, calls) = call("v.douyin.com/iJbXkLmN")
        assertEquals(1L, calls)
        assertTrue("字母短码要交给 302 落地页: ${e.url}", e.url!!.contains("v.douyin.com"))
        assertEquals(SiteError.NET_OFFLINE, e.kind)
    }

    // ---------------------------------------------------------------- 位数不对：一个字节都不发

    @Test
    fun fifteenDigitsAreRejectedWithoutAnyRequest() {
        val (e, calls) = call("123456789012345")
        assertEquals(SiteError.ID_UNRESOLVED, e.kind)
        assertEquals("位数不对必须 0 次请求（打了 reflow 会拿 10011，被说成「房间不存在」）", 0L, calls)
        assertTrue("要告诉用户差在哪: ${e.detail}", e.detail!!.contains("位数不对"))
    }

    @Test
    fun emptyInputIsRejectedWithoutAnyRequest() {
        val (e, calls) = call("   ")
        assertEquals(SiteError.ID_UNRESOLVED, e.kind)
        assertEquals(0L, calls)
    }

    @Test
    fun tooLongIsRejectedWithoutAnyRequest() {
        val (e, calls) = call("12345678901234567890")   // 20 位
        assertEquals(SiteError.ID_UNRESOLVED, e.kind)
        assertEquals(0L, calls)
    }

    @Test
    fun linkWithFifteenDigitTailIsRejectedWithoutAnyRequest() {
        val (e, calls) = call("live.douyin.com/123456789012345")
        assertEquals(SiteError.ID_UNRESOLVED, e.kind)
        assertEquals(0L, calls)
    }

    @Test
    fun idUnresolvedBlametheUserNotUs() {
        val (_, _) = call("")
        // 责任归属：这是「用户输入」问题，report=false 才不会进最高优先告警
        assertFalse(SiteError.ID_UNRESOLVED.report)
        // 动作：换一个输入才有用，重发一次结果一样
        assertFalse(SiteError.ID_UNRESOLVED.retryable)
    }

    // ---------------------------------------------------------------- 守卫函数本身

    @Test
    fun isDouyinRoomIdAcceptsOnlyNineteenDigits() {
        assertTrue(isDouyinRoomId("7026727478496348968"))
        assertFalse("15 位不是房间号", isDouyinRoomId("123456789012345"))
        assertFalse("4 位是短号，不是房间号", isDouyinRoomId("8888"))
        assertFalse("字母不是房间号", isDouyinRoomId("v.douyin.com"))
        assertFalse("20 位不是房间号", isDouyinRoomId("12345678901234567890"))
    }
}
