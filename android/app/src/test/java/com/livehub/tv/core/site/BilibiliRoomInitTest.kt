package com.livehub.tv.core.site

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 哔哩哔哩 `room_init` 的「房间不存在」判据。
 *
 * 为什么单独抽纯函数：旧实现只把 `-352` 当风控、其余非零码一律 `PARSE_BROKEN`，
 * 于是用户把房间号打错时会看到「接口可能已改版」。这条判据必须能被单测钉住，
 * 否则下一次改 `requireOk` 很容易又把它吞掉。
 */
class BilibiliRoomInitTest {

    @Test
    fun code60004IsRoomNotFound() {
        assertTrue(bilibiliRoomInitMissing(JSONObject("""{"code":60004,"message":"房间不存在"}""")))
    }

    @Test
    fun codeZeroWithoutDataIsRoomNotFound() {
        assertTrue(bilibiliRoomInitMissing(JSONObject("""{"code":0,"message":"0"}""")))
    }

    @Test
    fun codeZeroWithDataIsNormal() {
        assertFalse(
            bilibiliRoomInitMissing(
                JSONObject("""{"code":0,"data":{"room_id":545068,"live_status":1}}""")
            )
        )
    }

    @Test
    fun riskControlIsNotRoomNotFound() {
        // -352 是风控：退避有效、原地重试无效，和「房间号打错」是两个动作
        assertFalse(bilibiliRoomInitMissing(JSONObject("""{"code":-352,"message":"请求过于频繁"}""")))
    }
}
