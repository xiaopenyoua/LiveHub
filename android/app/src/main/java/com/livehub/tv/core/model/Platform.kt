package com.livehub.tv.core.model

/**
 * 四个平台。key 用于持久化与接口路由，绝不随 UI 文案变化。
 * color 是各平台品牌色，只用在「平台」这一处标识上，不做整页染色。
 */
enum class Platform(
    val key: String,
    val label: String,
    val color: Long,
    val host: String
) {
    BILIBILI("bilibili", "哔哩哔哩", 0xFFFB7299, "live.bilibili.com"),
    DOUYU("douyu", "斗鱼", 0xFFFF7D23, "www.douyu.com"),
    HUYA("huya", "虎牙", 0xFFFFA200, "www.huya.com"),
    DOUYIN("douyin", "抖音", 0xFF25F4EE, "live.douyin.com");

    companion object {
        fun of(key: String?): Platform? = entries.firstOrNull { it.key == key }
    }
}
