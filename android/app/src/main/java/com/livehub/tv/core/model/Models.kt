package com.livehub.tv.core.model

/**
 * 一个直播间。房间号永远用平台自己的号，不做跨平台统一编号：
 * 斗鱼 4–8 位数字，虎牙最多 8 位数字，哔哩短号与 8–10 位真实号并存，
 * 抖音只有「抖音号」（字母数字串）。所以 roomId 是 String，不是 Long。
 */
data class Room(
    val platform: Platform,
    val roomId: String,
    val title: String,
    val hostName: String,
    val coverUrl: String,
    val areaName: String = "",
    val viewers: Long = 0L,
    val live: Boolean = true,
    /** 封面不够用时（详情接口才有封面）可回填，避免列表与播放页封面不一致 */
    val keyframeUrl: String = ""
) {
    val identity: String get() = "${platform.key}:$roomId"
    val hostLabel: String get() = if (hostName.isBlank()) platform.label else hostName
}

/** 直播分区。id 是平台自己的分区 id，name 是给人看的分区名。 */
data class LiveCategory(
    val platform: Platform,
    val id: String,
    val name: String,
    val parentId: String = "",
    val iconUrl: String = ""
)

enum class StreamFormat { FLV, HLS, RTMP, OTHER }

/**
 * 一条可播放地址。headers 里放 Referer/User-Agent 这类拉流必需的请求头，
 * 播放器必须带上，否则 CDN 直接 403。
 *
 * [quality] 是**已经发生的事**：平台这次真的签发了哪一档（哔哩取响应里的 `current_qn`），
 * 不是我们请求时写的 `qn`。这两者**没有固定关系**——不是「未登录就低几档」那么简单：
 * 同一房间同一次响应里，avc 与 hevc 两个 codec 的 `current_qn` 就可能不同（2026-10-29
 * **匿名**实测：avc=150、hevc=250），而且同一个匿名请求在别的房间能直接拿到 `10000`（原画）。
 * 所以唯一可靠的做法是**读响应里那一格**，
 * 拿请求值当结果上屏就是骗用户，这条别在任何一个 site 里破例。
 * 逐条实测与复算口径（要锁 `credentials`+`protocol`+`format`+`codec` 四个量）见
 * `core/site/BilibiliSite.kt` 文件头「已知风险」第 2 条——**那里是条件的锚，别在这里抄行号**。
 *
 * [qn] 是那一档的原始码（也就是 `current_qn`）。留着它有三个用处：
 * 1. 排查时看得出 [quality] 这行字是从哪个码翻的，而不只是一句人话；
 * 2. 排线路/降级时按码比高低，不用去解析中文名（中文名会被平台改措辞）；
 * 3. 诊断页要能贴出「我们要 10000，实际给 150」这种可复核的事实。
 *
 * 别家平台没有 qn 概念的保持默认 0——**0 的意思是「平台没给码」，不是「码就是 0」**。
 *
 * **非 0 值今天只有哔哩会写**（全仓唯一赋值点 `BilibiliSite`）。别家要给 [qn] 赋非 0 之前先看这句：
 * `ui` 侧今天**不读** [qn] —— `qualityLabel(raw: String)` / `qualityRank(raw: String)` 只有一个字符串
 * 参数，靠 `raw.contains("蓝光"/"超清"/…)` 按**名字**去翻哔哩那张表。所以 [qn] 现在属于
 * 「符号在、读取点不在」：赋了非 0 既不会让显示变对，也不会让它变错。**别照 [qn] 判断现状。**
 *
 * 那条名字匹配不是在将来才会出错，**它今天就在误印**：虎牙的 `sDisplayName` 里有一档就叫「超清」，
 * 按名字会被翻成哔哩的 **「超清 720P」**，排序码落到 **250**。720P 只存在于哔哩那张表里——
 * 虎牙这一档响应里给的是 `iBitRate=2000`（kbps），**没有任何字段说分辨率是 720P**。
 * 它也不是稀有路径：357 个在播虎牙房间实测，取到的档名里「超清」占 57；另一个 30 房样本占 11
 * ⇒ **比例随房间构成大幅变，能引用的只有机制，不是比例**。（这一档为什么会被选中，以及那些分母，
 * 一并写在 `HuyaSite.qualityName` 的注释里，别在这里抄数——数会变。）
 * 排序码 250 还会撞上播放页那条「未登录，最高 1080P」：虎牙取流不走登录态，同一房间的最高档是
 * 「蓝光8M」，两个归因都假。core 修不了它（名字是平台给的，core 分不出平台）；修法只有一条：
 * `ui` 以 `qn > 0` 为门。
 * 判据写在这里而不是写在 `ui` 里，是因为「哪些平台会写非 0」这件事只有 core 说得准。
 */
data class PlayUrl(
    val url: String,
    val quality: String,
    val format: StreamFormat,
    val headers: Map<String, String> = emptyMap(),
    val qn: Int = 0
) {
    val isHls: Boolean get() = format == StreamFormat.HLS || url.contains(".m3u8")
}

/** 播放页要展示的完整房间信息（详情接口返回）。 */
data class RoomDetail(
    val room: Room,
    val announcement: String = "",
    val startedAtEpochSec: Long = 0L,
    val qualities: List<String> = emptyList()
)
