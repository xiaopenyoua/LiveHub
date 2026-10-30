package com.livehub.tv.core.danmaku

import com.livehub.tv.core.model.Platform
import kotlinx.coroutines.flow.Flow

/**
 * 弹幕事件。礼物与系统消息单独分类，因为它们在屏幕上不该走弹幕轨道：
 * 礼物是一行常驻提示，系统消息是状态说明。
 */
sealed interface DanmakuEvent {
    data class Chat(
        val user: String,
        val text: String,
        val color: Long = 0xFFFFFFFFL,
        val badge: String = ""
    ) : DanmakuEvent

    data class Gift(
        val user: String,
        val giftName: String,
        val count: Int = 1
    ) : DanmakuEvent

    data class System(val text: String) : DanmakuEvent

    data object Connected : DanmakuEvent

    data class Failed(val message: String) : DanmakuEvent
}

/**
 * 一个平台的弹幕连接。stream() 是冷流：collect 时建连，取消时断开。
 * 内部必须自己做心跳与重连，上层不管。
 */
interface DanmakuClient {
    val platform: Platform
    fun stream(roomId: String): Flow<DanmakuEvent>
}

object Danmaku {
    fun of(platform: Platform): DanmakuClient = when (platform) {
        Platform.BILIBILI -> BilibiliDanmaku()
        Platform.DOUYU -> DouyuDanmaku()
        Platform.HUYA -> HuyaDanmaku()
        Platform.DOUYIN -> DouyinDanmaku()
    }
}
