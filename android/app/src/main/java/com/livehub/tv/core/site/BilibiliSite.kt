package com.livehub.tv.core.site

import com.livehub.tv.core.model.LiveCategory
import com.livehub.tv.core.model.PlayUrl
import com.livehub.tv.core.model.Platform
import com.livehub.tv.core.model.Room
import com.livehub.tv.core.model.RoomDetail
import com.livehub.tv.core.model.SiteError
import com.livehub.tv.core.model.SiteException
import com.livehub.tv.core.model.StreamFormat
import com.livehub.tv.core.model.bilibiliQuality
import com.livehub.tv.core.net.Net
import java.text.SimpleDateFormat
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 哔哩哔哩。
 *
 * 数据来源：api.live.bilibili.com（房间 / 分区 / 取流）、api.bilibili.com（搜索、buvid3）。
 * 鉴权：免登录。取流前先领一个 buvid3 写进 Cookie，否则 getRoomPlayInfo 的 playurl_info 恒为空。
 * 已知风险：
 * 1. xlive/web-interface/v1/second/getList* 已被 w_rid 风控（固定 -352），所以列表走 room/v1 老接口
 *    （Area/getList、Area/getRoomList、room/get_user_recommend），这三个目前都免签。
 * 2. 请求里的 `qn=10000` 只是「我们要哪一档」，**平台实际签发哪一档只能读 `current_qn`**。
 *    签发值由房间和 codec 决定，不是「未登录就一刀切压到某一档」：实测（2026-10-29 匿名）
 *    匿名扫活跃房时，有直接给 `current_qn=10000`（原画，`accept_qn=[10000]`）的，而赛事房
 *    7734200 给的是 avc=150 / hevc=250（同一次响应里两个 codec 不同档）。所以「未登录一律
 *    被压到 250」是错的，**照请求值上屏就是在一部分房间里谎报画质**。
 *    `accept_qn` 挂在 codec 行上，只说明「允许请求哪些档」，回答不了「给了哪一档」——
 *    7734200 的 accept 里连 150 都有，签发的偏偏是 150。
 * 3. 搜索接口返回的标题/昵称里夹 <em class="keyword"> 高亮标签，解析时要剥掉。
 */
class BilibiliSite : LiveSite {

    override val platform = Platform.BILIBILI

    private companion object {
        const val LIVE_API = "https://api.live.bilibili.com"
        const val WEB_API = "https://api.bilibili.com"
        const val REFERER = "https://live.bilibili.com/"
        const val PAGE_SIZE = 30
    }

    private val apiHeaders = mapOf(
        "User-Agent" to Net.UA_PC,
        "Origin" to "https://live.bilibili.com"
    )

    private val streamHeaders = mapOf(
        "User-Agent" to Net.UA_PC,
        "Referer" to REFERER,
        "Origin" to "https://live.bilibili.com"
    )

    @Volatile
    private var buvidReady = false

    @Volatile
    private var areaParent: Map<String, String>? = null

    // ---------------------------------------------------------------- 列表

    override suspend fun recommend(page: Int): List<Room> {
        val json = Net.getJson(
            "$LIVE_API/room/v1/room/get_user_recommend?page=$page&page_size=20",
            apiHeaders, REFERER
        )
        requireOk(json)
        val arr = json.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<Room>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rid = o.optLong("roomid", 0L)
            if (rid <= 0L) continue
            out.add(
                Room(
                    platform = platform,
                    roomId = rid.toString(),
                    title = o.optString("title").ifBlank { "未命名直播间" },
                    hostName = o.optString("uname"),
                    coverUrl = cover(o.optString("user_cover").ifBlank { o.optString("system_cover") }),
                    areaName = o.optString("areaName"),
                    viewers = o.optJSONObject("watched_show")?.optLong("num", 0L)
                        ?: o.optLong("online", 0L),
                    live = true
                )
            )
        }
        return out
    }

    override suspend fun categories(): List<LiveCategory> {
        val json = Net.getJson("$LIVE_API/room/v1/Area/getList?need_web=1", apiHeaders, REFERER)
        requireOk(json)
        val root = json.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<LiveCategory>()
        for (i in 0 until root.length()) {
            val parent = root.optJSONObject(i) ?: continue
            val pid = parent.optString("id")
            val pname = parent.optString("name")
            if (pid.isBlank()) continue
            out.add(LiveCategory(platform, pid, pname))
            val children = parent.optJSONArray("list") ?: continue
            for (j in 0 until children.length()) {
                val c = children.optJSONObject(j) ?: continue
                val cid = c.optString("id")
                if (cid.isBlank()) continue
                out.add(
                    LiveCategory(
                        platform = platform,
                        id = cid,
                        name = c.optString("name").ifBlank { pname },
                        parentId = c.optString("parent_id").ifBlank { pid },
                        iconUrl = cover(c.optString("pic"))
                    )
                )
            }
        }
        return out
    }

    override suspend fun categoryRooms(categoryId: String, page: Int): List<Room> {
        // 分区号一级（2）与二级（86/145…）混在同一命名空间，靠 Area/getList 反查归属
        val parent = areaParentMap()[categoryId]
        val parentId = parent ?: categoryId
        val areaId = if (parent != null) categoryId else "0"
        val json = Net.getJson(
            "$LIVE_API/room/v1/Area/getRoomList?platform=web&parent_area_id=$parentId" +
                "&cate_id=0&area_id=$areaId&sort_type=online&page=$page&page_size=$PAGE_SIZE&actionKey=appkey",
            apiHeaders, REFERER
        )
        requireOk(json)
        val arr = json.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<Room>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rid = o.optLong("roomid", 0L)
            if (rid <= 0L) continue
            out.add(
                Room(
                    platform = platform,
                    roomId = rid.toString(),
                    title = o.optString("title").ifBlank { "未命名直播间" },
                    hostName = o.optString("uname"),
                    coverUrl = cover(o.optString("user_cover").ifBlank { o.optString("system_cover") }),
                    areaName = o.optString("area_name"),
                    viewers = o.optLong("online", 0L),
                    live = true
                )
            )
        }
        return out
    }

    override suspend fun search(keyword: String, page: Int): List<Room> {
        val url = "$WEB_API/x/web-interface/search/type?search_type=live" +
            "&keyword=${encode(keyword)}&page=$page"
        val json = Net.getJson(url, apiHeaders, "https://search.bilibili.com/")
        requireOk(json)
        val result = json.optJSONObject("data")?.optJSONObject("result") ?: return emptyList()
        val arr = result.optJSONArray("live_room") ?: return emptyList()
        val out = ArrayList<Room>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rid = o.optLong("roomid", 0L)
            if (rid <= 0L) continue
            out.add(
                Room(
                    platform = platform,
                    roomId = rid.toString(),
                    title = stripTags(o.optString("title")).ifBlank { "未命名直播间" },
                    hostName = stripTags(o.optString("uname")),
                    coverUrl = cover(o.optString("user_cover").ifBlank { o.optString("cover") }),
                    areaName = stripTags(o.optString("cate_name")),
                    viewers = o.optJSONObject("watched_show")?.optLong("num", 0L)
                        ?: o.optLong("online", 0L),
                    live = o.optInt("live_status", 1) == 1
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------- 详情 / 取流

    override suspend fun detail(roomId: String): RoomDetail? {
        val init = Net.getJson("$LIVE_API/room/v1/Room/room_init?id=$roomId", apiHeaders, REFERER)
        requireOk(init)
        val initData = init.optJSONObject("data") ?: return null
        val realId = initData.optLong("room_id", 0L)
        if (realId <= 0L) return null
        val live = initData.optInt("live_status", 0) == 1

        val info = Net.getJson("$LIVE_API/room/v1/Room/get_info?room_id=$realId", apiHeaders, REFERER)
        requireOk(info)
        val d = info.optJSONObject("data") ?: return null
        val uid = d.optLong("uid", 0L)

        // get_info 不带主播昵称，单独补一次；失败不影响主流程
        var hostName = ""
        if (uid > 0L) {
            hostName = runCatching {
                val m = Net.getJson("$LIVE_API/live_user/v1/Master/info?uid=$uid", apiHeaders, REFERER)
                m.optJSONObject("data")?.optJSONObject("info")?.optString("uname").orEmpty()
            }.getOrDefault("")
        }

        val room = Room(
            platform = platform,
            roomId = roomId,
            title = d.optString("title").ifBlank { "未命名直播间" },
            hostName = hostName,
            coverUrl = cover(d.optString("user_cover")),
            areaName = d.optString("area_name"),
            viewers = d.optLong("online", 0L),
            live = live,
            keyframeUrl = cover(d.optString("keyframe"))
        )
        return RoomDetail(
            room = room,
            announcement = d.optString("description"),
            startedAtEpochSec = epochSec(d.optString("live_time")),
            qualities = emptyList()
        )
    }

    override suspend fun playUrls(roomId: String, preferQuality: String?): List<PlayUrl> {
        ensureBuvid()
        val realId = resolveRealId(roomId)
        val url = "$LIVE_API/xlive/web-room/v2/index/getRoomPlayInfo?room_id=$realId" +
            "&protocol=0,1&format=0,1,2&codec=0&qn=10000&platform=web&ptype=8&dolby=5&panorama=1"
        val json = Net.getJson(url, apiHeaders, "https://live.bilibili.com/$roomId")
        requireOk(json, url)
        val data = json.optJSONObject("data")
            ?: throw SiteException(SiteError.PARSE_BROKEN, url, "哔哩哔哩：取流响应缺少 data")
        if (data.optInt("live_status", -1) != 1) {
            // 业务字段说没在播：HTTP 仍是 200，所以这不是 HTTP 错误，不能归 HTTP_4XX
            throw SiteException(SiteError.STREAM_OFFLINE, url, "哔哩哔哩：房间 $roomId 未开播")
        }
        val playurl = data.optJSONObject("playurl_info")?.optJSONObject("playurl")
            // 「在播却读不到 playurl」= 结构变了，归 PARSE_BROKEN。
            // 原句尾部的「或需要登录」是猜测，进 detail 不进主文案：未登录时哔哩是压低
            // 签发档（实测 150 与 250 都有），不是不给 playurl_info。文案统一用产品口径。
            ?: throw SiteException(
                SiteError.PARSE_BROKEN, url, "哔哩哔哩：已开播，但取不到画面（接口可能改版）"
            )

        val streams = playurl.optJSONArray("stream") ?: JSONArray()
        val hlsTs = ArrayList<PlayUrl>()   // 首选：标准 HLS（.m3u8 + ts）
        val hlsFmp4 = ArrayList<PlayUrl>() // 次选：HLS + fmp4 分片
        val flv = ArrayList<PlayUrl>()     // 兜底：HTTP-FLV
        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            val formats = stream.optJSONArray("format") ?: continue
            for (j in 0 until formats.length()) {
                val fmt = formats.optJSONObject(j) ?: continue
                val formatName = fmt.optString("format_name")
                if (formatName != "ts" && formatName != "fmp4" && formatName != "flv") continue
                val bucket = when (formatName) {
                    "ts" -> hlsTs
                    "fmp4" -> hlsFmp4
                    else -> flv
                }
                // 同一清晰度 avc / hevc 各一路，优先 avc（TV 芯片硬解兼容性最好）
                val codecs = fmt.optJSONArray("codec") ?: JSONArray()
                var codec: JSONObject? = null
                for (k in 0 until codecs.length()) {
                    val c = codecs.optJSONObject(k) ?: continue
                    if (c.optString("codec_name") == "avc") {
                        codec = c
                        break
                    }
                }
                if (codec == null && codecs.length() > 0) codec = codecs.optJSONObject(0)
                val c = codec ?: continue
                // current_qn 是「平台这次真的给了哪一档」，qn=10000 只是「我们要哪一档」。
                // 实测（2026-10-29 匿名，room 6）：请求 10000，flv/ts/fmp4 三行全部回 150。
                // 读的是**选中的这一条 codec** 的 current_qn，不是响应里任何一个 qn：
                // 同一响应里 hevc 行是 250，而我们要播的是这里这条 avc，档位必须跟着它走。
                // 名字从 core 的唯一那张表翻，本文件不再维护第二份。
                val q = bilibiliQuality(c.optInt("current_qn", 0))
                val base = c.optString("base_url")
                if (base.isBlank()) continue
                val format = if (formatName == "flv") StreamFormat.FLV else StreamFormat.HLS
                // 每个 host 是一条独立线路（多 CDN），全部保留交给播放器降级
                val urlInfos = c.optJSONArray("url_info") ?: JSONArray()
                for (k in 0 until urlInfos.length()) {
                    val u = urlInfos.optJSONObject(k) ?: continue
                    val host = u.optString("host")
                    if (host.isBlank()) continue
                    bucket.add(
                        // 全部命名：PlayUrl 头两个字段都是 String 且相邻，`url`/`quality` 互换
                        // 是唯一一个**能编过**的错位（quality/format、headers/qn 都会报类型错）。
                        // 别把它退回位置传参——那样这里错了只会表现为「标题对了、地址拿去当画质」。
                        PlayUrl(
                            url = host + base + u.optString("extra"),
                            quality = q.label,
                            format = format,
                            headers = streamHeaders,
                            qn = q.qn
                        )
                    )
                }
            }
        }

        val out = ArrayList<PlayUrl>()
        for (list in listOf(hlsTs, hlsFmp4, flv)) {
            for (p in list) if (out.none { it.url == p.url }) out.add(p)
        }
        // 空列表在 UI 上和「平台没播」长得一样，所以这里必须抛错（§3.3.1 规矩 4）
        if (out.isEmpty()) {
            throw SiteException(SiteError.PARSE_BROKEN, url, "哔哩哔哩：房间 $roomId 没有可用播放线路")
        }
        return out
    }

    // ---------------------------------------------------------------- 内部工具

    /** 短号 → 真实房间号。room_init 同时告诉我们开播状态。 */
    private suspend fun resolveRealId(roomId: String): String {
        val url = "$LIVE_API/room/v1/Room/room_init?id=$roomId"
        val json = Net.getJson(url, apiHeaders, REFERER)
        requireOk(json, url)
        // room_init 是「房间存不存在」的权威接口：code=0 但没有 data/room_id，就是房间没了。
        // 这里给 ROOM_NOT_FOUND 而不是 PARSE_BROKEN，是因为虎牙/斗鱼同一场景也归这个码，
        // UI 的「回首页」按钮只在这一个码上出现。
        val data = json.optJSONObject("data")
            ?: throw SiteException(SiteError.ROOM_NOT_FOUND, url, "哔哩哔哩：房间 $roomId 不存在")
        val real = data.optLong("room_id", 0L)
        if (real <= 0L) {
            throw SiteException(SiteError.ROOM_NOT_FOUND, url, "哔哩哔哩：房间 $roomId 不存在")
        }
        return real.toString()
    }

    private suspend fun ensureBuvid() {
        if (buvidReady) return
        val spi = runCatching {
            Net.getJson("$WEB_API/x/frontend/finger/spi", apiHeaders, REFERER)
        }.getOrNull()
        val b3 = spi?.optJSONObject("data")?.optString("b_3").orEmpty()
        if (b3.isNotBlank()) {
            Net.setCookie("api.live.bilibili.com", "buvid3", b3)
            Net.setCookie("api.bilibili.com", "buvid3", b3)
            buvidReady = true
        }
    }

    private suspend fun areaParentMap(): Map<String, String> {
        areaParent?.let { return it }
        val json = Net.getJson("$LIVE_API/room/v1/Area/getList?need_web=1", apiHeaders, REFERER)
        requireOk(json)
        val root = json.optJSONArray("data") ?: JSONArray()
        val map = HashMap<String, String>()
        for (i in 0 until root.length()) {
            val parent = root.optJSONObject(i) ?: continue
            val pid = parent.optString("id")
            val children = parent.optJSONArray("list") ?: continue
            for (j in 0 until children.length()) {
                val c = children.optJSONObject(j) ?: continue
                val cid = c.optString("id")
                if (cid.isNotBlank() && pid.isNotBlank()) map[cid] = pid
            }
        }
        areaParent = map
        return map
    }

    private fun requireOk(json: JSONObject, url: String? = null) {
        val code = json.optInt("code", -1)
        if (code == 0) return
        val msg = json.optString("message").ifBlank { json.optString("msg") }
        val detail = "哔哩哔哩：接口异常 code=$code${if (msg.isBlank()) "" else "（$msg）"}"
        // -352 是风控（w_rid 校验失败）。退避有效、原地立刻重试无效，所以和普通 4xx 分开：
        // 这两种失败的用户动作相反（等一会儿 vs 换线路）。
        val kind = if (code == -352) SiteError.RATE_LIMITED else SiteError.PARSE_BROKEN
        throw SiteException(kind, url, detail)
    }

    /** 搜索接口的封面是 //i0.hdslb.com/… 协议相对地址，必须补 https。 */
    private fun cover(raw: String): String = when {
        raw.isBlank() -> ""
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("http://") -> "https://" + raw.substring(7)
        else -> raw
    }

    private fun stripTags(raw: String): String = raw.replace(Regex("<[^>]+>"), "").trim()

    private fun encode(raw: String): String =
        java.net.URLEncoder.encode(raw, "UTF-8")

    /** "2026-10-15 15:50:15" → epoch 秒；"0000-00-00 00:00:00" 等异常值返回 0。 */
    private fun epochSec(raw: String): Long {
        if (raw.isBlank() || raw.startsWith("0000")) return 0L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).parse(raw)?.time?.div(1000L) ?: 0L
        }.getOrDefault(0L)
    }
}
