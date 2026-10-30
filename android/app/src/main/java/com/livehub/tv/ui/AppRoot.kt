package com.livehub.tv.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.livehub.tv.core.model.Platform
import com.livehub.tv.core.model.Room
import com.livehub.tv.ui.components.Ease
import com.livehub.tv.ui.components.GlassBox
import com.livehub.tv.ui.components.LocalStageBackdrop
import com.livehub.tv.ui.components.PillButton
import com.livehub.tv.ui.components.PillShape
import com.livehub.tv.ui.components.StageBackdrop
import com.livehub.tv.ui.components.Tier
import com.livehub.tv.ui.components.focusScale
import com.livehub.tv.ui.components.rememberTvFocus
import com.livehub.tv.ui.components.stageGlow
import com.livehub.tv.ui.components.tryRequestFocus
import com.livehub.tv.ui.components.tvClickable
import com.livehub.tv.ui.screens.BrowseScreen
import com.livehub.tv.ui.screens.FollowScreen
import com.livehub.tv.ui.screens.HomeScreen
import com.livehub.tv.ui.screens.PlayerScreen
import com.livehub.tv.ui.screens.SearchScreen
import com.livehub.tv.ui.screens.SettingsScreen
import com.livehub.tv.ui.theme.LiveHubType
import com.livehub.tv.ui.theme.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 五个主屏。播放页不是「屏」，它是压在所有屏之上的浮层——退出播放页要回到你原来那一屏。 */
enum class Screen(val label: String) {
    Home("首页"),
    Follow("关注"),
    Browse("分类"),
    Search("搜索"),
    Settings("设置")
}

/** 播放页控制层的三态。idle 时遮罩一起淡出，把整块屏幕还给内容。 */
enum class PlayerControls { Idle, Controls, Osd }

/** 最近搜索存的是「你打过的那串字」，不是四条直播间。只在本机内存里，不落盘。 */
data class RecentQuery(val text: String, val at: Long = System.currentTimeMillis())

/**
 * 应用级状态。
 *
 * 刻意不用 ViewModel：这一层只装「切屏、播放页、焦点记忆、搜索输入」这些
 * 一次会话内的东西，跨进程不需要活下来；用 AndroidX 的 ViewModel 反而要给
 * 五个屏各配一个 store。
 */
@Stable
class AppNav {
    var screen by mutableStateOf(Screen.Home)
    var playerRoom by mutableStateOf<Room?>(null)
    var playerPool by mutableStateOf<List<Room>>(emptyList())
    var playerControls by mutableStateOf(PlayerControls.Controls)
    var showExit by mutableStateOf(false)

    /** 关注/取关后递增，关注页据此重读本地列表 */
    var followVersion by mutableStateOf(0)

    /** 搜索输入留在导航层，切走再回来不丢 */
    var searchQuery by mutableStateOf("")

    var browsePlatform by mutableStateOf<Platform?>(null)
    var browseCategory by mutableStateOf("全部")
    var settingsGroup by mutableStateOf(0)

    val recent = mutableStateListOf<RecentQuery>()

    /** 每个屏记住「离开时焦点在哪一项」，回来直接落回去 */
    val focusMemo = mutableStateMapOf<String, String>()

    /**
     * 顶栏五个页签的 requester，由 [NavBar] 挂到对应节点上。
     *
     * 放在这里而不是 NavBar 内部，是为了让**每个屏都能把自己的 UP 明确指向当前页签**。
     * 这件事不能靠几何：1080p 上顶栏 116dp、网格 4 列、卡片聚焦放大 1.08，Compose 的
     * 按中心点就近在「顶栏右侧的『设置』」和「第 4 张卡」之间算错过一次，代价是遥控器
     * 到不了三个页签。落点写成结构关系，就不存在算错的可能。
     */
    val tabRequests: List<FocusRequester> = List(Screen.entries.size) { FocusRequester() }

    /**
     * 焦点是否停在顶栏。
     *
     * 用来拦住「切屏之后新屏立刻抢焦点」：如果不管，按 OK 切到「分类」的同一瞬间，
     * 新屏的入口会 requestFocus 到第一张卡上，**同一次按键的抬起会被那张卡吃掉**，
     * 于是用户只是切了个页，却直接进了某个直播间。
     */
    var inNav by mutableStateOf(false)

    fun open(room: Room, pool: List<Room>) {
        playerPool = if (pool.isEmpty()) listOf(room) else pool
        playerRoom = room
        playerControls = PlayerControls.Controls
    }

    fun noteQuery(query: String) {
        val text = query.trim()
        if (text.isEmpty()) return
        val existing = recent.indexOfFirst { it.text.equals(text, ignoreCase = true) }
        if (existing >= 0) recent.removeAt(existing)
        recent.add(0, RecentQuery(text))
        while (recent.size > 6) recent.removeAt(recent.size - 1)
    }
}

/**
 * 应用根：顶栏 + 屏幕状态机 + 返回键逐级返回 + 退出二次确认。
 *
 * 返回键规则（电视上最容易做出挫败感的地方）：任何次级页面按返回先回首页；
 * 首页再按返回弹「退出 LiveHub？」。**永远不直接退出 App**。
 */
@Composable
fun AppRoot() {
    val nav = remember { AppNav() }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    BackHandler(enabled = true) {
        when {
            nav.showExit -> nav.showExit = false
            nav.playerRoom != null -> {
                // 播放页：先收起控制条，再按才离开
                if (nav.playerControls != PlayerControls.Idle) {
                    nav.playerControls = PlayerControls.Idle
                } else {
                    nav.playerRoom = null
                }
            }
            nav.screen != Screen.Home -> nav.screen = Screen.Home
            else -> nav.showExit = true
        }
    }

    val stage = remember { StageBackdrop() }

    CompositionLocalProvider(LocalStageBackdrop provides stage) {
        Stage(backdrop = stage) {
            Column(Modifier.fillMaxSize()) {
                NavBar(nav = nav, onSelect = { nav.screen = it })
                ScreenHost(nav = nav, modifier = Modifier.weight(1f))
            }

            val room = nav.playerRoom
            if (room != null) {
                PlayerScreen(nav = nav, modifier = Modifier.fillMaxSize().zIndex(2f))
            }

            if (nav.showExit) {
                ExitDialog(
                    onKeepWatching = { nav.showExit = false },
                    onExit = {
                        nav.showExit = false
                        activity?.finish()
                    },
                    modifier = Modifier.zIndex(3f)
                )
            }
        }
    }
}

/**
 * 屏幕容器：切屏是**双向同一条 transition**。
 *
 * 设计稿的 `.screen` 只有一条规则：`opacity 240ms` + `transform 240ms`，`scale(.985)` ↔ `scale(1)`，
 * `.is-active` 才 `opacity:1`。所以「进来的」和「出去的」走的是同一个函数，只是方向相反——
 * 如果只做进入动画（老版本就是这样），退出的那一屏是硬切消失的，切换看起来像「弹了一下」。
 */
@Composable
private fun ScreenHost(nav: AppNav, modifier: Modifier = Modifier) {
    // A 档不做缩放：两块 1920×1080 的图层同时合成，在 1GB 盒子上就是一次掉帧。
    // 只留明度（淡入淡出），节奏仍然分得出来。
    val from = if (Tier.scaleEnabled) 0.985f else 1f
    AnimatedContent(
        targetState = nav.screen,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            (
                fadeIn(tween(Ease.DUR_SCREEN, easing = Ease.Focus)) +
                    scaleIn(tween(Ease.DUR_SCREEN, easing = Ease.Focus), initialScale = from)
                ).togetherWith(
                fadeOut(tween(Ease.DUR_SCREEN, easing = Ease.Focus)) +
                    scaleOut(tween(Ease.DUR_SCREEN, easing = Ease.Focus), targetScale = from)
                )
        },
        label = "screen"
    ) { screen ->
        when (screen) {
            Screen.Home -> HomeScreen(nav)
            Screen.Follow -> FollowScreen(nav)
            Screen.Browse -> BrowseScreen(nav)
            Screen.Search -> SearchScreen(nav)
            Screen.Settings -> SettingsScreen(nav)
        }
    }
}

/**
 * 舞台的三层背景光本体已经搬到 `components/Glass.kt` 的 `stageGlow()`：
 * 玻璃面要把「背后那层光」在自己形状内重画一遍才有「透上来」的效果
 * （minSdk 21 没有 `RenderEffect`，不能真模糊），两处必须共用同一份实现。
 */
@Composable
private fun Stage(backdrop: StageBackdrop, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Tokens.Ground)
            .onGloballyPositioned {
                val sz = it.size
                if (backdrop.size.width != sz.width.toFloat() ||
                    backdrop.size.height != sz.height.toFloat()
                ) {
                    backdrop.size = Size(sz.width.toFloat(), sz.height.toFloat())
                }
            }
            .drawBehind { stageGlow(size.width, size.height) }
    ) {
        content()
    }
}

/**
 * 顶栏。
 *
 * 五个页签**不是各画一块底**——那会让屏幕同时亮起五个「选中项」，正是 tvOS 最忌讳的。
 * 这里是**一层玻璃胶囊跟着焦点滑**：焦点进顶栏，胶囊淡入并滑到该页签下；焦点离开，淡出。
 * 位置用 positionInParent 而不是动画中的中间值，胶囊才不会追着跑偏。
 *
 * 方向键在页签之间是**在捕获阶段自己算的**：左右只在相邻的两个页签之间走，
 * 到头就吃掉按键（明确「不动」），UP 也吃掉——顶栏之上没有东西。
 * 不交给几何搜索（它按中心点就近，从「首页」往右会选中下方网格里的卡片，
 * 于是整个顶栏除首页外都到不了），也不交给 `focusProperties` 的定制 requester
 * （目标悬空时它静默吞键，见 `Modifier.dpadTo` 的注释）。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NavBar(nav: AppNav, onSelect: (Screen) -> Unit) {
    val tabs = Screen.entries
    val bounds = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }
    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    // requester 由 AppNav 持有（各屏的 UP 要指向它），TvFocus 只是借用同一个 requester
    val tabTv = List(tabs.size) { rememberTvFocus(nav.tabRequests[it]) }

    val targetX = focusedIndex?.let { bounds[it] }?.let { it.first - it.second * 0.03f - 5f } ?: 0f
    val targetW = focusedIndex?.let { bounds[it] }?.let { it.second * 1.06f + 10f } ?: 0f
    val pillX by animateFloatAsState(targetX, tween(Ease.DUR_PILL, easing = Ease.Focus), label = "tabX")
    val pillW by animateFloatAsState(targetW, tween(Ease.DUR_PILL, easing = Ease.Focus), label = "tabW")
    val pillAlpha by animateFloatAsState(
        targetValue = if (focusedIndex != null) 1f else 0f,
        animationSpec = tween(180, easing = Ease.Focus),
        label = "tabAlpha"
    )

    Row(
        modifier = Modifier
            .height(Tokens.NavHeight)
            .padding(horizontal = Tokens.Gutter),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Live",
                style = LiveHubType.tab.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
                color = Tokens.Text
            )
            Text(
                text = "Hub",
                style = LiveHubType.tab.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
                color = Tokens.Live
            )
        }

        Spacer(Modifier.width(44.dp))

        Box {
            if (pillAlpha > 0.01f) {
                // 导航药丸是全场最亮的一块（--a .34），视线锚点就靠它。
                // 它和卡片、按钮共用同一段材质公式，只是档位不同——没有第二套材质。
                GlassBox(
                    modifier = Modifier
                        .offset { IntOffset(pillX.roundToInt(), 0) }
                        .width(with(density) { pillW.toDp() })
                        .height(56.dp)
                        .graphicsLayer { alpha = pillAlpha },
                    radius = 28.dp,
                    bodyAlpha = Tokens.A_NAV_PILL
                ) {}
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, screen ->
                    val tv = tabTv[index]
                    val scale = focusScale(tv, Tier.BUTTON_SCALE)
                    Box(
                        Modifier
                            // 页签的方向键**挂在页签节点自己身上**，而且候选只从 `tabTv` 里取——
                            // 「页签的 right 指到内容区」这种错误在这里结构上不可能发生：
                            // 这段代码手里根本没有内容区的 requester。左右只可能是 index ± 1，
                            // 越界就吃掉按键（到头的两边明确「不动」），UP 也吃掉（顶栏之上没有东西）。
                            //
                            // 为什么不用 `focusProperties { left/right = ... }`：目标 requester 一旦没
                            // attach，`moveFocus` 返回 false、按键被静默吞掉，焦点原地不动、不抛异常、
                            // 不打日志。遥控器上就是「右键坏了」，截图里完全看不出来。
                            // 这里 requestFocus 失败就当没处理过，按键交回系统做几何搜索——
                            // 最坏情况是退化成默认行为，不会出现「按了没反应」。
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionLeft ->
                                        tabTv.getOrNull(index - 1)?.requester?.tryRequestFocus() ?: true
                                    Key.DirectionRight ->
                                        tabTv.getOrNull(index + 1)?.requester?.tryRequestFocus() ?: true
                                    Key.DirectionUp -> true
                                    else -> false
                                }
                            }
                            .onGloballyPositioned {
                                bounds[index] =
                                    it.positionInParent().x.roundToInt() to it.size.width
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .tvClickable(
                                tv = tv,
                                onFocusChange = { focused ->
                                    if (focused) {
                                        focusedIndex = index
                                        nav.inNav = true
                                    } else if (focusedIndex == index) {
                                        focusedIndex = null
                                        nav.inNav = false
                                    }
                                },
                                onClick = { onSelect(screen) }
                            )
                            .height(56.dp)
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = screen.label,
                            style = LiveHubType.tab.copy(
                                // 焦点这一项提到 SemiBold：胶囊之外再给一层「不是其它四个」
                                fontWeight = if (nav.screen == screen || tv.focused) FontWeight.SemiBold else FontWeight.Medium
                            ),
                            color = if (nav.screen == screen || tv.focused) Tokens.Text else Tokens.Muted
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        NavStatus()
    }
}

/** 右侧状态：网络类型 + 时间。取不到网络信息就写「无网络」，不编一个绿色圆点。 */
@Composable
private fun NavStatus() {
    val context = LocalContext.current
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.CHINA) }
    var clock by remember { mutableStateOf(formatter.format(Date())) }
    var network by remember { mutableStateOf(networkLabel(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            clock = formatter.format(Date())
            network = networkLabel(context)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(PillShape)
                .background(if (network == "无网络") Tokens.Faint else Color(0xFF30D158))
        )
        Spacer(Modifier.width(16.dp))
        Text(text = network, style = LiveHubType.meta, color = Tokens.Faint)
        Spacer(Modifier.width(16.dp))
        Text(text = clock, style = LiveHubType.meta, color = Tokens.Faint)
    }
}

/**
 * 退出确认。电视上最常见的挫败是「一按返回 App 就没了」，
 * 所以最高级页面按返回必须先问一次。
 */
@Composable
private fun ExitDialog(
    onKeepWatching: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val keepFocus = rememberTvFocus()
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { keepFocus.requester.requestFocus() }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(760.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Tokens.Raised)
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "退出 LiveHub？",
                style = LiveHubType.h2.copy(fontWeight = FontWeight.SemiBold),
                color = Tokens.Text
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "关注列表已经保存，下次打开还在。",
                style = LiveHubType.body,
                color = Tokens.Muted
            )
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton(
                    text = "继续看",
                    onClick = onKeepWatching,
                    solid = true,
                    tv = keepFocus,
                    height = 72.dp,
                    horizontalPadding = 40.dp,
                    fontSize = 26.sp
                )
                PillButton(
                    text = "退出",
                    onClick = onExit,
                    solid = false,
                    height = 72.dp,
                    horizontalPadding = 40.dp,
                    fontSize = 26.sp
                )
            }
        }
    }
}

private fun networkLabel(context: Context): String = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "无网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "无线网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
            else -> "已连接"
        }
    } else {
        "已连接"
    }
} catch (e: Throwable) {
    "无网络"
}

private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
