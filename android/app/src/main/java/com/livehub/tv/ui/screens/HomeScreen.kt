package com.livehub.tv.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.livehub.tv.core.model.Room
import com.livehub.tv.core.site.Sites
import com.livehub.tv.core.store.Prefs
import com.livehub.tv.ui.AppNav
import com.livehub.tv.ui.Screen
import com.livehub.tv.ui.components.Async
import com.livehub.tv.ui.components.EmptyState
import com.livehub.tv.ui.components.ErrorBar
import com.livehub.tv.ui.components.HeroBlock
import com.livehub.tv.ui.components.LoadingState
import com.livehub.tv.ui.components.RoomGrid
import com.livehub.tv.ui.components.SectionHead
import com.livehub.tv.ui.components.rememberAsync
import com.livehub.tv.ui.components.rememberTvFocus
import com.livehub.tv.ui.components.upTo
import com.livehub.tv.ui.theme.Tokens

/**
 * 首页：只回答「现在有什么好看」。
 *
 * 主图 = 当前最热的一个直播间；下面「正在直播」是全平台混排的 4 列竖网格，整页向下滚。
 * 首页刻意没有「关注的主播」和「平台索引」——前者会把推荐流切碎，后者会把换台变成
 * 「先看表再按键」。两件事各自有归宿。
 */
@Composable
fun HomeScreen(nav: AppNav, modifier: Modifier = Modifier, onSettled: () -> Unit = {}) {
    var sort by remember { mutableStateOf(Prefs.homeSort) }
    val handle = rememberAsync("home", sort) { Sites.homeFeedDetailed() }
    val heroTv = rememberTvFocus()
    val sortTv = rememberTvFocus()
    var didFocus by remember { mutableStateOf(false) }

    // 开屏等的是「第一批数据有结论」，不是「有一个能播的房间」：整屏失败也算结论，
    // 否则网络断了开屏会一直挂着。
    LaunchedEffect(handle.state) {
        if (handle.state !is Async.Loading) onSettled()
    }

    when (val state = handle.state) {
        Async.Loading -> Box(modifier.fillMaxSize()) {
            LoadingState(
                // 抖音首版没有推荐流，写「四家」是在报一个不会来的数
                text = "正在取各平台的热门…",
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        is Async.Failed -> Column(
            modifier = modifier
                .fillMaxSize()
                .padding(start = Tokens.Gutter, end = Tokens.Gutter, top = 96.dp)
        ) {
            ErrorBar(
                // 整句话由 `kind` 查表，这里不再拼前缀：`SiteException.message` 是
                // `detail ?: kind.name`，拼上去最坏会显示 `TLS: SSLHandshakeException`。
                error = state.kind,
                onRetry = { handle.retry() }
            )
            Spacer(Modifier.height(24.dp))
            EmptyState(
                title = "现在没有可看的直播",
                // 正文读 `kind.userText()`。原来这里写死「检查网络后重试，不显示任何假卡片」——
                // 四家失败的原因并不都是网络，而「不显示任何假卡片」是写给我们自己的话。
                error = state.kind,
                actionText = "重试",
                onAction = { handle.retry() }
            )
        }

        is Async.Ready -> {
            val feed = state.data
            val rooms = remember(feed.rooms, sort) { sortRooms(feed.rooms, sort) }
            // 单平台失败在 UI 上就是「缺席」：那家的卡片不出现，其它家照常。
            // 但只剩 0–1 家时，4 家里至少 3 家失败——这时候不该摆几行内容装作正常，
            // 整屏说「网络异常」并给重试，比让用户以为「今天只有这些直播」诚实。
            val platformCount = remember(rooms) { rooms.map { it.platform }.distinct().size }
            if (rooms.isEmpty() || platformCount <= 1) {
                Column(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(start = Tokens.Gutter, end = Tokens.Gutter, top = 96.dp)
                ) {
                    ErrorBar(
                        // 四家全挂时把第一家的真实归责码带上：断网/403/改版不能都缩成一句
                        error = if (rooms.isEmpty()) feed.failures.firstOrNull()?.kind else null,
                        text = if (rooms.isEmpty()) "所有平台都没有返回内容"
                        else "只有 ${rooms.first().platform.label} 一家返回了内容，其它平台都失败了",
                        onRetry = { handle.retry() }
                    )
                    Spacer(Modifier.height(24.dp))
                    EmptyState(
                        title = "网络异常",
                        body = "四家平台里至少三家没取到。这不是「今天没有直播」，是取数失败了——不显示任何假卡片。",
                        error = if (rooms.isEmpty()) feed.failures.firstOrNull()?.kind else null,
                        actionText = "重试",
                        onAction = { handle.retry() }
                    )
                }
            } else {
                val homeTab = nav.tabRequests[Screen.Home.ordinal]
                LaunchedEffect(rooms.first().identity) {
                    // 顶栏拿着焦点时不要抢；播放页打开时也不要抢——否则底层首页会
                    // 和播放页抢焦点，用户按键会落到背后的卡片上。
                    if (!didFocus && !nav.inNav && nav.playerRoom == null) {
                        withFrameNanos { }
                        runCatching { heroTv.requester.requestFocus() }
                        didFocus = true
                    }
                }
                val listState = rememberLazyListState()
                val scrolled by remember {
                    derivedStateOf {
                        listState.firstVisibleItemIndex > 0 ||
                            listState.firstVisibleItemScrollOffset > 0
                    }
                }
                val topFade by animateFloatAsState(
                    targetValue = if (scrolled) 1f else 0f,
                    animationSpec = tween(180),
                    label = "homeTopFade"
                )
                Box(modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        item {
                            HeroBlock(
                                room = rooms.first(),
                                onEnter = { nav.open(rooms.first(), rooms) },
                                buttonTv = heroTv,
                                buttonUpTo = homeTab
                            )
                        }
                        item {
                            Column(
                                modifier = Modifier.padding(
                                    start = Tokens.Gutter,
                                    end = Tokens.Gutter,
                                    top = Tokens.ShelfGap
                                )
                            ) {
                                SectionHead(
                                    title = "正在直播",
                                    count = "全平台 ${rooms.size} 个",
                                    trailing = if (sort == "new") "按最新排序" else "按人气排序",
                                    trailingTv = sortTv,
                                    onTrailingClick = {
                                        val next = if (sort == "new") "hot" else "new"
                                        Prefs.homeSort = next
                                        sort = next
                                    },
                                    // 排序钮是网格的上一级：网格首行 UP 先到它，再 UP 才回顶栏
                                    trailingModifier = Modifier.upTo(homeTab)
                                )
                                RoomGrid(
                                    rooms = rooms,
                                    columns = Tokens.GRID_COLUMNS,
                                    onClick = { room -> nav.open(room, rooms) },
                                    autoFocus = false,
                                    memoKey = "home",
                                    memo = nav.focusMemo,
                                    // 首行往上先到排序钮，再往上回顶栏；排序钮自己 UP 回页签
                                    upFromFirstRow = sortTv.requester
                                )
                            }
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                    // 只在**滚动后**才盖这道渐隐：静止时主图必须完整可见（用户反馈过
                    // 顶部一大片遮罩把海报压暗）。滚动时它仍然承担原来的职责——避免
                    // 主图/卡片被顶栏下沿齐齐切断。
                    if (topFade > 0.01f) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(96.dp)
                                .graphicsLayer { alpha = topFade }
                                .background(
                                    Brush.verticalGradient(
                                        0f to Tokens.Ground,
                                        0.55f to Tokens.Ground.copy(alpha = 0.86f),
                                        1f to Color.Transparent
                                    )
                                )
                        )
                    }
                }
            }
        }
    }
}

/**
 * 首页排序只做「人气」和「编辑顺序」两种，不排序号——房间号长度不一、
 * 抖音号还是字母串，排序没有意义。
 */
private fun sortRooms(rooms: List<Room>, sort: String): List<Room> =
    when (sort) {
        "new" -> rooms
        else -> rooms.sortedByDescending { it.viewers }
    }
