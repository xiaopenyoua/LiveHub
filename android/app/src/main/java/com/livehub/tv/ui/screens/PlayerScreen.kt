package com.livehub.tv.ui.screens

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.livehub.tv.core.danmaku.Danmaku
import com.livehub.tv.core.device.DeviceProfile
import com.livehub.tv.core.model.PlayUrl
import com.livehub.tv.core.model.Platform
import com.livehub.tv.core.model.Room
import com.livehub.tv.core.model.SiteException
import com.livehub.tv.core.model.SiteError
import com.livehub.tv.core.model.StreamFormat
import com.livehub.tv.core.model.toSiteException
import com.livehub.tv.core.site.Sites
import com.livehub.tv.core.store.Prefs
import com.livehub.tv.player.DanmakuLayer
import com.livehub.tv.player.LivePlayer
import com.livehub.tv.player.PlayerSurface
import com.livehub.tv.ui.AppNav
import com.livehub.tv.ui.PlayerControls
import com.livehub.tv.ui.components.CircleGlassButton
import com.livehub.tv.ui.components.Ease
import com.livehub.tv.ui.components.PillButton
import com.livehub.tv.ui.components.RailRow
import com.livehub.tv.ui.components.RoomCover
import com.livehub.tv.ui.components.TvFocus
import com.livehub.tv.ui.components.clockText
import com.livehub.tv.ui.components.keyHint
import com.livehub.tv.ui.components.qualityLabel
import com.livehub.tv.ui.components.qualityRank
import com.livehub.tv.ui.components.rememberTvFocus
import com.livehub.tv.ui.components.ridLabel
import com.livehub.tv.ui.components.shortName
import com.livehub.tv.ui.components.viewerText
import com.livehub.tv.ui.strings.userText
import com.livehub.tv.ui.theme.LiveHubType
import com.livehub.tv.ui.theme.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 同一条线路 EOF 后先原地重连几次，再判定「这条线不能连续播」。 */
private const val MAX_SAME_LINE_RECONNECTS = 4

/**
 * 播放页：画面永远是屏幕里最亮的东西。
 *
 * 画面不挂亮度滤镜——只在顶栏、底栏压着的两条带子上加深渐变，25%–60% 的画面一点不碰。
 * 控制层收起时遮罩一起淡出，把整块屏幕还给内容，而不是「只把按钮藏起来、画面还蒙着一层」。
 *
 * 键位（与设计稿一致）：
 *  - 控制条收起：↑↓ 换台（打开换台条）、←→ 换平台、OK 显示控制条
 *  - 控制条显示：←→ 在按钮之间移动、↑↓ 不换台（换台走「换台」按钮）、OK 执行、BACK 收起控制条
 *  - 换台条打开：↑↓ 换台、←→ 换平台、OK/ BACK 关闭
 */
@Composable
fun PlayerScreen(nav: AppNav, modifier: Modifier = Modifier) {
    val room = nav.playerRoom ?: return
    val context = LocalContext.current
    val player = remember(context) { LivePlayer(context) }
    // 退到后台停播、回前台续播：电视上按 Home 后直播声音不该继续。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> player.resume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    val ui by player.state.collectAsState()

    var loadError by remember { mutableStateOf<String?>(null) }
    /** 取流失败的归责码：决定给「重试」还是「回首页」（不可重试的房间不存在/已下播） */
    var loadErrorKind by remember { mutableStateOf<SiteError?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    var danmakuOn by remember { mutableStateOf(Prefs.playerDanmakuDefault) }
    var followed by remember(room.identity) { mutableStateOf(Prefs.isFollowed(room)) }

    /** 角落的一行小字：3s 安抚 / 4s 已切线路。刻意不进控制层，收起控制条时它也要在。 */
    var cornerNotice by remember(room.identity) { mutableStateOf<String?>(null) }

    /**
     * 弹幕通道自己说的话（`DanmakuEvent.System` / `Failed`）。
     *
     * 单独一个 state 而不是复用 [cornerNotice]：两者来源和寿命都不一样——播放提示关系到
     * 「画面出不出来」，弹幕提示关系到「底部滚不滚」。混用一个槽位的话，弹幕断了和线路
     * 在切会互相覆盖，用户看到的永远是后写的那一条。
     *
     * 为什么非要说出来：「弹幕不通但画面还在」是最容易被当成「这主播没人说话」的情况。
     * 它不是错误弹窗，是一条几秒后自己走掉的陈述。
     */
    var danmakuNotice by remember(room.identity) { mutableStateOf<String?>(null) }

    /** 画质被服务端压档时的那一句。静默失败是最糟的失败，所以必须说出来。 */
    var capNotice by remember(room.identity) { mutableStateOf<String?>(null) }

    /**
     * 同一条线路的自动重连次数。**只重连当前线路，绝不自动换到别的线路**。
     * 连续失败达到上限后置 [recoveryExhausted]，把选择权交还给用户（线路列表）。
     */
    var sameLineReconnects by remember(room.identity) { mutableStateOf(0) }

    /** 自动重连已用完：停在失败态，不再后台重试，也不再自动换线。 */
    var recoveryExhausted by remember(room.identity) { mutableStateOf(false) }

    /** 换台 OSD 的预览游标（nav.playerPool 下标）；打开时定位到当前房间。 */
    var osdCursor by remember(room.identity) { mutableStateOf(-1) }

    /** 本次 reload 是否由「用户切清晰度」触发；是的话不要清零「本场观看」。 */
    var qualityReload by remember(room.identity) { mutableStateOf(false) }

    /** 本次 reload 是否由「重连」触发：要回到同一条线路，且不清「本场观看」。 */
    var reconnectReload by remember(room.identity) { mutableStateOf(false) }

    /** 重连/重试时希望回到的线路下标。 */
    var resumeLine by remember(room.identity) { mutableStateOf(0) }

    val rootFocus = remember { FocusRequester() }
    val firstPill = rememberTvFocus()
    /** 失败态的默认焦点：重试。 */
    val retryFocus = rememberTvFocus()

    // **播放页一出现就拿住焦点**：否则底层首页的卡片仍可能持有焦点，
    // 用户按右键/OK 会操作到背后的卡片（实测会把哔哩房间换成斗鱼房间）。
    // 控制条可见时下面的 LaunchedEffect 会再把焦点落到第一枚胶囊；这里先兜底根节点。
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { rootFocus.requestFocus() }
    }

    /**
     * 回到**同一条线路**：能本地重签就本地重签（虎牙），否则重新取 playUrls
     * 再回到同一线路。这是自动恢复唯一允许的动作——绝不自动换到别的线路。
     */
    fun reconnectSameLine() {
        val current = ui.urls.getOrNull(ui.lineIndex)
        // 第一次优先本地重签（虎牙）；第二次信号说明 antiCode 本身也可能过期了，
        // 改走重新取 playUrls，拿整套新的 wsTime/seqid。
        if (current?.refresh != null && sameLineReconnects <= 1) {
            player.play(ui.urls, ui.lineIndex, resetWatch = false, refreshUrls = true)
        } else {
            resumeLine = ui.lineIndex
            reconnectReload = true
            reloadTick++
        }
    }

    /**
     * 用户从线路列表选了一条线。**这是唯一能切换线路的入口**。
     * 选中当前线路视为「重试这条线」；失败态下不需要先退出列表再按重试。
     */
    fun chooseLine(next: Int) {
        nav.playerControls = PlayerControls.Controls
        sameLineReconnects = 0
        recoveryExhausted = false
        if (next == ui.lineIndex && ui.error == null && !ui.ended && !ui.stalled) return
        val target = ui.urls.getOrNull(next)
        if (target?.refresh != null) {
            player.play(ui.urls, next, resetWatch = false, refreshUrls = true)
        } else if (room.platform == Platform.HUYA) {
            resumeLine = next
            reconnectReload = true
            reloadTick++
        } else {
            player.switchLine(next)
        }
        cornerNotice = "已切换到线路 ${next + 1}"
    }

    /**
     * 失败原因只有一个槽位，但**取用顺序决定了用户看到的是不是真原因**：
     * 播放层（`ui.error`，带「播放出错」前缀）比取流层更接近现场，所以它优先；
     * 旧实现 `loadError ?: ui.error` 让取流层永远压住播放层，403 被说成「网络不稳」。
     */
    val err: String? = ui.error
        ?: loadError
        ?: when {
            ui.stalled -> if (ui.firstFrameRendered) {
                "画面停住了（超过 8 秒没有新帧），按「重试」或打开线路列表"
            } else {
                "当前线路 12 秒内没有出画面，按「重试」或打开线路列表"
            }
            recoveryExhausted -> "当前线路反复中断，按「重试」或打开线路列表"
            else -> null
        }
    val hasError = err != null

    /**
     * 「在播」不再是一个字符串字面量：它必须同时满足
     * 真的渲染过首帧、播放器在播、没有错误/结束/停住。
     * 旧实现无条件画红点，全黑 + 「缓冲中…」的图上照样亮着（验收禁用该信号）。
     */
    val onAir = ui.firstFrameRendered && ui.playing && !ui.buffering &&
        ui.error == null && !ui.ended && !ui.stalled
    val onAirText = when {
        onAir -> "直播中"
        // 失败原因优先于「已结束」：recoveryExhausted 是错误态，不是主播下播。
        err != null -> "已中断"
        ui.ended || recoveryExhausted -> "已结束"
        // 房间不存在/已下播/ID 解析失败是「未在播」，不是「播着播着断了」
        loadErrorKind?.retryable == false -> "未在播"
        ui.buffering -> "缓冲中"
        else -> "未在播"
    }

    /**
     * 抖音弹幕握手要签名，v1 没有签名器，所以抖音没有弹幕可显示。
     * `LiveSite.capabilities.danmaku` 落地后这里改成读它；在那之前按「抖音没有弹幕」处理：
     * 不渲染弹幕层，控制条也不出现那个胶囊——宁可少一个按钮，也不给一个按了没反应的。
     */
    val danmakuAvailable = Sites.of(room.platform).capabilities.danmaku

    /** 上屏档位：只有哔哩用 qn 词表翻译；别家的名字原样显示，避免把虎牙「超清」印成「超清 720P」 */
    fun displayQuality(raw: String): String =
        if (room.platform == Platform.BILIBILI) qualityLabel(raw) else raw

    // 取流：房间一变（或用户改了清晰度 / 断流重连）就重拉
    LaunchedEffect(room.identity, reloadTick) {
        loadError = null
        loadErrorKind = null
        val keepWatch = qualityReload || reconnectReload
        val startLine = resumeLine
        qualityReload = false
        reconnectReload = false
        resumeLine = 0
        val wanted = Prefs.preferredQuality
        val urls: List<PlayUrl> = try {
            Sites.of(room.platform).playUrls(room.roomId, wanted.ifBlank { null })
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteException) {
            // 主文案只由 kind 查表（`ui/strings/SiteErrorText.kt`，全工程唯一一张），
            // `detail` 一个字都不上屏：它将来装的是 `HTTP 503 body=<html>` /
            // `TLS: SSLHandshakeException`，那是诊断页的证据，不是用户的语言。
            // 抖音下播 / 房间号贴错 / 接口改版对用户是三个完全不同的动作，不能并成一句。
            loadErrorKind = e.kind
            loadError = e.kind.userText()
            player.stop()
            return@LaunchedEffect
        } catch (e: Throwable) {
            val kind = e.toSiteException(room.roomId).kind
            loadErrorKind = kind
            loadError = kind.userText()
            player.stop()
            return@LaunchedEffect
        }
        if (urls.isEmpty()) {
            loadError = "没有取到播放地址"
            player.stop()
        } else {
            player.play(urls, startLine.coerceIn(0, urls.lastIndex), resetWatch = !keepWatch)
            // 要 400 却拿回 250：这是未登录被压档，必须显式说，不能静默
            val achieved = urls.first().quality
            val a = qualityRank(achieved)
            val w = qualityRank(wanted)
            // 只有哔哩真正消费 preferQuality；别家压档提示会因为全局偏好而误报
            capNotice = if (room.platform == Platform.BILIBILI && w > 0 && a in 0 until w) {
                "平台只给到 ${displayQuality(achieved)}"
            } else {
                null
            }
        }
    }

    /**
     * 统一自愈：**只重连当前线路，绝不自动换到别的线路**。
     *
     * 三个失败信号（播放层错误 / EOF / 看门狗停住）合并成一个入口，优先级
     * error > ended > stalled。每条线路最多 [MAX_SAME_LINE_RECONNECTS] 次；
     * 稳定播满后清零。用完置 [recoveryExhausted]，停在失败态，把选择权交还给
     * 用户（重试 / 线路列表）。用户投诉的「自动换台」正是旧实现里四套
     * 各自以 `switchLine(next)` 结尾的阶梯造成的。
     */
    LaunchedEffect(ui.error, ui.ended, ui.stalled, room.identity) {
        if (!Prefs.autoReconnect || loadError != null || recoveryExhausted) return@LaunchedEffect
        val signal = when {
            ui.error != null -> "error"
            ui.ended -> "ended"
            ui.stalled -> "stalled"
            else -> return@LaunchedEffect
        }
        // 解码器/解析失败换地址也不会好：别再重试，直接停在失败态。
        if (signal == "error" && !ui.errorRetryable) {
            recoveryExhausted = true
            return@LaunchedEffect
        }
        if (sameLineReconnects >= MAX_SAME_LINE_RECONNECTS) {
            recoveryExhausted = true
            cornerNotice = null
            return@LaunchedEffect
        }
        sameLineReconnects++
        cornerNotice = when (signal) {
            "ended" -> "直播流中断 · 正在重连当前线路…"
            else -> "正在重连当前线路…"
        }
        // EOF 是健康的播放结束，给对端 150ms 释放连接；错误/停住稍长一点。
        delay(if (signal == "ended") 150 else 800)
        reconnectSameLine()
    }

    /** 稳定播满一小段时间就把自动重连计数清零，并把恢复能力还回来。 */
    LaunchedEffect(ui.firstFrameRendered) {
        if (!ui.firstFrameRendered) return@LaunchedEffect
        Prefs.noteFirstFrame(ui.stats.firstFrameMs)
        // 虎牙 FLV 的连接本来就短（常见 5–9s），稳定阈值必须低于连接寿命，
        // 否则每次都被记成「短命」，播到上限就误判成失败。
        delay(if (room.platform == Platform.HUYA) 2_500 else 15_000)
        val st = player.state.value
        if (st.firstFrameRendered && !st.ended && st.error == null) {
            sameLineReconnects = 0
            recoveryExhausted = false
        }
    }

    // 压档提示自己淡出，不和首帧提示抢那一个位置
    LaunchedEffect(capNotice) {
        if (capNotice != null) {
            delay(6_000)
            capNotice = null
        }
    }

    // 弹幕提示同理：8 秒，比压档提示长一点——它说的多半是「不会再有弹幕了」，
    // 需要读到的人比「清晰度被压」更多。
    LaunchedEffect(danmakuNotice) {
        if (danmakuNotice != null) {
            delay(8_000)
            danmakuNotice = null
        }
    }

    // 换线/重连提示自己走掉，不长期占着角落
    LaunchedEffect(cornerNotice) {
        if (cornerNotice != null) {
            delay(3_000)
            cornerNotice = null
        }
    }

    /**
     * 起播时间线：只提示，不换线。
     *
     * 判据仍是 `firstFrameRendered`（真的渲染出首帧）。3s 还没画面就提示
     * 「正在连接」，12s 交给 LivePlayer 的 no-first-frame 看门狗（stalled）；
     * 之后的动作由统一自愈 effect 执行——**同线路重连，不自动换线**。
     *
     * 循环用 `delay(200)` 而不是 `withFrameNanos`：故障态的帧时钟本身可能就是停的。
     */
    LaunchedEffect(room.identity, reloadTick) {
        cornerNotice = null
        val started = SystemClock.elapsedRealtime()
        while (true) {
            delay(200)
            val st = player.state.value
            if (st.firstFrameRendered || st.error != null || st.ended || loadError != null) {
                cornerNotice = null
                return@LaunchedEffect
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed >= 3_000) {
                cornerNotice = "正在连接…"
            }
            if (elapsed >= 12_000) {
                // 交给 LivePlayer 的 no-first-frame 看门狗置 ui.stalled
                cornerNotice = null
                return@LaunchedEffect
            }
        }
    }

    val danmakuFlow = remember(room.identity) { Danmaku.of(room.platform).stream(room.roomId) }

    // 失败时把控制条叫出来，焦点直接落到「重试」：用户唯一想要的动作必须有按钮。
    // 其余情况维持原语义：控制条显示时落第一枚胶囊，收起时落回根节点。
    LaunchedEffect(hasError) {
        if (hasError && nav.playerControls == PlayerControls.Idle) {
            nav.playerControls = PlayerControls.Controls
        }
    }

    // 焦点：控制条显示时落在「重试」（失败）或第一枚胶囊（正常），收起时落回根节点。
    // 控制条是随 chromeAlpha 渐入才挂载的，所以在节点真正存在之前 requestFocus 会抛异常——
    // 逐帧重试几次，而不是「等一帧就假定它在」。
    LaunchedEffect(nav.playerControls, hasError) {
        when (nav.playerControls) {
            PlayerControls.Controls -> {
                val requester = if (hasError) retryFocus.requester else firstPill.requester
                var tries = 0
                while (tries < 10) {
                    withFrameNanos { }
                    if (runCatching { requester.requestFocus() }.isSuccess) break
                    tries++
                }
                // 胶囊没挂上（例如控制条还在渐入）就退回根节点，不能把焦点留在背后的屏幕
                if (tries >= 10) runCatching { rootFocus.requestFocus() }
            }
            // 线路列表自己请求行焦点；这里是兜底，防止焦点留在底下的节点上。
            PlayerControls.Lines -> runCatching { rootFocus.requestFocus() }
            PlayerControls.Osd, PlayerControls.Idle -> runCatching { rootFocus.requestFocus() }
        }
    }

    // 4 秒无操作自动收起控制条/进度条。任何按键都算操作，包括方向键和 OK。
    // 错误态也收起：错误原因在控制层之外（门外那条），收起后仍然可见；
    // 底部按钮由「按 OK」重新叫出，不会被计时器永久钉在屏幕上。
    var interactionTick by remember { mutableStateOf(0) }

    LaunchedEffect(nav.playerControls, interactionTick, hasError) {
        // 只有底部控制条参与 4 秒自动收起；线路列表/换台 OSD 是用户主动打开的
        // 二级界面，不会因为「停手」被收走。
        if (nav.playerControls != PlayerControls.Controls) return@LaunchedEffect
        delay(4_000)
        if (nav.playerControls == PlayerControls.Controls) {
            nav.playerControls = PlayerControls.Idle
        }
    }

    /** 换台候选池：同平台优先，否则全平台。 */
    fun osdPool(): List<Room> {
        val pool = nav.playerPool
        val same = pool.filter { it.platform == room.platform }
        return if (same.size >= 2) same else pool
    }

    /** 打开换台 OSD：只把游标放到当前房间，**不切换**。 */
    fun openOsd() {
        val pool = nav.playerPool
        osdCursor = pool.indexOfFirst { it.identity == room.identity }.let { if (it < 0) 0 else it }
        nav.playerControls = PlayerControls.Osd
    }

    /** 在 OSD 里移动候选（同平台内），不改变正在播放的房间。 */
    fun moveOsd(delta: Int) {
        val pool = nav.playerPool
        if (pool.isEmpty()) return
        val current = pool.getOrNull(osdCursor) ?: room
        val same = pool.filter { it.platform == current.platform }
        val list = if (same.size >= 2) same else pool
        val ci = list.indexOfFirst { it.identity == current.identity }.coerceAtLeast(0)
        val next = list[((ci + delta) % list.size + list.size) % list.size]
        val idx = pool.indexOfFirst { it.identity == next.identity }
        if (idx >= 0) osdCursor = idx
    }

    /** 在 OSD 里换平台候选（全平台池），不改变正在播放的房间。 */
    fun moveOsdPlatform(direction: Int) {
        val pool = nav.playerPool
        if (pool.isEmpty()) return
        val current = pool.getOrNull(osdCursor) ?: room
        val order = Platform.entries
        val start = order.indexOf(current.platform)
        for (step in 1..order.size) {
            val target = order[((start + direction * step) % order.size + order.size) % order.size]
            if (target == current.platform) continue
            val idx = pool.indexOfFirst { it.platform == target }
            if (idx >= 0) {
                osdCursor = idx
                return
            }
        }
    }

    /** OK：只在这里提交换台。 */
    fun commitOsd() {
        val candidate = nav.playerPool.getOrNull(osdCursor)
        nav.playerControls = PlayerControls.Controls
        if (candidate != null && candidate.identity != room.identity) {
            nav.playerRoom = candidate
        }
    }

    /**
     * 清晰度：哔哩是唯一能按「请求档位」重新取流的平台，所以走 `Prefs.preferredQuality + 重拉`；
     * 其它平台按当前取回的地址列表**真实切换**（抖音会给多档），只有一档时明确说只有一档——
     * 不给一个按了没反应的按钮。
     *
     * 线路已经列表化（线路胶囊打开线路列表）；清晰度本轮仍逐档循环：四家的档位语义
     * 不统一（哔哩读请求 qn、抖音按偏好排序、虎牙/斗鱼忽略偏好），强行做统一列表会把
     * 「设置没生效」暴露成一个新问题。
     */
    fun cycleQuality() {
        if (room.platform == Platform.BILIBILI) {
            val order = listOf("", "400", "250", "150")
            val current = Prefs.preferredQuality
            val index = order.indexOf(current).let { if (it < 0) 0 else it }
            val next = order[(index + 1) % order.size]
            Prefs.preferredQuality = next
            sameLineReconnects = 0
            recoveryExhausted = false
            // 保留当前线路：切清晰度不该把正在用的 CDN 丢掉
            resumeLine = ui.lineIndex
            qualityReload = true
            capNotice = "正在切换到 ${if (next.isBlank()) "自动（最高）" else qualityLabel(next)}…"
            reloadTick++
            return
        }
        val qualities = ui.urls.map { it.quality }.distinct()
        if (qualities.size <= 1) {
            capNotice = "这个平台只提供一档：${displayQuality(ui.quality)}"
            return
        }
        val index = qualities.indexOf(ui.quality).let { if (it < 0) -1 else it }
        val target = qualities[(index + 1) % qualities.size]
        val urlIndex = ui.urls.indexOfFirst { it.quality == target }
        if (urlIndex >= 0) {
            player.play(ui.urls, urlIndex, resetWatch = false)
            capNotice = "已切到 ${displayQuality(target)}"
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                interactionTick++
                val controls = nav.playerControls
                when (event.key) {
                    Key.DirectionUp, Key.DirectionDown -> when (controls) {
                        // Idle：方向键只叫出控制条，绝不换台（用户反馈的误触换台）。
                        PlayerControls.Idle -> {
                            nav.playerControls = PlayerControls.Controls
                            true
                        }
                        // Controls：吞掉，别让焦点逃到背后的顶栏/卡片。
                        PlayerControls.Controls -> true
                        // OSD：只移动预览游标，不提交。
                        PlayerControls.Osd -> {
                            if (event.nativeKeyEvent.repeatCount == 0) {
                                moveOsd(if (event.key == Key.DirectionUp) -1 else 1)
                            }
                            true
                        }
                        // Lines：交给线路列表容器处理。
                        PlayerControls.Lines -> false
                    }

                    Key.DirectionLeft, Key.DirectionRight -> when (controls) {
                        PlayerControls.Idle -> {
                            nav.playerControls = PlayerControls.Controls
                            true
                        }
                        // Controls：在胶囊之间移动焦点
                        PlayerControls.Controls -> false
                        PlayerControls.Osd -> {
                            if (event.nativeKeyEvent.repeatCount == 0) {
                                moveOsdPlatform(if (event.key == Key.DirectionLeft) -1 else 1)
                            }
                            true
                        }
                        PlayerControls.Lines -> false
                    }

                    Key.DirectionCenter, Key.Enter -> when (controls) {
                        PlayerControls.Idle -> {
                            nav.playerControls = PlayerControls.Controls
                            true
                        }
                        // OSD：只有 OK 才真正换台。
                        PlayerControls.Osd -> {
                            commitOsd()
                            true
                        }
                        // 交给当前聚焦的胶囊 / 线路行
                        PlayerControls.Controls, PlayerControls.Lines -> false
                    }

                    else -> false
                }
            }
            .background(Color.Black)
    ) {
        PlayerSurface(player = player, modifier = Modifier.fillMaxSize())

        // 弹幕：上三分之一屏，24s 穿屏；描边代替投影，不给画面蒙灰
        if (danmakuAvailable) {
            DanmakuLayer(
                events = danmakuFlow,
                enabled = danmakuOn,
                sizeSp = Prefs.danmakuSize,
                alpha = Prefs.danmakuAlpha / 100f,
                // A 档老盒子同屏上限压到 8 条（DeviceProfile 的判据，不看机型名单）
                maxOnScreen = if (DeviceProfile.isLowEnd) {
                    minOf(8, Prefs.danmakuMaxOnScreen)
                } else {
                    Prefs.danmakuMaxOnScreen
                },
                modifier = Modifier.fillMaxSize(),
                // 礼物不弹横幅：大屏上滚过一行「X 送出 Y」既看不清也抢戏。
                // 让 `onGift` 保持 null 是有意的——要做得做成侧栏，不是一行字。
                onGift = null,
                // System / Failed 都从这里进来。`DanmakuClient` 侧的约定是消息自带主语
                // （「虎牙弹幕暂不可用」「直播已结束」），所以这里不再加「弹幕：」前缀，
                // 加了反而和「直播已结束」这种句子打架。
                onSystem = { msg -> danmakuNotice = msg }
            )
        }

        Scrim(visible = nav.playerControls != PlayerControls.Idle)

        // ---------- 失败原因（门外） ----------
        // 它不属于控制层：控制条收起、画面停住时，用户仍然必须看得见「为什么」。
        // 旧实现把整句提示放在 chromeAlpha 门内（DEF-6），收起控制条后屏幕上只剩一张
        // 不动的画面——用户说的「播几分钟就崩」正是这一态。
        if (err != null) {
            // OSD 打开时错误条放顶部：底部整块被 OSD 卡 + 键位提示占着，
            // 放底部会和「↑↓ 换台 ←→ 换平台…」叠字（实测）。
            val osdOpenForBanner = nav.playerControls == PlayerControls.Osd
            Row(
                modifier = Modifier
                    .align(if (osdOpenForBanner) Alignment.TopCenter else Alignment.BottomStart)
                    .padding(
                        start = Tokens.Gutter,
                        end = Tokens.Gutter,
                        top = if (osdOpenForBanner) 150.dp else 0.dp,
                        bottom = when (nav.playerControls) {
                            PlayerControls.Idle -> 64.dp
                            PlayerControls.Osd -> 0.dp
                            else -> 250.dp
                        }
                    )
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Tokens.Live)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = err,
                    style = LiveHubType.small,
                    color = Color(0xFFE8E8ED)
                )
                Spacer(Modifier.width(18.dp))
                Text(
                    // 控制条收起后 OK 只是把它叫回来，不会直接重试——提示必须说真话。
                    text = when {
                        loadErrorKind?.retryable == false -> "按返回键回首页"
                        nav.playerControls == PlayerControls.Idle -> "按 OK 显示控制条"
                        else -> "按 OK 重试"
                    },
                    style = LiveHubType.small,
                    color = Tokens.Muted
                )
            }
        }

        // ---------- 顶部信息条 ----------
        val chromeAlpha by animateFloatAsState(
            targetValue = if (nav.playerControls == PlayerControls.Idle) 0f else 1f,
            animationSpec = tween(200, easing = Ease.Focus),
            label = "chrome"
        )
        if (chromeAlpha > 0.01f) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Tokens.Gutter, end = Tokens.Gutter, top = 56.dp)
                    .graphicsLayer { alpha = chromeAlpha },
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleGlassButton(
                    text = "‹",
                    onClick = { nav.playerRoom = null }
                )
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = room.platform.shortName,
                            style = LiveHubType.meta,
                            color = Tokens.Muted
                        )
                        Text(text = "  ·  ", style = LiveHubType.meta, color = Tokens.Muted)
                        Text(
                            text = room.hostLabel,
                            style = LiveHubType.meta,
                            color = Tokens.Muted
                        )
                        Text(text = "  ·  ", style = LiveHubType.meta, color = Tokens.Muted)
                        Text(
                            text = "${room.ridLabel} ${room.roomId}",
                            style = LiveHubType.meta,
                            color = Color(0xFFC7C7CC)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = room.title.ifBlank { room.hostLabel },
                        style = LiveHubType.h2,
                        color = Tokens.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(20.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${viewerText(room.viewers)} 人在看",
                        style = LiveHubType.meta,
                        color = Tokens.Muted
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = buildString {
                            // 显示服务端真正给的档位，不硬编码「原画」：哔哩未登录会被压到 250
                            append(displayQuality(ui.quality))
                            // positionMs 是「本场起播后的偏移」，不是主播已播多久——
                            // 直播流没有绝对起点，写「已播」会变成一个对不上的数字。
                            // 取流失败/从未出画时不显示：否则「已下播」的房间也会跳秒。
                            if (loadError == null && (ui.firstFrameRendered || ui.playing) &&
                                ui.positionMs > 0
                            ) {
                                append("　·　本场观看 ${clockText(ui.positionMs)}")
                            }
                        },
                        style = LiveHubType.meta,
                        color = Tokens.Muted
                    )
                }
            }
        }

        // ---------- 换台条 ----------
        val osdOpen = nav.playerControls == PlayerControls.Osd
        val osdAlpha by animateFloatAsState(
            targetValue = if (osdOpen) 1f else 0f,
            animationSpec = tween(280, easing = Ease.Focus),
            label = "osdAlpha"
        )
        val osdShift by animateFloatAsState(
            targetValue = if (osdOpen) 0f else 24f,
            animationSpec = tween(280, easing = Ease.Focus),
            label = "osdShift"
        )
        if (osdAlpha > 0.01f) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = Tokens.Gutter, bottom = 272.dp)
                    .width(1240.dp)
                    .graphicsLayer {
                        alpha = osdAlpha
                        translationY = osdShift
                    }
            ) {
                // 预览游标：只移动候选，不改变正在播放的房间；OK 才提交。
                val pool = nav.playerPool
                val candidate = pool.getOrNull(osdCursor) ?: room
                val osdPos = pool.indexOfFirst { it.identity == candidate.identity }
                    .let { if (it < 0) 1 else it + 1 }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xF71C1C1F))
                        .padding(horizontal = 44.dp, vertical = 34.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = candidate.hostLabel,
                        style = LiveHubType.hero.copy(lineHeight = 80.sp),
                        color = Tokens.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(360.dp)
                    )
                    Spacer(Modifier.width(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "${candidate.platform.shortName} · ${candidate.ridLabel} ${candidate.roomId}",
                            style = LiveHubType.meta,
                            color = Tokens.Muted
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = candidate.title.ifBlank { candidate.hostLabel },
                            style = LiveHubType.title.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
                            color = Tokens.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "${viewerText(candidate.viewers)} 人在看　·　$osdPos/${pool.size}",
                            style = LiveHubType.meta,
                            color = Tokens.Faint
                        )
                    }
                    Spacer(Modifier.width(44.dp))
                    Box(
                        Modifier
                            .size(width = 300.dp, height = 169.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Tokens.Panel)
                    ) {
                        OsdThumb(room = candidate)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.padding(start = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(30.dp)
                ) {
                    Text(text = keyHint("↑↓" to true, " 选台" to false), style = LiveHubType.meta, color = Tokens.Muted)
                    Text(text = keyHint("←→" to true, " 换平台" to false), style = LiveHubType.meta, color = Tokens.Muted)
                    Text(text = keyHint("OK" to true, " 换到这台" to false), style = LiveHubType.meta, color = Tokens.Muted)
                    Text(text = keyHint("返回" to true, " 关闭" to false), style = LiveHubType.meta, color = Tokens.Muted)
                }
            }
        }

        // ---------- 线路列表（用户主动打开；OK 才切，返回取消） ----------
        if (nav.playerControls == PlayerControls.Lines) {
            LineSheet(
                urls = ui.urls,
                current = ui.lineIndex,
                onChoose = { chooseLine(it) }
            )
        }

        // ---------- 底部控制条 ----------
        if (chromeAlpha > 0.01f) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = Tokens.Gutter, end = Tokens.Gutter, bottom = 56.dp)
                    .graphicsLayer { alpha = chromeAlpha }
            ) {
                // 直播进度：直播没有终点，整条是红的；它同时也是「这是直播」的信号
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.22f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Tokens.Live)
                        )
                    }
                    Spacer(Modifier.width(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (onAir) Tokens.Live else Tokens.Faint)
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = onAirText,
                            style = LiveHubType.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = if (onAir) Tokens.Live else Tokens.Faint
                        )
                    }
                }
                Spacer(Modifier.height(26.dp))

                // 失败原因已经在门外那一条里说了；控制条里不再重复一遍（重复会让两处
                // 生命周期不同步，用户看到同一件事有两种消失时机）。
                if (err == null && ui.buffering) {
                    Text(
                        text = "缓冲中…",
                        style = LiveHubType.small,
                        color = Color(0xFFC7C7CC),
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val enabled = nav.playerControls == PlayerControls.Controls
                    if (hasError) {
                        val nonRetryable = loadErrorKind?.retryable == false
                        PillButton(
                            text = if (nonRetryable) "回首页" else "重试",
                            onClick = {
                                if (nonRetryable) {
                                    nav.playerRoom = null
                                    return@PillButton
                                }
                                // 重新取地址，而不是 prepare 一个可能已经死掉的旧 URL。
                                // 取流层错误同样适用：重试按钮必须对两种失败都能用。
                                sameLineReconnects = 0
                                recoveryExhausted = false
                                resumeLine = ui.lineIndex
                                reconnectReload = true
                                reloadTick++
                            },
                            solid = true,
                            tv = retryFocus,
                            height = 68.dp,
                            horizontalPadding = 32.dp,
                            fontSize = 24.sp
                        )
                    }
                    if (danmakuAvailable) {
                        PillButton(
                            text = "弹幕",
                            valueText = if (danmakuOn) "开" else "关",
                            onClick = { danmakuOn = !danmakuOn },
                            tv = firstPill,
                            height = 68.dp,
                            horizontalPadding = 30.dp,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Medium,
                            enabled = enabled
                        )
                    }
                    PillButton(
                        text = "清晰度",
                        valueText = displayQuality(ui.quality),
                        onClick = { cycleQuality() },
                        // 抖音没有弹幕胶囊时，首焦点落到清晰度上——否则 firstPill 永远挂不上节点，
                        // 控制条出来了却没有任何按钮能聚焦
                        tv = if (danmakuAvailable) rememberTvFocus() else firstPill,
                        height = 68.dp,
                        horizontalPadding = 30.dp,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        enabled = enabled
                    )
                    PillButton(
                        text = "线路",
                        valueText = if (ui.lineCount <= 1) "主" else "${ui.lineIndex + 1}/${ui.lineCount}",
                        onClick = { nav.playerControls = PlayerControls.Lines },
                        height = 68.dp,
                        horizontalPadding = 30.dp,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        enabled = enabled
                    )
                    PillButton(
                        text = "换台",
                        onClick = { openOsd() },
                        height = 68.dp,
                        horizontalPadding = 30.dp,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        enabled = enabled
                    )
                    PillButton(
                        text = if (followed) "已关注" else "＋ 关注",
                        onClick = {
                            followed = Prefs.toggleFollow(room)
                            nav.followVersion++
                        },
                        solid = true,
                        height = 68.dp,
                        horizontalPadding = 30.dp,
                        fontSize = 24.sp,
                        enabled = enabled
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "按「换台」换台　·　返回键收起控制条，再按回首页",
                        style = LiveHubType.meta,
                        color = Tokens.Muted
                    )
                }
            }
        }

        // ---------- 角落小字 ----------
        // 「现在发生了什么」不属于控制层，所以它在 chromeAlpha 之外：控制条收起时照样显示。
        // **三条提示各占一行**，不再用 `?:` 折叠成一个槽位——压档、换线、弹幕是不同通道，
        // 折叠会让后来者完全遮住先来者。
        val notices = listOfNotNull(capNotice, cornerNotice, danmakuNotice)
        if (notices.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = Tokens.Gutter,
                        bottom = if (nav.playerControls == PlayerControls.Idle) 64.dp else 236.dp
                    ),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                notices.forEach { notice ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.46f))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(text = notice, style = LiveHubType.small, color = Color(0xFFE8E8ED))
                    }
                }
            }
        }
    }
}

/**
 * 遮罩只压两条带子。36%–55% 的画面一点不碰——那是主播的脸和比分。
 * 收起控制层时它一起淡出，画面回到 100%，而不是「按钮藏了、画面还蒙着一层」。
 */
@Composable
private fun Scrim(visible: Boolean) {
    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(280, easing = Ease.Focus),
        label = "scrim"
    )
    if (scrimAlpha <= 0.01f) return
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = scrimAlpha }
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        0.00f to Color.Black.copy(alpha = 0.90f),
                        0.12f to Color.Black.copy(alpha = 0.74f),
                        0.20f to Color.Black.copy(alpha = 0.44f),
                        0.36f to Color.Transparent,
                        0.55f to Color.Transparent,
                        0.74f to Color.Black.copy(alpha = 0.58f),
                        0.88f to Color.Black.copy(alpha = 0.82f),
                        1.00f to Color.Black.copy(alpha = 0.94f),
                        startY = 0f,
                        endY = size.height
                    )
                )
            }
    )
}

/** 换台条右侧的预览图；封面为空时留 --panel 纯色 + 平台名，不留白块。 */
@Composable
private fun OsdThumb(room: Room) {
    RoomCover(room = room, modifier = Modifier.fillMaxSize())
}

/**
 * 线路列表：用户主动打开的二级界面。**OK 提交，返回取消**。
 *
 * 这是播放页唯一能切换线路的入口。旧实现把「线路」胶囊直接做成
 * `switchLine(next)` 循环——按一下就走，用户没有看见/确认的机会。
 *
 * 焦点策略：容器自己是一个可聚焦的兜底节点，行各自有 requester；
 * 打开时先把焦点落到当前行，失败再落到容器。方向键由容器在捕获阶段
 * 处理（root → leaf），所以列表里不会因为几何搜索跑到背后的胶囊上。
 */
@Composable
private fun LineSheet(
    urls: List<PlayUrl>,
    current: Int,
    onChoose: (Int) -> Unit
) {
    // 线路数最多 8（抖音 4 FLV + 4 HLS）；固定 16 个槽位，避免 urls 变化时
    // remember 调用数量变化导致焦点槽错位。
    val focuses = List(16) { rememberTvFocus() }
    val sheetFocus = remember { FocusRequester() }
    var cursor by remember(urls) {
        mutableStateOf(current.coerceIn(0, (urls.size - 1).coerceAtLeast(0)))
    }

    LaunchedEffect(urls, current) {
        var tries = 0
        while (tries < 10) {
            withFrameNanos { }
            val requester = focuses.getOrNull(cursor)?.requester
            if (requester != null && runCatching { requester.requestFocus() }.isSuccess) {
                return@LaunchedEffect
            }
            tries++
        }
        runCatching { sheetFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .focusRequester(sheetFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        cursor = (cursor - 1).coerceAtLeast(0)
                        runCatching { focuses[cursor].requester.requestFocus() }
                        true
                    }
                    Key.DirectionDown -> {
                        cursor = (cursor + 1).coerceAtMost((urls.size - 1).coerceAtLeast(0))
                        runCatching { focuses[cursor].requester.requestFocus() }
                        true
                    }
                    // 左右没有二级动作：吃掉，避免焦点逃出列表。
                    Key.DirectionLeft, Key.DirectionRight -> true
                    Key.DirectionCenter, Key.Enter -> {
                        if (urls.isNotEmpty()) onChoose(cursor)
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(900.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Tokens.Panel)
                .padding(32.dp)
        ) {
            Text(text = "线路", style = LiveHubType.h2, color = Tokens.Text)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "当前线路 ${current + 1}/${urls.size}　·　OK 切换　·　返回取消",
                style = LiveHubType.small,
                color = Tokens.Muted
            )
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp)
            ) {
                urls.forEachIndexed { index, url ->
                    val format = when {
                        url.isHls -> "HLS"
                        url.format == StreamFormat.FLV -> "FLV"
                        else -> url.format.name
                    }
                    RailRow(
                        label = "线路 ${index + 1} · ${url.quality}",
                        trailing = if (index == current) "$format · 当前" else format,
                        current = index == current,
                        onClick = { onChoose(index) },
                        tv = focuses[index],
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
            if (urls.size <= 1) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "当前只有一条可用线路；断流时会自动重连同一条线路，不会自动换线。",
                    style = LiveHubType.small,
                    color = Tokens.Faint
                )
            }
        }
    }
}
