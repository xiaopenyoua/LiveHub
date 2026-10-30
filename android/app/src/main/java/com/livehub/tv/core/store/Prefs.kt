package com.livehub.tv.core.store

import android.content.Context
import android.content.SharedPreferences
import com.livehub.tv.core.model.Platform
import com.livehub.tv.core.model.Room
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地存储。刻意不用 DataStore / Room：
 * 电视盒子 IO 慢、内存小，一个 SharedPreferences 加几段 JSON 足够，
 * 也避免多引两个依赖拖慢冷启动。
 */
object Prefs {

    private const val NAME = "livehub"
    private lateinit var sp: SharedPreferences

    /**
     * 关注/历史在内存里留一份。
     * 卡片的「已关注」角标是在 Compose 重组中每帧调 isFollowed() 的，
     * 每次去 SharedPreferences 再 JSONArray 解析一遍，四行×四列就是几十次，
     * 低端盒子上会直接掉帧。写路径顺手更新缓存，保证读写一致。
     */
    private var followsCache: List<Room>? = null
    private var historyCache: List<Room>? = null

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        followsCache = null
        historyCache = null
    }

    // ---------- 设置 ----------

    var danmakuEnabled: Boolean
        get() = sp.getBoolean("danmaku_enabled", true)
        set(v) = sp.edit().putBoolean("danmaku_enabled", v).apply()

    /** 弹幕字号（sp）。设计规范第十一节：大屏默认 40，手机端通常 24 */
    var danmakuSize: Int
        get() = sp.getInt("danmaku_size", 40)
        set(v) = sp.edit().putInt("danmaku_size", v).apply()

    /** 弹幕不透明度 0-100，设计规范默认 80% */
    var danmakuAlpha: Int
        get() = sp.getInt("danmaku_alpha", 80)
        set(v) = sp.edit().putInt("danmaku_alpha", v).apply()

    /** 屏蔽词，逗号分隔 */
    var danmakuKeywords: String
        get() = sp.getString("danmaku_shield", "").orEmpty()
        set(v) = sp.edit().putString("danmaku_shield", v).apply()

    /**
     * 首选画质。取值是**平台自己的数字码**：`""`（不指定）/ `"150"` / `"250"` / `"400"`。
     * **不是「超清」这种名字**——这段 KDoc 以前写的是「首选清晰度名」，但那从来不是事实，
     * `SettingsScreen` 的 `write` 一直存的是数字码。
     *
     * **这里曾经替一道不存在的防线作证（2026-10-29 更正）**。旧文写的是：
     * 「`ui` 侧唯一的消费者是 `qualityRank(wanted)`，而它靠 `wanted.toIntOrNull()` 把
     * 『格式不对』变成『没有偏好』。存了名字就落到 null，表现是**上限提示永远不弹**」。
     * 两半都不成立：`qualityRank` 一次都没调过 `toIntOrNull`（今天全 `main` 里它只出现在
     * `DouyuDanmaku` 数弹幕那一处；`ui` 侧将来若拆出设置专用的整数解析，那是另一件事，
     * 见下一段）；而存了名字会命中它的 `contains("超清")`，提示照弹——只是按**哔哩那张表**
     * 折算，虎牙的「超清」也被当成 250。所以「存名字」的问题不是失能，是**把平台的措辞
     * 当成了跨平台的键**。这条错的危害在于：它让人以为存名字有兜底，而那条兜底本身就是
     * 错的来源——`qualityRank(raw: String)` 里那四个 `contains` 拿哔哩的词表翻译任何平台的措辞。
     *
     * 判这个偏好有没有被用上，看 `qualityRank` 的实参，不看这段字。**在 `prefRank` 出现之前它只
     * 收一个 `String` 参数，而偏好侧（`PlayerScreen` 里的 `wanted`）和实得侧（`urls.first().quality`，
     * 即平台串）走的是同一个函数**——两侧共用同一份名字匹配，这就是上面那句「跨平台的键」在代码里的形状。
     * 哪天把偏好侧拆成 `prefRank`（设置码专用的整数解析），名字匹配才只剩实得侧一处。
     * 这里点符号不点任务号：符号在源码里出现的那天，这句话自动为真；任务号不会。
     *
     * `rank` 的结果只当闸门，不上屏；上屏的文字来自 `qualityLabel`，而**那里今天也在按名字
     * 翻译**——虎牙的「超清」被印成「超清 720P」就是「翻译后上屏」的后果（不是翻译错，是翻译了）。
     * 但在比较侧，名字匹配不能就这么删：`PlayUrl.qn` 的默认值是 0，全仓只有 `BilibiliSite`
     * 写非 0（`Models.kt` 的 `val qn: Int = 0`），而 `qualityRank` 今天还只能吃字符串，
     * 四个平台全靠名字/数字码换算。删掉它，虎牙/抖音/斗鱼的上限提示会恒不触发（`a` 恒 -1，
     * `a in 0 until w` 恒假）。那不叫口径纯，叫少一句真话。
     *
     * 另一个容易误解的点：`playUrls(roomId, preferQuality)` 这个参数**四个实现都没读**——
     * 2026-10-29 数过两次，`preferQuality` 在 `Bilibili/Douyu/Huya/Douyin` 四个 Site 里各只出现
     * 1 次，就是签名本身。所以今天这个偏好只决定「要不要提示被压档」，不决定请求哪一档；
     * 「设了 400 却还是 1080P」不是 bug。哪天接上了请求侧，改这句。
     */
    var preferredQuality: String
        get() = sp.getString("quality", "").orEmpty()
        set(v) = sp.edit().putString("quality", v).apply()

    /** 0 = 自动，1 = 硬解，2 = 软解 */
    var decoderMode: Int
        get() = sp.getInt("decoder", 0)
        set(v) = sp.edit().putInt("decoder", v).apply()

    /** 播放页默认显示弹幕 */
    var playerDanmakuDefault: Boolean
        get() = sp.getBoolean("player_danmaku", true)
        set(v) = sp.edit().putBoolean("player_danmaku", v).apply()

    /** 首页默认排序：hot / new */
    var homeSort: String
        get() = sp.getString("home_sort", "hot").orEmpty()
        set(v) = sp.edit().putString("home_sort", v).apply()

    /** 启动页：home / follow */
    var startScreen: String
        get() = sp.getString("start_screen", "home").orEmpty()
        set(v) = sp.edit().putString("start_screen", v).apply()

    /** 线路自动降级：出不了首帧时自动换下一条（关掉就只提示、不换） */
    var autoDowngrade: Boolean
        get() = sp.getBoolean("auto_downgrade", true)
        set(v) = sp.edit().putBoolean("auto_downgrade", v).apply()

    /** 弹幕同屏上限（条）。设计规范：12 条是 4K 大屏的舒适值，多了会糊成一片 */
    var danmakuMaxOnScreen: Int
        get() = sp.getInt("danmaku_max", 12)
        set(v) = sp.edit().putInt("danmaku_max", v).apply()

    /** 弹幕占用画面高度百分比（20-100）。默认 33 = 只占上 1/3，不遮主播面部与比分 */
    var danmakuAreaPercent: Int
        get() = sp.getInt("danmaku_area", 33)
        set(v) = sp.edit().putInt("danmaku_area", v).apply()

    // ---------- 关注 ----------

    fun follows(): List<Room> {
        followsCache?.let { return it }
        val raw = sp.getString("follows", "[]") ?: "[]"
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val p = Platform.of(o.optString("platform")) ?: return@mapNotNull null
                Room(
                    platform = p,
                    roomId = o.optString("roomId"),
                    title = o.optString("title"),
                    hostName = o.optString("host"),
                    coverUrl = o.optString("cover"),
                    areaName = o.optString("area"),
                    viewers = o.optLong("viewers"),
                    live = o.optBoolean("live", true)
                )
            }
        }.getOrDefault(emptyList())
        followsCache = parsed
        return parsed
    }

    fun isFollowed(room: Room): Boolean =
        follows().any { it.identity == room.identity }

    fun toggleFollow(room: Room): Boolean {
        val list = follows().toMutableList()
        val exist = list.indexOfFirst { it.identity == room.identity }
        val nowFollowing: Boolean
        if (exist >= 0) {
            list.removeAt(exist)
            nowFollowing = false
        } else {
            list.add(0, room)
            nowFollowing = true
        }
        saveFollows(list)
        return nowFollowing
    }

    fun saveFollows(list: List<Room>) {
        followsCache = list
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("platform", r.platform.key)
                put("roomId", r.roomId)
                put("title", r.title)
                put("host", r.hostName)
                put("cover", r.coverUrl)
                put("area", r.areaName)
                put("viewers", r.viewers)
                put("live", r.live)
            })
        }
        sp.edit().putString("follows", arr.toString()).apply()
    }

    // ---------- 历史 ----------

    fun history(): List<Room> {
        historyCache?.let { return it }
        val raw = sp.getString("history", "[]") ?: "[]"
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val p = Platform.of(o.optString("platform")) ?: return@mapNotNull null
                Room(
                    platform = p,
                    roomId = o.optString("roomId"),
                    title = o.optString("title"),
                    hostName = o.optString("host"),
                    coverUrl = o.optString("cover"),
                    areaName = o.optString("area"),
                    viewers = o.optLong("viewers")
                )
            }
        }.getOrDefault(emptyList())
        historyCache = parsed
        return parsed
    }

    fun pushHistory(room: Room, limit: Int = 40) {
        val list = history().filterNot { it.identity == room.identity }.toMutableList()
        list.add(0, room)
        while (list.size > limit) list.removeAt(list.size - 1)
        historyCache = list
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("platform", r.platform.key)
                put("roomId", r.roomId)
                put("title", r.title)
                put("host", r.hostName)
                put("cover", r.coverUrl)
                put("area", r.areaName)
                put("viewers", r.viewers)
            })
        }
        sp.edit().putString("history", arr.toString()).apply()
    }
}
