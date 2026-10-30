package com.livehub.tv.core.site

import com.livehub.tv.core.model.LiveCategory
import com.livehub.tv.core.model.PlayUrl
import com.livehub.tv.core.model.Platform
import com.livehub.tv.core.model.Room
import com.livehub.tv.core.model.RoomDetail

/**
 * 一个平台的全部取数能力。
 *
 * 为什么是接口：四家接口的字段、翻页方式、鉴权手段完全不同，但 UI 只认这 6 个方法。
 * 平台改版时只改对应实现，播放页、首页、搜索页一行都不用动。
 *
 * 约定：
 * - 所有方法抛异常表示「这次取数失败」，由上层决定是否降级提示；不要返回 null 假装成功。
 * - roomId 一律用平台原始房间号字符串。
 */
interface LiveSite {

    val platform: Platform

    /**
     * 本版本真实具备的能力。
     *
     * 故意是 `val` 而不是 `suspend fun`：这是编译期事实，UI 在 composition 里要同步读，
     * 不能为了一个常量去开协程、更不能在读不到时先假设 true。
     * 默认全支持；返回空列表的实现必须把对应位改 false，见 [SiteCapabilities]。
     */
    val capabilities: SiteCapabilities get() = SiteCapabilities()

    /** 首页推荐 / 热门。page 从 1 开始。 */
    suspend fun recommend(page: Int = 1): List<Room>

    /** 全部分区（含二级分区的平坦化结果，parentId 标明归属）。 */
    suspend fun categories(): List<LiveCategory>

    /** 某分区下的直播间。 */
    suspend fun categoryRooms(categoryId: String, page: Int = 1): List<Room>

    /** 关键词搜索。支持房间号、主播名、标题。 */
    suspend fun search(keyword: String, page: Int = 1): List<Room>

    /** 房间详情。房间不存在或已关播时尽量返回带 live=false 的对象。 */
    suspend fun detail(roomId: String): RoomDetail?

    /**
     * 取真实流地址，按清晰度从高到低排列。
     *
     * [preferQuality] 是用户上次选的档位，取值是**平台自己的数字码串**
     * （`"150"` / `"250"` / `"400"`，见 `Prefs.preferredQuality`），
     * **不是「原画」「蓝光」这种名字**——这里曾经写「清晰度名（如 "原画" / "蓝光"）」，
     * 但从来没有任何一处传过名字。参数文档写错名字，等于教下一个调用方传错。
     *
     * **今天四个实现全都没读它**：2026-10-29 数过一次，`preferQuality` 在
     * `Bilibili` / `Douyu` / `Huya` / `Douyin` 四个实现里各只出现 1 次，就是签名本身
     * （`BilibiliSite` 的请求串里 `qn` 还是写死的 `10000`）。所以别指望「传了 400 就拿到 400」。
     *
     * 要接上它，先想清楚「平台不给这一档时怎么表现」——否则就变成又一个
     * 「界面上有承诺、实现里没有读取点」的符号，和 `SiteError.retryable` 同一族。
     */
    suspend fun playUrls(roomId: String, preferQuality: String? = null): List<PlayUrl>
}
