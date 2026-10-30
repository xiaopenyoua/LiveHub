package com.livehub.tv

import com.livehub.tv.core.model.SiteError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源集自检：只验证 `app/src/test` 真的被 Gradle 认到、并且看得见 `main` 里的类。
 *
 * 为什么要单独一个文件先跑：单测写完 13 条再发现「源集没被认到 / 测试看不到 main」，
 * 会以为是断言写错了，白查半小时。所以先一个 `assertTrue(true)` 空跑通过，再写真的。
 */
class SourceSetSmokeTest {

    @Test
    fun sourceSetIsWired() {
        println("== 被测对象：app/src/test 源集本身（能否编译、能否看见 main 的类）==")
        assertTrue(true)
    }

    @Test
    fun mainSourcesAreVisible() {
        // 编不过就是答案：说明 test 源集看不到 main（Kotlin friend module 没配好）
        assertEquals(13, SiteError.entries.size)
    }
}
