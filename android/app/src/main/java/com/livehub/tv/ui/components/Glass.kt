package com.livehub.tv.ui.components

import android.os.Build
import android.util.Log
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livehub.tv.core.device.DeviceProfile
import com.livehub.tv.ui.theme.LiveHubType
import com.livehub.tv.ui.theme.Tokens
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 设备档位的**唯一出口**：所有降级常量都收在这里，判据在 `core/device/DeviceProfile`。
 *
 * 这一层不再自己算 `SDK_INT` / `maxMemory()`。之前那份是同一套判据的第二份拷贝，
 * 两份判据必然会漂移（比如 core 那边以后加上 `isTv`，UI 这边就永远不知道）。
 * 现在这里只回答「A 档时 UI 该长什么样」，不再回答「这台机器是不是 A 档」。
 *
 * A 档 = 老盒子/低内存。这些机器上「1.08 缩放 + 渐变高光」每一帧都要重新合成图层，
 * 焦点移动会变成掉帧的手感——只留「变亮 + 高光」反而跟手。
 */
object Tier {
    /** 判据来自 core，这里只读不判 */
    val low: Boolean get() = DeviceProfile.isLowEnd

    /** 焦点动效时长：A 档 120ms，其余 200ms */
    val focusAnimMs: Int get() = if (low) 120 else 200

    /** 要不要缩放与按压回弹：A 档关掉。放大是「加号」，明度与高光才是主干 */
    val scaleEnabled: Boolean get() = !low

    /** 卡片聚焦放大倍数（A 档无效） */
    const val CARD_SCALE = 1.08f
    const val BUTTON_SCALE = 1.06f
    const val ROW_SCALE = 1.02f
}

/**
 * 动效参数，全部来自 design/v1.0.0/ui.html 的 :root 与 design/system/设计规范.md 第七章。
 *
 * 焦点是「一块面被举起来」：起步极快、末端吸附式收住（.32,.72,0,1），200ms。
 * 开关是「一个点滑过去」：起步必须能被看见，所以另用一条曲线（.4,0,.2,1），180ms。
 * 两者不能共用曲线——焦点曲线给 38px 的位移用会变成一记弹射。
 */
object Ease {
    val Focus: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    val Switch: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** 由 [Tier] 统一给出，A 档 120ms */
    val DUR_FOCUS: Int get() = Tier.focusAnimMs
    const val DUR_PRESS = 120
    const val DUR_PILL = 260
    const val DUR_SWITCH = 180
    const val DUR_SWITCH_COLOR = 120
    const val DELAY_SWITCH_COLOR = 55
    const val DUR_SCREEN = 240
}

/** 全圆胶囊。999px 在 Compose 里就是「比半径大」的圆角，统一收在这里。 */
val PillShape = RoundedCornerShape(percent = 50)

/**
 * 舞台三层光的尺寸，由 `AppRoot` 挂在根节点上报进来。
 *
 * 为什么玻璃面需要知道「整个舞台多大」：设计稿那段材质靠
 * `backdrop-filter: blur(18px) saturate(150%)` 把背后的暖光揉散透上来。而
 * **`RenderEffect` 是 API 31 才有的**，本项目 `minSdk = 21`，21–30 上根本没有背景模糊。
 * 写一个 `if (SDK_INT >= 31)` 再给 else 凑一套，就等于同时维护两套材质——
 * 正是设计稿注释里在骂的那件事。
 *
 * 好在这层模糊在我们这里**本来就不需要**：玻璃背后没有别人的东西，只有我们自己画的
 * 三层径向渐变，而模糊一段平滑渐变的结果几乎就是它自己。所以「透上来」用
 * **把同一段渐变在玻璃形状内再画一遍**得到，API 21 到 36 是同一套代码，没有分支。
 *
 * 一个 [StageBackdrop] 实例全程复用（只在尺寸变化时改 `size`），所以 provide 下去
 * 不会触发整棵子树重组；玻璃面是在**绘制阶段**读它，只让绘制失效。
 */
@Stable
class StageBackdrop {
    var size by mutableStateOf(Size.Zero)
}

val LocalStageBackdrop = staticCompositionLocalOf<StageBackdrop?> { null }


/**
 * 一个元素的焦点与按压状态。
 *
 * 不用 tv-material 的 Surface/Button：它们的交互色板固定，而设计稿要求
 * 焦点 = 放大 1.08 + 表面高光提亮 + 亮度提升，按压 = scale(.96) 且必须与焦点
 * 共用同一条过渡（只把时长压短），这些只能自己拿 interactionSource 做。
 */
@Stable
class TvFocus(
    /**
     * 默认自己造一个。允许外部传进来是为了「跨文件钉落点」：
     * 顶栏页签的 requester 由 `AppNav` 持有，各屏才能把自己的 UP 明确指向当前页签，
     * 而不是指望几何搜索在 116dp 顶栏 + 1.08 缩放 + 一行行距 36dp 的网格里算对。
     */
    val requester: FocusRequester = FocusRequester()
) {
    /** 当前是否持有焦点 */
    var focused by mutableStateOf(false)

    /** 当前是否按下（遥控器 OK / 触摸） */
    var pressed by mutableStateOf(false)

    val source = MutableInteractionSource()
}

@Composable
fun rememberTvFocus(ownedRequester: FocusRequester? = null): TvFocus {
    val tv = remember { TvFocus(ownedRequester ?: FocusRequester()) }
    val pressed by tv.source.collectIsPressedAsState()
    SideEffect { tv.pressed = pressed }
    return tv
}

/**
 * 可聚焦 + 可点击。
 *
 * 焦点不画边框（tvOS 的语言）：一切视觉反馈由调用方用 [focusScale] 与
 * [GlassBox] 的高光表达。这里只负责把状态交出去。
 */
fun Modifier.tvClickable(
    tv: TvFocus,
    enabled: Boolean = true,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit
): Modifier = this
    .focusRequester(tv.requester)
    .onFocusChanged {
        tv.focused = it.isFocused
        onFocusChange?.invoke(it.isFocused)
    }
    .clickable(
        interactionSource = tv.source,
        indication = null,
        enabled = enabled,
        role = Role.Button,
        onClick = onClick
    )

/**
 * 请求焦点，成功返回 true。
 *
 * `FocusRequester.requestFocus()` 是 `void`，且在这个版本里**唯一**的失败信号就是抛异常：
 * requester 没有 attach 到任何节点时抛 `IllegalStateException`。
 * 对用户来说「目标不存在」和「抛异常」是同一件事——焦点没动，所以这里统一折成 false，
 * 让调用方据此**把按键交回系统**，而不是自己吞掉。
 *
 * 失败时留一行 `LiveHubFocus` 日志：悬空 requester 原来是这类 bug 里最难查的一种
 * ——静默吞键、不抛到界面、截图里也看不出来。留着这行，下次真出问题时
 * `adb logcat -s LiveHubFocus` 就能直接看到是**哪个方向**的落点没 attach。
 */
fun FocusRequester.tryRequestFocus(): Boolean {
    val result = runCatching { requestFocus() }
    result.exceptionOrNull()?.let {
        Log.w("LiveHubFocus", "方向键落点没 attach 到节点，按键已交回系统：${it.message}")
    }
    return result.isSuccess
}

/**
 * 方向键落点：**捕获阶段直接 requestFocus，成功才吞键**。
 *
 * 为什么不用 `focusProperties { right = requester }`（原来那版 `navTargets`）：
 * 那条路在目标 requester 没 attach 时，`moveFocus` 直接返回 false，按键**静默消失**——
 * 焦点原地不动，不抛异常、不打日志。遥控器上的表现就是「这个方向坏了」，
 * 而在截图、录屏里完全看不出来，只能靠 uiautomator 的 `focused="true"` 逐一比对才发现。
 *
 * 这里走捕获阶段，[tryRequestFocus] 的结果就是真相：成了吃掉按键，没成就当没处理过，
 * 把按键原样放回去给系统做几何搜索。**最坏情况是退化成默认行为，不会出现「按了没反应」。**
 */
fun Modifier.dpadTo(key: Key, target: FocusRequester?): Modifier {
    if (target == null) return this
    return this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        if (event.key != key) return@onPreviewKeyEvent false
        target.tryRequestFocus()
    }
}

/** 往上回落到指定节点。传 null 就是**什么都不设**（交给几何搜索），不是「往上不动」。 */
fun Modifier.upTo(target: FocusRequester?): Modifier = dpadTo(Key.DirectionUp, target)

/** 焦点放大 + 按压回弹。按压必须比焦点快（120ms），否则按下去像没反应。 */
@Composable
fun focusScale(tv: TvFocus, focusedScale: Float = Tier.BUTTON_SCALE): Float {
    // A 档不缩放：合成 1.x 图层在 1GB 盒子上是焦点移动掉帧的主因。
    // 缩放是「加号」，明度与高光才是主干——主干在，焦点依然一眼看得见。
    if (!Tier.scaleEnabled) return 1f
    val focus by animateFloatAsState(
        targetValue = if (tv.focused) focusedScale else 1f,
        animationSpec = tween(Ease.DUR_FOCUS, easing = Ease.Focus),
        label = "focusScale"
    )
    val press by animateFloatAsState(
        targetValue = if (tv.pressed) 0.96f else 1f,
        animationSpec = tween(Ease.DUR_PRESS, easing = Ease.Focus),
        label = "pressScale"
    )
    return focus * press
}

/**
 * Liquid Glass 表面 —— **全项目唯一的一段材质公式**，与 design/v1.0.0/ui.html 里
 * `.srow,.btn,.pbtn,.seg,…` 那条规则一一对应。
 *
 * 调用方只声明一件事：这块面有多「厚」，即 [bodyAlpha]（设计稿的 `--a`）。
 * 静止 `.045`、压媒体 `.10`、焦点 `.175`、压媒体的焦点 `.26`、导航药丸 `.34`。
 * 换色不在这里做 —— 焦点是「同一块玻璃被灯照到了」，不是「换了一块」。
 *
 * 三层，各管一件事（详见 [glass]）：
 *  - ① 暖金顶光：横贯全宽，34px 走完，停靠点是**绝对像素**
 *  - ② 中性体：16px 之内落到 a，之后一路平到底，**底边不压暗**
 *  - ③ 1px 边光，由 a 推导（0.7a + 0.045）
 *
 * 体亮度走 200ms 同一条焦点曲线：色带只能硬切，插值出来的才是「亮起来」。
 */
@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    radius: Dp = Tokens.CardRadius,
    bodyAlpha: Float = Tokens.A_STATIC,
    /**
     * 要不要把「背后的舞台光」在玻璃形状内重画一遍（材质稿的 backdrop-filter 等价物）。
     *
     * 默认 true：本项目绝大多数玻璃面都压在舞台那层径向光上。**压在封面图 / 视频 /
     * 遮罩上的面必须传 false**——那背后不是我们的光，重画一遍就是在没有光的地方
     * 画一层暖调，属于「看着对、原因是错的」。
     * A 档由 [stageGlow] 内部跳过，不需要调用方判断。
     */
    backdrop: Boolean = true,
    /**
     * **过渡期兼容，下一批四屏（设置/搜索/播放/弹幕）迁移时删掉这四个参数。**
     *
     * 还没迁过来的调用点仍在按旧口径传颜色（`SearchScreen:339/521`、
     * `SettingsScreen:262`、`Bits.kt:341/396`）。给了颜色就按旧的一对一做线性插值折成
     * 体亮度，并**忽略** [bodyAlpha] —— 这样这批文件不在本轮改动范围内也能编过，
     * 而且拿到的是新公式，不会出现「新老两套材质同时上屏」。
     */
    baseColor: Color? = null,
    focusedColor: Color? = null,
    focused: Boolean = false,
    solid: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val target = if (baseColor != null || focusedColor != null) {
        val rest = baseColor ?: Tokens.Glass
        val hot = focusedColor ?: rest
        val raw = lerp(rest, hot, if (focused) 1f else 0f).alpha
        if (solid) raw.coerceAtLeast(Tokens.A_BTN_FOCUS) else raw
    } else {
        bodyAlpha
    }
    val a by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(Ease.DUR_FOCUS, easing = Ease.Focus),
        label = "glassBody"
    )
    val stage = LocalStageBackdrop.current
    // 画布是自己这一块，舞台光是按整个屏幕算的，所以要减掉自己在根节点里的位置。
    // 只在绘制阶段读（不在组合里读），位置变了只让绘制失效，不触发重组。
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(RoundedCornerShape(radius))
            .background(Tokens.GlassTint)
            .drawBehind {
                if (backdrop) {
                    val s = stage?.size
                    if (s != null && s.width > 0f) {
                        translate(-origin.x, -origin.y) { stageGlow(s.width, s.height) }
                    }
                }
                glass(a, radius.toPx())
            },
        content = content
    )
}

/**
 * 舞台的三层背景光，与 design/v1.0.0/ui.html `.stage` 的 `background:` 四行一一对应。
 *
 * 绘制顺序就是书写顺序（CSS 的 background 是**从后往前**叠的，所以第一行在最上面）：
 * 底 → 主光 → 核部 → 暗角。**四层 alpha 全部在 .11 以下**：这层东西全部在内容区之下，
 * 不参与文字对比度计算，它的工作只是让「纯黑」变成「有一盏灯的黑」。
 *
 * 每层都是**椭圆**（820×560 这种）。Compose 的 radialGradient 只有正圆，
 * **没有用「取长轴当正圆」糊过去**：核部 820×560 取长轴会让它在竖直方向多出 260px，
 * 正好压到主图标题上；主光 1720×1220 同理会吃掉整块下方内容区。所以这里把圆纵向
 * 压成 ry/rx 再画，形状是真的。
 *
 * 这一份实现同时给两处用：根节点铺底，以及玻璃面「把背后再画一遍」（见 [LocalStageBackdrop]）。
 * **只有一份**——两份判据必然会漂移。
 *
 * A 档整层跳过：三块全屏渐变每帧重新光栅化，老盒子上比它带来的观感值钱。
 */
internal fun DrawScope.stageGlow(w: Float, h: Float) {
    if (Tier.low) return
    if (w <= 0f || h <= 0f) return
    val cx = w * 0.5f

    // 主光 1720×1220 at 50% 45%
    ellipseGlow(
        center = Offset(cx, h * 0.45f),
        rx = 1720.dp.toPx(),
        ry = 1220.dp.toPx(),
        w = w,
        h = h,
        stops = arrayOf(
            0f to Tokens.Glow.copy(alpha = 0.082f),
            0.42f to Tokens.Glow.copy(alpha = 0.030f),
            0.70f to Tokens.Glow.copy(alpha = 0f)
        )
    )

    // 核部 820×560 at 50% 41%
    ellipseGlow(
        center = Offset(cx, h * 0.41f),
        rx = 820.dp.toPx(),
        ry = 560.dp.toPx(),
        w = w,
        h = h,
        stops = arrayOf(
            0f to Tokens.Glow.copy(alpha = 0.105f),
            0.68f to Tokens.Glow.copy(alpha = 0f)
        )
    )

    // 暗角 1500×1100 at 50% 46%：中心 40% 之内一点不碰，向外压到 rgba(8,9,12,.74)
    ellipseGlow(
        center = Offset(cx, h * 0.46f),
        rx = 1500.dp.toPx(),
        ry = 1100.dp.toPx(),
        w = w,
        h = h,
        stops = arrayOf(
            0.40f to Color(0xFF08090C).copy(alpha = 0f),
            1f to Color(0xFF08090C).copy(alpha = 0.74f)
        )
    )
}

/** 一枚椭圆径向光：把正圆纵向压成 ry/rx，再按 rx 当半径画。 */
private fun DrawScope.ellipseGlow(
    center: Offset,
    rx: Float,
    ry: Float,
    w: Float,
    h: Float,
    stops: Array<Pair<Float, Color>>
) {
    if (rx <= 0f || ry <= 0f) return
    withTransform({ scale(scaleX = 1f, scaleY = ry / rx, pivot = center) }) {
        drawRect(
            brush = Brush.radialGradient(colorStops = stops, center = center, radius = rx),
            topLeft = Offset(-w, -h),
            size = Size(w * 3f, h * 3f)
        )
    }
}

/**
 * 那唯一的一段材质公式。
 *
 * **停靠点是绝对像素**（0 / 14 / 34px 与 0 / 16px），不是百分比。
 * 百分比会让 1220px 宽的行拿到一片光、150px 宽的胶囊只拿到中间一个点 ——
 * 同一条公式在不同形状上画出两样东西，是这版最想干掉的问题。
 *
 * **底部不加暖反光**：34px 的顶光和 20px 的底光在 60px 的胶囊里会互相盖掉，
 * 中间反而变成最暗的一段，一颗胶囊看起来像「两头亮的沙漏」。那说明光源变成了两个。
 * 光只从上面来，底沿交给那圈 1px 边光。
 *
 * **不用 radial**：每块面各点一盏小灯，一屏就有几十个光源，和「正中只有一盏暖金灯」
 * 直接打架。光源只有一个，面只负责被它照到。
 *
 * @param bodyAlpha 体亮度 a（设计稿 `--a`），决定三层各自的浓度与边光强度
 * @param radiusPx 圆角半径（px），边光要沿圆角走，不能画成方框
 */
private fun DrawScope.glass(bodyAlpha: Float, radiusPx: Float) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val a = bodyAlpha.coerceIn(0f, 1f)
    if (a <= 0.002f) return

    // ① 暖金顶光：0px 满、14px 落到 30%、34px 收干净。横贯全宽。
    val glowEnd = 34.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            0f to Tokens.GlowLit.copy(alpha = a),
            (14.dp.toPx() / glowEnd) to Tokens.GlowLit.copy(alpha = a * 0.30f),
            1f to Tokens.GlowLit.copy(alpha = 0f),
            startY = 0f,
            endY = glowEnd
        )
    )

    // ② 中性体：0px 是 0.95a，16px 落到 a，之后平到底 —— 底边不压暗。
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = a * 0.95f),
            (16.dp.toPx() / h).coerceIn(0f, 1f) to Color.White.copy(alpha = a),
            1f to Color.White.copy(alpha = a),
            startY = 0f,
            endY = h
        )
    )

    // ③ 边光由 a 推导：薄的面边光也薄，不会「行几乎看不见、边框却很清楚」。
    val edge = (a * 0.7f + 0.045f).coerceAtMost(1f)
    val stroke = 1.dp.toPx()
    val half = stroke / 2f
    val r = radiusPx.coerceIn(0f, min(w, h) / 2f)
    drawRoundRect(
        color = Color.White.copy(alpha = edge),
        topLeft = Offset(half, half),
        size = Size((w - stroke).coerceAtLeast(0f), (h - stroke).coerceAtLeast(0f)),
        cornerRadius = CornerRadius((r - half).coerceAtLeast(0f)),
        style = Stroke(width = stroke)
    )
}

/**
 * 胶囊按钮。全项目只有两种：
 *  - 主按钮 [solid] = true：`--a` .245 → 焦点 .34（A_BTN / A_BTN_FOCUS）
 *  - 次按钮 [solid] = false：压在媒体上的面，`--a` .10 → 焦点 .26（A_ON_MEDIA / A_FOCUS_MEDIA）
 *
 * 焦点**不再反色**：老版本聚焦时会填成白底黑字，那等于让按钮在聚焦瞬间变成另一个控件，
 * 用户看到的是「换了一块」而不是「这块被举起来了」。亮度和缩放就够，字始终是 [Tokens.Text]。
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    solid: Boolean = false,
    tv: TvFocus = rememberTvFocus(),
    valueText: String? = null,
    height: Dp = 72.dp,
    horizontalPadding: Dp = 40.dp,
    fontSize: TextUnit = 26.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
    enabled: Boolean = true
) {
    val scale = focusScale(tv, if (solid) 1.07f else 1.06f)
    val focused = tv.focused && enabled
    val restA = if (solid) Tokens.A_BTN else Tokens.A_ON_MEDIA
    val focusA = if (solid) Tokens.A_BTN_FOCUS else Tokens.A_FOCUS_MEDIA
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .tvClickable(tv, enabled = enabled, onClick = onClick)
            .height(height)
    ) {
        GlassBox(
            modifier = Modifier.height(height),
            radius = 999.dp,
            bodyAlpha = if (focused) focusA else restA,
            // 按钮全场唯一一个「背后不确定」的面：首页主图那颗压在**封面图**上，
            // 空态的压在舞台光上，退出确认的压在一层 72% 黑遮罩上。同一个组件三种背后，
            // 所以不重画背景光——在没有光的地方画一层暖调，正是「看着对、原因是错的」。
            backdrop = false
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = horizontalPadding)
                    .height(height),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = text,
                    style = LiveHubType.body.copy(fontSize = fontSize, fontWeight = fontWeight),
                    color = Tokens.Text
                )
                if (valueText != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = valueText,
                        style = LiveHubType.body.copy(fontSize = fontSize, fontWeight = FontWeight.Normal),
                        color = Tokens.Muted
                    )
                }
            }
        }
    }
}

/** 圆形玻璃按钮（播放页返回）。 */
@Composable
fun CircleGlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tv: TvFocus = rememberTvFocus(),
    size: Dp = 64.dp,
    fontSize: TextUnit = 30.sp
) {
    val scale = focusScale(tv, Tier.CARD_SCALE)
    val a by animateFloatAsState(
        targetValue = if (tv.focused) Tokens.A_BTN_FOCUS else Tokens.A_BTN,
        animationSpec = tween(Ease.DUR_FOCUS, easing = Ease.Focus),
        label = "circleBody"
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .tvClickable(tv, onClick = onClick)
            .size(size)
            .clip(CircleShape)
            .background(Tokens.GlassTint)
            .drawBehind { glass(a, this.size.minDimension / 2f) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = LiveHubType.tab.copy(fontSize = fontSize),
            color = Tokens.Text
        )
    }
}

/**
 * 开关（不可聚焦，行本身承担焦点——和设计稿一致：一行就是一块玻璃）。
 *
 * 位移 180ms 走 Material standard，滑块变色延后 55ms 再翻：
 * 先看见它滑过去，再看见颜色反过来，中途不会出现「灰滑块 + 半亮轨道」那一帧。
 */
@Composable
fun SwitchVisual(checked: Boolean, modifier: Modifier = Modifier) {
    val knobX by animateDpAsState(
        targetValue = if (checked) 38.dp else 0.dp,
        animationSpec = tween(Ease.DUR_SWITCH, easing = Ease.Switch),
        label = "switchKnob"
    )
    val on by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(
            Ease.DUR_SWITCH_COLOR,
            delayMillis = Ease.DELAY_SWITCH_COLOR,
            easing = Ease.Focus
        ),
        label = "switchColor"
    )
    Box(
        modifier
            .size(width = 88.dp, height = 50.dp)
            .clip(PillShape)
            .background(Tokens.GlassTint)
            // 轨道 .sw：静止 rgba(255,255,255,.13)，打开时 #fff —— 走同一条材质公式，
            // 只是把「多厚」从 .13 抬到 1.0，而不是换一种填充方式。
            .drawBehind {
                glass(0.13f + (1f - 0.13f) * on, this.size.minDimension / 2f)
            }
    ) {
        Box(
            Modifier
                .offset(x = 4.dp + knobX, y = 4.dp)
                .size(42.dp)
                .clip(CircleShape)
                .background(lerp(Color.White, Tokens.Raised, on))
        )
    }
}

/**
 * 分段控件（不可聚焦）。选中项由一枚会滑动的玻璃药丸表示（`--a` .34，全场只比导航药丸低一档），
 * 而不是给某个选项换底色——换底色就是硬切，眼睛接不住切换。
 */
@Composable
fun SegmentedVisual(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier
) {
    val bounds = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }
    val density = LocalDensity.current
    val targetX = bounds[selectedIndex]?.first?.toFloat() ?: 0f
    val targetW = bounds[selectedIndex]?.second?.toFloat() ?: 0f
    val px by animateFloatAsState(targetX, tween(Ease.DUR_PILL, easing = Ease.Focus), label = "segX")
    val pw by animateFloatAsState(targetW, tween(Ease.DUR_PILL, easing = Ease.Focus), label = "segW")

    Box(modifier) {
        Box(
            Modifier
                .height(52.dp)
                .clip(PillShape)
                .background(Tokens.GlassTint)
                // .seg{--a:.06}
                .drawBehind { glass(0.06f, this.size.minDimension / 2f) }
        ) {
            // 药丸先画、文字后画：药丸在底下，选中的那枚文字才压在亮面上
            if (pw > 0f) {
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                px.roundToInt() + with(density) { 5.dp.roundToPx() },
                                with(density) { 5.dp.roundToPx() }
                            )
                        }
                        .width(with(density) { pw.toDp() })
                        .height(42.dp)
                        .clip(PillShape)
                        .background(Tokens.GlassTint)
                        // .seg__pill{--a:.34}
                        .drawBehind { glass(Tokens.A_NAV_PILL, this.size.minDimension / 2f) }
                )
            }
            Row(
                modifier = Modifier.padding(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                options.forEachIndexed { index, option ->
                    Box(
                        Modifier
                            .onGloballyPositioned {
                                bounds[index] = it.positionInParent().x.roundToInt() to it.size.width
                            }
                            .height(42.dp)
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = option,
                            style = LiveHubType.meta.copy(
                                fontSize = 22.sp,
                                fontWeight = if (index == selectedIndex) FontWeight.SemiBold else FontWeight.Medium
                            ),
                            // .seg b{color:var(--muted)} / .seg b.on{color:var(--text)}
                            color = if (index == selectedIndex) Tokens.Text else Tokens.Muted
                        )
                    }
                }
            }
        }
    }
}
