package com.livehub.tv.core.site

import com.livehub.tv.core.model.SiteError
import com.livehub.tv.core.net.NetBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **被测对象**：`DouyuSite.betard` 的响应体形状判据（`betardShape` + `betardFailure`）。
 *
 * 这条判据被审出来过一次方向性错误，又被实测推翻过一次，所以这里的断言分两层：
 *
 * 1. **形状本身**（`betardShape`）：空白 / 数据 / 房间不存在页 / 其它 HTML。
 * 2. **形状 → 码**（`betardFailure`）：房间不存在 `report=false`（怪用户），
 *    其余两个 `report=true`（怪我们）。两者 `report` 一样的话，这轮改动等于没做。
 *
 * 中间版本按「非 JSON 且体积 < 4000 字节 = 房间不存在」判，被实测否掉了：
 * `betard/0` 是 1349 字节的「没有找到该房间」、`betard/-1` 是 5239 字节的通用 404 页、
 * `betard/abc` 是 238 字节的 Tengine 403 页、正常房间 119584 字节。体积 238–127820 连续分布，
 * 「小 = 房间不存在」不成立——[smallWafPageIsParseBrokenNotRoomNotFound] 就是那个会误判的样本。
 */
class DouyuBetardShapeTest {

    @Before
    fun setUp() {
        println("== 被测对象：DouyuSite.betard 的响应体形状判据（房间不存在 vs 我们读不懂）==")
    }

    /** 按真实字节数造一个 body：`String.length` 是字符数，判据必须按 `toByteArray` 的字节数 */
    private fun body(s: String) = NetBody(s, s.toByteArray(Charsets.UTF_8).size)

    /**
     * 补到**目标字节数**，不是目标字符数。
     * 用 `padEnd(target)` 会差出「中文个数 × 2」个字节，而这里要断言的就是字节数——
     * 拿字符数补出来的样本去验字节判据，样本本身先错了一次。
     */
    private fun padToBytes(s: String, target: Int): String {
        val cur = s.toByteArray(Charsets.UTF_8).size
        check(cur <= target) { "样本已是 $cur 字节，超过目标 $target" }
        return s + " ".repeat(target - cur)
    }

    /** 实测正常响应：`betard/9999` = 127820 字节、`betard/288016` = 119584 字节，都是 JSON */
    private fun realJson(): String = "{" + "a".repeat(119_586) + "}"

    /** 实测「该房间目前没有开放」页：`betard/1` / `/88888888` / `/999999999` = 1354 字节 */
    private fun absentOpenPage(): String = padToBytes(
        """<!DOCTYPE html><html><head>
        <title>提示信息 -斗鱼</title></head><body>
        <p>该房间目前没有开放</p><p>返回首页</p>
        </body></html>""".trimIndent(),
        1354
    )

    /** 实测「没有找到该房间」页：`betard/0` = 1349 字节，同一张模板、另一句文案 */
    private fun absentNotFoundPage(): String = padToBytes(
        """<!DOCTYPE html><html><head>
        <title>提示信息 -斗鱼</title></head><body>
        <p>没有找到该房间</p><p>返回首页</p>
        </body></html>""".trimIndent(),
        1349
    )

    /** 实测通用 404 页：`betard/-1` = 5239 字节，**同一张 `提示信息` 模板**，但没有房间相关文案 */
    private fun generic404Page(): String = padToBytes(
        """<!DOCTYPE html><html><head>
        <title>提示信息 -斗鱼</title><style>.mod_404{min-height:850px}</style>
        </head><body><div class="main_404">页面不见了</div></body></html>""".trimIndent(),
        5239
    )

    /** 实测 Tengine 拦截页的正文：`betard/abc` 走的是 HTTP 403（238 字节），这里假设它被 200 吐出来 */
    private fun smallWafPage(): String =
        "<html><head><title>403 Forbidden</title></head><body>" +
            "You don't have permission to access the URL on this server.</body></html>"

    // ------------------------------------------------------------ 形状

    @Test
    fun realJsonIsNotAFailure() {
        assertEquals(119_588, realJson().length)
        assertEquals(BetardShape.JSON, betardShape(body(realJson())))
    }

    @Test
    fun bothAbsentRoomMarkersAreRoomNotFound() {
        assertEquals(1354, body(absentOpenPage()).bytes)
        assertEquals(1349, body(absentNotFoundPage()).bytes)
        assertEquals(BetardShape.NOT_FOUND_PAGE, betardShape(body(absentOpenPage())))
        assertEquals(BetardShape.NOT_FOUND_PAGE, betardShape(body(absentNotFoundPage())))
    }

    @Test
    fun absentDecisionDoesNotDependOnSize() {
        // 斗鱼改版后错误页可能变胖：标记串在，就还是「房间不存在」，不因为体积翻界改判
        val fat = absentOpenPage() + "x".repeat(200_000)
        assertEquals(BetardShape.NOT_FOUND_PAGE, betardShape(body(fat)))
    }

    @Test
    fun generic404PageIsNotRoomNotFound() {
        // 这张页和「房间不存在」共用 <title>提示信息 -斗鱼</title>，只匹配标题就会把
        // 「路径/域名写错了」说成「用户房间号打错了」——所以判据是正文里那句房间文案
        assertEquals(5239, body(generic404Page()).bytes)
        assertEquals(BetardShape.OTHER_HTML, betardShape(body(generic404Page())))
    }

    @Test
    fun smallWafPageIsParseBrokenNotRoomNotFound() {
        // **这条例题是体积判据的反例**：真实那张 Tengine 拦截页 238 字节 < 4000，
        // 旧判据会判 ROOM_NOT_FOUND(report=false)，于是风控/网关拦截整站静默失效。
        // 按标记串判，它落到 OTHER_HTML(report=true)，我们被叫醒。
        val b = body(smallWafPage())
        assertTrue("样本必须小于旧门限 4000 才有说服力：实际 ${b.bytes}", b.bytes < 4000)
        assertEquals(BetardShape.OTHER_HTML, betardShape(b))
        assertEquals(
            SiteError.PARSE_BROKEN,
            betardFailure(BetardShape.OTHER_HTML, "u", "1", b).kind
        )
    }

    @Test
    fun emptyBodyIsItsOwnShape() {
        // 空体不能落进 OTHER_HTML：「网关给了空响应」和「平台改版了」是两条不同的排查路径
        assertEquals(BetardShape.EMPTY, betardShape(body("")))
        assertEquals(BetardShape.EMPTY, betardShape(body("   \n\t ")))
    }

    @Test
    fun bomDoesNotTurnRealJsonIntoAnErrorPage() {
        // BOM 会让 startsWith("{") 假失败，于是 119KB 的正常响应被判成提示页
        assertEquals(BetardShape.JSON, betardShape(body("\uFEFF" + realJson())))
        assertEquals(BetardShape.JSON, betardShape(body("  \n" + realJson())))
    }

    // ------------------------------------------------------------ 形状 → 码

    @Test
    fun absentPageMapsToRoomNotFound() {
        val e = betardFailure(BetardShape.NOT_FOUND_PAGE, "u", "1", body(absentOpenPage()))
        assertEquals(SiteError.ROOM_NOT_FOUND, e.kind)
        assertTrue("detail 要能看出是命中了哪类页", e.detail!!.contains("不存在"))
    }

    @Test
    fun emptyAndOtherHtmlMapToParseBroken() {
        assertEquals(
            SiteError.PARSE_BROKEN,
            betardFailure(BetardShape.EMPTY, "u", "1", body("")).kind
        )
        assertEquals(
            SiteError.PARSE_BROKEN,
            betardFailure(BetardShape.OTHER_HTML, "u", "1", body(generic404Page())).kind
        )
    }

    @Test
    fun jsonIsNotAFailureShape() {
        // JSON 不是失败形状：拿它问「该抛什么」是调用方的 bug，必须当场炸，
        // 而不是返回一个「凑合的码」——返回凑合的码就是这次的返工原因
        assertThrows(IllegalStateException::class.java) {
            betardFailure(BetardShape.JSON, "u", "1", body(realJson()))
        }
    }

    @Test
    fun theTwoOutcomesHaveOppositeResponsibility() {
        // 这是整条改动的意义：房间不存在不叫醒我们（怪用户），读不懂必须叫醒（怪我们）
        assertFalse("房间不存在不该上报", SiteError.ROOM_NOT_FOUND.report)
        assertTrue("读不懂必须上报", SiteError.PARSE_BROKEN.report)
        assertEquals(
            "两者 retryable 一致（都不该给重试按钮）",
            SiteError.ROOM_NOT_FOUND.retryable, SiteError.PARSE_BROKEN.retryable
        )
    }

    // ------------------------------------------------------------ 单位

    @Test
    fun sizeReportsBytesAndCharsSeparately() {
        // detail 里 `bytes=` 必须是真字节数。中文页 chars < bytes，两个数一起写出来，
        // 下一个人 `curl | wc -c` 才对得上
        val b = body(absentOpenPage())
        assertEquals(1354, b.bytes)
        assertTrue("中文页面 chars 必须小于 bytes，否则这组样本证明不了单位问题", b.text.length < b.bytes)
        assertTrue(b.size.contains("bytes=1354"))
        assertTrue(b.size.contains("chars=${b.text.length}"))
        assertTrue(betardFailure(BetardShape.OTHER_HTML, "u", "1", b).detail!!.contains("bytes=1354"))
    }
}
