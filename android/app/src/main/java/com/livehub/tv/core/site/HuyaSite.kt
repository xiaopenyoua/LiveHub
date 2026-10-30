package com.livehub.tv.core.site

import android.util.Base64
import com.livehub.tv.core.model.LiveCategory
import com.livehub.tv.core.model.PlayUrl
import com.livehub.tv.core.model.Platform
import com.livehub.tv.core.model.Room
import com.livehub.tv.core.model.RoomDetail
import com.livehub.tv.core.model.SiteError
import com.livehub.tv.core.model.SiteException
import com.livehub.tv.core.model.StreamFormat
import com.livehub.tv.core.net.Net
import org.json.JSONArray
import org.json.JSONObject

/**
 * 虎牙。
 *
 * 数据来源：mp.huya.com/cache.php?m=Live&do=profileRoom（房间详情 + 取流签名参数）、
 *          www.huya.com/cache.php?m=LiveList&do=getLiveListByPage（推荐 / 分区列表）、
 *          live.cdn.huya.com/liveconfig/game/bussLive?bussType=1（分区树）、
 *          search.cdn.huya.com/?m=Search&do=getSearchContent（搜索）。
 * 鉴权：免登录。反爬靠 sFlvAntiCode 里的 wsSecret/wsTime/fm，fm 是 base64，
 *      解出首段 prefix 后 wsSecret = md5(prefix_uid_streamName_seqid_wsTime)，必须自己算。
 * 已知风险：
 * 1. live.cdn.huya.com/liveconfig/game/bussLive 不带 bussType=1 时返回空数组，不是接口挂了。
 * 2. HLS 线路只有 HS 系 CDN（hs.hls / HS24.hls 等）真的出 m3u8，AL / TX（含 AL13、TX15）
 *    的 .m3u8 恒 403，所以按 sCdnType 前缀过滤，只把 HS 的 HLS 交给播放器；
 *    FLV 保留全部 CDN（302/403 会由播放器回落吃掉）。
 * 3. 搜索接口的 docs 结构是 JSONP 时代的 key=1 字典，且分页参数是 rows/page。
 */
class HuyaSite : LiveSite {

    override val platform = Platform.HUYA

    private companion object {
        const val WEB = "https://www.huya.com"
        const val MP = "https://mp.huya.com"
        const val REFERER = "https://www.huya.com/"
        const val HEX = "0123456789abcdef"
    }

    private val apiHeaders = mapOf(
        "User-Agent" to Net.UA_PC,
        "Origin" to "https://www.huya.com"
    )

    private val streamHeaders = mapOf(
        "User-Agent" to Net.UA_PC,
        "Referer" to REFERER,
        "Origin" to "https://www.huya.com"
    )

    // ---------------------------------------------------------------- 列表

    override suspend fun recommend(page: Int): List<Room> = liveList("0", page)

    override suspend fun categories(): List<LiveCategory> {
        val json = Net.getJson(
            "https://live.cdn.huya.com/liveconfig/game/bussLive?bussType=1", apiHeaders, REFERER
        )
        if (json.optInt("status", 0) != 200) {
            throw SiteException(
                SiteError.PARSE_BROKEN, "https://live.cdn.huya.com/liveconfig/game/bussLive?bussType=1",
                "虎牙：分区接口异常 status=${json.optInt("status")}"
            )
        }
        val arr = json.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<LiveCategory>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optInt("isHide", 0) == 1) continue
            val gid = o.optDouble("gid", 0.0).toLong()
            if (gid <= 0L) continue
            out.add(
                LiveCategory(
                    platform = platform,
                    id = gid.toString(),
                    name = o.optString("gameFullName").ifBlank { o.optString("gameHostName") }
                )
            )
        }
        return out
    }

    override suspend fun categoryRooms(categoryId: String, page: Int): List<Room> =
        liveList(categoryId, page)

    override suspend fun search(keyword: String, page: Int): List<Room> {
        val url = "https://search.cdn.huya.com/?m=Search&do=getSearchContent" +
            "&q=${encode(keyword)}&uid=0&v=4&typ=-5&livestate=0&rows=40&page=$page"
        val json = Net.getJson(url, apiHeaders, REFERER)
        val header = json.optJSONObject("responseHeader")
        if (header != null && header.optInt("status", 0) != 0) {
            throw SiteException(SiteError.PARSE_BROKEN, url, "虎牙：搜索接口异常 status=${header.optInt("status")}")
        }
        // response["1"] 才是直播间（1024/1026/1027 是主播、视频、专区）
        val docs = json.optJSONObject("response")?.optJSONObject("1")?.optJSONArray("docs")
            ?: return emptyList()
        val out = ArrayList<Room>(docs.length())
        for (i in 0 until docs.length()) {
            val o = docs.optJSONObject(i) ?: continue
            val rid = o.optLong("room_id", 0L)
            if (rid <= 0L) continue
            out.add(
                Room(
                    platform = platform,
                    roomId = rid.toString(),
                    title = o.optString("live_intro").ifBlank { o.optString("game_nick") },
                    hostName = o.optString("game_nick"),
                    coverUrl = upgrade(o.optString("game_avatarUrl180")),
                    areaName = o.optString("game_name"),
                    viewers = 0L,
                    live = o.optBoolean("gameLiveOn", true)
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------- 详情 / 取流

    override suspend fun detail(roomId: String): RoomDetail? {
        val json = profile(roomId)
        val data = json.optJSONObject("data")
            ?: throw SiteException(SiteError.ROOM_NOT_FOUND, null, "虎牙：房间 $roomId 不存在")
        val liveData = data.optJSONObject("liveData") ?: JSONObject()
        val profile = data.optJSONObject("profileInfo") ?: JSONObject()
        val status = data.optString("liveStatus")
        // 详情页封面用 liveData.screenshot（直播间截图），avatar180 只是头像，列表页兜底用
        val screenshot = upgrade(liveData.optString("screenshot"))
        val avatar = upgrade(
            liveData.optString("avatar180").ifBlank { profile.optString("avatar180") }
        )
        val nick = profile.optString("nick")
        // roomName 实测经常是空串，兜底顺序固定：roomName → introduction → "{nick} 的直播"
        val title = liveData.optString("roomName")
            .ifBlank { liveData.optString("introduction") }
            .ifBlank { if (nick.isBlank()) "未命名直播间" else "$nick 的直播" }
        return RoomDetail(
            room = Room(
                platform = platform,
                roomId = roomId,
                title = title,
                hostName = nick,
                coverUrl = screenshot.ifBlank { avatar },
                areaName = "",
                viewers = liveData.optLong("userCount", 0L),
                live = status == "ON",
                keyframeUrl = screenshot
            ),
            announcement = liveData.optString("contentIntro"),
            startedAtEpochSec = 0L,
            qualities = emptyList()
        )
    }

    override suspend fun playUrls(roomId: String, preferQuality: String?): List<PlayUrl> {
        val json = profile(roomId)
        val data = json.optJSONObject("data")
            ?: throw SiteException(SiteError.ROOM_NOT_FOUND, null, "虎牙：房间 $roomId 不存在")
        val status = data.optString("liveStatus")
        if (status != "ON") {
            throw SiteException(
                SiteError.STREAM_OFFLINE, null,
                "虎牙：房间 $roomId " + if (status == "REPLAY") "正在回放，无直播流" else "未开播"
            )
        }
        val stream = data.optJSONObject("stream")
            // liveStatus 已经是 ON，却拿不到 stream：这是我们解析不出地址，不是「没开播」。
            // 归 STREAM_OFFLINE 会把我们的故障说成平台正常（抖音那边刚修掉同一个 bug），
            // 而且 detail() 里这张卡是「直播中」，两处会互相打脸。
            ?: throw SiteException(SiteError.PARSE_BROKEN, null, "虎牙：已开播，但取不到画面（接口可能改版）")
        val cdns = stream.optJSONArray("baseSteamInfoList")
            ?: throw SiteException(SiteError.PARSE_BROKEN, null, "虎牙：取流响应缺少 baseSteamInfoList")

        val quality = qualityName(stream)
        val flv = ArrayList<PlayUrl>()
        val hls = ArrayList<PlayUrl>()
        for (i in 0 until cdns.length()) {
            val cdn = cdns.optJSONObject(i) ?: continue
            val streamName = cdn.optString("sStreamName")
            val uid = cdn.optString("lPresenterUid")
            // FLV 与 HLS 各用各的 antiCode（实测两者内容一致，但字段是分开给的）
            val flvAnti = cdn.optString("sFlvAntiCode").ifBlank { cdn.optString("sHlsAntiCode") }
            if (streamName.isBlank()) continue
            val cdnType = cdn.optString("sCdnType")

            val flvQuery = antiQuery(flvAnti, uid, streamName)
            if (flvQuery != null) {
                val flvUrl = cdn.optString("sFlvUrl").ifBlank { "http://al.flv.huya.com/src" }
                val flvSuffix = cdn.optString("sFlvUrlSuffix").ifBlank { "flv" }
                flv.add(
                    // 命名传参：url 与 quality 都是 String 且相邻，互换能编过（见 BilibiliSite 同处注释）
                    PlayUrl(
                        url = upgrade("$flvUrl/$streamName.$flvSuffix?$flvQuery"),
                        quality = quality,
                        format = StreamFormat.FLV,
                        headers = streamHeaders
                    )
                )
            }

            // HLS 只信 HS 系 CDN：实测 AL / TX（含 AL13、TX15）的 .m3u8 恒 403，
            // 只有 hs.hls 出真 m3u8。别把必死的线路塞给播放器，回落要花掉一次首帧超时。
            val hlsAnti = cdn.optString("sHlsAntiCode").ifBlank { flvAnti }
            val hlsQuery = if (cdnType.startsWith("HS")) antiQuery(hlsAnti, uid, streamName) else null
            if (hlsQuery != null && cdn.optString("sHlsUrl").isNotBlank()) {
                val hlsUrl = cdn.optString("sHlsUrl")
                val hlsSuffix = cdn.optString("sHlsUrlSuffix").ifBlank { "m3u8" }
                hls.add(
                    PlayUrl(
                        url = upgrade("$hlsUrl/$streamName.$hlsSuffix?$hlsQuery"),
                        quality = "$quality·HLS",
                        format = StreamFormat.HLS,
                        headers = streamHeaders
                    )
                )
            }
        }
        val out = ArrayList<PlayUrl>(flv.size + hls.size)
        out.addAll(flv)
        out.addAll(hls)
        if (out.isEmpty()) {
            throw SiteException(SiteError.PARSE_BROKEN, null, "虎牙：房间 $roomId 没有可用播放线路")
        }
        return out
    }

    private suspend fun profile(roomId: String): JSONObject {
        val json = fetchProfile(roomId)
        val status = json.optInt("status", -1)
        if (status == 200) return json
        val msg = json.optString("message")
        // 虎牙「房间不存在」是 **HTTP 200 + status:422**，不是 404——所以只能靠业务字段判，
        // 归到 HTTP_4XX 会得到一句「接口拒绝（可能是平台改版）」外加一个没用的重试按钮。
        if (status == 422) {
            throw SiteException(SiteError.ROOM_NOT_FOUND, profileUrl(roomId), "虎牙：房间号 $roomId 不存在")
        }
        throw SiteException(
            SiteError.PARSE_BROKEN, profileUrl(roomId),
            "虎牙：房间状态异常 status=$status${if (msg.isBlank()) "" else "（$msg）"}"
        )
    }

    private fun profileUrl(roomId: String) = "$MP/cache.php?m=Live&do=profileRoom&roomid=$roomId&showSecret=1"

    /**
     * mp.huya.com 的边缘节点会偶发返回 nginx 404（实测同一个 URL 隔几秒再拉就 200），
     * 所以非 2xx 时重试一次再报错，避免把「网络抖动」当成「房间不存在」。
     *
     * 重试的取舍用的是 [SiteError.retryable]，不在这个文件里再写一套判据：
     * 用户点一下重试有没有意义，和这里重发一次有没有意义，本来就是同一个问题。
     * 于是 `ROOM_NOT_FOUND` / `PARSE_BROKEN` 不重试，超时、4xx、5xx 重试一次。
     */
    private suspend fun fetchProfile(roomId: String): JSONObject {
        val url = profileUrl(roomId)
        var last: SiteException? = null
        for (attempt in 0..1) {
            try {
                return Net.getJson(url, apiHeaders, "$WEB/$roomId")
            } catch (e: SiteException) {
                last = e
                if (!e.kind.retryable || attempt == 1) break
                kotlinx.coroutines.delay(300)
            }
        }
        val cause = last
        throw SiteException(
            cause?.kind ?: SiteError.PARSE_BROKEN, url,
            "虎牙：房间 $roomId 详情获取失败（${cause?.detail ?: "未知错误"}）", cause
        )
    }

    /**
     * 反爬参数：wsSecret = md5(prefix_uid_streamName_seqid_wsTime)。
     * prefix 是 fm（base64）解码后的首段；seqid 用毫秒时间戳 + 4 位随机数。
     */
    private fun antiQuery(antiCode: String, uid: String, streamName: String): String? {
        val params = HashMap<String, String>()
        for (pair in antiCode.split("&")) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = pair.substring(0, idx)
            val raw = pair.substring(idx + 1)
            params[key] = try {
                java.net.URLDecoder.decode(raw, "UTF-8")
            } catch (e: Exception) {
                raw
            }
        }
        val fm = params["fm"] ?: return null
        val wsTime = params["wsTime"] ?: return null
        val decoded = try {
            String(Base64.decode(fm, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
        val prefix = decoded.substringBefore('_')
        if (prefix.isBlank()) return null
        val seqid = System.currentTimeMillis().toString() + (1000 + java.util.Random().nextInt(9000))
        val secret = md5("${prefix}_${uid}_${streamName}_${seqid}_$wsTime")
        val ctype = params["ctype"] ?: "tars_mp"
        val fs = params["fs"] ?: "bgct"
        val uuid = java.util.UUID.randomUUID().toString().replace("-", "")
        return "wsSecret=$secret&wsTime=$wsTime&seqid=$seqid&ctype=$ctype" +
            "&ver=1&fs=$fs&uuid=$uuid&u=$uid&t=100&sv=2403051630"
    }

    /** 直播列表：gameId=0 就是全站热门，分区靠 gameId={gid} */
    private suspend fun liveList(gameId: String, page: Int): List<Room> {
        val url = "$WEB/cache.php?m=LiveList&do=getLiveListByPage&gameId=$gameId&tagAll=0&page=$page"
        val json = Net.getJson(url, apiHeaders, REFERER)
        if (json.optInt("status", 0) != 200) {
            throw SiteException(SiteError.PARSE_BROKEN, url, "虎牙：列表接口异常 status=${json.optInt("status")}")
        }
        val data = json.optJSONObject("data") ?: return emptyList()
        val arr = data.optJSONArray("datas") ?: return emptyList()
        val out = ArrayList<Room>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // profileRoom 才是直播间号，uid/liveChannel 是主播号，混用会取不到流
            val rid = o.optString("profileRoom")
            if (rid.isBlank() || rid == "0") continue
            val cover = o.optString("screenshot").ifBlank { o.optString("avatar180") }
            val nick = o.optString("nick")
            out.add(
                Room(
                    platform = platform,
                    roomId = rid,
                    title = o.optString("roomName")
                        .ifBlank { o.optString("introduction") }
                        .ifBlank { if (nick.isBlank()) "未命名直播间" else "$nick 的直播" },
                    hostName = nick,
                    coverUrl = upgrade(cover),
                    areaName = o.optString("gameFullName"),
                    viewers = o.optString("totalCount").toLongOrNull() ?: 0L,
                    live = true
                )
            )
        }
        return out
    }

    /**
     * 档名。**返回的是「第一个 `iBitRate > 0` 的档」，不是「码率最高的档」**——这两者今天不等价：
     * 357 个在播房间实测，`rateArray` 的码率序列一共只有 10 种形状，**全部按档位从高到低排**
     * （首档 >0 的 87 个全部严格递减；首档报 0 的那 270 个，除首位之外也都严格递减，
     * 没有一个是真升序的档位序列）。问题出在首档：**`iBitRate` 报 0 的有 270/357**，
     * 被 `> 0` 跳过的正是最高档 ⇒ 其中 236 个房间丢了首档名字（落到「超清」「蓝光4M」「流畅」），
     * 另外 34 个整个数组只有一档且报 0，于是连名字一起丢、返回「默认画质」
     * （抽 25 个这种房间：高清 9 / 蓝光4M 5 / 超清 5 / 流畅 3 / 蓝光 3 —— 平台是给了名字的）。
     * 要真修，两条弯路都得避开：既不能用「第一个有码率的档」当「最高」，也**不能改成「按
     * `iBitRate` 取 max」**——首档报 0 时 0 是最小值，max 同样选不中它，那 236 个房间一个
     * 也修不好。上面那组实测的另一种读法就是答案：**真·升序 0/357**（首档报 0 的 270 个里
     * 除首位之外也都严格递减）⇒ 档位序由**数组顺序本身**携带：取第一个 `sDisplayName`
     * 非空的档就是最高档名，`iBitRate` 只用来排序和展示，不该用来过滤名字。
     * 名字与码率是两个字段，缺一个不该把另一个也扔掉。
     */
    private fun qualityName(stream: JSONObject): String {
        val rates = stream.optJSONObject("flv")?.optJSONArray("rateArray") ?: return "默认画质"
        for (i in 0 until rates.length()) {
            val o = rates.optJSONObject(i) ?: continue
            val name = o.optString("sDisplayName")
            if (name.isNotBlank() && o.optInt("iBitRate", 0) > 0) return name
        }
        return "默认画质"
    }

    private fun upgrade(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("http://") -> "https://" + url.substring(7)
        else -> url
    }

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
