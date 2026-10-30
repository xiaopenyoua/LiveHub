package com.livehub.tv.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.livehub.tv.core.store.Prefs
import com.livehub.tv.ui.AppNav
import com.livehub.tv.ui.Screen
import com.livehub.tv.ui.components.CardMode
import com.livehub.tv.ui.components.EmptyState
import com.livehub.tv.ui.components.GroupHead
import com.livehub.tv.ui.components.PageHead
import com.livehub.tv.ui.components.RoomGrid
import com.livehub.tv.ui.theme.Tokens

/**
 * 关注页：只回答「谁在播」。
 *
 * 两组：正在直播（原亮度）与未开播（封面压暗 0.5、保留平台名、角标写「未开播」）。
 * 一组里没有任何数据时，那句话就直说要做什么，不放一个占位卡。
 */
@Composable
fun FollowScreen(nav: AppNav, modifier: Modifier = Modifier) {
    // followVersion 在关注/取关后递增，用来触发重读本地列表
    val version = nav.followVersion
    val follows = remember(version) { Prefs.follows() }
    val live = follows.filter { it.live }
    val offline = follows.filterNot { it.live }
    val followTab = nav.tabRequests[Screen.Follow.ordinal]

    Column(modifier.fillMaxSize()) {
        PageHead(
            title = "关注",
            subtitle = "${follows.size} 位主播　·　${live.size} 位正在直播"
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = Tokens.Gutter, end = Tokens.Gutter, top = 40.dp)
        ) {
            if (follows.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有关注的主播",
                        body = "在播放页按 OK，或按「＋ 关注」就能关注。关注列表按各平台自己的房间号保存，不做跨平台映射。",
                        actionText = "去搜索找主播",
                        onAction = { nav.screen = Screen.Search }
                    )
                }
            } else {
                if (live.isNotEmpty()) {
                    item {
                        GroupHead(title = "正在直播", count = "${live.size} 位")
                    }
                    item {
                        RoomGrid(
                            rooms = live,
                            columns = Tokens.GRID_COLUMNS,
                            mode = CardMode.Host,
                            live = true,
                            onClick = { room -> nav.open(room, live + offline) },
                            memoKey = "follow-live",
                            memo = nav.focusMemo,
                            upFromFirstRow = followTab
                        )
                    }
                }
                if (offline.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(if (live.isEmpty()) 0.dp else 56.dp))
                        GroupHead(title = "未开播", count = "${offline.size} 位")
                    }
                    item {
                        RoomGrid(
                            rooms = offline,
                            columns = Tokens.GRID_COLUMNS,
                            mode = CardMode.Host,
                            live = false,
                            autoFocus = live.isEmpty(),
                            onClick = { room -> nav.open(room, live + offline) },
                            memoKey = "follow-off",
                            memo = nav.focusMemo,
                            autoFocusBlocked = nav.inNav,
                            upFromFirstRow = followTab
                        )
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}
