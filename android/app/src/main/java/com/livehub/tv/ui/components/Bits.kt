package com.livehub.tv.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livehub.tv.core.model.SiteError
import com.livehub.tv.core.site.toSiteError
import com.livehub.tv.ui.strings.userText
import com.livehub.tv.ui.theme.LiveHubType
import com.livehub.tv.ui.theme.Tokens
import kotlinx.coroutines.CancellationException

/**
 * 一次取数的三种状态。空结果也是「成功」，由各屏自己决定空态文案。
 *
 * `Failed` 装的是**归责码** [SiteError]，不是一句话。这条改动的理由不是整洁：
 * 以前它是 `String`，由 `e.message` 填，而 `SiteException.message = detail ?: kind.name`
 * ——于是首页/分类页的错误条会把 `TLS: SSLHandshakeException: ...` 印给用户。装 `kind`
 * 之后，屏上那句中文只能来自 `ui/strings/SiteErrorText.kt` 这一张表。
 *
 * **`Async.Failed(` 全工程只有一个构造点**（本文件 `rememberAsync` 的兜底 catch）。
 * 这才是「`detail` 上不了屏」的真正保障——不是大家约好不读它，而是**错误状态只有一条
 * 入口、且这条入口的参数类型是 `SiteError`**。想上屏 `detail` 的人不是「违约」，是
 * **找不到地方放**：要么改那个唯一构造点的参数类型（一次 `e:` 报给所有人看），要么在
 * UI 里新开一个字符串出口（多出来的那个点 review 一眼能看见）。
 * 约束落在「只有一个入口」上，别落在「大家都记得」上——约定靠人守，类型不用。
 */
sealed interface Async<out T> {
    data object Loading : Async<Nothing>
    data class Ready<T>(val data: T) : Async<T>
    data class Failed(val kind: SiteError) : Async<Nothing>
}

/** 取数句柄：持有状态与「重试」入口。重试只递增一个计数，不重建协程。 */
@Stable
class AsyncHandle<T> {
    var state by mutableStateOf<Async<T>>(Async.Loading)
    internal var tick by mutableStateOf(0)

    fun retry() {
        tick++
    }
}

/**
 * 无网络 / 接口全失败时必须给出可读的说明，不能白屏。
 *
 * 这里只负责把异常翻成**归责码**，不翻成句子——句子在 `ui/strings` 那一张表里，
 * 由各屏查表得到。翻成句子的代价是 `detail` 跟着上屏（`HTTP 503 body=<html>` 这种）。
 *
 * `CancellationException` 必须原样抛：它不是失败，是切屏/改输入时协程被取消。
 */
@Composable
fun <T> rememberAsync(vararg keys: Any?, loader: suspend () -> T): AsyncHandle<T> {
    val handle = remember(*keys) { AsyncHandle<T>() }
    androidx.compose.runtime.LaunchedEffect(handle, handle.tick, *keys) {
        handle.state = Async.Loading
        handle.state = try {
            Async.Ready(loader())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // `toSiteError()` 是 core 里唯一一份 `Throwable → SiteError` 映射，
            // 这里只转交、不再写第二份 `when`。`e.message` 彻底不进这个类型。
            Async.Failed(e.toSiteError())
        }
    }
    return handle
}

/** 页面标题 + 一句说明。分类 / 关注 / 搜索 / 设置共用。 */
@Composable
fun PageHead(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(start = Tokens.Gutter, end = Tokens.Gutter, top = 48.dp)) {
        Text(text = title, style = LiveHubType.h1, color = Tokens.Text)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(text = subtitle, style = LiveHubType.body, color = Tokens.Muted)
        }
    }
}

/**
 * 内容行标题：标题 + 计数 + 右侧动作（「按人气排序」）。
 * 下沿必须留 34dp：卡片聚焦放大 1.08 会向上顶出约 4% 卡高，18dp 的旧间距会让第一行啃到标题。
 */
@Composable
fun SectionHead(
    title: String,
    count: String? = null,
    trailing: String? = null,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = LiveHubType.h2
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 34.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = title,
            style = titleStyle,
            color = Tokens.Text,
            modifier = Modifier.alignByBaseline()
        )
        if (!count.isNullOrBlank()) {
            Text(
                text = count,
                style = LiveHubType.meta,
                color = Tokens.Faint,
                modifier = Modifier.alignByBaseline()
            )
        }
        if (!trailing.isNullOrBlank()) {
            Spacer(Modifier.weight(1f))
            Text(
                text = trailing,
                style = LiveHubType.meta,
                color = Tokens.Muted,
                modifier = Modifier.alignByBaseline()
            )
        }
    }
}

/** 关注页的分组标题：一行 26sp + 右侧计数，第二组前多 56dp。 */
@Composable
fun GroupHead(
    title: String,
    count: String?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 34.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = title,
            style = LiveHubType.title.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
            color = Tokens.Text,
            modifier = Modifier.alignByBaseline()
        )
        if (!count.isNullOrBlank()) {
            Text(
                text = count,
                style = LiveHubType.meta,
                color = Tokens.Faint,
                modifier = Modifier.alignByBaseline()
            )
        }
    }
}

/**
 * 空态：一句说明 + 最多一个动作。宁可少一张卡，也不显示点进去会黑的卡。
 *
 * 正文**不再由调用方各写一句**：给了 [error] 就读 `error.userText()`——13 个码的中文
 * 只有 `ui/strings/SiteErrorText.kt` 那一张表，屏上没有第二个出口；什么错误对象都没有
 * （真的是空的，不是坏了）才用中性的「还没有内容」。
 */
@Composable
fun EmptyState(
    title: String,
    body: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    error: SiteError? = null
) {
    val bodyText = error?.userText() ?: body ?: "还没有内容"
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Gutter, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = title, style = LiveHubType.h2, color = Tokens.Text)
        Spacer(Modifier.height(14.dp))
        Text(text = bodyText, style = LiveHubType.body, color = Tokens.Muted)
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(28.dp))
            PillButton(
                text = actionText,
                onClick = onAction,
                solid = false,
                height = 64.dp,
                horizontalPadding = 32.dp,
                fontSize = 24.sp
            )
        }
    }
}

/** 加载态：--panel 纯色 + 一行说明，不闪光、不骨架跳动。 */
@Composable
fun LoadingState(
    text: String = "正在加载…",
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "loading")
    val alpha by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = Ease.Focus),
            repeatMode = RepeatMode.Reverse
        ),
        label = "loadingDot"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Gutter, vertical = 64.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(PillShape)
                .background(Tokens.Muted.copy(alpha = alpha))
        )
        Spacer(Modifier.width(14.dp))
        Text(text = text, style = LiveHubType.body, color = Tokens.Muted)
    }
}

/**
 * 错误条：一句可读的说明 + 一枚**只在还能再试的时候才出现**的重试按钮。
 *
 * 给了 [error] 之后，这句话和这枚按钮都归 [SiteError] 管：
 *  - 文案读 `userText()`，13 个码只有一张表；
 *  - 按钮只在 `retryable == true` 时出现。4xx 重试一百次还是 4xx；解析坏了、
 *    房间里没人播、地区不给看，再点一下也不会好——**摆一个按不出结果的按钮
 *    比不摆更伤人**，用户会以为是自己的网络。
 *
 * **[text] 和 [error] 只留一个**：给了 [error] 就整句用它，`text` 被忽略。不做
 * 「前缀 + 表里那句」的拼接——那等于允许每个屏再写半句自己的文案，半句和整句一样会分叉。
 */
@Composable
fun ErrorBar(
    text: String = "",
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    retryText: String = "重试",
    error: SiteError? = null
) {
    val showRetry = onRetry != null && (error == null || error.retryable)
    GlassBox(
        modifier = modifier.fillMaxWidth(),
        radius = 16.dp,
        bodyAlpha = Tokens.A_STATIC
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = error?.userText() ?: text,
                style = LiveHubType.small,
                color = Tokens.Muted,
                modifier = Modifier.weight(1f)
            )
            if (showRetry) {
                Spacer(Modifier.width(20.dp))
                PillButton(
                    text = retryText,
                    onClick = onRetry!!,
                    solid = true,
                    height = 56.dp,
                    horizontalPadding = 28.dp,
                    fontSize = 22.sp
                )
            }
        }
    }
}

/** 分类标签（玻璃胶囊）。选中是白底黑字，焦点是提亮 + 放大 1.06。 */
@Composable
fun CategoryChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tv: TvFocus = rememberTvFocus()
) {
    val scale = focusScale(tv, Tier.BUTTON_SCALE)
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .tvClickable(tv, onClick = onClick)
            .height(56.dp)
    ) {
        GlassBox(
            modifier = Modifier.height(56.dp),
            radius = 999.dp,
            focused = tv.focused,
            solid = selected,
            baseColor = if (selected) Color.White.copy(alpha = 0.94f) else Color.White.copy(alpha = 0.09f),
            focusedColor = Color.White
        ) {
            Box(
                Modifier
                    .height(56.dp)
                    .padding(horizontal = 26.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    style = LiveHubType.body.copy(
                        fontSize = 24.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    // 选中和焦点都是白底（focusedColor = Color.White），所以字必须是深的。
                    // 之前焦点走了 Tokens.Text，白底白字——真机上看起来是「一个空胶囊」。
                    color = if (selected || tv.focused) Color.Black else Tokens.Muted
                )
            }
        }
    }
}

/**
 * 左栏的一行（平台 / 设置分组）。它是「我在哪」的坐标系，不是浮起的按钮，
 * 所以缩放只给 1.02，落影也不给——只有玻璃底色与顶沿高光。
 */
@Composable
fun RailRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    current: Boolean = false,
    height: Dp = 84.dp,
    paddingHorizontal: Dp = 24.dp,
    labelSize: Int = 26,
    tv: TvFocus = rememberTvFocus()
) {
    val scale = focusScale(tv, Tier.ROW_SCALE)
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .tvClickable(tv, onClick = onClick)
    ) {
        GlassBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
            radius = 16.dp,
            focused = tv.focused,
            baseColor = Color.Transparent,
            focusedColor = Tokens.Glass
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .padding(horizontal = paddingHorizontal),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = LiveHubType.title.copy(
                        fontSize = labelSize.sp,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = if (current || tv.focused) Tokens.Text else Tokens.Muted,
                    modifier = Modifier.weight(1f)
                )
                if (trailing != null) {
                    Text(
                        text = trailing,
                        style = LiveHubType.small,
                        color = Color(0xFF5A5A5E)
                    )
                }
            }
        }
    }
}

/** 最近搜索的一行：左边是你打过的那串字，右边是多久之前。 */
@Composable
fun RecentRow(
    text: String,
    ago: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tv: TvFocus = rememberTvFocus()
) {
    Box(
        modifier
            .fillMaxWidth()
            .tvClickable(tv, onClick = onClick)
            .clip(RoundedCornerShape(12.dp))
            .background(if (tv.focused) Tokens.Raised else Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = LiveHubType.body,
                color = if (tv.focused) Tokens.Text else Tokens.Muted,
                modifier = Modifier.weight(1f)
            )
            Text(text = ago, style = LiveHubType.small, color = Color(0xFF5A5A5E))
        }
    }
}

/** 提示行里高亮按键名（「↑↓ 换台」里的「↑↓」是白的）。 */
fun keyHint(vararg parts: Pair<String, Boolean>): androidx.compose.ui.text.AnnotatedString =
    buildAnnotatedString {
        parts.forEach { (text, highlight) ->
            if (highlight) {
                withStyle(SpanStyle(color = Tokens.Text, fontWeight = FontWeight.SemiBold)) {
                    append(text)
                }
            } else {
                append(text)
            }
        }
    }

/** 状态点 + 一句话（直播中 / 未开播）。 */
@Composable
fun StateInline(
    text: String,
    live: Boolean,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(PillShape)
                .background(if (live) Tokens.Live else Color(0xFF5A5A5E))
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = LiveHubType.meta.copy(fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal),
            color = if (live) Tokens.Live else Tokens.Faint
        )
    }
}
