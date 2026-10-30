package com.livehub.tv.core.site

import com.livehub.tv.core.model.LiveCategory
import com.livehub.tv.core.model.PlayUrl
import com.livehub.tv.core.model.Platform
import com.livehub.tv.core.model.Room
import com.livehub.tv.core.model.RoomDetail
import com.livehub.tv.core.model.SiteError
import com.livehub.tv.core.model.SiteException
import com.livehub.tv.core.model.StreamFormat
import com.livehub.tv.core.net.Net
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * 抖音。
 *
 * 数据来源：webcast.amemv.com/webcast/room/reflow/info（免签名详情 + 取流）、
 *          live.douyin.com/{web_rid} 页面内嵌 JSON（web_rid → 19 位 room_id 唯一免签转换方式）。
 * 鉴权：detail/playUrls 完全免签。列表、分区、搜索走 live.douyin.com/webcast/room/web/enter 与
 *      aweme/v1/web/live/search，这些接口现在强制 a_bogus 签名（算法在 webmssdk 里，需跑 JS），
 *      未登录还会直接返回 2483「请先登录，再继续搜索吧」，所以 recommend/categories/categoryRooms/search
 *      **故意返回空列表**，UI 会显示「该平台暂不支持」而不是假数据。
 * 已知风险：
 * 1. 想恢复列表/搜索的最小改动点：补一个 a_bogus 签名器（webmssdk 的 s_v_web_id + 指纹 + X-Bogus），
 *    在 enter 接口上带签名后，用 response.data.data[].room 解析，本版不做。
 * 2. 页面里 19 位数字很多（推荐位），必须靠 "roomId" 后紧跟 "web_rid" 配对，不能取第一个 19 位数。
 * 3. 拉流地址是 http，必须升到 https（Android 9+ 默认禁明文）。
 * 4. 线路顺序 FLV 优先、HLS 兜底：实测 douyincdn 的 .m3u8 在部分边缘节点间歇返回 404
 *    （同一个 URL 隔几秒再拉就 200/206），FLV 稳定；但 HLS 必须留在列表里（tvOS 的
 *    AVPlayer 只吃 HLS），真机上 HLS 首拉失败要允许重试一次再判定换线路。
 * 5. 入参归一化在 core（[IdInput.RoomIdOrWebRidOrLink]）：19 位 room_id、web_rid、
 *    `live.douyin.com/xxx`、带 query 的整条分享链都吃。`v.douyin.com` 短链**能识别但不承诺可用**：
 *    实测失效短链会 302 到 www.douyin.com 且落地页 HTTP 200、一个 roomId 都没有，
 *    所以短链必须恰好命中一个 roomId+web_rid 配对才认，否则报「无法从这条链接定位直播间」。
 *    对用户不要说「短链已失效」——用户不知道什么是短链，只给他下一步。
 * 6. 在播判据只有 `room.status`（2 直播中 / 4 已下播）。下播房间的 `stream_url` **不是空的**，
 *    照样给满 4 路 FLV + 4 路 HLS，只是拉起来 FLV 404、HLS 无响应。拿 stream_url 判 live
 *    会把下播房间显示成「直播中」，点进去转圈到首帧超时——「接口全通但用户黑屏」最难查的那类。
 */
class DouyinSite : LiveSite {

    override val platform = Platform.DOUYIN

    /**
     * 抖音首版的能力边界。列表/分区/搜索要 a_bogus 签名，弹幕握手要 X-Bogus，
     * 所以这四项必须是 false —— UI 会显示「该平台暂不支持」而不是空列表。
     * 详情与取流（reflow + 页面配对）是完全可用的，所以 hls/flv 保持 true。
     */
    override val capabilities = SiteCapabilities(
        recommend = false,
        categories = false,
        search = false,
        danmaku = false,
        idInput = IdInput.RoomIdOrWebRidOrLink
    )

    private companion object {
        const val WEB = "https://live.douyin.com"
        const val REFLOW = "https://webcast.amemv.com/webcast/room/reflow/info/"
        const val REFERER = "https://live.douyin.com/"
    }

    private val apiHeaders = mapOf(
        "User-Agent" to Net.UA_PC,
        "Origin" to "https://live.douyin.com"
    )

    private val streamHeaders = mapOf(
        "User-Agent" to Net.UA_PC,
        "Referer" to REFERER
    )

    /** web_rid → 19 位 room_id，页面解析一次就够了 */
    private val ridCache = ConcurrentHashMap<String, String>()

    // ------------------------------------------------ 列表：受签名限制，按约定返回空

    /** 需 a_bogus 签名，见文件头「已知风险 1」。 */
    override suspend fun recommend(page: Int): List<Room> = emptyList()

    /** 抖音的分区只能从签名接口拿；返回空列表，UI 退化成「按房间号进入」。 */
    override suspend fun categories(): List<LiveCategory> = emptyList()

    override suspend fun categoryRooms(categoryId: String, page: Int): List<Room> = emptyList()

    override suspend fun search(keyword: String, page: Int): List<Room> = emptyList()

    // ---------------------------------------------------------------- 详情 / 取流

    override suspend fun detail(roomId: String): RoomDetail? {
        val json = reflow(roomId)
        val data = json.optJSONObject("data")
            // 走到这里说明 status_code 已经是 0（reflow 里非 0 早抛了），所以缺 data 是结构变了
            ?: throw SiteException(SiteError.PARSE_BROKEN, null, "抖音：接口返回里没有 data（接口可能改版）")
        val room = data.optJSONObject("room")
            ?: throw SiteException(SiteError.PARSE_BROKEN, null, "抖音：接口返回里没有 room（接口可能改版）")
        val user = data.optJSONObject("user") ?: room.optJSONObject("owner")
        // reflow 的 data.user.nickname 经常是空串，真实昵称在 room.owner 里
        val nick = user?.optString("nickname").orEmpty()
            .ifBlank { room.optJSONObject("owner")?.optString("nickname").orEmpty() }
        val cover = firstUrl(room.optJSONObject("cover"))
            .ifBlank { firstUrl(user?.optJSONObject("avatar_thumb")) }
        val stream = room.optJSONObject("stream_url")
        // 在播判据只有 room.status：2 = 直播中，4 = 已下播。
        // **不能拿 stream_url 是否存在当判据** —— 下播房间照样返回完整的 4 路 FLV + 4 路 HLS
        // （实测 room_id=7701710981495720704，status 4，8 条地址一条不少），只是这些地址
        // 拉起来 FLV 404、HLS 无响应。用 stream_url 判 live 会把下播房间显示成「直播中」，
        // 点进去就是转圈到首帧超时——这是最难查的那种「接口全通但用户看到黑屏」。
        val status = room.optInt("status", 0)
        // 两层判据，**不要合并成一个 &&**：
        //   status           = 平台给的「态度」（在不在播）
        //   hasPullUrl(…)    = 平台给的「能力」（有没有线路）
        // 两者都成立才算「真的能看」，但 Room.live 只有一个 bit，表达不了「在播但没线路」。
        // 所以 live 只填**态度**：把「我们没解析到线路」说成「未开播」，等于拿我们的故障
        // 冒充平台正常——用户以为是主播没开，诊断页也不留痕（工具修的正是这个毛病）。
        // 能力缺失留给 playUrls 抛一条**独立**的错：能点、但点进去说清楚"接口结构变了"。
        val live = status == 2
        val playable = live && stream != null && hasPullUrl(stream)
        // roomId 归一成平台真实 id（reflow 的 room.id_str），而不是用户输入的原样。
        // 另外三家 detail 返回的也都是平台真实 id（哔哩短号 6 → 7734200、虎牙 profileRoom、
        // 斗鱼 rid），抖音不能是唯一一个「用户输 web_rid 就回 web_rid」的例外：
        //   · UI 拿它当收藏/最近观看的 key 时，同一个房间才不会出现两个 key；
        //   · 弹幕以后要用 19 位 room_id，直接从 detail 里取，不必再从 web_rid 反查。
        val canonicalId = room.optString("id_str")
            .ifBlank { room.optLong("id", 0L).takeIf { it > 0L }?.toString().orEmpty() }
            .ifBlank { roomId }
        return RoomDetail(
            room = Room(
                platform = platform,
                roomId = canonicalId,
                title = room.optString("title").ifBlank { "未命名直播间" },
                hostName = nick,
                coverUrl = cover,
                areaName = "",
                viewers = room.optLong("user_count", 0L).takeIf { it > 0L }
                    ?: room.optJSONObject("stats")?.optLong("total_user", 0L) ?: 0L,
                live = live,
                keyframeUrl = cover
            ),
            announcement = "",
            startedAtEpochSec = 0L,
            // 下播房间也有 8 条清晰度，列出来只会诱使用户去按一个必然失败的档位
            // playable 已含 stream != null，这里不必再判一次（编译器会告诉你那条件是恒真）
            qualities = if (playable) resolutionNames(stream) else emptyList()
        )
    }

    override suspend fun playUrls(roomId: String, preferQuality: String?): List<PlayUrl> {
        val json = reflow(roomId)
        val room = json.optJSONObject("data")?.optJSONObject("room")
            ?: throw SiteException(SiteError.PARSE_BROKEN, null, "抖音：接口返回里没有 room（接口可能改版）")
        // 先判 status 再判 stream_url：下播房间的 stream_url 是「齐全但全死」的，
        // 不先拦下来就会给播放器 8 条必然失败的地址，用户看到的是转圈而不是「已下播」。
        when (room.optInt("status", 0)) {
            2 -> Unit
            4 -> throw SiteException(SiteError.STREAM_OFFLINE, null, "抖音：房间 $roomId 已下播")
            // 未知取值一律当不在播：宁可少播一个,也不要拿一个猜出来的状态去拉流
            else -> throw SiteException(SiteError.STREAM_OFFLINE, null, "抖音：房间 $roomId 未开播")
        }
        // 「已开播，但取不到画面（接口可能改版）」是产品定的用户文案（team-lead），
        // 这里直接用同一句，避免出现两套说法。诊断用的细节（缺 stream_url / 缺线路 /
        // 结构哪一段变了）等 ui/strings 接上后进 SiteException.detail 的诊断位，
        // 现在的临时载体是 docs/06 §4.6。
        val stream = room.optJSONObject("stream_url")
            ?: throw SiteException(
                SiteError.PARSE_BROKEN, null, "抖音：已开播，但取不到画面（接口可能改版）"
            )
        if (!hasPullUrl(stream)) {
            throw SiteException(
                SiteError.PARSE_BROKEN, null, "抖音：已开播，但取不到画面（接口可能改版）"
            )
        }

        val names = stream.optJSONObject("resolution_name")
        val hlsMap = stream.optJSONObject("hls_pull_url_map") ?: JSONObject()
        val flvMap = stream.optJSONObject("flv_pull_url") ?: JSONObject()

        val out = ArrayList<PlayUrl>()
        // 清晰度排序：原画 > 蓝光 > 超清 > 高清 > 标清，未知档位追加在后面。
        // FLV 排前面：实测同一房间 FLV 4/4 全 200，而 douyincdn 的 m3u8 在部分边缘节点会
        // 间歇性返回 404（同一个 URL 隔几秒再拉又 200），所以 FLV 先试、HLS 作为回落，
        // 但 HLS 一定留在列表里（tvOS 的 AVPlayer 只能放 HLS）。
        // 这四处全部命名传参：`url` 与 `quality` 都是 String 且相邻，互换能编过（见 BilibiliSite 同处注释）。
        // 抖音这两行尤其危险——`upgrade(...)` 与 `label` 两个都是短表达式，看错一眼就是「地址当画质」。
        for (key in orderedKeys(flvMap)) {
            val label = names?.optString(key).orEmpty().ifBlank { key }
            out.add(
                PlayUrl(
                    url = upgrade(flvMap.optString(key)), quality = label,
                    format = StreamFormat.FLV, headers = streamHeaders
                )
            )
        }
        for (key in orderedKeys(hlsMap)) {
            val label = names?.optString(key).orEmpty().ifBlank { key }
            out.add(
                PlayUrl(
                    url = upgrade(hlsMap.optString(key)), quality = label,
                    format = StreamFormat.HLS, headers = streamHeaders
                )
            )
        }
        if (out.isEmpty()) {
            // 极少数房间只给一个默认 hls_pull_url / rtmp_pull_url
            val hls = stream.optString("hls_pull_url")
            if (hls.isNotBlank()) {
                out.add(
                    PlayUrl(
                        url = upgrade(hls), quality = "默认画质",
                        format = StreamFormat.HLS, headers = streamHeaders
                    )
                )
            }
            val flv = stream.optString("rtmp_pull_url")
            if (flv.isNotBlank() && flv.contains(".flv")) {
                out.add(
                    PlayUrl(
                        url = upgrade(flv), quality = "默认画质",
                        format = StreamFormat.FLV, headers = streamHeaders
                    )
                )
            }
        }
        // 空列表在 UI 上和「主播没播」长得一样，所以必须抛（§3.3.1 规矩 4）
        if (out.isEmpty()) {
            throw SiteException(SiteError.PARSE_BROKEN, null, "抖音：房间 $roomId 没有可用播放线路")
        }
        return out
    }

    // ---------------------------------------------------------------- 内部工具

    private suspend fun reflow(roomId: String): JSONObject {
        val realId = resolveRoomId(roomId)
        val url = "$REFLOW?type_id=0&live_id=1&room_id=$realId&app_id=1128"
        val json = Net.getJson(url, apiHeaders, REFERER)
        val code = json.optInt("status_code", -1)
        if (code == 0) return json
        val msg = json.optJSONObject("data")?.optString("message").orEmpty()
        // `10011` = "Request params error"，**裸映射成「房间不存在」，不加任何前缀条件**。
        // 理由由守卫保证、不由现象保证：能走到这里的 id 一定是 19 位纯数字（[isDouyinRoomId]），
        // 所以「参数不合法」这一种解释在这个位置物理上不存在，`10011` 只剩「这个号没了」。
        // 昨天在页面上看到「位数不对也回 10011」，那是**页面**的行为，不是 reflow 的。
        //
        // 别把「id 是否来自页面配对」重新加回判据：用户收藏/最近观看的 19 位号从不过页面，
        // 那样加会让最常见的失败永远进不了这个码，把一个必须显眼的码变成不可达。
        if (code == 10011) {
            throw SiteException(SiteError.ROOM_NOT_FOUND, url, "抖音：房间 $roomId 不存在（status_code=10011）")
        }
        // 陌生的业务码一律当「我们没看懂」——协议真变时回来的码几乎不可能恰好还是 10011，
        // 所以第一个陌生码就会红，这是裸映射能自证的地方。
        throw SiteException(
            SiteError.PARSE_BROKEN, url,
            "抖音：房间 $roomId " + reflowError(code, msg) + "（status_code=$code）"
        )
    }

    /**
     * 用户能给的四种形态都收：19 位 room_id、web_rid（分享文案里的短号，位数见
     * `WEB_RID_MIN..WEB_RID_MAX`，**这里不重复数字**）、
     * `live.douyin.com/xxx`、以及整条链接。归一化在 core 做，不让 UI 自己剥 URL
     * （否则「哪些形态能用」会有两份各自漂移的实现，见 [IdInput]）。
     *
     * reflow 只认 19 位，页面 HTML 里 "roomId" 紧邻 "web_rid"，配对取即可。
     *
     * 三条失败路径的归责写死在这里，因为它们看起来都是「定位不到」但**不是一件事**：
     * - 空入参 / 纯数字但位数不对 → [SiteError.ID_UNRESOLVED]，**不发任何请求**；
     * - 短链/整链落地页里配不上唯一的配对 → [SiteError.ID_UNRESOLVED]，怪用户粘错了；
     * - 页面拿到了、里面一个配对都没有 → [SiteError.PARSE_BROKEN]，怪我们。
     */
    private suspend fun resolveRoomId(roomId: String): String {
        val raw = roomId.trim()
        if (raw.isEmpty()) {
            throw SiteException(SiteError.ID_UNRESOLVED, null, "抖音：房间号为空")
        }
        // 纯数字只有两种合法形状，**其余长度一个请求都不发**（见 [douyinIdShape]）：
        //   19 位  = room_id，reflow 直接认；
        //   4–12 位 = 分享文案里的 web_rid，要拿页面配对换成 19 位。
        // 13–18 位是最常见的「少打/多打了几位」：走到 reflow 会拿回 10011，被显示成
        // 「房间不存在」——那是把用户的输入错误说成平台的结论，还白耗一次风控额度。
        //
        // 注意这里**不能**写成「不是 19 位就抛」：web_rid 是抖音唯一的常规入口形态
        // （用户在搜索页粘的就是这串短号），按 19 位一刀切会把整条正常路径判死。
        if (raw.all { it.isDigit() }) {
            when (douyinIdShape(raw)) {
                DouyinIdShape.ROOM_ID -> return raw
                DouyinIdShape.WEB_RID -> Unit   // 落到下面的页面配对
                DouyinIdShape.NEITHER -> throw SiteException(
                    SiteError.ID_UNRESOLVED, null,
                    "抖音：房间号位数不对（输入 ${raw.length} 位；19 位是房间号，4–12 位是分享短号）"
                )
            }
        }

        // 链接 / 短链归一化：取出最后一段路径，剩下的交给页面配对
        val isLink = raw.contains("://") ||
            raw.startsWith("v.douyin.com") ||
            raw.startsWith("live.douyin.com") ||
            raw.startsWith("www.douyin.com")
        val bare = raw.substringAfterLast('/').substringBefore('?').ifBlank { raw }
        when (douyinIdShape(bare)) {
            DouyinIdShape.ROOM_ID -> return bare
            // 链接末段是纯数字但位数不对：既不是 room_id 也不是 web_rid，同样不发请求。
            // 非纯数字的末段（v.douyin.com 的字母短码）走 WEB_RID，交给落地页配对。
            DouyinIdShape.NEITHER -> throw SiteException(
                SiteError.ID_UNRESOLVED, "$WEB/$bare",
                "抖音：链接里的房间号位数不对（${bare.length} 位）"
            )
            DouyinIdShape.WEB_RID -> Unit
        }
        ridCache[bare]?.let { return it }

        // OkHttp 默认跟随 302：v.douyin.com 短链会落到直播间页，下面同一套正则照样命中
        val pageUrl = if (isLink) {
            if (raw.contains("://")) raw else "https://$raw"
        } else {
            "$WEB/$bare"
        }
        val html = Net.get(pageUrl, apiHeaders, REFERER)
        val normalized = html.replace("\\\"", "\"").replace("&quot;", "\"")
        // 只认**相邻**的两种顺序：roomId→web_rid 和 web_rid→roomId。
        // 不做「往前找最近的 roomId」：一个直播间页实测有 78 个 19 位数字，其中 77 个不是
        // room_id（推荐位、作品、专题卡），「最近的那个」没有任何保证是这一个房间的。
        // 配错的后果不是报错，是给用户放另一个人的直播且一声不响——比失败更糟。
        // 两种相邻顺序都不中，就按「页面结构变了」处理，宁可报错。
        val pairs =
            Regex("\"roomId\":\"(\\d{10,25})\",\"web_rid\":\"(\\d+)\"")
                .findAll(normalized).map { it.groupValues[1] to it.groupValues[2] }.toList() +
            Regex("\"web_rid\":\"(\\d+)\",\"roomId\":\"(\\d{10,25})\"")
                .findAll(normalized).map { it.groupValues[2] to it.groupValues[1] }.toList()

        pairs.firstOrNull { it.second == bare }?.let { (real, _) ->
            ridCache[bare] = real
            return real
        }
        if (isLink) {
            // 短链/整链：跳转后的落地页若就是直播间页，页里会有且只有一个配对。
            // 多于一个说明落在了首页/分区页（失效短链会 302 到 www.douyin.com），
            // 此时**不许猜**，猜错就是给用户放另一个人的直播。
            if (pairs.size == 1) {
                val real = pairs[0].first
                ridCache[bare] = real
                return real
            }
            throw SiteException(
                SiteError.ID_UNRESOLVED, pageUrl,
                "抖音：无法从这条链接定位直播间，请改用 live.douyin.com/房间号 或 App 内的分享链接"
            )
        }
        // 页面拿到了、一个配对都没有 → 页面结构变了（或者房间真的注销了），归我们。
        throw SiteException(
            SiteError.PARSE_BROKEN, pageUrl,
            "抖音：没有从 $roomId 的页面里解析到房间号（pairing-miss）"
        )
    }

    private fun hasPullUrl(stream: JSONObject): Boolean {
        if (stream.optString("hls_pull_url").isNotBlank()) return true
        if (stream.optString("rtmp_pull_url").isNotBlank()) return true
        if (keysOf(stream.optJSONObject("hls_pull_url_map")).isNotEmpty()) return true
        if (keysOf(stream.optJSONObject("flv_pull_url")).isNotEmpty()) return true
        return false
    }

    /** 只列真实存在的档位：resolution_name 里有「原画」，但匿名用户常常拿不到 ORIGION 地址。 */
    private fun resolutionNames(stream: JSONObject): List<String> {
        val names = stream.optJSONObject("resolution_name") ?: return emptyList()
        val available = orderedKeys(stream.optJSONObject("hls_pull_url_map") ?: JSONObject()) +
            orderedKeys(stream.optJSONObject("flv_pull_url") ?: JSONObject())
        val out = ArrayList<String>()
        for (key in available) {
            val label = names.optString(key).ifBlank { key }
            if (label.isNotBlank() && !out.contains(label)) out.add(label)
        }
        return out
    }

    private fun orderedKeys(obj: JSONObject): List<String> {
        val keys = keysOf(obj).filter { obj.optString(it).isNotBlank() }
        val rank = listOf("ORIGION", "FULL_HD1", "HD1", "SD2", "SD1")
        return keys.sortedBy { key ->
            val idx = rank.indexOf(key)
            if (idx >= 0) idx else rank.size
        }
    }

    private fun keysOf(obj: JSONObject?): List<String> {
        if (obj == null) return emptyList()
        val out = ArrayList<String>()
        val it = obj.keys()
        while (it.hasNext()) out.add(it.next())
        return out
    }

    private fun firstUrl(obj: JSONObject?): String {
        val arr = obj?.optJSONArray("url_list") ?: return ""
        for (i in 0 until arr.length()) {
            val url = arr.optString(i)
            if (url.isNotBlank()) return upgrade(url)
        }
        return ""
    }

    private fun reflowError(code: Int, msg: String): String =
        "接口异常 status_code=$code${if (msg.isBlank()) "" else "（$msg）"}"

    private fun upgrade(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("http://") -> "https://" + url.substring(7)
        else -> url
    }
}

/**
 * 抖音房间号：纯数字且**恰好 19 位**。
 *
 * `reflow/info` 只认这个形状，弹幕层（`core/danmaku`，同模块，直接调）将来也要用同一个判定。
 * **不许在任何地方再写 `19` / `15` 这两个字面量的形状判断**——今天代码里已经出现过三份
 * （`resolveRoomId` 的两处守卫 + 弹幕层注释里的示例），而三份字面量的危险不在于今天不一致，
 * 在于**将来改的人只看得见其中一份**。抽成一个函数之后，「改一半」在物理上不可能发生。
 *
 * 返回 false 的含义是「这不是一个抖音房间号」，不是「这个房间不存在」——
 * 前者归 `ID_UNRESOLVED`（怪输入），后者归 `ROOM_NOT_FOUND`（怪平台）。
 */
internal fun isDouyinRoomId(s: String): Boolean = s.length == 19 && s.all { it.isDigit() }

/** web_rid（分享文案里那串短号）的长度区间。**实测 4 位也能在页面里配对出 19 位 room_id**。 */
private const val WEB_RID_MIN = 4
private const val WEB_RID_MAX = 12

/**
 * 纯数字入参的三种形状。**长度规则只写在这里**，[DouyinSite.resolveRoomId] 的两处守卫
 * （直接输入 / 链接末段）都调它，不允许任何一处自己再写一遍数字。
 *
 * - [ROOM_ID]：19 位，reflow 直接认，**0 次额外请求**；
 * - [WEB_RID]：4–12 位，必须去页面换 19 位（这是抖音唯一的常规入口形态，不能判死）；
 * - [NEITHER]：13–18 位、<4 位、>19 位，既不是房间号也不是短号 → 输入错，
 *   归 `ID_UNRESOLVED` 且**一个请求都不发**。
 *
 * 非纯数字一律当 [WEB_RID]：`v.douyin.com` 的字母短码要靠 302 落地页配对才知道是不是直播间，
 * 在这里没法离线判定，也不该判。
 */
private enum class DouyinIdShape { ROOM_ID, WEB_RID, NEITHER }

private fun douyinIdShape(s: String): DouyinIdShape = when {
    !s.all { it.isDigit() } -> DouyinIdShape.WEB_RID
    isDouyinRoomId(s) -> DouyinIdShape.ROOM_ID
    s.length in WEB_RID_MIN..WEB_RID_MAX -> DouyinIdShape.WEB_RID
    else -> DouyinIdShape.NEITHER
}
