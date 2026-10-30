package com.livehub.tv.core.model

import com.livehub.tv.core.site.SiteFailure
import com.livehub.tv.core.site.toSiteError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **被测对象**：两个「异常 → 归责」入口，它们是**同一份映射的两个出口**，不是两张表。
 *
 * - [toSiteError]（`Throwable → SiteError`）：公开给 UI 的。C 段之前它是一行委托
 *   （`= Sites.kindOf(this)`），现在 `kindOf` 已删，`when` 就在它体内——**全仓一份**。
 *   存在的理由是 `Async.Failed(e.message ?: "取数失败")` 会把
 *   `TLS: SSLHandshakeException` 或 `PARSE_BROKEN` 直接印到首页的错误卡片上。
 * - [toSiteException]（`Throwable → SiteException`）：给兜底 `catch` 用，保住
 *   `kind` + `detail` + `cause`。
 *
 * 两个名字必须不同：同名时 `e.toSiteError()` 会在「返回 SiteError」和
 * 「返回 SiteException（带默认参数）」之间**歧义**，报的是 overload resolution ambiguity。
 *
 * 还有一条历史：这里原本测的是 `SiteTimeout`（聚合层的 `private class SiteTimeout`）。
 * 那个类被删了——**「超时」有归责含义，不能表示成一个匿名 Exception**，
 * 否则任何调用方判不到它，落到 `PARSE_BROKEN(report=true)`：用户 Wi-Fi 差，
 * 首页说「接口可能已改版」还怪我们。所以下面有一条专门钉住「超时仍是 NET_TIMEOUT」。
 */
class ToSiteExceptionTest {

    @Before
    fun setUp() {
        println("== 被测对象：toSiteError / toSiteException（同一份映射的两个出口）==")
    }

    @Test
    fun siteExceptionPassesThroughUnchanged() {
        val original = SiteException(SiteError.ROOM_NOT_FOUND, "https://x/betard/1", "斗鱼：房间 1 不存在")
        val mapped = original.toSiteException("https://other")
        assertSame("已经分好类的异常不许被重新包装", original, mapped)
        assertEquals("https://x/betard/1", mapped.url)
        assertEquals("斗鱼：房间 1 不存在", mapped.detail)
        assertEquals(SiteError.ROOM_NOT_FOUND, original.toSiteError())
    }

    @Test
    fun aggregateTimeoutStaysNetTimeout() {
        // 聚合层超时现在抛的是 SiteException(NET_TIMEOUT)，不再是匿名的 SiteTimeout。
        // 这条钉的是「不要因为类型没了就让它退回 PARSE_BROKEN」。
        val timeout = SiteException(SiteError.NET_TIMEOUT, detail = "单站超时 6000ms")
        assertEquals(SiteError.NET_TIMEOUT, timeout.toSiteError())
        assertNotEquals("超时不许退化成人人都走的兜底桶", SiteError.PARSE_BROKEN, timeout.toSiteError())
        assertTrue("超时可重试", SiteError.NET_TIMEOUT.retryable)
        assertEquals("detail 是诊断串，原样保留", "单站超时 6000ms", timeout.toSiteException().detail)
    }

    @Test
    fun unexpectedThrowableBecomesParseBrokenAndReports() {
        val boom = IllegalStateException("playUrls 返回了 0 条")
        val mapped = boom.toSiteException("https://x/play/1")
        assertEquals(SiteError.PARSE_BROKEN, mapped.kind)
        assertTrue("归不了类必须上报", mapped.kind.report)
        assertEquals(SiteError.PARSE_BROKEN, boom.toSiteError())
        assertTrue("detail 要能看出是什么异常", mapped.detail!!.contains("IllegalStateException"))
        assertTrue("detail 要带原始 message", mapped.detail!!.contains("playUrls 返回了 0 条"))
        assertSame("cause 不许丢，否则没有栈", boom, mapped.cause)
    }

    @Test
    fun noThrowableMapsToASilentCode() {
        // 兜底路径上最危险的是悄悄落进一个 report=false 的码（比如 ROOM_NOT_FOUND）；
        // 那会让「我们炸了」长成「用户房间号打错了」
        val kinds = listOf(
            RuntimeException(), IllegalStateException(), NullPointerException(), Error()
        ).map { it.toSiteError() }
        assertTrue("兜底不许映到不上报的码：$kinds", kinds.all { it.report })
    }

    @Test
    fun siteFailureFactoryKeepsKind() {
        // `SiteFailure.of` 是 `SiteFailure(platform, String)` 过渡构造器的替代品：
        // 老构造器把异常重新字符串化，kind 一律 PARSE_BROKEN——超时也会变成「我们没看懂」
        val timeout = SiteFailure.of(Platform.HUYA, SiteException(SiteError.NET_TIMEOUT, detail = "单站超时 6000ms"))
        assertEquals(SiteError.NET_TIMEOUT, timeout.kind)
        assertEquals("单站超时 6000ms", timeout.detail)

        val unknown = SiteFailure.of(Platform.DOUYU, RuntimeException("boom"))
        assertEquals(SiteError.PARSE_BROKEN, unknown.kind)
        assertTrue("诊断串留在 detail，不当人话", unknown.detail!!.contains("RuntimeException"))
    }
}
