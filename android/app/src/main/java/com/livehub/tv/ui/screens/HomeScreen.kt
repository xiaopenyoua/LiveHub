package com.livehub.tv.ui.screens

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.livehub.tv.ui.theme.Tokens

/**
 * 首页：只回答「现在有什么好看」。
 *
 * 主图 = 当前最热的一个直播间；下面「正在直播」是全平台混排的 4 列竖网格，整页向下滚。
 * 首页刻意没有「关注的主播」和「平台索引」——前者会把推荐流切碎，后者会把换台变成
 * 「先看表再按键」。两件事各自有归宿。
 */
@Composable
fun HomeScreen(nav: AppNav, modifier: Modifier = Modifier) {
    val sort = Prefs.homeSort
    val handle = rememberAsync("home", sort) { Sites.homeFeed() }
    val heroTv = rememberTvFocus()
    var didFocus by remember { mutableStateOf(false) }

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
            val rooms = remember(state.data, sort) { sortRooms(state.data, sort) }
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
                        text = if (rooms.isEmpty()) "所有平台都没有返回内容"
                        else "只有 ${rooms.first().platform.label} 一家返回了内容，其它平台都失败了",
                        onRetry = { handle.retry() }
                    )
                    Spacer(Modifier.height(24.dp))
                    EmptyState(
                        title = "网络异常",
                        body = "四家平台里至少三家没取到。这不是「今天没有直播」，是取数失败了——不显示任何假卡片。",
                        actionText = "重试",
                        onAction = { handle.retry() }
                    )
                }
            } else {
                val homeTab = nav.tabRequests[Screen.Home.ordinal]
                LaunchedEffect(rooms.first().identity) {
                    // 顶栏拿着焦点时不要抢：否则按 OK 切到本屏的同一瞬间，
                    // 入口 requestFocus 到主图按钮上，同一次按键的抬起会把直播间点开。
                    if (!didFocus && !nav.inNav) {
                        withFrameNanos { }
                        runCatching { heroTv.requester.requestFocus() }
                        didFocus = true
                    }
                }
                Box(modifier.fillMaxSize()) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                                    trailing = if (sort == "new") "按最新排序" else "按人气排序"
                                )
                                RoomGrid(
                                    rooms = rooms,
                                    columns = Tokens.GRID_COLUMNS,
                                    onClick = { room -> nav.open(room, rooms) },
                                    autoFocus = false,
                                    memoKey = "home",
                                    memo = nav.focusMemo,
                                    // 第一行往上不落到主图按钮，直接回顶栏：主图按钮在上方偏左，
                                    // 从第 3、4 列按上去靠几何会「就近」跳到它，观感上是横向窜位。
                                    upFromFirstRow = homeTab
                                )
                            }
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                    // 内容滚到顶栏下沿时不能硬切：真实截图里主图副标题被顶栏齐齐切断，
                    // 像被贴纸压住。这里是**首页独有**的一道渐隐——其它四屏的页标题
                    // 就在 y=48dp，整屏加遮罩会把标题一起压暗，那是另一个错误。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(96.dp)
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

/**
 * 首页排序只做「人气」和「编辑顺序」两种，不排序号——房间号长度不一、
 * 抖音号还是字母串，排序没有意义。
 */
private fun sortRooms(rooms: List<Room>, sort: String): List<Room> =
    when (sort) {
        "new" -> rooms
        else -> rooms.sortedByDescending { it.viewers }
    }
