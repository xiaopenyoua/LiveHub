package com.livehub.tv.core.site

/**
 * 一个平台在**本版本**真实具备的能力。
 *
 * 为什么需要它：`LiveSite` 的 6 个方法对所有平台签名一致，但抖音的列表/分区/搜索
 * 现在拿不到数据（a_bogus 签名）。如果 UI 只看「方法返回空列表」，它无法区分
 * 「这个平台今天没人播」和「这个平台本版本根本没实现」，只能猜、只能写死平台判断，
 * 一改实现就和 UI 漂移。所以把「本版本事实」显式声明出来，UI 据此决定占位文案。
 *
 * 约束：**任何返回空列表的实现，必须把对应位写成 false。空列表 + true 是欺骗。**
 * 默认全 true —— 哔哩/斗鱼/虎牙确实全支持，只有抖音显式关掉几项。
 */
data class SiteCapabilities(
    /** 是否有 HLS 线路（tvOS 只能放 HLS，这条决定 tvOS 版能不能上） */
    val hls: Boolean = true,
    /** 是否有 FLV 线路（Android 上通常比 HLS 更快出首帧） */
    val flv: Boolean = true,
    /** 首页推荐是否可用 */
    val recommend: Boolean = true,
    /** 分区列表是否可用 */
    val categories: Boolean = true,
    /** 搜索是否可用 */
    val search: Boolean = true,
    /** 弹幕是否可用 */
    val danmaku: Boolean = true,
    /** 打开房间时用户能输入什么，UI 据此给输入框提示与解析失败文案 */
    val idInput: IdInput = IdInput.RoomId,
    /** 本版本实际能拿到的最高清晰度名（如「原画」）；null 表示随房间变、无法静态声明 */
    val maxQualityLabel: String? = null
)

/**
 * 房间号输入形态。
 *
 * 解析归 core/site，不归 UI：`DouyinSite.resolveRoomId` 已经能吃下面这几种形态，
 * 如果让 UI 自己剥 URL，「哪些形态能用」就会有两份会各自漂移的实现。
 */
enum class IdInput {
    /** 只认平台原始房间号（哔哩/斗鱼/虎牙） */
    RoomId,

    /** 房间号、web_rid（分享文案里的 6–12 位）、`live.douyin.com/xxx` 或整条链接都吃 */
    RoomIdOrWebRidOrLink
}
