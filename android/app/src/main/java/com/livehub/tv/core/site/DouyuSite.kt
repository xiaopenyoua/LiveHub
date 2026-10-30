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
import com.livehub.tv.core.net.NetBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 斗鱼。
 *
 * 数据来源：www.douyu.com/betard（房间详情）、gapi/rkc/directory/mixList（一级分区列表）、
 *          m.douyu.com/api（分区树 + 二级分区列表 + 热门流）、japi/search/api/searchShow（搜索）、
 *          playweb.douyucdn.cn/lapi/live/hlsH5Preview（免签名取流）。
 * 鉴权：免登录。取流走 h5 预览接口，auth = md5(rid + 秒级时间戳)，必须同时带 time / rid 两个请求头，
 *      少带 rid 头会返回 2002「鉴权参数错误」。
 * 已知风险：
 * 1. japi/weblist/apinc/getC2List（分区树）已 404，分区改走 m.douyu.com/api/cate/list。
 * 2. 预览接口只给一路「预览画质」HLS；官网的 vplay1a.douyucdn.cn/live/{key}.flv 已实测失效
 *    （TLS 直接断连），所以不再拼 FLV，避免把死地址交给播放器。
 * 3. 播流地址带 token，有过期时间（约数小时），拿到就用，不要缓存。
 */
class DouyuSite : LiveSite {

    override val platform = Platform.DOUYU

    private companion object {
        const val WEB = "https://www.douyu.com"
        const val MOBILE = "https://m.douyu.com"
        const val PREVIEW_HOST = "https://playweb.douyucdn.cn"
        const val DID = "10000000000000000000000000001501"
        const val REFERER = "https://www.douyu.com/"
        const val HEX = "0123456789abcdef"

        /** 一级分区轮转顺序：recommend 落在「网游/娱乐/手游/单机」四个大类上，避免整屏同一个游戏 */
        val HOT_CATE1 = intArrayOf(1, 2, 9, 15)
    }

    private val webHeaders = mapOf(
        "User-Agent" to Net.UA_PC,
        "Origin" to "https://www.douyu.com"
    )

    private val mobileHeaders = mapOf(
        "User-Agent" to Net.UA_MOBILE,
        "Origin" to "https://m.douyu.com"
    )

    private val streamHeaders = mapOf(
        "User-Agent" to Net.UA_MOBILE,
        "Referer" to REFERER
    )

    /** cate2Id → shortName，categoryRooms 拼 m.douyu 的 type= 参数要用 */
    @Volatile
    private var cateMeta: CateMeta? = null

    private data class CateMeta(
        val cate1Ids: Set<String>,
        val cate2Short: Map<String, String>
    )

    // ---------------------------------------------------------------- 列表

    override suspend fun recommend(page: Int): List<Room> {
        val cate = HOT_CATE1[(page - 1).coerceAtLeast(0) % HOT_CATE1.size]
        val subPage = (page - 1).coerceAtLeast(0) / HOT_CATE1.size + 1
        // mixList 的 "2_" 前缀表示按一级分区取；单页 120 条，翻页靠 {cate}_{page}
        val rooms = runCatching { webMixList(cate.toString(), subPage) }.getOrDefault(emptyList())
        if (rooms.isNotEmpty()) return rooms
        return mobileRoomList(null, page)
    }

    override suspend fun categories(): List<LiveCategory> {
        val json = Net.getJson("$MOBILE/api/cate/list", mobileHeaders, "$REFERER")
        if (json.optInt("code", -1) != 0) {
            throw SiteException(
                SiteError.PARSE_BROKEN, "$MOBILE/api/cate/list",
                "斗鱼：分区接口异常 code=${json.optInt("code")}"
            )
        }
        val data = json.optJSONObject("data")
            ?: throw SiteException(SiteError.PARSE_BROKEN, null, "斗鱼：分区响应缺少 data")
        val out = ArrayList<LiveCategory>()
        val parents = data.optJSONArray("cate1Info") ?: JSONArray()
        val parentNames = HashMap<String, String>()
        for (i in 0 until parents.length()) {
            val o = parents.optJSONObject(i) ?: continue
            val id = o.optString("cate1Id")
            if (id.isBlank()) continue
            val name = o.optString("cate1Name")
            parentNames[id] = name
            out.add(LiveCategory(platform, id, name))
        }
        val children = data.optJSONArray("cate2Info") ?: JSONArray()
        for (i in 0 until children.length()) {
            val o = children.optJSONObject(i) ?: continue
            val id = o.optString("cate2Id")
            if (id.isBlank()) continue
            val pid = o.optString("cate1Id")
            out.add(
                LiveCategory(
                    platform = platform,
                    id = id,
                    name = o.optString("cate2Name").ifBlank { parentNames[pid].orEmpty() },
                    parentId = pid,
                    iconUrl = o.optString("icon").ifBlank { o.optString("pic") }
                )
            )
        }
        return out
    }

    override suspend fun categoryRooms(categoryId: String, page: Int): List<Room> {
        val meta = ensureCateMeta()
        // 一级和二级分区的号段重叠（cate1=1 与 cate2=1 都存在），先按一级取（单页 120 条），
        // 取不到再退回二级分区的移动端列表，两个都失败才报错。
        if (meta.cate1Ids.contains(categoryId)) {
            val rooms = runCatching { webMixList(categoryId, page) }.getOrDefault(emptyList())
            if (rooms.isNotEmpty()) return rooms
        }
        val shortName = meta.cate2Short[categoryId]
            // 分区 id 是 UI 从我们给的列表里选的，过期了就是「这个分类现在没房间」——
            // 空列表才是正确答案，不该抛错。**这不违反「空列表不是错误」那条**：那条管的是
            // 「平台给了数据我们读不出」（那是我们的故障）；这里是「这个目标本来就不存在」。
            // 塞进 ROOM_NOT_FOUND 会让用户在分类页看到「直播间不存在」，比空列表更糟。
            ?: return emptyList()
        return mobileRoomList(shortName, page)
    }

    override suspend fun search(keyword: String, page: Int): List<Room> {
        val url = "$WEB/japi/search/api/searchShow?kw=${encode(keyword)}&page=$page&pageSize=20"
        val json = Net.getJson(url, webHeaders, "https://www.douyu.com/search/")
        val data = json.optJSONObject("data") ?: return emptyList()
        val arr = data.optJSONArray("relateShow") ?: return emptyList()
        val out = ArrayList<Room>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rid = o.optLong("rid", 0L)
            if (rid <= 0L) continue
            out.add(
                Room(
                    platform = platform,
                    roomId = rid.toString(),
                    title = o.optString("roomName").ifBlank { o.optString("nickName") },
                    hostName = o.optString("nickName"),
                    coverUrl = o.optString("roomSrc").ifBlank { o.optString("avatar") },
                    areaName = o.optString("cateName"),
                    viewers = parseHot(o.optString("hot")),
                    live = o.optInt("isLive", 1) == 1
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------- 详情 / 取流

    override suspend fun detail(roomId: String): RoomDetail? {
        val root = betard(roomId)
        val room = root.optJSONObject("room")
            ?: throw SiteException(SiteError.ROOM_NOT_FOUND, "$WEB/betard/$roomId", "斗鱼：房间 $roomId 不存在")
        val pic = room.optString("room_pic")
        val avatar = room.optJSONObject("avatar")?.optString("big").orEmpty()
        return RoomDetail(
            room = Room(
                platform = platform,
                roomId = roomId,
                title = room.optString("room_name").ifBlank { "未命名直播间" },
                hostName = room.optString("nickname"),
                coverUrl = pic.ifBlank { avatar },
                areaName = room.optString("second_lvl_name"),
                viewers = 0L,
                live = room.optInt("show_status", 0) == 1,
                keyframeUrl = pic
            ),
            // betard 的 room_gg 是个对象，真正的公告文案在 show 字段里
            announcement = root.optJSONObject("room_gg")?.optString("show").orEmpty(),
            startedAtEpochSec = 0L,
            qualities = emptyList()
        )
    }

    override suspend fun playUrls(roomId: String, preferQuality: String?): List<PlayUrl> {
        val previewUrl = "$PREVIEW_HOST/lapi/live/hlsH5Preview/$roomId"
        val json = preview(roomId)
        val error = json.optInt("error", -1)
        if (error != 0) {
            throw SiteException(previewKind(error), previewUrl, "斗鱼：" + previewError(error, json.optString("msg")))
        }
        val data = json.optJSONObject("data")
            ?: throw SiteException(SiteError.PARSE_BROKEN, previewUrl, "斗鱼：取流响应缺少 data")
        val host = data.optString("rtmp_url")
        val live = data.optString("rtmp_live")
        if (host.isBlank() || live.isBlank()) {
            // 业务字段没给地址：HTTP 仍是 200，属于「平台说没在播」，不是解析故障
            throw SiteException(SiteError.STREAM_OFFLINE, previewUrl, "斗鱼：房间 $roomId 未开播")
        }
        val url = if (live.startsWith("http")) live else "$host/$live"
        // 命名传参：`url` 与 `quality` 都是 String 且相邻，互换能编过（见 BilibiliSite 同处注释）。
        // 这一行尤其容易看错——`upgrade(url)` 和「预览画质」都是短表达式，看清位置才知道谁是谁。
        return listOf(
            PlayUrl(
                url = upgrade(url),
                quality = "预览画质",
                format = StreamFormat.HLS,
                headers = streamHeaders
            )
        )
    }

    /**
     * h5 预览取流：auth = md5(rid + 秒级时间戳)。
     * 关键点：rid 必须同时出现在 body 和 **请求头**，只放 body 会 2002。
     */
    private suspend fun preview(rid: String): JSONObject {
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val headers = mapOf(
            "User-Agent" to Net.UA_MOBILE,
            "Referer" to REFERER,
            "Origin" to "https://www.douyu.com",
            "auth" to md5(rid + ts),
            "time" to ts,
            "rid" to rid
        )
        val text = Net.postForm(
            "$PREVIEW_HOST/lapi/live/hlsH5Preview/$rid",
            form = mapOf("rid" to rid, "did" to DID),
            headers = headers
        )
        return try {
            JSONObject(text)
            // 只接 JSONException：`catch (e: Exception)` 会连网络异常一起接住，把上游
            // 辛苦分好的 SIGN_INVALID / STREAM_OFFLINE 一律降级成 PARSE_BROKEN。
            // 命中比不命中更坏，因为它看起来「被处理了」。
        } catch (e: JSONException) {
            throw SiteException(
                SiteError.PARSE_BROKEN, "$PREVIEW_HOST/lapi/live/hlsH5Preview/$rid",
                "斗鱼：取流接口返回了非 JSON 内容（接口可能已改版）", e
            )
        }
    }

    /**
     * 房间详情。**两种 200 必须分开**：正常是 JSON，房间不可用是一张 HTML 提示页，
     * 两者都是 HTTP 200（实测见 `docs/06 §2.4`）。
     *
     * 判据是**平台自己的错误页标记串**，不是「解析失败就说我们想说的那句话」。
     * 原写法 `catch (JSONException) → ROOM_NOT_FOUND` 抓的是「任何非 JSON」：斗鱼改版、
     * 维护页、WAF 风控拦截页、运营商劫持页——**每一条**都会命中「房间不存在」，而它是
     * `report=false`，后果是全站每房间都显示「房间不存在」、诊断页一声不响。
     *
     * 走过一次弯路：中间版本用「非 JSON 且体积 < 4000 字节 = 房间不存在」。实测把它否了——
     * 同一个接口下 `betard/0` 是 1349 字节的「没有找到该房间」、`betard/-1` 是 5239 字节的
     * 通用 404 页、`betard/abc` 是 238 字节的 Tengine 403 页、正常房间 119584 字节。
     * 体积从 238 到 127820 连续分布，「小 = 房间不存在」不成立：**一个 300 字节的
     * HTTP 200 风控页会被判成「用户房间号打错了」**，正是要防的那件事。
     *
     * 现在的四条（见 [betardShape]）：空白 → 空响应；首字符 `{` → 数据；命中
     * 「该房间目前没有开放 / 没有找到该房间」→ 房间不存在；其余非 JSON → 我们读不懂。
     * 改版时标记串匹配不上，落到「我们读不懂」那一侧（`report=true`）——**误报比漏报安全**，
     * 和抖音「配不出 room_id 宁可报 PARSE_BROKEN」是同一条原则。
     */
    private suspend fun betard(roomId: String): JSONObject {
        val url = "$WEB/betard/$roomId"
        val body = Net.getBody(url, webHeaders, REFERER)
        val shape = betardShape(body)
        // 形状是判据，kind 与 detail 都在 betardFailure 的同一条分支里出：
        // 结构上不存在「形状说 A、码说 B」的缝。
        // JSON 单独判出来是「成功」，不是「其余都算成功」。
        if (shape != BetardShape.JSON) throw betardFailure(shape, url, roomId, body)
        return parseBetard(url, body)
    }

    private fun parseBetard(url: String, body: NetBody): JSONObject = try {
        JSONObject(body.text)
    } catch (e: JSONException) {
        // 形状像 JSON 却解析不了 = 我们读不懂（不是「房间不存在」）
        throw SiteException(
            SiteError.PARSE_BROKEN, url,
            "斗鱼：betard 首字符是 { 却解析失败（截断/拼包/改版）${body.size}", e
        )
    }

    // ---------------------------------------------------------------- 内部工具

    /** 一级分区列表：gapi/rkc/directory/mixList/2_{cate1}/{page}，单页 120 条 */
    private suspend fun webMixList(cate1: String, page: Int): List<Room> {
        val json = Net.getJson(
            "$WEB/gapi/rkc/directory/mixList/2_$cate1/$page",
            webHeaders, "$WEB/directory/all"
        )
        if (json.optInt("code", -1) != 0) {
            throw SiteException(
                SiteError.PARSE_BROKEN, "$WEB/gapi/rkc/directory/mixList/2_$cate1/$page",
                "斗鱼：列表接口异常 code=${json.optInt("code")}"
            )
        }
        val data = json.optJSONObject("data") ?: return emptyList()
        val arr = data.optJSONArray("rl") ?: return emptyList()
        val out = ArrayList<Room>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rid = o.optLong("rid", 0L)
            if (rid <= 0L) continue
            out.add(
                Room(
                    platform = platform,
                    roomId = rid.toString(),
                    title = o.optString("rn").ifBlank { "未命名直播间" },
                    hostName = o.optString("nn"),
                    coverUrl = o.optString("rs16").ifBlank { o.optString("rs16_avif") },
                    areaName = o.optString("c2name"),
                    viewers = o.optLong("ol", 0L),
                    live = true
                )
            )
        }
        return out
    }

    /** 移动端列表：type={shortName}。单页固定 8 条，是二级分区的唯一可用入口。 */
    private suspend fun mobileRoomList(shortName: String?, page: Int): List<Room> {
        val type = if (shortName.isNullOrBlank()) "" else "type=$shortName&"
        val json = Net.getJson("$MOBILE/api/room/list?${type}page=$page", mobileHeaders, REFERER)
        if (json.optInt("code", -1) != 0) {
            throw SiteException(
                SiteError.PARSE_BROKEN, "$MOBILE/api/room/list?${type}page=$page",
                "斗鱼：列表接口异常 code=${json.optInt("code")}"
            )
        }
        val data = json.optJSONObject("data") ?: return emptyList()
        val arr = data.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<Room>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rid = o.optLong("rid", 0L)
            if (rid <= 0L) continue
            out.add(
                Room(
                    platform = platform,
                    roomId = rid.toString(),
                    title = o.optString("roomName").ifBlank { "未命名直播间" },
                    hostName = o.optString("nickname"),
                    coverUrl = o.optString("roomSrc"),
                    areaName = "",
                    viewers = parseHot(o.optString("hn")),
                    live = o.optInt("isLive", 1) == 1
                )
            )
        }
        return out
    }

    private suspend fun ensureCateMeta(): CateMeta {
        cateMeta?.let { return it }
        val json = Net.getJson("$MOBILE/api/cate/list", mobileHeaders, REFERER)
        val data = json.optJSONObject("data")
            ?: throw SiteException(SiteError.PARSE_BROKEN, "$MOBILE/api/cate/list", "斗鱼：分区接口不可用")
        val cate1 = HashSet<String>()
        val parents = data.optJSONArray("cate1Info") ?: JSONArray()
        for (i in 0 until parents.length()) {
            val id = parents.optJSONObject(i)?.optString("cate1Id").orEmpty()
            if (id.isNotBlank()) cate1.add(id)
        }
        val cate2 = HashMap<String, String>()
        val children = data.optJSONArray("cate2Info") ?: JSONArray()
        for (i in 0 until children.length()) {
            val o = children.optJSONObject(i) ?: continue
            val id = o.optString("cate2Id")
            val short = o.optString("shortName")
            if (id.isNotBlank() && short.isNotBlank()) cate2[id] = short
        }
        if (cate1.isEmpty() && cate2.isEmpty()) {
            // 「接口通了但一个分区都没有」= 结构变了，不能返回空列表让 UI 显示「没有分区」
            throw SiteException(SiteError.PARSE_BROKEN, "$MOBILE/api/cate/list", "斗鱼：分区数据为空")
        }
        val meta = CateMeta(cate1, cate2)
        cateMeta = meta
        return meta
    }

    /**
     * hlsH5Preview 的 `error` 码 → 错误码。和 [previewError]（给人看的文案）必须成对维护：
     * 文案里出现一个新码而这里没跟上，用户就会看到一句准确的话配一个错误的按钮。
     */
    private fun previewKind(code: Int): SiteError = when (code) {
        102 -> SiteError.ROOM_NOT_FOUND
        104 -> SiteError.STREAM_OFFLINE
        101 -> SiteError.PARSE_BROKEN          // 参数/路径改版：我们这边的错，重试没用
        2002, 2005 -> SiteError.SIGN_INVALID   // auth 时间戳/rid 校验：平台改版的最早信号
        else -> SiteError.PARSE_BROKEN
    }

    private fun previewError(code: Int, msg: String): String = when (code) {
        102 -> "房间不存在"
        104 -> "房间未开播"
        101 -> "取流参数错误（接口已改版）"
        2002, 2005 -> "取流鉴权失败（接口已改版）"
        else -> "取流失败 error=$code${if (msg.isBlank()) "" else "（$msg）"}"
    }

    /** "270.0万" / "1.2亿" / "12345" → 数字 */
    private fun parseHot(raw: String): Long {
        val t = raw.trim()
        if (t.isEmpty()) return 0L
        return when {
            t.endsWith("亿") -> ((t.dropLast(1).toDoubleOrNull() ?: 0.0) * 100_000_000L).toLong()
            t.endsWith("万") -> ((t.dropLast(1).toDoubleOrNull() ?: 0.0) * 10_000L).toLong()
            else -> t.toLongOrNull() ?: 0L
        }
    }

    private fun upgrade(url: String): String =
        if (url.startsWith("http://")) "https://" + url.substring(7) else url

    private fun encode(raw: String): String = java.net.URLEncoder.encode(raw, "UTF-8")

    private fun md5(text: String): String {
        val bytes = java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}

/**
 * `betard` 响应体的形状。
 *
 * **为什么是 enum 而不是 `SiteError?`**：`null` 兼职表达「解析成功」的时候，`when` 上少写一个
 * 分支编译器不会拦（Kotlin 只在 `when` 有值时才要求穷尽，`when` 当语句用时靠的是
 * `SiteError` 那 13 个成员——于是漏掉的码被 `else` 一兜，就静默归进「成功」那一侧）。
 * 换成 4 成员的字面量枚举以后，「成功」变成一个**必须显式写出来**的分支。
 */
internal enum class BetardShape { JSON, NOT_FOUND_PAGE, OTHER_HTML, EMPTY }

/**
 * 斗鱼「房间不可用」提示页的正文标记。**实测两个变体**（`docs/06 §2.4`）：
 * - `该房间目前没有开放` —— `betard/1`、`betard/88888888`、`betard/999999999`，1354 字节
 * - `没有找到该房间` —— `betard/0`，1349 字节
 *
 * 两句话在平台上是一回事（这个号没有可展示的房间），所以映射同一个码，靠 `detail` 记谁命中。
 * 注意**不能**只匹配 `<title>提示信息 -斗鱼</title>`：那张模板也被通用 404 页复用
 * （`betard/-1`，5239 字节），会把「路径写错了」说成「房间不存在」。
 */
private val BETARD_ABSENT_MARKERS = listOf("该房间目前没有开放", "没有找到该房间")

/**
 * 响应体 → 形状。纯函数，不碰网络，单测直接喂字节。
 *
 * 顺序有意义：**先判空，再判 JSON，最后才看标记串**。反过来的话，一个空体会落进
 * 「不含标记 → 我们读不懂」，把「网关给了空响应」和「平台改版」混成一件事。
 */
internal fun betardShape(body: NetBody): BetardShape {
    if (body.text.isBlank()) return BetardShape.EMPTY
    // BOM 会让 `startsWith("{")` 假失败，把 119KB 的正常响应判成 HTML 提示页
    val head = body.text.trimStart().removePrefix("\uFEFF").trimStart()
    if (head.startsWith("{")) return BetardShape.JSON
    return if (BETARD_ABSENT_MARKERS.any { body.text.contains(it) }) BetardShape.NOT_FOUND_PAGE
    else BetardShape.OTHER_HTML
}

/**
 * 三个**失败**形状 → 异常。形状与 `kind`/`detail` 在同一个分支里出，结构上不可能对不上。
 *
 * `when` 穷尽 4 个成员，`JSON` 单独写成一个 [error] 而不是 `else`：将来给 [BetardShape]
 * 加成员，这里会**编译报错**，加的人必须表态它算不算失败。`SiteError?` 那版做不到这一点——
 * 那边一个 `else -> Unit` 就编过了，而这正是这次返工的原因。
 */
internal fun betardFailure(
    shape: BetardShape,
    url: String,
    roomId: String,
    body: NetBody
): SiteException = when (shape) {
    BetardShape.NOT_FOUND_PAGE -> SiteException(
        SiteError.ROOM_NOT_FOUND, url,
        "斗鱼：房间 $roomId 不存在（命中斗鱼提示页「该房间目前没有开放 / 没有找到该房间」）${body.size}"
    )

    BetardShape.EMPTY -> SiteException(
        SiteError.PARSE_BROKEN, url,
        "斗鱼：betard 返回了空响应（接口或网关异常，不是用户输错）${body.size}"
    )

    BetardShape.OTHER_HTML -> SiteException(
        SiteError.PARSE_BROKEN, url,
        "斗鱼：betard 返回了 HTML 而不是数据，且不是房间不存在的提示页（改版/维护/风控/劫持？）${body.size}"
    )

    BetardShape.JSON -> error("betardFailure 只接失败形状；JSON 走 parseBetard")
}
