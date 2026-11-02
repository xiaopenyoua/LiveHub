package com.livehub.tv.ui.screens

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.focusable
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
import com.livehub.tv.ui.components.RoomCover
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
private const val MAX_EOF_RETRIES = 4

/**
 * 播放页：画面永远是屏幕里最亮的东西。
 *
 * 画面不挂亮度滤镜——只在顶栏、底栏压着的两条带子上加深渐变，25%–60% 的画面一点不碰。
 * 控制层收起时遮罩一起淡出，把整块屏幕还给内容，而不是「只把按钮藏起来、画面还蒙着一层」。
 *
 * 键位（与设计稿一致）：
 *  - 控制条收起：↑↓ 换台（打开换台条）、←→ 换平台、OK 显示控制条
 *  - 控制条显示：←→ 在按钮之间移动、↑↓ 换台、OK 执行、BACK 收起控制条
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

    /** 已经结束过的线路。用来避免「1→2→3→1」无限换线。 */
    var endedLines by remember(room.identity) { mutableStateOf(emptySet<Int>()) }

    /** 本会话里已经证明坏掉的线路（例如 HLS 被劫持/解析失败），自动选线时跳过。 */
    var badLines by remember(room.identity) { mutableStateOf(emptySet<Int>()) }

    /** 所有线路都结束了（或用户关了自动降级）：停在终态，不再假装在播。 */
    var endedTerminal by remember(room.identity) { mutableStateOf(false) }

    /** 播起来之后「停住」触发的自动换线次数，防止反复切。 */
    var stallSwitches by remember(room.identity) { mutableStateOf(0) }

    /** 同一条线路被对端结束后的重连次数；连续短命才换线，避免一条短线就丢掉能播的 CDN。 */
    var eofRetries by remember(room.identity) { mutableStateOf(0) }

    /** 虎牙 FLV 的抗盗链串可用几小时，EOF 后先用同一 URL 快重连，省掉一次取流往返。 */
    var fastReconnects by remember(room.identity) { mutableStateOf(0) }

    /** 播放层错误触发的自动换线次数。 */
    var errorSwitches by remember(room.identity) { mutableStateOf(0) }

    /** 只有一条线路的平台（斗鱼）在地址过期时自动重新取流；这是它的「换线」等价物。 */
    var singleLineRetries by remember(room.identity) { mutableStateOf(0) }

    /**
     * 多线路平台（哔哩）的 CDN 地址过期重取次数。
     *
     * 同一次 `playUrls` 返回的所有线路共享同一个 `expires`，所以 60 分钟后
     * **换哪条线都还是 403**；正确动作是先重新取地址，再回到同一条线。
     * 这和单线路平台的 `singleLineRetries` 是两件事：这里线路数 > 1，
     * 不能靠「换下一条」绕过 token 过期。
     */
    var expiryReloads by remember(room.identity) { mutableStateOf(0) }

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

    /** 选下一条自动线路：跳过当前、已结束、本会话已证明坏掉的。 */
    fun nextAutoLine(current: Int, count: Int, banned: Set<Int>): Int? =
        (0 until count).firstOrNull {
            it != current && it !in endedLines && it !in banned
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
                "画面停住了（超过 8 秒没有新帧），按「重试」或换线路"
            } else {
                "所有线路在 12 秒内都没有出画面，按「重试」或换线路"
            }
            endedTerminal -> "所有线路都被平台中断了，主播可能已下播"
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
        ui.ended || endedTerminal -> "已结束"
        // 房间不存在/已下播/ID 解析失败是「未在播」，不是「播着播着断了」
        loadErrorKind?.retryable == false -> "未在播"
        err != null -> "已中断"
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
        fastReconnects = 0
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
     * EOF 处理（DEF-10）：对端干净地结束直播流时，ExoPlayer 不报 error。
     * 先逐条换线路；全部结束才停在终态，并且**明确说出来**。
     * `endedLines` 保证不会 1→2→3→1 无限闪「正在换线路…」。
     */
    LaunchedEffect(ui.ended, room.identity) {
        if (!ui.ended || endedTerminal || loadError != null) return@LaunchedEffect
        // 虎牙 FLV 的抗盗链串寿命以小时计，EOF 只是这条连接被对端关掉。
        // 先用**同一个 URL** 快重连（省掉一次 playUrls 往返，实测首帧 ~150ms），
        // 连续 3 次仍短命才重新取地址。
        if (room.platform == Platform.HUYA && ui.urls.isNotEmpty() && fastReconnects < 3) {
            fastReconnects++
            cornerNotice = "直播流中断 · 正在重连…"
            player.play(ui.urls, ui.lineIndex, resetWatch = false)
            return@LaunchedEffect
        }
        // 其它平台 / 快重连用完：重新取地址（新的 wsTime/seqid/token），再回到同一条线。
        if (eofRetries < MAX_EOF_RETRIES) {
            eofRetries++
            resumeLine = ui.lineIndex
            reconnectReload = true
            cornerNotice = "直播流中断 · 正在重连…"
            delay(400)
            reloadTick++
            return@LaunchedEffect
        }
        val current = ui.lineIndex
        val next = nextAutoLine(current, ui.lineCount, badLines)
        if (Prefs.autoDowngrade && next != null) {
            endedLines = endedLines + current
            eofRetries = 0
            fastReconnects = 0
            Prefs.noteDowngrade()
            cornerNotice = "这条线路结束了 · 正在换线路…"
            player.switchLine(next)
        } else {
            endedTerminal = true
            cornerNotice = null
        }
    }

    /** 稳定播满 15s 就把「短命重连」计数清零：之后的偶发 EOF 仍然先重连同一条线。 */
    LaunchedEffect(ui.firstFrameRendered) {
        if (!ui.firstFrameRendered) return@LaunchedEffect
        Prefs.noteFirstFrame(ui.stats.firstFrameMs)
        delay(15_000)
        val st = player.state.value
        if (st.firstFrameRendered && !st.ended && st.error == null) {
            eofRetries = 0
            fastReconnects = 0
            stallSwitches = 0
            errorSwitches = 0
            singleLineRetries = 0
            expiryReloads = 0
        }
    }

    /**
     * 播放层错误（403/解码失败等）也自动换线——不能只让用户按。
     * 每条线路最多轮到一次；全部失败后保留错误和重试按钮，不无限切。
     */
    LaunchedEffect(ui.error, room.identity) {
        val error = ui.error
        // **error 变 null 不能清零 errorSwitches**：自动换线会清掉 error，
        // 清零后下一条线再错就又能无限换。只在稳定播放 15s / 换房 / 手动重试时清零。
        if (error == null) return@LaunchedEffect
        if (!Prefs.autoDowngrade) return@LaunchedEffect
        // 多线路平台也会过期：同一次 playUrls 的所有线路共享同一个 expires，
        // 换线解决不了 403。先重新取地址（最多 2 次），仍失败才逐条换线。
        val expired = error.contains("CDN 拒绝") || error.contains("过期")
        if (ui.lineCount > 1 && expired && expiryReloads < 2) {
            expiryReloads++
            resumeLine = ui.lineIndex
            reconnectReload = true
            cornerNotice = "地址过期了 · 正在重新取流…"
            delay(800)
            reloadTick++
            return@LaunchedEffect
        }
        if (ui.lineCount <= 1) {
            // 斗鱼只有一条预览线路，地址带短 TTL。过期时没有「下一条线」可换，
            // 正确动作是**重新取地址**（新的 txSecret），而不是把错误甩给用户。
            if (singleLineRetries >= MAX_EOF_RETRIES) return@LaunchedEffect
            singleLineRetries++
            resumeLine = ui.lineIndex
            reconnectReload = true
            cornerNotice = "地址过期了 · 正在重新取流…"
            delay(1_200)
            reloadTick++
            return@LaunchedEffect
        }
        if (errorSwitches >= ui.lineCount - 1) return@LaunchedEffect
        delay(1_500)
        // HLS manifest 解析失败通常意味着这条线路在本网络不可用（劫持/坏边缘）；
        // 记进 badLines，后续自动选线不再反复撞它。换房间/手动重试会清空。
        val manifest = error.contains("MANIFEST") || error.contains("流格式异常")
        val banned = if (manifest) badLines + ui.lineIndex else badLines
        if (manifest) badLines = banned
        val next = nextAutoLine(ui.lineIndex, ui.lineCount, banned) ?: return@LaunchedEffect
        errorSwitches++
        Prefs.noteDowngrade()
        cornerNotice = "这条线路播不了 · 正在换线路…"
        player.switchLine(next)
    }

    /**
     * 播起来又断（不是 ERROR、也不一定立刻 ENDED）：看门狗在 LivePlayer 里置 `ui.stalled`。
     * 自动换线最多 `lineCount - 1` 次；换完还停住就留在失败态，交给重试按钮。
     */
    LaunchedEffect(ui.stalled, room.identity) {
        if (!ui.stalled || !Prefs.autoDowngrade) return@LaunchedEffect
        if (ui.lineCount <= 1) {
            // 单线路平台（斗鱼）停住时没有「下一条线」可换：重新取地址是唯一正确的动作。
            if (singleLineRetries >= MAX_EOF_RETRIES) return@LaunchedEffect
            singleLineRetries++
            resumeLine = ui.lineIndex
            reconnectReload = true
            cornerNotice = "画面停住了 · 正在重新取流…"
            delay(800)
            reloadTick++
            return@LaunchedEffect
        }
        if (stallSwitches >= ui.lineCount - 1) return@LaunchedEffect
        val next = nextAutoLine(ui.lineIndex, ui.lineCount, badLines) ?: return@LaunchedEffect
        stallSwitches++
        Prefs.noteDowngrade()
        cornerNotice = "画面停住了 · 正在换线路…"
        player.switchLine(next)
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

    /**
     * 起播时间线：3s 提示 / 4s 起逐条换线路 / 12s 放弃。
     *
     * 判据是 **`firstFrameRendered`（真的渲染出首帧）**，不是 `playing`（播放器自认为在播）。
     * 旧实现用 `playing`，只要 ExoPlayer 进了 `STATE_READY`，三层兜底一次都不执行——
     * 于是「有地址、没画面」的线路会被当成成功（DEF-10 的同族问题）。
     *
     * 条数不是只切一次：每条线路都给它一次机会，最多 `lineCount - 1` 次；
     * 每 3.5s 一次，12s 封顶。
     *
     * 循环用 `delay(200)` 而不是 `withFrameNanos`：故障态的帧时钟本身可能就是停的，
     * 拿帧当计时器等于让看门狗和故障一起停摆。
     */
    LaunchedEffect(room.identity, reloadTick) {
        cornerNotice = null
        val started = SystemClock.elapsedRealtime()
        var switches = 0
        var lastSwitchAt = 0L
        while (true) {
            delay(200)
            val st = player.state.value
            if (st.firstFrameRendered || st.error != null || st.ended || loadError != null) {
                cornerNotice = null
                return@LaunchedEffect
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            if (switches == 0 && elapsed >= 3_000 && cornerNotice == null) {
                cornerNotice = "正在换线路…"
            }
            if (Prefs.autoDowngrade && st.lineCount > 1 &&
                switches < st.lineCount - 1 && elapsed >= 4_000 &&
                elapsed - lastSwitchAt >= 3_500
            ) {
                val next = nextAutoLine(st.lineIndex, st.lineCount, badLines)
                if (next == null) {
                    cornerNotice = null
                    return@LaunchedEffect
                }
                switches++
                lastSwitchAt = elapsed
                Prefs.noteDowngrade()
                player.switchLine(next)
                cornerNotice = "已切到备用线路 · ${displayQuality(st.quality)}（${switches + 1}/${st.lineCount}）"
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
        if (nav.playerControls == PlayerControls.Controls) {
            val requester = if (hasError) retryFocus.requester else firstPill.requester
            var tries = 0
            while (tries < 10) {
                withFrameNanos { }
                if (runCatching { requester.requestFocus() }.isSuccess) break
                tries++
            }
            // 胶囊没挂上（例如控制条还在渐入）就退回根节点，不能把焦点留在背后的屏幕
            if (tries >= 10) runCatching { rootFocus.requestFocus() }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    fun surf(delta: Int) {
        val pool = nav.playerPool
        if (pool.isEmpty()) return
        val samePlatform = pool.filter { it.platform == room.platform }
        val list = if (samePlatform.size >= 2) samePlatform else pool
        val index = list.indexOfFirst { it.identity == room.identity }
        val size = list.size
        val next = if (index < 0) list.first()
        else list[((index + delta) % size + size) % size]
        if (next.identity != room.identity) nav.playerRoom = next
    }

    fun surfPlatform(direction: Int) {
        val pool = nav.playerPool
        if (pool.isEmpty()) return
        val order = Platform.entries
        val current = order.indexOf(room.platform)
        for (step in 1..order.size) {
            val target = order[((current + direction * step) % order.size + order.size) % order.size]
            if (target == room.platform) continue
            val candidate = pool.firstOrNull { it.platform == target }
            if (candidate != null) {
                nav.playerRoom = candidate
                return
            }
        }
    }

    /**
     * 清晰度：哔哩是唯一能按「请求档位」重新取流的平台，所以走 `Prefs.preferredQuality + 重拉`；
     * 其它平台按当前取回的地址列表**真实切换**（抖音会给多档），只有一档时明确说只有一档——
     * 不给一个按了没反应的按钮。
     *
     * 为什么不弹菜单：一条 1920×1080 的控制条上再叠一层列表，焦点层级和返回键都要重做；
     * 逐档循环同样能到达每一档，且不增加新的焦点陷阱。明确写在这里，免得下一个人以为漏了。
     */
    fun cycleQuality() {
        if (room.platform == Platform.BILIBILI) {
            val order = listOf("", "400", "250", "150")
            val current = Prefs.preferredQuality
            val index = order.indexOf(current).let { if (it < 0) 0 else it }
            val next = order[(index + 1) % order.size]
            Prefs.preferredQuality = next
            endedTerminal = false
            eofRetries = 0
            errorSwitches = 0
            singleLineRetries = 0
            expiryReloads = 0
            badLines = emptySet()
            endedLines = emptySet()
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
                val controls = nav.playerControls
                when (event.key) {
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (controls != PlayerControls.Osd) nav.playerControls = PlayerControls.Osd
                        surf(if (event.key == Key.DirectionUp) -1 else 1)
                        true
                    }

                    Key.DirectionLeft, Key.DirectionRight -> {
                        // 控制条上左右是「在按钮之间移动」，其余状态是「换平台」
                        if (controls == PlayerControls.Controls) {
                            false
                        } else {
                            if (controls != PlayerControls.Osd) nav.playerControls = PlayerControls.Osd
                            surfPlatform(if (event.key == Key.DirectionLeft) -1 else 1)
                            true
                        }
                    }

                    Key.DirectionCenter, Key.Enter -> when (controls) {
                        PlayerControls.Idle -> {
                            nav.playerControls = PlayerControls.Controls
                            true
                        }

                        PlayerControls.Osd -> {
                            nav.playerControls = PlayerControls.Controls
                            true
                        }

                        // 交给当前聚焦的按钮
                        PlayerControls.Controls -> false
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
                    text = if (loadErrorKind?.retryable == false) "按返回键回首页" else "按 OK 重试",
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xF71C1C1F))
                        .padding(horizontal = 44.dp, vertical = 34.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = room.hostLabel,
                        style = LiveHubType.hero.copy(lineHeight = 80.sp),
                        color = Tokens.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(360.dp)
                    )
                    Spacer(Modifier.width(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "${room.platform.shortName} · ${room.ridLabel} ${room.roomId}",
                            style = LiveHubType.meta,
                            color = Tokens.Muted
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = room.title.ifBlank { room.hostLabel },
                            style = LiveHubType.title.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
                            color = Tokens.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "${viewerText(room.viewers)} 人在看",
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
                        OsdThumb(room = room)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.padding(start = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(30.dp)
                ) {
                    Text(text = keyHint("↑↓" to true, " 换台" to false), style = LiveHubType.meta, color = Tokens.Muted)
                    Text(text = keyHint("←→" to true, " 换平台" to false), style = LiveHubType.meta, color = Tokens.Muted)
                    Text(text = keyHint("OK" to true, " 确定" to false), style = LiveHubType.meta, color = Tokens.Muted)
                    Text(text = keyHint("返回" to true, " 关闭" to false), style = LiveHubType.meta, color = Tokens.Muted)
                }
            }
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
                    val enabled = nav.playerControls != PlayerControls.Idle
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
                                endedTerminal = false
                                eofRetries = 0
                                errorSwitches = 0
                                singleLineRetries = 0
                                expiryReloads = 0
                                badLines = emptySet()
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
                        onClick = {
                            if (ui.lineCount > 1) player.switchLine((ui.lineIndex + 1) % ui.lineCount)
                        },
                        height = 68.dp,
                        horizontalPadding = 30.dp,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        enabled = enabled
                    )
                    PillButton(
                        text = "换台",
                        onClick = { nav.playerControls = PlayerControls.Osd },
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
                        text = "按 ↑ 换台　·　返回键收起控制条，再按回首页",
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
