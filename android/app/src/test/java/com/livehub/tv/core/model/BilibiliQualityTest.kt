package com.livehub.tv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **被测对象**：`core/model/Quality.kt` 里那张 qn → 档位的表，以及 `PlayUrl.qn` 的语义。
 *
 * 这张表的每一行都不是我定的，是**哔哩自己在取流响应里返回的 `g_qn_desc`**。
 * 实测条件（2026-10-29，匿名）：
 * ```
 * GET /xlive/web-room/v2/index/getRoomPlayInfo?room_id=7734200&…&qn=10000
 * 响应 current_qn = 150            ← 我们要原画，它给 480P
 * 响应 accept_qn  = [15000, 10000, 400, 250, 150]
 * 响应 g_qn_desc  = 80 流畅 / 150 高清(480P) / 250 超清(720P) / 400 蓝光(1080P)
 *                   / 10000 原画(1080P) / 15000 2K / 20000 4K / 30000 杜比
 * ```
 *
 * 旧实现把 150 叫「流畅」、250 叫「高清」，**整条链低报一档**，而屏幕上那句话正是用户
 * 唯一能看到的画质声明。下面第二条测试就是钉这件事的。
 */
class BilibiliQualityTest {

    @Test
    fun tableMatchesPlatformsOwnDescription() {
        assertEquals(VideoQuality(80, "流畅"), bilibiliQuality(80))
        assertEquals(VideoQuality(150, "高清", "480P"), bilibiliQuality(150))
        assertEquals(VideoQuality(250, "超清", "720P"), bilibiliQuality(250))
        assertEquals(VideoQuality(400, "蓝光", "1080P"), bilibiliQuality(400))
        assertEquals(VideoQuality(10000, "原画", "1080P"), bilibiliQuality(10000))
        assertEquals(VideoQuality(15000, "2K 极清", "2K"), bilibiliQuality(15000))
        assertEquals(VideoQuality(20000, "4K"), bilibiliQuality(20000))
        assertEquals(VideoQuality(30000, "杜比"), bilibiliQuality(30000))
    }

    @Test
    fun offByOneNamingIsGone() {
        // 这条不是为了「跑绿」，是为了让旧实现的红能复现：
        // 旧表 80,150 -> "流畅"、250 -> "高清"，本断言当场炸。
        assertNotEquals("150 是高清 480P，不是流畅", "流畅", bilibiliQuality(150).label)
        assertNotEquals("250 是超清 720P，不是高清", "高清", bilibiliQuality(250).label)
        assertEquals("高清", bilibiliQuality(150).label)
        assertEquals("超清", bilibiliQuality(250).label)
    }

    @Test
    fun anonymousCurrentQnMapsToWhatTheUserActuallySees() {
        // 匿名实际拿到的 current_qn=150。屏幕上该写的是「高清 480P」，
        // 不是「原画」（我们请求的），也不是「流畅」（旧表的错标）。
        val achieved = bilibiliQuality(150)
        assertEquals("高清 480P", achieved.display)
        assertNotEquals("原画", achieved.label)
        assertTrue(achieved.known)
    }

    @Test
    fun unknownQnKeepsRawCodeAndDoesNotInventAName() {
        val q = bilibiliQuality(9999)
        assertEquals(9999, q.qn)
        assertEquals("qn9999", q.label)
        assertTrue("给了码就算已知，只是我们没名字", q.known)
    }

    @Test
    fun zeroQnIsUnknownNotAnInventedTier() {
        // current_qn 缺字段时是 0。**不许猜一档填进来**：猜出来的名字就是假数据。
        val q = bilibiliQuality(0)
        assertEquals(VideoQuality.UNKNOWN, q)
        assertEquals("未知画质", q.label)
        assertFalse(q.known)
        assertEquals("未知画质", q.display)
    }

    @Test
    fun displayDoesNotRepeatLabelWhenResolutionIsBlank() {
        // 80 流畅 没有分辨率、20000 4K 也没有：别拼成「流畅 」「4K 」
        assertEquals("流畅", bilibiliQuality(80).display)
        assertEquals("4K", bilibiliQuality(20000).display)
        assertEquals("原画 1080P", bilibiliQuality(10000).display)
    }

    @Test
    fun playUrlCarriesTheCodeButDefaultsToNoCode() {
        // 哔哩会给 qn；别家平台没有这个概念，必须留 0 而不是编一个数。
        val withQn = PlayUrl("http://x/a.m3u8", "高清", StreamFormat.HLS, emptyMap(), 150)
        assertEquals(150, withQn.qn)

        val withoutQn = PlayUrl("http://x/a.flv", "默认画质", StreamFormat.FLV)
        assertEquals(0, withoutQn.qn)
        assertEquals("默认画质", withoutQn.quality)
    }

    @Test
    fun qnOrderIsTheRankingNotTheResolution() {
        // 蓝光与原画同为 1080P，但蓝光码更低；排序必须看码，不能看分辨率字符串。
        assertTrue(bilibiliQuality(400).resolution == bilibiliQuality(10000).resolution)
        assertTrue(bilibiliQuality(400).qn < bilibiliQuality(10000).qn)
    }
}
