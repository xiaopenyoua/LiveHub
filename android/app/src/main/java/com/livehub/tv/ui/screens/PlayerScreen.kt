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
import com.livehub.tv.core.danmaku.Danmaku
import com.livehub.tv.core.model.PlayUrl
import com.livehub.tv.core.model.Platform
import com.livehub.tv.core.model.Room
import com.livehub.tv.core.model.SiteException
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
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    val ui by player.state.collectAsState()

    var loadError by remember { mutableStateOf<String?>(null) }
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

    /** 全线路 10s 都没出画面。这时候该停下来说话，而不是无限换线路。 */
    var stalled by remember(room.identity) { mutableStateOf(false) }

    val rootFocus = remember { FocusRequester() }
    val firstPill = rememberTvFocus()

    /**
     * 抖音弹幕握手要签名，v1 没有签名器，所以抖音没有弹幕可显示。
     * `LiveSite.capabilities.danmaku` 落地后这里改成读它；在那之前按「抖音没有弹幕」处理：
     * 不渲染弹幕层，控制条也不出现那个胶囊——宁可少一个按钮，也不给一个按了没反应的。
     */
    val danmakuAvailable = Sites.of(room.platform).capabilities.danmaku

    // 取流：房间一变就换线路重拉
    LaunchedEffect(room.identity, reloadTick) {
        loadError = null
        stalled = false
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
            loadError = e.kind.userText()
            return@LaunchedEffect
        } catch (e: Throwable) {
            emptyList()
        }
        if (urls.isEmpty()) {
            loadError = "没有取到播放地址"
        } else {
            player.play(urls, 0)
            // 要 400 却拿回 250：这是未登录被压档，必须显式说，不能静默
            val achieved = urls.first().quality
            val a = qualityRank(achieved)
            val w = qualityRank(wanted)
            capNotice = if (w > 0 && a in 0 until w) {
                if (a == 250) "未登录，最高 1080P" else "平台只给到 ${qualityLabel(achieved)}"
            } else {
                null
            }
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

    /**
     * 首帧时间线：3s 提示 / 4s 切线路 / 10s 放弃。
     *
     * 三个值对应三件不同的事，不能合成一个阈值：
     *  - 3s 只是安抚（实测虎牙 FLV 弱网首帧要 3.2–3.8s，3s 就切会把能播的线路判死）；
     *  - 4s 才真切，所以提示的文案是「正在换线路」，不是「已切」——提示和事实差 1 秒；
     *  - 10s 全线路都没出画面 → 停下，交给用户，别把「无限换线路」当特性。
     * 整条时间线在一个协程里跑，**不按 lineIndex 重启**，否则切一次线计时就归零，
     * 永远走不到 10s。
     */
    LaunchedEffect(room.identity, reloadTick) {
        cornerNotice = null
        val started = SystemClock.elapsedRealtime()
        var switched = false
        while (true) {
            withFrameNanos { }
            val st = player.state.value
            val playing = st.playing && !st.buffering && st.error == null
            if (playing || st.error != null || loadError != null) {
                cornerNotice = null
                return@LaunchedEffect
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            if (!switched && elapsed >= 3_000 && cornerNotice == null) {
                cornerNotice = "正在换线路…"
            }
            if (!switched && elapsed >= 4_000) {
                switched = true
                // 设置里关掉「线路自动降级」就只提示、不切：用户说了不自动换，就真的不换。
                // 提示那条保留——不然画面一直不出来，用户不知道为什么。
                if (Prefs.autoDowngrade && st.lineCount > 1) {
                    player.switchLine((st.lineIndex + 1) % st.lineCount)
                    cornerNotice = "已切到备用线路 · ${qualityLabel(st.quality)}"
                } else {
                    cornerNotice = null
                }
            }
            if (elapsed >= 10_000) {
                stalled = true
                cornerNotice = null
                return@LaunchedEffect
            }
        }
    }

    val danmakuFlow = remember(room.identity) { Danmaku.of(room.platform).stream(room.roomId) }

    // 焦点：控制条显示时落在第一枚胶囊上，收起时落回根节点（否则方向键没有接收者）。
    // 控制条是随 chromeAlpha 渐入才挂载的，所以在节点真正存在之前 requestFocus 会抛异常——
    // 逐帧重试几次，而不是「等一帧就假定它在」。
    LaunchedEffect(nav.playerControls) {
        if (nav.playerControls == PlayerControls.Controls) {
            var tries = 0
            while (tries < 10) {
                withFrameNanos { }
                if (runCatching { firstPill.requester.requestFocus() }.isSuccess) break
                tries++
            }
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
                            append(qualityLabel(ui.quality))
                            // positionMs 是「本场起播后的偏移」，不是主播已播多久——
                            // 直播流没有绝对起点，写「已播」会变成一个对不上的数字
                            if (ui.positionMs > 0) append("　·　本场观看 ${clockText(ui.positionMs)}")
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
                                .background(Tokens.Live)
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = "直播中",
                            style = LiveHubType.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = Tokens.Live
                        )
                    }
                }
                Spacer(Modifier.height(26.dp))

                val err = loadError ?: ui.error
                    ?: if (stalled) "所有线路在 10 秒内都没有出画面" else null
                if (err != null) {
                    Text(
                        text = if (loadError != null)
                            "$loadError　·　按 ↑↓ 换台，或按返回键回首页"
                        else if (ui.error != null)
                            "播放出错：$err　·　按 ↑↓ 换台"
                        else
                            "$err　·　按 ↑↓ 换台，或按 ←→（控制条上）换线路",
                        style = LiveHubType.small,
                        color = Color(0xFFC7C7CC),
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                } else if (ui.buffering) {
                    // 首帧的三段提示在角落（它属于「现在发生了什么」，不属于控制层）；
                    // 这里只说事实：还在缓冲。
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
                        valueText = qualityLabel(ui.quality),
                        onClick = { reloadTick++ },
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

        // ---------- 角落一行小字 ----------
        // 「现在发生了什么」不属于控制层，所以它在 chromeAlpha 之外：控制条收起时照样显示。
        // 位置随控制条上下让开，避免压在按钮上。
        // 优先级按「影响的是不是画面」排：清晰度被压 > 首帧/换线路 > 弹幕通道。
        val notice = capNotice ?: cornerNotice ?: danmakuNotice
        if (notice != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = Tokens.Gutter,
                        bottom = if (nav.playerControls == PlayerControls.Idle) 64.dp else 236.dp
                    )
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.46f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(text = notice, style = LiveHubType.small, color = Color(0xFFE8E8ED))
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
