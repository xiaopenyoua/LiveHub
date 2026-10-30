package com.livehub.tv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * **被测对象**：[logFields]——弹幕层/取流层打日志时唯一该用的入口。
 *
 * 它存在的理由是一条结构性事实，不是编码风格：
 * [SiteException] 的构造器是 `Exception(detail ?: kind.name, cause)`，所以
 * `Log.w(TAG, "… ${e.message}")` 的实际行为是**「有 detail 就丢归责码，没 detail 才有」**。
 * 而 [com.livehub.tv.core.net.Net] 的四个抛出点（`httpError` / `ioError` /
 * `parseError` / `NET_OFFLINE`）`detail` **都不可能为空**——也就是说凡是 `Net` 抛出来的
 * `SiteException`，用 `message` 打日志就永远只能看见 detail。
 *
 * 下面第三条 [messageAloneNeverCarriesKind] 就是在**钉住上面那个前提**。它不是
 * 「测一个显然的东西」：哪天有人把 `message` 改成带 kind 的格式，这条会红，逼下一个
 * 人重新读一遍 `logFields` 的 KDoc，而不是让「${e.message} 够用」悄悄变成真的。
 */
class SiteExceptionLogFieldsTest {

    @Before
    fun setUp() {
        println("== 被测对象：Throwable.logFields（kind / detail 两个独立字段）==")
    }

    @Test
    fun kindAndDetailAreBothPresent() {
        val e = SiteException(SiteError.SIGN_INVALID, "https://x/room_init", "HTTP 403 body=risk control")

        val line = e.logFields

        assertTrue("归责码必须在：$line", line.contains("kind=SIGN_INVALID"))
        assertTrue("细节必须在：$line", line.contains("detail=HTTP 403 body=risk control"))
    }

    /** 没有 detail 时不能退化成 `kind=DANMAKU_PROTOCOL DANMAKU_PROTOCOL` 那种重复。 */
    @Test
    fun missingDetailDoesNotRepeatKind() {
        val e = SiteException(SiteError.DANMAKU_PROTOCOL)

        val line = e.logFields

        assertTrue("归责码必须在：$line", line.contains("kind=DANMAKU_PROTOCOL"))
        assertTrue("detail 缺失要显式留一格：$line", line.contains("detail=-"))
        assertEquals(
            "kind 只能出现一次，否则等于又把两个字段拼回一句",
            1, Regex("DANMAKU_PROTOCOL").findAll(line).count()
        )
    }

    /**
     * **这条是前提钉子，不是废话。**
     *
     * 断言 `message` 里**看不见** kind——因为 detail 非空时 `message` 就是 detail。
     * 这正是 `logFields` 存在的全部理由。若此条变红，说明 `SiteException.message`
     * 的语义被改了，`logFields` 的 KDoc 里那张「四个抛出点」的表也要一起重看。
     */
    @Test
    fun messageAloneNeverCarriesKind() {
        val e = SiteException(SiteError.RATE_LIMITED, "https://x/danmu_info", "HTTP 429 body=too many")

        assertFalse(
            "前提变了：message 现在能看见 kind 了（${e.message}），回头重读 logFields 的 KDoc",
            e.message.orEmpty().contains("RATE_LIMITED")
        )
        assertEquals("message 在 detail 非空时就是 detail", "HTTP 429 body=too many", e.message)
    }

    /** 非 [SiteException] 也要能直接用，别逼调用方先写个 `as?`。 */
    @Test
    fun plainThrowableIsLabelledAsUnknownKind() {
        val e = IOException("unexpected end of stream")

        val line = e.logFields

        assertTrue("必须显式声明没有归责码：$line", line.contains("kind=-"))
        assertTrue("原始信息不能丢：$line", line.contains("detail=unexpected end of stream"))
    }

    /** message 为 null 的异常不能让日志出现 `detail=null` 这种半截字符串。 */
    @Test
    fun nullMessageFallsBackToClassName() {
        val e = IllegalStateException()

        val line = e.logFields

        assertTrue("要有可辨认的来源：$line", line.contains("detail=IllegalStateException"))
    }
}
