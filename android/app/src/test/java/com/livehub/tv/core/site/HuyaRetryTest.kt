package com.livehub.tv.core.site

import com.livehub.tv.core.model.SiteError
import com.livehub.tv.core.model.SiteException
import com.livehub.tv.core.net.Net
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * **被测对象**：`HuyaSite.fetchProfile` 的「非 2xx 重试一次」。
 *
 * 这条只能用**请求次数**验，不能用「抛对了码」验：
 * `catch (e: IllegalStateException)` 在异常类型换成 `SiteException` 之后**完全不匹配**，
 * 而 Kotlin 不会为「catch 落空」报错或警告——编译通过、没有日志、重试悄悄变成一次就失败。
 * 唯一能看见的现象就是请求次数从 2 变成 1。
 *
 * 做法：把连通性探针设成 false，每次请求在闸门处抛**可重试**的 `NET_OFFLINE`
 * （`retryable = true`，正好落在 `fetchProfile` 的 `catch (e: SiteException)` 位置上），
 * 然后数 `Net.requestCount`。全程不访问公网。
 */
class HuyaRetryTest {

    private val huya = HuyaSite()

    @Before
    fun setUp() {
        println("== 被测对象：HuyaSite.fetchProfile 的重试次数（硬规矩 6）==")
        Net.onConnectivityCheck { false }
    }

    @After
    fun tearDown() {
        Net.onConnectivityCheck { true }
    }

    @Test
    fun retryableFailureIsRetriedExactlyOnce() {
        val before = Net.requestCount
        val err = runBlocking {
            try {
                huya.detail("123456")
                throw AssertionError("应该抛 SiteException，但正常返回了")
            } catch (e: SiteException) {
                e
            }
        }
        val calls = Net.requestCount - before
        assertEquals("可重试失败必须重发一次：1 次 = catch 落空，3 次 = 重试失控", 2L, calls)
        // 期望值是 NET_OFFLINE（探针触发的那个），不是 NET_TIMEOUT。
        // 这个断言钉的是「没有把 kind 降级成 PARSE_BROKEN」——降级了用户会看到
        // 「接口可能改版」，而真实原因是没网。
        assertEquals(SiteError.NET_OFFLINE, err.kind)
        assertNotEquals(SiteError.PARSE_BROKEN, err.kind)
    }

    @Test
    fun nonRetryableKindsAreNotRetried() {
        // 这一条不联网：`fetchProfile` 的重试条件就是 `kind.retryable`，
        // 所以表对了逻辑就对，表错了逻辑必错。三个「重发也没用」的码必须全 false。
        assertEquals(false, SiteError.ROOM_NOT_FOUND.retryable)
        assertEquals(false, SiteError.PARSE_BROKEN.retryable)
        assertEquals(false, SiteError.STREAM_OFFLINE.retryable)
        // 反过来：这三种重发一次可能就通了
        assertEquals(true, SiteError.NET_TIMEOUT.retryable)
        assertEquals(true, SiteError.RATE_LIMITED.retryable)
        assertEquals(true, SiteError.HTTP_5XX.retryable)
    }

    @Test
    fun http4xxIsNotRetried() {
        // 4xx 是「参数/路径/权限错」：重发一次拿回来的必然是同一个 4xx，只是让用户多等一趟。
        // 这一条同时锁住两个决定：
        // 1. 虎牙不会对 404 白重试（行为由上面那条「可重试的会重发一次」+ 本表单共同锁死：
        //    重试循环读的就是 `kind.retryable`，不是写死的次数）；
        // 2. `HTTP_4XX` 与 `HTTP_5XX` 从此取值不同 ⇒ 按 SiteError 头顶「加码门槛」第 2 条，
        //    这两个码**必须分开、不许合并**（前端问过要不要合并，答案在这里）。
        assertEquals(false, SiteError.HTTP_4XX.retryable)
        assertNotEquals(
            "4xx/5xx 的 retryable 同值就该合并，不同值就必须拆开",
            SiteError.HTTP_4XX.retryable, SiteError.HTTP_5XX.retryable
        )
        // 上报仍然要：4xx 往往是「域名/路径改了」，正是要叫醒我们的那类信号
        assertEquals(true, SiteError.HTTP_4XX.report)
    }
}
