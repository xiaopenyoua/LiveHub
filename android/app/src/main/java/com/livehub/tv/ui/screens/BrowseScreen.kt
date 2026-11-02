package com.livehub.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livehub.tv.core.model.LiveCategory
import com.livehub.tv.core.model.Platform
import com.livehub.tv.core.model.Room
import com.livehub.tv.core.site.FeedResult
import com.livehub.tv.core.site.IdInput
import com.livehub.tv.core.site.Sites
import com.livehub.tv.ui.AppNav
import com.livehub.tv.ui.Screen
import com.livehub.tv.ui.components.Async
import com.livehub.tv.ui.components.CategoryChip
import com.livehub.tv.ui.components.dpadTo
import com.livehub.tv.ui.components.rememberTvFocus
import com.livehub.tv.ui.components.EmptyState
import com.livehub.tv.ui.components.ErrorBar
import com.livehub.tv.ui.components.LoadingState
import com.livehub.tv.ui.components.RailRow
import com.livehub.tv.ui.components.RoomGrid
import com.livehub.tv.ui.components.rememberAsync
import com.livehub.tv.ui.components.upTo
import com.livehub.tv.ui.components.rememberTvFocus
import com.livehub.tv.ui.theme.LiveHubType
import com.livehub.tv.ui.theme.Tokens
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 分类页：左栏钉死（平台 + 在播数量），只有右栏滚。
 *
 * 左栏是「我在哪」的坐标系，跟着内容滚走用户就不知道站在哪了；所以整屏不是
 * 一个滚动容器，只有右侧分类标签 + 网格滚。
 *
 * 本页对平台的所有判断都走 `capabilities`：**能取列表的平台才发请求、才显示数量**。
 * 抖音本版 `recommend/categories` 都是 false，于是它在这里不会出现「0 个」
 * 和「这个平台现在没有在播的直播间」——那两句话都是在把「取不到」说成「没人播」。
 */
@Composable
fun BrowseScreen(nav: AppNav, modifier: Modifier = Modifier) {
    val platform = nav.browsePlatform
    val caps = platform?.let { Sites.of(it).capabilities }
    val counts = rememberAsync("browse-counts") { loadPlatformCounts() }
    val feed = rememberAsync("browse-feed", platform?.key) {
        when {
            // 「全部平台」也要把四家的失败归因带上来，不能只给一句「没人播」
            platform == null -> Sites.homeFeedDetailed()
            caps?.recommend == false -> FeedResult(emptyList(), emptyList())
            else -> FeedResult(Sites.of(platform).recommend(1), emptyList())
        }
    }
    val categories = rememberAsync("browse-cats", platform?.key) {
        when {
            platform == null -> emptyList()
            caps?.categories == false -> emptyList()
            else -> loadCategories(platform)
        }
    }
    val railFirst = rememberTvFocus()
    val browseTab = nav.tabRequests[Screen.Browse.ordinal]

    // 选中的 chip → 真实 categoryId；有 id 就调 categoryRooms，不再只过滤 recommend(1)。
    // 旧实现 chips 只是对首屏推荐做 areaName 过滤，绝大多数分类会显示「暂时没有直播间」。
    val selectedCategoryId: String? = remember(categories.state, nav.browseCategory) {
        (categories.state as? Async.Ready)?.data
            ?.firstOrNull { it.name == nav.browseCategory }?.id
    }
    val usingCategoryFeed = platform != null &&
        nav.browseCategory != "全部" && selectedCategoryId != null
    val categoryFeed = rememberAsync("browse-cat", platform?.key, selectedCategoryId) {
        val p = platform
        if (p == null || selectedCategoryId == null) {
            FeedResult(emptyList(), emptyList())
        } else {
            FeedResult(Sites.of(p).categoryRooms(selectedCategoryId, 1), emptyList())
        }
    }
    val activeState = if (usingCategoryFeed) categoryFeed.state else feed.state
    fun retryActive() {
        if (usingCategoryFeed) categoryFeed.retry() else feed.retry()
    }

    val activeData = (activeState as? Async.Ready)?.data
    val rooms: List<Room> = activeData?.rooms.orEmpty()
    val feedFailures = activeData?.failures.orEmpty()
    // 标签只来自真数据（接口给的分区 + 内容里出现过的分区），没有再编一套兜底标签：
    // 点进去必然为空的标签不是入口，是骗人。
    val categoryNames: List<String> = remember(categories.state, rooms) {
        val fetched = (categories.state as? Async.Ready)?.data?.map { it.name }.orEmpty()
        val fromData = rooms.map { it.areaName }.filter { it.isNotBlank() }
        (fetched + fromData).distinct()
    }
    val chips = listOf("全部") + categoryNames
    val chipRequesters = remember(chips) { chips.map { FocusRequester() } }
    val shown = remember(rooms, nav.browseCategory, usingCategoryFeed) {
        if (usingCategoryFeed || nav.browseCategory == "全部") rooms
        else rooms.filter { it.areaName == nav.browseCategory }
    }

    // 选平台后**不要抢焦点**。旧实现在「右栏为空」时把焦点拉回左栏第一行
    // 「全部平台」，于是同屏出现「抖音选中」+「全部平台聚焦」两个当前项（DEF-1）。
    // 选中的那一行本来就是焦点所在，留在原地最不容易误读；想去「去搜索」按 RIGHT 即可。

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 44.dp)
    ) {
        // ---------- 左栏：平台 + 在播数量 ----------
        Column(
            modifier = Modifier
                .width(320.dp)
                .padding(start = Tokens.Gutter)
        ) {
            Text(text = "分类", style = LiveHubType.h1, color = Tokens.Text)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "按平台筛，或在搜索里输入房间号。",
                style = LiveHubType.small,
                color = Tokens.Faint
            )
            Spacer(Modifier.height(26.dp))
            RailRow(
                label = "全部平台",
                trailing = countLabel(counts.state, null),
                current = platform == null,
                onClick = {
                    nav.browsePlatform = null
                    nav.browseCategory = "全部"
                },
                // 左栏第一行往上回顶栏「分类」页签；往右先到分类标签行（chip），
                // 不靠几何——几何会把焦点直接送到下面的卡片，用户按 RIGHT 就进了房间。
                modifier = Modifier
                    .upTo(browseTab)
                    .dpadTo(Key.DirectionRight, chipRequesters.firstOrNull()),
                tv = railFirst
            )
            Platform.entries.forEach { p ->
                RailRow(
                    label = p.label,
                    trailing = countLabel(counts.state, p),
                    current = platform == p,
                    onClick = {
                        nav.browsePlatform = p
                        nav.browseCategory = "全部"
                    },
                    modifier = Modifier.dpadTo(Key.DirectionRight, chipRequesters.firstOrNull())
                )
            }
            Spacer(Modifier.height(36.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Tokens.Panel)
                    .padding(horizontal = 26.dp, vertical = 24.dp)
            ) {
                Text(
                    text = "房间号各是各的",
                    style = LiveHubType.meta.copy(fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = Tokens.Muted
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "斗鱼四到八位，虎牙最多八位，哔哩另有一套短号，抖音只有抖音号。四家没有统一编号。",
                    style = LiveHubType.small,
                    color = Tokens.Faint
                )
            }
        }

        Spacer(Modifier.width(44.dp))

        // ---------- 右栏：分类标签 + 网格（只有这里滚） ----------
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(end = Tokens.Gutter),
        ) {
            item {
                // 分类标签给了才显示：只有一个「全部」的标签行是纯装饰 + 一次多余的方向键
                if (categoryNames.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(chips) { index, name ->
                            val chipTv = rememberTvFocus(chipRequesters[index])
                            // 左右只在本行 chip 之间走：不钉的话几何会把焦点送到下方网格卡片，
                            // 用户按 RIGHT 想换分类，结果直接进了房间。
                            val chipNav = Modifier
                                .dpadTo(Key.DirectionLeft, chipRequesters.getOrNull(index - 1))
                                .dpadTo(Key.DirectionRight, chipRequesters.getOrNull(index + 1))
                            CategoryChip(
                                text = name,
                                selected = nav.browseCategory == name,
                                onClick = { nav.browseCategory = name },
                                tv = chipTv,
                                // 标签行是本屏最上面的一排可聚焦内容：往上直接回页签。
                                // 网格第一行往上走几何会落到这排标签上，那是对的，不要抢。
                                modifier = if (index == 0) chipNav.upTo(browseTab) else chipNav
                            )
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }

            when (val state = activeState) {
                Async.Loading -> item {
                    LoadingState(text = "正在取${platform?.label ?: "各平台"}的直播间…")
                }

                is Async.Failed -> item {
                    Column {
                        ErrorBar(
                            // 文案与「能不能重试」都由 `kind` 决定：
                            // `detail`（`HTTP 503 body=…` 这类）是诊断页的证据，不上屏。
                            error = state.kind,
                            onRetry = { retryActive() }
                        )
                        Spacer(Modifier.height(24.dp))
                        EmptyState(
                            title = "这一栏现在是空的",
                            error = state.kind,
                            actionText = "重试",
                            onAction = { retryActive() }
                        )
                    }
                }

                is Async.Ready -> {
                    if (caps?.recommend == false) {
                        // 「本版取不到」和「今天没人播」是两件事，必须说成两句话。
                        item {
                            EmptyState(
                                title = "该平台暂不支持浏览",
                                body = if (caps.idInput != IdInput.RoomId) {
                                    "本版取不到这家平台的推荐与分区。可以直接在搜索页把直播间链接或房间号粘进来。"
                                } else {
                                    "本版取不到这家平台的推荐与分区。可以在搜索页按房间号或主播名找。"
                                },
                                actionText = "去搜索",
                                onAction = { nav.screen = Screen.Search }
                            )
                        }
                    } else if (shown.isEmpty()) {
                        val failureKind = feedFailures.firstOrNull()?.kind
                        item {
                            if (failureKind != null) {
                                Column {
                                    ErrorBar(error = failureKind, onRetry = { retryActive() })
                                    Spacer(Modifier.height(24.dp))
                                    EmptyState(
                                        title = "这一栏取数失败了",
                                        error = failureKind,
                                        actionText = "重试",
                                        onAction = { retryActive() }
                                    )
                                }
                            } else {
                                EmptyState(
                                    title = if (rooms.isEmpty()) "这个平台现在没有在播的直播间" else "「${nav.browseCategory}」下暂时没有直播间",
                                    body = "换个分类或换个平台看看。",
                                    actionText = if (nav.browseCategory != "全部") "看全部" else "重试",
                                    onAction = {
                                        if (nav.browseCategory != "全部") nav.browseCategory = "全部"
                                        else retryActive()
                                    }
                                )
                            }
                        }
                    } else {
                        item {
                            RoomGrid(
                                rooms = shown,
                                columns = Tokens.GRID_COLUMNS,
                                onClick = { room -> nav.open(room, shown) },
                                gapX = 20.dp,
                                gapY = 28.dp,
                                memoKey = "browse",
                                memo = nav.focusMemo,
                                // **不要自动抢焦点**：本屏的入口在左栏平台行/分类 chip，
                                // 选平台或 chip 会触发重载，网格一抢焦点，用户的下一次 RIGHT
                                // 就落到卡片上、OK 直接进房（实测多次）。
                                autoFocus = false,
                                // 从顶栏切进来时不要抢焦点：同一次按键的抬起会点开第一张卡
                                autoFocusBlocked = nav.inNav,
                                // 第 1 行往上先回分类标签行（chip），再往上才回「分类」页签：
                                // 标签行是网格的父级选择器，跳过它会让 chip 在几何上不可达。
                                upFromFirstRow = chipRequesters.firstOrNull() ?: browseTab
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(60.dp)) }
        }
    }
}

/** 在播数量。取数失败、或这家本版没有列表接口时写「—」，不编一个 0 出来。 */
private fun countLabel(
    counts: Async<Map<Platform, Int>>,
    platform: Platform?
): String {
    if (platform != null && !Sites.of(platform).capabilities.recommend) return "—"
    val data = (counts as? Async.Ready)?.data ?: return "—"
    return if (platform == null) {
        // 只把「能取列表」的平台加在一起：把取不到的那家算成 0，
        // 总数就会悄悄少一截，而用户以为那就是全部。
        val sum = data.filterKeys { Sites.of(it).capabilities.recommend }.values.sum()
        "$sum 个"
    } else {
        "${data[platform] ?: 0} 个"
    }
}

/** 四家并发取「首页推荐」的第一页，只为拿各平台的在播数量。 */
private suspend fun loadPlatformCounts(): Map<Platform, Int> = coroutineScope {
    Sites.all
        .filter { it.capabilities.recommend }
        .map { site ->
            async {
                val list = withTimeoutOrNull(6_000) {
                    runCatching { site.recommend(1) }.getOrDefault(emptyList())
                } ?: emptyList()
                site.platform to list.size
            }
        }
        .map { it.await() }
        .toMap()
}

private suspend fun loadCategories(platform: Platform): List<LiveCategory> =
    withTimeoutOrNull(6_000) {
        runCatching { Sites.of(platform).categories() }.getOrDefault(emptyList())
    } ?: emptyList()
