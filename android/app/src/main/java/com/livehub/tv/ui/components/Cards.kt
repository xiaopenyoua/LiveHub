package com.livehub.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.livehub.tv.core.model.Platform
import com.livehub.tv.core.model.Room
import com.livehub.tv.ui.theme.LiveHubType
import com.livehub.tv.ui.theme.Tokens
import java.util.Locale

/** 平台的两字短名。设计稿明确不用四家品牌色，平台只靠这两个字表达。 */
val Platform.shortName: String
    get() = when (this) {
        Platform.BILIBILI -> "哔哩"
        Platform.DOUYU -> "斗鱼"
        Platform.HUYA -> "虎牙"
        Platform.DOUYIN -> "抖音"
    }

/** 房间标识的字段名跟平台走：抖音没有房间号，写「房间号」三个字就是错的。 */
val Room.ridLabel: String
    get() = if (platform == Platform.DOUYIN) "抖音号" else "房间号"

/**
 * 人数：3510000 → 351万，12.2 万，1.2 亿。
 *
 * **单位是这个函数的一部分，调用方只管在后面拼「人」。**
 * 之前它只返回数字（3_510_000 → "351"），调用方再拼「人」，于是同一个房间
 * hero 写「353 万人在看」、卡片写「353 人」——差 10000 倍，用户会以为卡上看的是在线人数。
 * 一个数字只有一份格式化逻辑，就在这一处。
 */
fun viewerText(viewers: Long): String {
    if (viewers <= 0L) return "0"
    if (viewers < 10_000L) return viewers.toString()
    if (viewers >= 100_000_000L) {
        return trimZero(String.format(Locale.CHINA, "%.1f", viewers / 100_000_000.0)) + "亿"
    }
    val wan = viewers / 10_000.0
    val text = if (wan >= 100) String.format(Locale.CHINA, "%.0f", wan)
    else String.format(Locale.CHINA, "%.1f", wan)
    return trimZero(text) + "万"
}

/** 12.0 → 12；12.5 保留 */
private fun trimZero(text: String): String =
    if (text.endsWith(".0")) text.dropLast(2) else text

/** 毫秒 → 2:18:04 / 18:04 */
fun clockText(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.CHINA, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.CHINA, "%d:%02d", m, s)
}

/**
 * 清晰度档位 → 人话。服务端给什么就显示什么。
 *
 * **这里不替平台宣布上限，也不替样本宣布分布。** 这段 KDoc 曾经写「未登录的哔哩最高只到
 * 250」、「哔哩未登录时服务端把 current_qn 压到 250」——那是从一两个房间推出来的规则，
 * 后来被实测推翻了：匿名（只有 `buvid3`）请求 `qn=10000`，**两个方向都有**——
 * 有的房间直接回 `current_qn=10000`（原画），有的回 250；赛事房 7734200 单独测，
 * 同一次响应里 avc=150、hevc=250。签发档由**房间与推流端**决定，登录态不是决定因素。
 *
 * **比例可以记，推论不许写**（这条的边界是架构师划的，我原来写成「一律不许写」，太宽：
 * 那会把逐行证据一起删掉）。「这一轮 12 个房间里 9 个是 10000」这种句子必须同时带三件：
 * 抽样源（按在线人数排序的分区列表）、「这一轮」这个限定、以及它的失效条件（同一天重扫，
 * 落到 250 与 10000 的房间数就会变）。**缺任何一件就退化成分布**；而「所以匿名多数能拿
 * 原画」「所以未登录会被压」这类**推论**，带不带限定都不许写——把一次抽样升级成规则，
 * 这个项目已经犯过两次（斗鱼体积门限一次，这里一次），形状完全一样。
 * 逐行数据见 `core/site/BilibiliSite.kt` 文件头「已知风险」第 2 条与
 * `android/docs/接口实测.md` §1.3.1——**指过去的是第几条/哪一节，不是行号**：
 * 那句话改完的当天行号就过期了。
 *
 * 所以下面「刻意不默认到原画」的理由不是「未登录所以拿不到」，
 * 而是**我们不知道这个房间给什么，就不能替它写**——认不出来的档位原样显示，
 * 宁可难看也别猜。反过来，「未登录，最高 720P」这种句子同样不许写：
 * 用户会为了它去登录，登完这个房间还是 720P，白跑一趟。
 *
 * **250 = 超清 = 720P**，这个数字来自平台自己在取流响应里返回的
 * `playurl_info.playurl.g_qn_desc`（实测 2026-10-29，匿名，房间 7734200，
 * `{"qn":250,"desc":"超清","media_base_desc":{"brief_desc":{"desc":"720P"}}}`），
 * 不是我们猜的。这里曾经写「超清 1080P」：那个 `1080P` 在全仓找不到出处
 * （只有 400 蓝光 / 10000 原画 才是 1080P），而且当时它是**死分支**——
 * `PlayUrl.quality` 由旧的 `qnName(250)` 翻成「高清」，先被 `contains("高清")` 截走。
 * 上游改成 `bilibiliQuality(qn)` 之后这条分支当场复活，于是屏幕上开始
 * 把 720P 报成 1080P。**死分支比错分支更值得盯：它不报错，只是等着被人唤醒。**
 *
 * 分辨率只在有出处时显示：150 是 480P、400/10000 是 1080P，但现在只有超清这一档
 * 会把分辨率写进这句人话（其余几档上屏名里没有分辨率，也就不会说错）。
 * 长远的口径是把签名换成吃 `PlayUrl`（它带 `qn`），从 `bilibiliQuality(qn).display`
 * 取——那要动 PlayerScreen 的四个调用点，下一批做。
 */
fun qualityLabel(raw: String): String = when {
    raw.isBlank() -> "自动"
    raw.contains("原画") || raw == "10000" -> "原画"
    raw.contains("蓝光") || raw == "400" -> "蓝光"
    raw.contains("超清") || raw == "250" -> "超清 720P"
    raw.contains("高清") || raw == "150" -> "高清"
    else -> raw
}

/**
 * 档位高低。只用来判断「用户要的档」和「服务端给的档」是不是同一档，
 * 好把「选了原画却还是 1080P」显式说出来，而不是静默失败。认不出的档一律 -1（不比较）。
 */
fun qualityRank(raw: String): Int = when {
    raw.isBlank() -> -1
    raw == "150" || raw.contains("高清") -> 150
    raw == "250" || raw.contains("超清") -> 250
    raw == "400" || raw.contains("蓝光") -> 400
    raw == "10000" || raw.contains("原画") -> 10000
    raw == "20000" -> 20000
    else -> -1
}

/**
 * 封面。全项目只有这一处加载图片：
 * 底是 --panel 纯色 + 平台名（加载中与失败都停在这里，不留白块），图片来了淡入 200ms。
 *
 * [colorFilter] 只给「未开播」卡用：设计稿要的是 `saturate(.55)`，亮度可以靠叠黑纱糊过去，
 * 去饱和不行——灰要真的灰。传 null 就一个滤镜都不挂。
 */
@Composable
fun RoomCover(
    room: Room,
    modifier: Modifier = Modifier,
    labelSize: Int = 22,
    colorFilter: ColorFilter? = null
) {
    val context = LocalContext.current
    Box(modifier.background(Tokens.Panel)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = room.platform.shortName,
                style = LiveHubType.small.copy(fontSize = labelSize.sp),
                color = Tokens.Faint
            )
        }
        if (room.coverUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(room.coverUrl)
                    .crossfade(200)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = colorFilter
            )
        }
    }
}

enum class CardMode { Room, Host }

/**
 * 两层字影。CSS 的 `text-shadow` 可以给多条，Compose 的 [TextStyle.shadow] 只有一条。
 *
 * 平台名和 LIVE 压在最亮的那部分画面上，需要「贴边收锐」和「散开托住」两条一起才不飘，
 * 所以同一个 Text 叠两遍：底下一层只负责散开的那圈，上面一层负责贴边。
 * A 档只画上面那一层——两层字要排两遍版，几十张卡一起付这个钱不值得。
 */
@Composable
private fun LayeredShadowText(
    text: String,
    style: TextStyle,
    color: Color,
    tight: Shadow,
    soft: Shadow,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    Box(modifier) {
        if (!Tier.low) {
            Text(
                text = text,
                style = style.copy(shadow = soft),
                color = color,
                maxLines = maxLines,
                overflow = TextOverflow.Clip
            )
        }
        Text(
            text = text,
            style = style.copy(shadow = tight),
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Clip
        )
    }
}

/**
 * 16:9 直播卡。
 *
 * **正文压在画面里，不再是封面下面的一块信息板。** 这是这一版最要紧的一次取舍：
 * 原来「图 + 信息板」是两件事，看卡时眼睛要先看图、再往下移一行读字，卡片是一张
 * 「带图的信息块」；现在只有一件事——一整幅画面，字浮在画面下沿，卡片变回「节目」。
 *
 * 焦点不画边框：scale 1.08 + 封面提亮 + 一点落影。字级从 26 降到 22：压在画面上的字
 * 一放大就变成字幕，抢戏。这里要的是「标在画上的名字」，不是标题。
 *
 * 运镜和提亮是两件事，所以有两条时长：`scale(1.04)` 走 600ms（镜头慢慢推），
 * 亮度走 200ms（灯照到就到）。
 */
@Composable
fun LiveCard(
    room: Room,
    mode: CardMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = true,
    metaOverride: String? = null,
    tv: TvFocus = rememberTvFocus(),
    onFocused: () -> Unit = {}
) {
    val scale = focusScale(tv, Tier.CARD_SCALE)
    val density = LocalDensity.current

    // 灯照到就到：200ms
    val dim by animateFloatAsState(
        targetValue = when {
            !live -> 0.50f          // brightness(.5)
            tv.focused -> 0f        // brightness(1.04) 由 lift 承担
            else -> 0.05f           // brightness(.95)
        },
        animationSpec = tween(Ease.DUR_FOCUS, easing = Ease.Focus),
        label = "coverDim"
    )
    val lift by animateFloatAsState(
        targetValue = when {
            !live && tv.focused -> 0.25f   // .5 → .75
            live && tv.focused -> 0.04f    // 1.0 → 1.04
            else -> 0f
        },
        animationSpec = tween(Ease.DUR_FOCUS, easing = Ease.Focus),
        label = "coverLift"
    )
    val sat by animateFloatAsState(
        targetValue = if (!live) if (tv.focused) 0.7f else 0.55f else 1f,
        animationSpec = tween(Ease.DUR_FOCUS, easing = Ease.Focus),
        label = "coverSat"
    )
    // 镜头慢慢推：600ms，和焦点那 200ms 是两件事
    val mediaZoom by animateFloatAsState(
        targetValue = if (tv.focused) 1.04f else 1f,
        animationSpec = tween(600, easing = Ease.Focus),
        label = "coverZoom"
    )
    val shadowEl by animateFloatAsState(
        targetValue = if (tv.focused) 24f else 10f,
        animationSpec = tween(Ease.DUR_FOCUS, easing = Ease.Focus),
        label = "cardLift"
    )

    val filter = remember(sat) {
        if (sat >= 0.999f) null
        else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(sat) })
    }

    val px = { v: Float -> with(density) { v.dp.toPx() } }
    val platTight = Shadow(Color.Black.copy(alpha = 0.92f), Offset(0f, px(1f)), px(2f))
    val platSoft = Shadow(Color.Black.copy(alpha = 0.60f), Offset(0f, px(2f)), px(14f))
    val warnShadow = Shadow(Color.Black.copy(alpha = 0.90f), Offset(0f, px(1f)), px(2f))
    val titleShadow = Shadow(Color.Black.copy(alpha = 0.66f), Offset(0f, px(1f)), px(3f))
    val metaShadow = Shadow(Color.Black.copy(alpha = 0.62f), Offset(0f, px(1f)), px(2f))

    // 落影：悬浮感不是靠放大给的，是靠影给的。
    // 静置一层薄的 `0 10px 26px -14px rgba(0,0,0,.55)`，焦点换成大而散的
    // `0 26px 56px -24px rgba(0,0,0,.42)`（`--card-lift`）。
    //
    // **这是近似，不是对齐**：Compose 的 `Modifier.shadow` 只有 elevation，没有 CSS 的
    // blur / 负 spread。近似的方向是「影比设计稿实一点、扩散小一点」——26→10dp 的
    // 等效值取的偏小，就是为了别让 24dp 的 elevation 在 1080p 上糊成一块。将来有人
    // 觉得「浮得不够」，先把 elevation 往上调一档，不要去改这里的 10/24 两个数字就
    // 以为对齐了 CSS。
    // 顺序也有讲究：`shadow` 在外层 Box、`clip` 在内层，影和裁剪不在同一条链上，
    // 不可能被裁掉；`graphicsLayer{scale}` 在 `shadow` 之前，缩放会把影一起放大
    // ——和 CSS `transform` 缩放 box-shadow 的行为一致。
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = shadowEl.dp,
                shape = RoundedCornerShape(Tokens.CardRadius),
                clip = false,
                ambientColor = Color.Black,
                spotColor = Color.Black
            )
            .tvClickable(
                tv = tv,
                onFocusChange = { if (it) onFocused() },
                onClick = onClick
            )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(Tokens.CardRadius))
                .background(Tokens.Panel)
        ) {
            RoomCover(
                room = room,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = mediaZoom
                        scaleY = mediaZoom
                    },
                colorFilter = filter
            )
    // 亮度：叠黑纱压暗、叠白纱提亮。两种都能随焦点插值，`filter:brightness()` 做不到
    // （Compose 没有 `filter:`，等价物是 `ColorMatrix`，那会让 AsyncImage 每帧重组）。
    //
    // **这是纱层近似，不是乘法**，两端的行为差异要知道：
    //  - 往暗处（未开播 `.5`）纱层比乘法「糊」一点——纱是往颜色里混黑，乘法是整体压亮，
    //    暗部细节保不住，所以未开播的封面会比设计稿略平一层；
    //  - 往亮处（焦点 `1.04`）纱层是往颜色里混白，高光会先到顶再溢出，所以别指望
    //    「焦点时封面还能再亮 4%」这种细调——真要调，改的是这里的 0.04，不是 brightness。
    // 去饱和不一样，那条必须是真的，走 `ColorFilter` 的 `setToSaturation`。
    if (dim > 0.001f) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
            }
            if (lift > 0.001f) {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = lift)))
            }
            // 字幕底：起笔 50%（早整张封面会被斜灰切成两半），收口只到 .72
            //（收满就是「黑底板上放两行字」，画面又没了）。剩下的对比度交给字自己的影。
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.50f to Color(0xFF040406).copy(alpha = 0f),
                                0.64f to Color(0xFF040406).copy(alpha = 0.16f),
                                0.80f to Color(0xFF040406).copy(alpha = 0.44f),
                                1f to Color(0xFF040406).copy(alpha = 0.72f),
                                startY = 0f,
                                endY = size.height
                            )
                        )
                    }
            )
            // 左上平台名：是字，不是玻璃 —— 没有底。22px 和正文的 padding 同值，
            // 平台名必须和标题在同一条竖线上，差 4px 就看得出来没对齐。
            LayeredShadowText(
                text = room.platform.shortName,
                style = LiveHubType.small.copy(
                    fontSize = 20.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    letterSpacing = 0.06.em
                ),
                color = Color.White,
                tight = platTight,
                soft = platSoft,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 22.dp, top = 15.dp)
            )
            if (live) {
                // 右上 LIVE：`.14em` 的字距是加在每个字后面，最后一个字后面那点白留
                // 会把右对齐顶歪 2.8px，往右补回去（CSS 里是 margin-right:-.14em）。
                LayeredShadowText(
                    text = "LIVE",
                    style = LiveHubType.small.copy(
                        fontSize = 20.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        letterSpacing = 0.14.em,
                        lineHeight = 20.sp
                    ),
                    color = Color.White,
                    tight = platTight,
                    soft = platSoft,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 22.dp, top = 16.dp)
                        .offset(x = 2.8.dp)
                )
            } else {
                Text(
                    text = "未开播",
                    style = LiveHubType.small.copy(
                        fontSize = 20.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        letterSpacing = 0.06.em,
                        lineHeight = 20.sp,
                        shadow = warnShadow
                    ),
                    color = Color.White.copy(alpha = 0.66f),
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 22.dp, top = 16.dp)
                )
            }
            // 正文压在画面下沿：padding 0 22 18
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, bottom = 18.dp)
            ) {
                Text(
                    text = (if (mode == CardMode.Host) room.hostLabel else room.title).ifBlank { room.hostLabel },
                    style = LiveHubType.cardTitle.copy(shadow = titleShadow),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = metaOverride ?: when (mode) {
                    CardMode.Room ->
                        "${room.platform.shortName} · ${room.hostLabel} · ${viewerText(room.viewers)}人"
                    CardMode.Host ->
                        if (live) "${room.platform.shortName} · ${viewerText(room.viewers)}人在看"
                        else "未开播"
                }
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = LiveHubType.cardMeta.copy(shadow = metaShadow),
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * 首页主图。478dp 高、左右满血，遮罩只压「文字压着的那一块」：
 * 横向 0→66% 由黑到透明，纵向底 0→48% 由黑到透明，中右部画面一点不碰。
 */
@Composable
fun HeroBlock(
    room: Room,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
    buttonTv: TvFocus = rememberTvFocus(),
    /** 「进入直播间」往上落到哪：首页传顶栏「首页」页签的 requester。 */
    buttonUpTo: FocusRequester? = null
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(478.dp)
            .background(Tokens.Panel)
    ) {
        RoomCover(room = room, modifier = Modifier.fillMaxSize(), labelSize = 34)
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.92f),
                            0.34f to Color.Black.copy(alpha = 0.72f),
                            0.66f to Color.Transparent,
                            startX = 0f,
                            endX = size.width
                        )
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.48f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.85f),
                            startY = 0f,
                            endY = size.height
                        )
                    )
                }
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = Tokens.Gutter, bottom = 56.dp)
                .width(900.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = room.platform.shortName,
                    style = LiveHubType.meta,
                    color = Tokens.Muted
                )
                Box(
                    Modifier
                        .padding(horizontal = 14.dp)
                        .size(4.dp)
                        .clip(PillShape)
                        .background(Color(0xFF6E6E73))
                )
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(PillShape)
                        .background(Tokens.Live)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (room.live) "直播中" else "未开播",
                    style = LiveHubType.meta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = if (room.live) Tokens.Live else Tokens.Muted
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = room.title.ifBlank { room.hostLabel },
                style = LiveHubType.hero.copy(lineHeight = 80.sp),
                color = Tokens.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = "${room.hostLabel}　·　${viewerText(room.viewers)}人在看" +
                    if (room.areaName.isNotBlank()) "　·　${room.areaName}" else "",
                style = LiveHubType.body,
                color = Tokens.Muted
            )
            Spacer(Modifier.height(30.dp))
            PillButton(
                text = "进入直播间",
                onClick = onEnter,
                modifier = Modifier.upTo(buttonUpTo),
                solid = true,
                tv = buttonTv,
                height = 72.dp,
                horizontalPadding = 40.dp,
                fontSize = 26.sp
            )
        }
    }
}

/**
 * 等宽网格。首页 / 关注 / 分类 / 搜索共用同一段结构，禁止在屏幕文件里再抄一遍。
 *
 * 行距默认 34dp（设计稿 `.card-grid{gap:34px 24px}`）：卡片聚焦会向外顶出约 8%，
 * 行距低于 34dp 时第一行会啃到分组标题。
 * 焦点记忆：进来时先找 memo 里记住的那一张，找不到就落在第一张。
 *
 * **方向键落点只有一个来源**：`rows` / `tvRows` 这套「行 × 列」结构，按行列直接算，
 * 不靠 Compose 的几何搜索（卡片放大 1.08、行距 36dp 时它会把「最近」算歪），
 * 也不靠 `focusProperties` 的定制 requester（目标悬空时它会静默吞键，见 [dpadTo]）。
 *
 * **四向连线必须挂在每一张卡自己的 modifier 链上，不能挂在网格容器上。**
 * 这是真机上量出来的：页签的 handler 挂在自己链上 → 生效；网格的 handler 挂在祖先
 * `Column` 上 → 收不到方向键，整个网格一直在走几何搜索。表现就是
 * 「第 2 列 RIGHT 正常（几何相邻）、第 1 行 UP 死（几何找不到上方目标）、
 *  第 3 列 UP 往左窜一格（几何的 beam 兜底）」——三种症状，同一个原因。
 *
 * 越界的边 `dpadTo(key, null)` 什么都不设，按键原样交回几何搜索（第 1 列往左去左栏、
 * 最后一行往下进下一个区块）；唯一的例外是第一行往上，几何会跳过顶栏落到主图按钮，
 * 所以用 [upFromFirstRow] 显式钉住。
 */
@Composable
fun RoomGrid(
    rooms: List<Room>,
    columns: Int,
    onClick: (Room) -> Unit,
    modifier: Modifier = Modifier,
    mode: CardMode = CardMode.Room,
    gapX: Dp = Tokens.CardGap,
    gapY: Dp = 34.dp,
    live: Boolean = true,
    autoFocus: Boolean = true,
    /**
     * 入口抢焦点的「闸」：为 true 时这一次不要抢。
     *
     * 传 `nav.inNav`。**刻意不做成 LaunchedEffect 的 key**：它只在自动聚焦发生的那一瞬
     * 被读一次。做成 key 的话，焦点一离开顶栏它就变 true→false，effect 重启，
     * 会把正在看内容的用户硬拽回第一张卡。
     */
    autoFocusBlocked: Boolean = false,
    memoKey: String? = null,
    memo: MutableMap<String, String>? = null,
    /**
     * 第一行往上落到哪。传顶栏页签的 requester，遥控器从任意一张卡往上走
     * 都能确定地回到顶栏；不传就交回几何搜索（它在这个布局里会算歪）。
     */
    upFromFirstRow: FocusRequester? = null
) {
    val tvMap = remember { mutableMapOf<String, TvFocus>() }
    var didAutoFocus by remember { mutableStateOf(false) }

    val rows = rooms.chunked(columns)
    // 每张卡一份自己的 requester。
    // key 用 identity 而不是下标：列表重排（首页换排序）时 requester 不换、节点不重新 attach，
    // 焦点视觉不会闪一下。但同一个 identity 出现两次时按出现次序加后缀——
    // **一个 FocusRequester 同时挂到两个节点上，requestFocus 落到哪个是未定义的**，
    // 这也是「按一下窜位」的一类来源。
    val slotKeys = remember(rooms) {
        val seen = mutableMapOf<String, Int>()
        rooms.map { room ->
            val n = (seen[room.identity] ?: 0) + 1
            seen[room.identity] = n
            if (n == 1) room.identity else "${room.identity}#$n"
        }
    }
    val tvRows = rows.mapIndexed { rowIndex, rowRooms ->
        rowRooms.mapIndexed { columnIndex, _ ->
            tvMap.getOrPut(slotKeys[rowIndex * columns + columnIndex]) { TvFocus() }
        }
    }

    LaunchedEffect(rooms.map { it.identity }, autoFocus) {
        if (!autoFocus || autoFocusBlocked || didAutoFocus || rooms.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        val remembered = if (memoKey != null) memo?.get(memoKey) else null
        val index = rooms.indexOfFirst { it.identity == remembered }.coerceAtLeast(0)
        val tv = tvRows.getOrNull(index / columns)?.getOrNull(index % columns) ?: return@LaunchedEffect
        tv.requester.tryRequestFocus()
        didAutoFocus = true
    }

    Column(modifier) {
        rows.forEachIndexed { rowIndex, rowRooms ->
            if (rowIndex > 0) Spacer(Modifier.height(gapY))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gapX)
            ) {
                rowRooms.forEachIndexed { columnIndex, room ->
                    val tv = tvRows[rowIndex][columnIndex]
                    Box(Modifier.weight(1f)) {
                        LiveCard(
                            room = room,
                            mode = mode,
                            live = live,
                            onClick = { onClick(room) },
                            modifier = Modifier
                                .fillMaxWidth()
                                // 四向落点挂在**卡片自己**的链上。挂容器上收不到方向键（见上面注释），
                                // 这是这一轮最贵的一条教训：机制挂错层级时，「看起来能走」和「真的能走」
                                // 长得一模一样——只有逐列抓 `focused="true"` 的矩形才能区分。
                                .dpadTo(
                                    Key.DirectionUp,
                                    tvRows.getOrNull(rowIndex - 1)?.getOrNull(columnIndex)?.requester
                                        ?: if (rowIndex == 0) upFromFirstRow else null
                                )
                                .dpadTo(
                                    Key.DirectionDown,
                                    tvRows.getOrNull(rowIndex + 1)?.getOrNull(columnIndex)?.requester
                                )
                                .dpadTo(
                                    Key.DirectionLeft,
                                    tvRows.getOrNull(rowIndex)?.getOrNull(columnIndex - 1)?.requester
                                )
                                .dpadTo(
                                    Key.DirectionRight,
                                    tvRows.getOrNull(rowIndex)?.getOrNull(columnIndex + 1)?.requester
                                ),
                            tv = tv,
                            onFocused = {
                                if (memoKey != null && memo != null) memo[memoKey] = room.identity
                            }
                        )
                    }
                }
                repeat(columns - rowRooms.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
