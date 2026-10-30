package com.livehub.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * 设计令牌，全部来自 design/v1.0.0/ui.html 的 :root。
 *
 * **坐标系约定（重要，别改）**：
 * 设计稿是 1920×1080 的**像素**画布，而 Android TV 的 dp 基准是 960×540
 * （1080p 电视通常报 density=320，即 1dp=2px）。如果直接把设计稿的 px 当 dp 写，
 * 整屏会放大一倍，导航栏吃掉 1/5 屏幕、主图标题被顶出容器。
 *
 * 所以 [LiveHubTheme] 把 Compose 的 density 统一除以 2，让 **1dp = 1 设计稿像素**。
 * 这个换算在任何分辨率下都成立：720p 电视报 density≈1.33，除以 2 得 0.67，
 * 80dp → 53px = 80×(720/1080)，比例仍然对。
 *
 * 结论：这里所有数字直接照抄设计稿的 px 值，不要自己再换算一次。
 */
object Tokens {
    // 色彩 —— 与 design/v1.0.0/ui.html :root 同名同值
    val Ground = Color(0xFF15161A)         // --ground
    /** 舞台最底一层的暗部，背景光的落点，比 Ground 再压一档 */
    val GroundDeep = Color(0xFF0C0D10)     // --ground-deep
    val Panel = Color(0xFF1B1C20)           // --panel
    val Raised = Color(0xFF232428)          // --raised
    val Raised2 = Color(0xFF2C2D32)         // --raised-2
    val Text = Color(0xFFFFFDFA)            // --text：纯白在暖金背景上会发青，带一点点暖
    val Muted = Color(0xFFC3C4C9)           // --muted
    val Faint = Color(0xFF9C9DA4)           // --faint
    val Live = Color(0xFFFF453A)            // --live

    // 暖金光源：全屏只有这一盏，背景光、材质顶光、焦点高光都从它来
    val Glow = Color(0xFFD8A65C)            // --glow: 216,166,92
    val GlowLit = Color(0xFFFFE9C4)         // --glow-lit: 255,233,196
    /** 大面积平铺的白雾，给非焦点面一点点存在感，不改色相 */
    val Wash = Color(0x0EFFFFFF)            // rgba(255,255,255,.055)
    /** 玻璃体的底色：不是白，是偏冷的黑，压在封面上时不发灰 */
    val GlassTint = Color(0x380B0C0F)       // rgba(11,12,15,.22)

    // 玻璃材质（旧 token，别的屏还在引用，保留不删）
    val Glass = Color(0x21FFFFFF)          // rgba(255,255,255,.13)
    val GlassFocused = Color(0x33FFFFFF)
    val Specular = Color(0xF9FFFFFF)       // 顶沿高光
    val InnerDark = Color(0x3D000000)

    // 表面亮度档位（--a）。设计稿里所有面只有亮度差异，没有边框、没有描边。
    /** 列表行 / 静止行：几乎只是「有个面」 */
    const val A_ROW = 0.028f
    /** 静置玻璃面：卡片底座、面板 */
    const val A_STATIC = 0.045f
    /** 压在媒体上的面：正文要穿过它，必须更透 */
    const val A_ON_MEDIA = 0.10f
    /** 焦点面 */
    const val A_FOCUS = 0.175f
    /** 压在媒体上的焦点面 */
    const val A_FOCUS_MEDIA = 0.26f
    /** 导航药丸：全场最亮的一块，视线锚点 */
    const val A_NAV_PILL = 0.34f
    /** 按钮静置 */
    const val A_BTN = 0.245f
    /** 按钮焦点 */
    const val A_BTN_FOCUS = 0.34f

    // 尺寸
    val Gutter = 80.dp
    val Safe = 60.dp
    val NavHeight = 116.dp
    val CardGap = 24.dp
    val ShelfGap = 50.dp
    val CardRadius = 20.dp
    val SheetRadius = 28.dp

    // 卡网：1920 下 4 列
    const val GRID_COLUMNS = 4

    // 动效（毫秒）
    const val DUR_FOCUS = 200
    const val DUR_PRESS = 120
    const val DUR_SWITCH = 180
}

/**
 * 字号阶梯。数字是**设计稿像素**（1920 宽画布），由 [LiveHubTheme] 的 density
 * 换算成真实像素，所以不要在这里乘 2 或除 2。
 */
data class TypeScale(
    val hero: TextStyle = TextStyle(fontSize = 76.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
    val display: TextStyle = TextStyle(fontSize = 120.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp),
    val h1: TextStyle = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.SemiBold),
    val h2: TextStyle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Medium),
    val title: TextStyle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium),
    /** 卡片标题：22/600，行高 1.28，负字距 -.01em —— 压在封面下沿，比 26 更收 */
    val cardTitle: TextStyle = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp,
        letterSpacing = (-0.22).sp
    ),
    val body: TextStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Normal),
    val meta: TextStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal),
    val tab: TextStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Medium),
    val small: TextStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Normal),
    /** 卡片副文本单独一档：26 配 22 在 3 米外会糊成一片，18 才拉出层级 */
    val cardMeta: TextStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal)
)

val LocalType = staticCompositionLocalOf { TypeScale() }
val LocalFontFamily = staticCompositionLocalOf<FontFamily?> { null }

@Composable
fun LiveHubTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Tokens.Text,
        onPrimary = Color.Black,
        background = Tokens.Ground,
        onBackground = Tokens.Text,
        surface = Tokens.Panel,
        onSurface = Tokens.Text,
        surfaceVariant = Tokens.Raised,
        onSurfaceVariant = Tokens.Muted
    )
    // 设计稿 1920×1080px ≡ TV 的 960×540dp，所以 density 除以 2，1dp = 1 设计稿 px。
    // fontScale 保留系统值，用户在系统里放大字号仍然生效。
    val base = LocalDensity.current
    val design = Density(density = base.density / 2f, fontScale = base.fontScale)

    CompositionLocalProvider(LocalDensity provides design) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(
                LocalType provides TypeScale(),
                LocalFontFamily provides null
            ) {
                content()
            }
        }
    }
}

/** 便捷访问器：`LiveHubType.title` */
val LiveHubType: TypeScale
    @Composable get() = LocalType.current
