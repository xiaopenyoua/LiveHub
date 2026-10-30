package com.livehub.tv.core.site

import com.livehub.tv.core.model.Platform
import com.livehub.tv.core.model.Room
import com.livehub.tv.core.model.SiteError
import com.livehub.tv.core.model.SiteException
import com.livehub.tv.core.model.toSiteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 单家为什么没出内容。UI 拿 [kind] 查 `ui/strings/SiteErrorText.kt` 得到一行说明
 * （「哔哩哔哩 超时」），**不读 [detail] 上屏**。
 *
 * **[kind] 是判据，[detail] 是诊断细节**（原始 body / 状态码 / `单站超时 6000ms`）。
 * 两者分工是整套错误码的意义所在：用户看到什么由 `kind` 决定，我们怎么描述由 `detail`
 * 记录。**谁都不许从 [detail] 反推 kind**（§3.3.1 硬规矩 1）——那是拿人话当协议。
 *
 * 这里原来还有一个 `reason: String`（过渡期文案）和一个 `SiteFailure(platform, String)`
 * 过渡构造器，C 段已一并删除：`reason` 把「异常」重新字符串化成一句话，`kind` 一律填
 * `PARSE_BROKEN`——超时也被说成「我们没看懂接口」。删它们的收益不是少两行代码，是
 * **少一条把归责丢掉的路径**：现在想造一个 `SiteFailure`，只剩 [of] 这一个入口。
 */
data class SiteFailure(
    val platform: Platform,
    val kind: SiteError,
    val detail: String?
) {
    companion object {
        /**
         * 由异常直接构造：`kind` 从 [toSiteError] 取，`detail` 取诊断串。
         *
         * **这是唯一的入口。** 别再加 `SiteFailure(platform, 一句话)` 那种构造器：
         * 它必须先把异常压成字符串才填得出参数，压的过程 `kind` 就丢了，超时会被记成
         * `PARSE_BROKEN`（`report = true`）——用户 Wi-Fi 差，账记到我们头上。
         *
         * `detail` 里装的是**诊断细节**，不是给人看的成品文案。这条别破例：
         * 破了就是又一次把 `detail` 变成第二份契约，和 `kind` 各说一套。
         */
        fun of(platform: Platform, t: Throwable): SiteFailure =
            SiteFailure(platform, t.toSiteError(), t.toSiteException().detail)
    }
}

/**
 * 聚合结果。
 *
 * `rooms` 为空**不等于**「今天没人直播」——可能是四家全挂了。
 * 设计规范要求「状态诚实」，所以聚合层必须把失败一起交上去，
 * 让 UI 能说「哔哩哔哩 超时」，而不是画一个空荡荡的首页让用户以为平台死了。
 *
 * 这里原来的 `note(): String?` 已随 C 段删除：它把 `failures` 拼成一行灰字，
 * 而那行字只可能来自 `SiteFailure.reason`（人话），等于在 core 里留了第二张文案表。
 * 现在拼提示语是 ui 的事，它手上有 `kind`，查表即可。
 */
data class FeedResult(
    val rooms: List<Room>,
    val failures: List<SiteFailure>
) {
    val allFailed: Boolean get() = rooms.isEmpty() && failures.isNotEmpty()
}

/**
 * 平台注册表 + 跨平台聚合。
 *
 * 「聚合」只在两个地方做：首页信息流和全平台搜索。其余场景（分类、关注、播放）
 * 都是单平台行为，混在一起没有意义。
 */
object Sites {

    val all: List<LiveSite> = listOf(
        BilibiliSite(),
        DouyuSite(),
        HuyaSite(),
        DouyinSite()
    )

    fun of(platform: Platform): LiveSite =
        all.firstOrNull { it.platform == platform }
            ?: throw IllegalArgumentException("unknown platform $platform")

    /**
     * 单平台失败包成 Result，但**不能吞掉 CancellationException**：
     * 页面切走/用户离开时协程被取消，若在这里被吃掉并当成「失败」，
     * 上层会把它显示成「哔哩哔哩超时」——用户什么都没干就看到一句错误。
     *
     * 超时（[withTimeoutOrNull] 返回 null）归 [SiteError.NET_TIMEOUT]，和 `Net` 的连接超时
     * 同一个码：对用户来说「哪一段慢」没区别，能做的动作都是再试一次。
     *
     * **这里原来有一个 `private class SiteTimeout : Exception("超时")`，已删除。**
     * 那个类的存在让「超时」变成了一个匿名异常：任何调用方想知道「这是超时」都得靠
     * `is` 判断一个私有类型，判不到就落到「我们没读懂接口」（`PARSE_BROKEN`，
     * `report = true`）——**用户 Wi-Fi 差导致的超时，首页会说「接口可能已改版」，
     * 并且怪到我们头上**。根因不是「两份映射」，是「一个有归责含义的失败被表示成了
     * 一个没有归责信息的类型」。所以修法不是让两份映射互相委托，是把它消掉。
     */
    private suspend fun siteResult(
        timeoutMs: Long,
        block: suspend () -> List<Room>
    ): Result<List<Room>> =
        try {
            val rooms = withTimeoutOrNull(timeoutMs) { block() }
                ?: return Result.failure(
                    // detail 只进诊断页，不是给用户看的话；`timeoutMs` 写进来是为了
                    // 一眼分清「单站被裁」和「连接超时」——两者的码一样，动作也一样
                    SiteException(SiteError.NET_TIMEOUT, detail = "单站超时 ${timeoutMs}ms")
                )
            Result.success(rooms)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }

    /**
     * 真正能出内容的平台：**按能力表过滤，不按平台名**。
     *
     * 为什么要过滤而不是「发出去让它自己返回空」：
     * - 用户得等这一家的超时才明白「这家本来就不支持」，白等 6 秒；
     * - `failures` 里会多一条「抖音 暂不可用」，把「本版没做」和「今天挂了」混成同一件事，
     *   诊断页的失败率被永久污染（一家必然失败的平台天天占 Top1）。
     *
     * 加一家新平台、或抖音的 a_bogus 签名补上了，都只改 `capabilities`，这里不用动。
     */
    fun recommendable(): List<LiveSite> = all.filter { it.capabilities.recommend }

    fun searchable(): List<LiveSite> = all.filter { it.capabilities.search }

    /**
     * 异常 → 一条失败归因。**只是 [SiteFailure.of] 的一层转发，不放任何映射**：
     * 这里曾经长过 `SiteFailure(platform, kindOf(t), describe(t))`，那意味着
     * 「取 kind」「取文案」两步各有一份实现，而两处都能编译通过。
     */
    private fun failureOf(platform: Platform, t: Throwable) = SiteFailure.of(platform, t)

    /** 首页信息流（带失败归因）：能出内容的几家并发取，谁慢谁被裁掉，不让一个平台拖住整屏。 */
    suspend fun homeFeedDetailed(perSiteTimeoutMs: Long = 6_000): FeedResult = coroutineScope {
        val jobs = recommendable().map { site ->
            async { site to siteResult(perSiteTimeoutMs) { site.recommend(1) } }
        }
        val collected = jobs.map { it.await() }
        FeedResult(
            rooms = collected.mapNotNull { it.second.getOrNull() }.flatten(),
            failures = collected.mapNotNull { (site, r) ->
                r.exceptionOrNull()?.let { failureOf(site.platform, it) }
            }
        )
    }

    /** 只要数据、不在乎谁失败的调用方用这个 */
    suspend fun homeFeed(perSiteTimeoutMs: Long = 6_000): List<Room> =
        homeFeedDetailed(perSiteTimeoutMs).rooms

    /**
     * 全平台搜索（带失败归因）。**只发给 `search = true` 的几家**：
     * 抖音首版没有原生搜索接口（要 a_bogus 签名），发过去必然是空列表 + 白等超时，
     * 而且会把「本版不支持」记成一条失败。
     */
    suspend fun searchAllDetailed(keyword: String, perSiteTimeoutMs: Long = 6_000): FeedResult = coroutineScope {
        val jobs = searchable().map { site ->
            async { site to siteResult(perSiteTimeoutMs) { site.search(keyword, 1) } }
        }
        val collected = jobs.map { it.await() }
        FeedResult(
            rooms = collected.mapNotNull { it.second.getOrNull() }
                .flatten()
                .sortedByDescending { it.viewers },
            failures = collected.mapNotNull { (site, r) ->
                r.exceptionOrNull()?.let { failureOf(site.platform, it) }
            }
        )
    }

    /** 全平台搜索：并发，按人气排序后返回。 */
    suspend fun searchAll(keyword: String, perSiteTimeoutMs: Long = 6_000): List<Room> =
        searchAllDetailed(keyword, perSiteTimeoutMs).rooms
}

/**
 * 任意异常 → [SiteError]。**公开入口，体就是那份唯一的映射**。
 *
 * 为什么需要它：UI 拿 `kind` 去查文案表，`message` 才彻底不上屏。在它之前
 * `ui/components/Bits.kt` 写的是 `Async.Failed(e.message ?: "取数失败")`，而
 * `SiteException.message = detail ?: kind.name`——首页最坏会印出
 * `TLS: SSLHandshakeException: ...` 或 `PARSE_BROKEN`。
 *
 * **映射为什么在这里、而不是 `Sites` 里再留一个 `kindOf`**：C 段之前
 * `Sites.kindOf` 是那份表的家，这个扩展只是 `= Sites.kindOf(this)` 一行委托。
 * 现在 `kindOf` 已删，`when` **搬到这里**——是搬家不是抄写，全仓只有这一份。
 * 两份映射的坏处不是重复几行，是改一份漏一份而两处都能编译过；上一轮
 * 「超时同时有两个答案」（`NET_TIMEOUT` / `PARSE_BROKEN`）就是这么长出来的。
 *
 * 兜底为什么是 [SiteError.PARSE_BROKEN] 而不是别的码：它是唯一诚实的选项——
 * 走到这里意味着「有一个异常我们没分类」，`report = true` 让人看见它，
 * 而不是把它伪装成一个用户自己能解决的失败。
 */
fun Throwable.toSiteError(): SiteError = when (this) {
    is SiteException -> this.kind
    else -> SiteError.PARSE_BROKEN
}
