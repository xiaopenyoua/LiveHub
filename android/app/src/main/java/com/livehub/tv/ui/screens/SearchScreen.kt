package com.livehub.tv.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livehub.tv.core.model.Room
import com.livehub.tv.core.model.RoomDetail
import com.livehub.tv.core.model.SiteError
import com.livehub.tv.core.model.SiteException
import com.livehub.tv.core.site.FeedResult
import com.livehub.tv.core.site.IdInput
import com.livehub.tv.core.site.LiveSite
import com.livehub.tv.core.site.SiteFailure
import com.livehub.tv.core.site.Sites
import com.livehub.tv.ui.AppNav
import com.livehub.tv.ui.Screen
import com.livehub.tv.ui.components.Async
import com.livehub.tv.ui.components.EmptyState
import com.livehub.tv.ui.components.ErrorBar
import com.livehub.tv.ui.components.GlassBox
import com.livehub.tv.ui.components.LoadingState
import com.livehub.tv.ui.components.PageHead
import com.livehub.tv.ui.components.RecentRow
import com.livehub.tv.ui.components.RoomGrid
import com.livehub.tv.ui.components.Tier
import com.livehub.tv.ui.components.TvFocus
import com.livehub.tv.ui.components.focusScale
import com.livehub.tv.ui.components.upTo
import com.livehub.tv.ui.components.rememberAsync
import com.livehub.tv.ui.components.rememberTvFocus
import com.livehub.tv.ui.components.tvClickable
import com.livehub.tv.ui.strings.userText
import com.livehub.tv.ui.theme.LiveHubType
import com.livehub.tv.ui.theme.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private val KB_DIGITS = "1234567890"
private val KB_LETTERS = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")

/** 19 位 room_id + 一整个分享链接都要塞得下，所以上限从 12 提到 64 */
private const val MAX_QUERY = 64

/** 单家超时口径与 core 的聚合层一致：慢的那家被裁掉，不让它拖住整屏。 */
private const val SITE_TIMEOUT_MS = 6_000L

/**
 * 搜索页：一块键盘，一种输入。
 *
 * 数字行在最上面、和字母同宽同高——它不是「另一个模式」，是同一块键盘的一行。
 * 上一版要搜「小鹿233」得先按 123、打完再按 ABC 切回来，而「我现在要输入数字」
 * 这个决定本身就是凭空多出来的：直播没有台号。
 *
 * 关键词匹配只按「准不准」分三档，没有字段权重：完全相等 > 前缀 > 包含。
 * 房间号一律当字符串比，永远不 Number()。
 *
 * ## 这一页没有「抖音特判」
 *
 * 「哪家能按关键词搜」「哪家能吃链接」两个问题的答案都在 [SiteCapabilities] 里，
 * 由各平台自己声明。UI 只做两件事：把 `search = true` 的平台拿去搜关键词；
 * 把一串「不像词」的输入原样透传给声明了 `idInput != RoomId` 的平台取详情。
 * 抖音首版这两项分别是 false / RoomIdOrWebRidOrLink，于是它自然只会走直连那条路；
 * 等哪天签名器接通、把 `search` 改回 true，这一页一行都不用改就恢复了搜索能力。
 *
 * 输入串怎么归一化成内部 room_id（web_rid、短链 302、带 query 的分享链）全是 core 的事：
 * UI 里再写一遍正则，协议一改就是两处一起漂，而且一定会先漂。
 */
@Composable
fun SearchScreen(nav: AppNav, modifier: Modifier = Modifier) {
    val query = nav.searchQuery
    val firstKey = rememberTvFocus()
    val searchTab = nav.tabRequests[Screen.Search.ordinal]

    // ---- 能力表：这两行是本页唯一的「平台判断」来源，没有任何平台名 ----
    val keywordSites = remember { Sites.all.filter { it.capabilities.search } }
    val linkSites = remember { Sites.all.filter { it.capabilities.idInput != IdInput.RoomId } }

    // 「不像词」才值得绕开搜索直接按房间取。这里只判形态，不认平台、不认域名。
    val directInput: String? = remember(query, linkSites) {
        if (linkSites.isNotEmpty() && looksLikeIdentifier(query)) query.trim() else null
    }

    val keywordLoad = rememberAsync("search", query, keywordSites.isEmpty()) {
        if (query.isBlank() || keywordSites.isEmpty()) {
            FeedResult(emptyList(), emptyList())
        } else {
            delay(220) // 边打边筛：连续按键只发最后一次
            searchCapable(keywordSites, query)
        }
    }

    // 直连：用户输入原样透传，认不认得出来是 core 的判断
    val directLoad = rememberAsync("direct", directInput) {
        if (directInput == null) null else firstDetail(linkSites, directInput)
    }

    val keywordState = keywordLoad.state
    val directState = directLoad.state

    val keywordHits: List<Pair<Room, Int>> = remember(keywordState, query) {
        val rooms = (keywordState as? Async.Ready)?.data?.rooms.orEmpty()
        rooms
            .mapNotNull { room -> rankFor(room, query)?.let { rank -> room to rank } }
            .sortedWith(compareBy({ it.second }, { -it.first.viewers }))
    }

    val directRoom: Room? = (directState as? Async.Ready)?.data?.room

    // 直连命中的那张排最前（-1 档），并按 identity 去重：同一场直播不该出现两次
    val hits: List<Pair<Room, Int>> = remember(keywordHits, directRoom) {
        val head = directRoom?.let { listOf(it to DIRECT_RANK) }.orEmpty()
        (head + keywordHits.filter { it.first.identity != directRoom?.identity })
            .take(24)
    }

    val loading = keywordState is Async.Loading || directState is Async.Loading
    // 只留中文、不留 message：格式化放在这里一次，下面三处用它的地方不用各自再查表。
    val keywordFailure = (keywordState as? Async.Failed)?.kind
    val directFailure = (directState as? Async.Failed)?.kind
    val settled = keywordState !is Async.Loading &&
        (directInput == null || directState !is Async.Loading)

    fun submit() {
        if (query.isBlank()) return
        nav.noteQuery(query)
        val top = hits.firstOrNull()
        if (top != null) {
            nav.open(top.first, hits.map { it.first })
            return
        }
        // 详情还没回来，但形态是对的：播放页自己会取流，先进去。
        // roomId 存的是**原样输入**，换内部号是 core 的事。
        val input = directInput
        if (input != null) {
            val site = linkSites.first()
            val stub = Room(
                platform = site.platform,
                roomId = input,
                title = "",
                hostName = "",
                coverUrl = ""
            )
            nav.open(stub, listOf(stub))
        }
    }

    fun type(ch: String) {
        if (nav.searchQuery.length >= MAX_QUERY) return
        nav.searchQuery += ch
    }

    Column(modifier.fillMaxSize()) {
        PageHead(
            title = "搜索",
            subtitle = searchSubtitle(keywordSites, linkSites)
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp)
        ) {
            // ---------- 左：输入框 + 键盘 ----------
            Column(
                modifier = Modifier
                    .width(830.dp)
                    .padding(start = Tokens.Gutter)
            ) {
                // 输入框。模式徽标没有——输入框左边不该有任何东西说明「你在哪种模式」。
                // 提示文案的域名取自 Platform.host，不在 UI 里写死某一家。
                val hint = placeholder(keywordSites, linkSites)
                val shown = if (query.isEmpty()) hint else query
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .clip(RoundedCornerShape(Tokens.CardRadius))
                        .background(Tokens.Raised)
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = shown,
                            style = LiveHubType.h2.copy(
                                // 字号按**实际显示的那串字**算：提示文案比一般房间号长得多，
                                // 按 query 长度算是上一版的坑——空输入时 query 是空的，
                                // 提示语就用 34sp 硬塞，末尾直接省略号。
                                fontSize = when {
                                    shown.length > 34 -> 22.sp
                                    shown.length > 24 -> 26.sp
                                    else -> 34.sp
                                },
                                fontWeight = FontWeight.Medium
                            ),
                            color = if (query.isEmpty()) Color(0xFF5A5A5E) else Tokens.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(6.dp))
                        Caret()
                    }
                }

                Spacer(Modifier.height(28.dp))

                // 数字行：十列满铺，与字母键同尺寸
                // 这一排是键盘最上面一行：往上直接回顶栏页签，不靠几何（它会找到输入框那一片空白）
                KeyRow {
                    KB_DIGITS.forEach { ch ->
                        KeyCap(
                            label = ch.toString(),
                            onClick = { type(ch.toString()) },
                            modifier = Modifier
                                .weight(1f)
                                .upTo(searchTab),
                            fontSize = 34.sp,
                            fontWeight = FontWeight.SemiBold,
                            tv = if (ch == '1') firstKey else rememberTvFocus()
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                // 三行 QWERTY，短行错位：第二行缩半键、第三行缩一键半
                KB_LETTERS.forEachIndexed { rowIndex, row ->
                    KeyRow {
                        if (rowIndex == 1) Spacer(Modifier.weight(0.5f))
                        if (rowIndex == 2) Spacer(Modifier.weight(1.5f))
                        row.forEach { ch ->
                            KeyCap(
                                label = ch.toString(),
                                onClick = { type(ch.toString()) },
                                modifier = Modifier.weight(1f),
                                tv = rememberTvFocus()
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // 工具行：3 + 3 + 4 凑满十列，搜索键最宽——它是这一屏唯一的下一步
                KeyRow {
                    KeyCap(
                        label = "删除",
                        onClick = { nav.searchQuery = nav.searchQuery.dropLast(1) },
                        modifier = Modifier.weight(3f),
                        util = true,
                        tv = rememberTvFocus()
                    )
                    KeyCap(
                        label = "清空",
                        onClick = { nav.searchQuery = "" },
                        modifier = Modifier.weight(3f),
                        util = true,
                        tv = rememberTvFocus()
                    )
                    KeyCap(
                        label = "搜索",
                        onClick = { submit() },
                        modifier = Modifier.weight(4f),
                        util = true,
                        tv = rememberTvFocus()
                    )
                }
            }

            // ---------- 右：实时结果 + 最近搜索（只有这里滚） ----------
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 44.dp, end = Tokens.Gutter)
            ) {
                if (query.isNotBlank()) {
                    // 两条取数路径（关键词 / 按房间直取）各有各的状态，
                    // 但**用户只看到一句话**：搜索是一个动作，不是两次请求。
                    val status: String = when {
                        hits.isNotEmpty() && !loading -> "找到 ${hits.size} 个结果"
                        loading && directInput != null -> "正在按直播间 $directInput 取详情…"
                        loading -> "正在搜「$query」…"
                        // 两条路都没结果：先报关键词失败（它覆盖三家），再报直连失败。
                        // 这两句来自 `ui/strings` 那张表，是一句完整的话，**不带平台名**、
                        // 这里也不加「搜索失败：」前缀（半句和整句会分叉）。
                        // 注意必须在这里就 `userText()`：这一行要的是 `String`，
                        // 把 `SiteError` 直接塞进 `when` 会让整条分支被推成
                        // `Comparable<String & SiteError>`，编译不过。
                        keywordFailure != null -> keywordFailure.userText()
                        directFailure != null -> directFailure.userText()
                        else -> "没有匹配「$query」的直播间"
                    }

                    item {
                        Text(
                            text = status,
                            style = LiveHubType.meta,
                            color = if (keywordFailure != null || directFailure != null) Tokens.Live else Tokens.Faint,
                            modifier = Modifier.padding(bottom = 20.dp)
                        )
                    }

                    val top = hits.firstOrNull()
                    if (top != null && top.second == DIRECT_RANK) {
                        item {
                            // 直达提示不是按钮：那一步该由搜索键完成，多一个可聚焦元素
                            // 就是多一次方向键。
                            GlassBox(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 24.dp),
                                radius = 14.dp,
                                baseColor = Color.White.copy(alpha = 0.08f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "按「搜索」直达",
                                        style = LiveHubType.meta.copy(fontWeight = FontWeight.SemiBold),
                                        color = Tokens.Text
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        text = top.first.platform.label,
                                        style = LiveHubType.meta,
                                        color = Tokens.Muted
                                    )
                                    Text(text = " · ", style = LiveHubType.meta, color = Tokens.Muted)
                                    Text(
                                        text = top.first.hostLabel,
                                        style = LiveHubType.meta,
                                        color = Tokens.Muted
                                    )
                                    Text(text = " · ", style = LiveHubType.meta, color = Tokens.Muted)
                                    Text(
                                        text = "房间号 ${top.first.roomId}",
                                        style = LiveHubType.meta,
                                        color = Tokens.Muted
                                    )
                                }
                            }
                        }
                    }

                    val failure = keywordFailure ?: directFailure
                    when {
                        loading && hits.isEmpty() -> item {
                            LoadingState(
                                text = if (directInput != null) "正在按直播间号取详情…"
                                else "正在搜${keywordSites.joinToString("、") { it.platform.label }}…"
                            )
                        }

                        failure != null && hits.isEmpty() -> item {
                            ErrorBar(
                                // 整句与「能不能重试」都从 `kind` 查表 + 读 `retryable`，
                                // 这里不再自己拼「搜索失败：」——半句和整句一样会分叉。
                                error = failure,
                                onRetry = {
                                    if (keywordFailure != null) keywordLoad.retry()
                                    if (directFailure != null) directLoad.retry()
                                }
                            )
                        }
                    }

                    if (hits.isNotEmpty()) {
                        item {
                            RoomGrid(
                                rooms = hits.map { it.first },
                                columns = 2,
                                onClick = { room ->
                                    nav.noteQuery(query)
                                    nav.open(room, hits.map { it.first })
                                },
                                gapX = 24.dp,
                                gapY = 36.dp,
                                autoFocus = false,
                                memoKey = "search",
                                memo = nav.focusMemo,
                                // 结果卡在这页是「次要区」：往上就是顶栏，不靠几何
                                upFromFirstRow = searchTab
                            )
                            Spacer(Modifier.height(44.dp))
                        }
                    } else if (settled && failure == null) {
                        item {
                            EmptyState(
                                title = "没有匹配「$query」的直播间",
                                body = searchHint(keywordSites, linkSites)
                            )
                        }
                    }
                }

                item {
                    Text(
                        text = "最近搜索",
                        style = LiveHubType.meta,
                        color = Tokens.Faint,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )
                }
                if (nav.recent.isEmpty()) {
                    item {
                        Text(
                            text = "还没有搜过。搜一次之后，这里会记住你打过的那串字。",
                            style = LiveHubType.small,
                            color = Color(0xFF5A5A5E)
                        )
                    }
                } else {
                    items(nav.recent.size) { index ->
                        val item = nav.recent[index]
                        RecentRow(
                            text = item.text,
                            ago = item.agoLabel(),
                            onClick = { nav.searchQuery = item.text },
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    // 默认焦点落在键盘第一枚键上（顶栏拿着焦点时除外：那是切屏的同一次按键）
    LaunchedEffect(Unit) {
        if (nav.inNav) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstKey.requester.requestFocus() }
    }
}

@Composable
private fun Caret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(560, easing = com.livehub.tv.ui.components.Ease.Focus),
            repeatMode = RepeatMode.Reverse
        ),
        label = "caretAlpha"
    )
    Box(
        Modifier
            .width(3.dp)
            .height(40.dp)
            .background(Tokens.Live.copy(alpha = alpha))
    )
}

@Composable
private fun KeyRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

/**
 * 一枚键。焦点是「白底黑字 + 放大 1.06」——白键是这块键盘上唯一的焦点信号，
 * 而它的高度一律 92dp：加一行不改任何一枚键的手感。
 */
@Composable
private fun KeyCap(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tv: TvFocus = rememberTvFocus(),
    util: Boolean = false,
    height: Dp = 92.dp,
    fontSize: TextUnit = 30.sp,
    fontWeight: FontWeight = FontWeight.Medium
) {
    val scale = focusScale(tv, Tier.BUTTON_SCALE)
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .tvClickable(tv, onClick = onClick)
            .height(height)
    ) {
        GlassBox(
            modifier = Modifier.height(height),
            radius = 16.dp,
            focused = tv.focused,
            baseColor = if (util) Color.White.copy(alpha = 0.19f) else Color.White.copy(alpha = 0.10f),
            focusedColor = Color.White
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = LiveHubType.tab.copy(
                        fontSize = if (util) 24.sp else fontSize,
                        fontWeight = fontWeight
                    ),
                    color = if (tv.focused) Color.Black else Tokens.Text
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 取数（按能力过滤）

/** 直连命中的排序档位：比任何关键词档（0/1/2）都靠前，但和「完全相等」区分开。 */
private const val DIRECT_RANK = -1

/**
 * 只向**声明了 `search = true`** 的平台发搜索。
 *
 * 明知某家本版返回空列表还去请求一次，用户要等它超时才明白「这家搜不了」；
 * 过滤放在这里、文案放在 empty state，两件事都只发生在能力表一处。
 */
private suspend fun searchCapable(sites: List<LiveSite>, keyword: String): FeedResult =
    coroutineScope {
        val results = sites
            .map { site -> async { site to tryCall { site.search(keyword, 1) } } }
            .map { it.await() }
        FeedResult(
            rooms = results.mapNotNull { it.second.getOrNull() }.flatten(),
            failures = results.mapNotNull { (site, result) ->
                result.exceptionOrNull()?.let { failure ->
                    // 不要回到 `SiteFailure(platform, 文案)`：那条过渡构造器把异常重新
                    // 拼成字符串、`kind` 一律填 PARSE_BROKEN，超时也会被说成「接口改版」。
                    // `of()` 保归责（`toSiteError()`），`detail` 里留的是诊断串。
                    SiteFailure.of(site.platform, failure)
                }
            }
        )
    }

/**
 * 按房间取详情，谁先取到用谁的。
 *
 * 用户粘进来的可能是任何一家的链接，UI 不去判断「这是谁家的」——把原样输入丢给所有
 * 声明了能吃链接的平台，认得出的那家返回详情，认不出的抛异常（异常文案自带平台名）。
 * 一次失败不阻断整体：只要有一家认出来，这一串就是可用的。
 */
private suspend fun firstDetail(sites: List<LiveSite>, input: String): RoomDetail? =
    coroutineScope {
        val results = sites
            .map { site -> async { tryCall { site.detail(input) } } }
            .map { it.await() }
        results.firstOrNull { it.isSuccess }?.getOrNull()
            ?: throw results.firstNotNullOfOrNull { it.exceptionOrNull() }
                ?: IllegalStateException("没有平台能按这串输入取到直播间")
    }

/**
 * 单家请求包成 Result。
 *
 * **CancellationException 必须原样抛**：切屏/改输入时协程被取消，若在这里被吃掉
 * 并当成「失败」，用户什么都没干就会看到一行「搜索失败」。
 *
 * 超时抛的是 `SiteException(NET_TIMEOUT)`，**不是自己那个 `SiteTimeout`**。以前这里
 * 有个和 `Sites.kt` 同名的私有类，结果同一次超时在两条路上被说成两种原因：走
 * 统一映射（现在收在 `Throwable.toSiteError()`，`Sites.kt:199`）的是 `NET_TIMEOUT`
 * （「网络可能不太稳」），走这里的落到 `PARSE_BROKEN`（「接口可能已改版」，且
 * `report = true` 把账算在我们头上）。
 * 用户 Wi-Fi 差，我们告诉他接口改版了——一次超时不能有两个名字。
 */
private suspend fun <T> tryCall(block: suspend () -> T): Result<T> =
    try {
        Result.success(
            withTimeoutOrNull(SITE_TIMEOUT_MS) { block() }
                ?: throw SiteException(
                    SiteError.NET_TIMEOUT,
                    detail = "单站超时 ${SITE_TIMEOUT_MS}ms"
                )
        )
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }

// ---------------------------------------------------------------- 文案（按能力拼，不写平台名）

/**
 * 页面副标题。能搜的、只能吃链接的，两组名字都从能力表来。
 */
private fun searchSubtitle(keywordSites: List<LiveSite>, linkSites: List<LiveSite>): String {
    val keywordNames = keywordSites.joinToString("、") { it.platform.label }
    val linkNames = linkSites.joinToString("、") { it.platform.label }
    return when {
        keywordSites.isEmpty() && linkSites.isEmpty() -> "本版没有可用的取数接口，只能从首页进入。"
        keywordSites.isEmpty() -> "$linkNames 请把直播间链接或房间号粘到上面。"
        linkSites.isEmpty() -> "输入房间号或主播名；$keywordNames 支持关键词。"
        else -> "输入房间号或主播名；$keywordNames 支持关键词，$linkNames 请把直播间链接或房间号粘到上面。"
    }
}

/**
 * 输入框提示语。
 *
 * 域名取 [com.livehub.tv.core.model.Platform.host]，不在 UI 里写死「live.douyin.com」：
 * 哪天接入下一家「只吃链接」的平台，这句提示自动跟着变。
 */
private fun placeholder(keywordSites: List<LiveSite>, linkSites: List<LiveSite>): String {
    val link = linkSites.firstOrNull()
    return when {
        link != null -> "房间号或直播间链接，例：${link.platform.host}/123456"
        keywordSites.isNotEmpty() -> "房间号、主播名或标题关键词"
        else -> "输入直播间链接"
    }
}

/**
 * 空态下面那句。
 *
 * 本版没有搜索接口的平台，用户在这里看到「没有匹配」会以为是自己打错了；
 * 该说的是「这家搜不了，但你可以直接把链接粘进来」。
 */
private fun searchHint(keywordSites: List<LiveSite>, linkSites: List<LiveSite>): String {
    if (keywordSites.isEmpty()) {
        return "本版没有可用的搜索接口：把直播间链接或房间号粘到上面，按「搜索」可以直接进。"
    }
    val keywordNames = keywordSites.joinToString("、") { it.platform.label }
    val linkOnly = linkSites.filter { !it.capabilities.search }
    if (linkOnly.isEmpty()) {
        return "$keywordNames 支持主播名、标题与房间号关键词，换个词再试。"
    }
    val linkNames = linkOnly.joinToString("、") { it.platform.label }
    return "$keywordNames 支持主播名、标题与房间号关键词；" +
        "$linkNames 本版没有搜索接口，把直播间链接或房间号粘到上面，按「搜索」可以直接进。"
}

// ---------------------------------------------------------------- 输入形态

/**
 * 这串字**有没有可能是关键词**？只有不像词的才值得绕开搜索、按房间直取。
 *
 * 只做形态判断，不认平台也不认域名：
 *  - 一串数字 → 房间号（最短 4 位，斗鱼的号就是这么短）；
 *  - 带 `/` 的 → 链接，或分享文案里那截链接。
 *
 * 至于这串数字是谁家的号、要不要跟 302、web_rid 怎么换成内部 room_id，全是 core 的事。
 * 这里多写一条规则，平台改版时就是两处一起改。
 */
private fun looksLikeIdentifier(raw: String): Boolean {
    val text = raw.trim()
    if (text.isEmpty()) return false
    if (text.all { it.isDigit() }) return text.length >= 4
    return text.contains('/')
}

/**
 * 匹配档位。只按「匹配得有多准」排，没有字段权重：
 * 0 完全相等（主播名 / 房间号 / 抖音号 / 标题）
 * 1 前缀（房间号 / 主播名）
 * 2 包含（主播名 / 标题 / 房间号 / 分类）
 * 分类命中的是一类、不是一个直播间，所以固定落在最后一档。
 */
private fun rankFor(room: Room, rawQuery: String): Int? {
    val q = rawQuery.lowercase()
    if (q.isEmpty()) return null
    val host = room.hostName.lowercase()
    val title = room.title.lowercase()
    val rid = room.roomId.lowercase()
    val area = room.areaName.lowercase()
    return when {
        host == q || rid == q || title == q -> 0
        rid.startsWith(q) || host.startsWith(q) -> 1
        host.contains(q) || title.contains(q) || rid.contains(q) || area == q -> 2
        else -> null
    }
}

/** 最近搜索的「多久之前」。只存在内存里，所以时间戳是本机的，不做同步。 */
private fun com.livehub.tv.ui.RecentQuery.agoLabel(): String {
    val minutes = ((System.currentTimeMillis() - at) / 60_000L).toInt()
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        else -> "${minutes / (60 * 24)} 天前"
    }
}
