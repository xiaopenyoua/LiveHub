package com.livehub.tv.ui

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livehub.tv.R
import kotlinx.coroutines.delay

/**
 * 应用内开屏：**整屏 `#1A140F` + 一行字标**，首帧即定稿。
 *
 * 规格唯一来源：`design/v1.0.0/splash.html`（17:57）+ `design/v1.0.0/spec/07-字标图标与开屏规格.md` v1.5。
 * （`09-开屏动效-v2.md` 与 `11-开屏帧取证复核.md` 两份曾计划但从未落地，2026-10-30 起不再引用：
 * v2 的动效裁决就以本文件头上说的那份 `splash.html` 终稿为准。）
 * 取证的 7 帧（`design/v1.0.0/shots/`）就是这个 html 的产物：整屏 `#1A140F` 四点逐通道相等、`#231C16` 全帧 0 次、
 * cap 顶 426.499 / 基线 653.501 / 红墨左缘 972.717。
 *
 * ## 为什么是「零动效」而不是省事
 * 屏上唯一可见的东西是一行字标，「能动什么」只剩一个答案——只能动字。而字**不许动**：
 * 品牌字标只有一个正确形态，动画经过的每个中间态都是错的品牌，**没有任何一帧可验收**。
 * 同一个标志在静态就已经有两个都能自圆其说的分界读数（advance 边界 953.91 / 红墨左缘
 * 972.79，差 18.88px = `H` 的左副距）；一旦让它缩放或位移，红墨边每帧落在不同的 x 上。
 * 所以这里**没有入场**：P0 那一帧最亮的对象（字标）已经在终值。
 *
 * ## 会发生的只有两件事（时间刻度见 09 §4/§6）
 * ① **退场只淡墨** 200ms，`cubic-bezier(.4,0,1,1)`；墨归零那一帧整层销毁（= [onDone]）。
 *    底与「版面框」全程不动 —— 版面框**本来就没有填充**，所以撤层时画面零变化，
 *    「卡片不淡」不是一条要执行的纪律，是一个不需要执行的事实。
 * ② **1500ms 到点仍无结论**才出那一行（[failText]），80ms alpha，`#FFFDFA` 满值、
 *    22px/400、不加粗、不用灰 —— 用灰会让 `allfail` 那一帧同时出现第 5、第 6 个颜色，
 *    把「四色封闭」这条判据当场打成假失败。
 *
 * ## 最短驻留 400ms：防的是「闪一下」
 * 结论 80ms 就回来时若照着撤，观众看到的是一整屏闪了一下——整屏画面短于 ~400ms
 * 不读作一幅画面，读作一次闪烁，而闪烁恰好落在启动第一秒，最容易被解释成故障。
 * 白等 320ms 买「没闪」，比省它值。
 *
 * ## 调用约定（宿主必须遵守的两条，**不在本文件里实现**）
 * 1. 本层**自己**在淡墨结束那一帧调 [onDone]（= A = T+200）。不要在别处再挂一个
 *    `delay(2000)` 之类的猜测——A 是由 T 算出来的，不是猜出来的。
 * 2. 顶栏那枚 30px 字标**必须等 A 之后再空 200ms** 才起（T+400 起、80ms 到 1）。
 *    v2 是「同形同色两份拷贝」，尺寸那层保护没有了，零交叠**独木难支**：两个同形刺激的
 *    间隔在 200ms 以内，视觉系统会自动连成同一个物体的运动轨迹，看到的仍然是
 *    「那个大标志缩小飞到了左上角」。这 200ms 是唯一那道闸，不是补充约束。
 *    实施纪律：只挂 `graphicsLayer{alpha}`、**归零不摘除**——摘除会把该槽宽度归零，
 *    页签整块左跳一整槽（30px 字标 advance 3.63em = **108.9px**），那不是淡入是硬切。
 *    `if (alpha > 0f) { Wordmark() }` / `AnimatedVisibility` / `Crossfade` 是同一个错的三种写法。
 *
 * ## 底色为什么不跟设计稿的 `#000000`
 * `#000000` 是**第 5 个颜色值**（`08` §6 四色封闭：第 5 个即违规），而且它与 `#1A140F`
 * 差 ΔL\* 6.81 / Weber 自 0 起算 100% —— 撤层那一帧会看见整屏变亮，正是「开屏已经
 * 销毁、屏幕却又变了一次色」这种最难查的现场。开屏底与应用内底同为 `#1A140F`，
 * 只跟**系统帧** `#231C16` 差 ΔL\* 4.08，那次落差全案只允许出现一次。
 *
 * @param settled 取流层已给出可用结论（`Loaded`）。开屏的 T 就是这一刻；false 时屏上什么都不出。
 * @param failText 1500ms 到点仍无结论时的文案；**null = 正常路径，这一行永不出现**。
 *   文案由宿主给（`03` §6：系统网络决定「电视没有连上网」还是「四家平台都没连上」），
 *   本文件不硬编码任何一句——写死一句就等于在数据层之外立了第二个真相。
 * @param onDone 墨归零那一帧。宿主在这里撤层。
 */
@Composable
fun SplashScreen(
    settled: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    failText: String? = null
) {
    val context = LocalContext.current

    // 动画时长缩放 = 0 时按 09 §9 把「过程」压到 1 帧（16ms），但**间隔与顺序不压**：
    // 开屏的 400ms 驻留是信息时长，不是动画时长，它一个字都不变。
    val animScale = remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f)
    }
    val fadeMs = if (animScale == 0f) REDUCED_FADE_MS else SPLASH_FADE_MS
    val failInMs = if (animScale == 0f) REDUCED_FADE_MS else SPLASH_FAIL_IN_MS

    val startedAt = remember { SystemClock.elapsedRealtime() }
    val ink = remember { Animatable(1f) }
    val failInk = remember { Animatable(0f) }
    var failing by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    val latestOnDone by rememberUpdatedState(onDone)

    // ① 结论已到 → 补满最短驻留 → 淡墨 200ms → 墨归零那一帧交还宿主
    LaunchedEffect(settled) {
        if (!settled || done) return@LaunchedEffect
        val waited = SystemClock.elapsedRealtime() - startedAt
        delay((SPLASH_MIN_DWELL_MS - waited).coerceAtLeast(0L))
        ink.animateTo(0f, tween(fadeMs, easing = SplashFadeOut))
        done = true
        latestOnDone()
    }

    // ② 到点仍无结论 → 出那一行，**不退出**（09 §4「失败」行：不退出，出异常行）
    LaunchedEffect(settled, failText) {
        if (settled || failText == null) return@LaunchedEffect
        val waited = SystemClock.elapsedRealtime() - startedAt
        delay((SPLASH_FAIL_AT_MS - waited).coerceAtLeast(0L))
        failing = true
        failInk.animateTo(1f, tween(failInMs))
    }

    Box(modifier.fillMaxSize().background(SplashGround)) {
        // 设计画布是 1920×1080 的**像素**画布，而 LiveHubTheme 已经把 density 除以 2
        // （1dp = 1 设计稿 px），所以 16:9 上 s 恒等于 1：下面的 dp 数就是设计稿的 px 数。
        // 取 min 是为了 16:10 这类调试屏不裁边——与设计稿演示外壳的
        // `Math.min(W/1920, H/1080)` 同一口径（`?canvas=1` 那种按高裁宽的做法只给演示用）。
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val s = minOf(maxWidth / DESIGN_W, maxHeight / DESIGN_H)
            Box(Modifier.width(DESIGN_W * s).height(DESIGN_H * s)) {
                Image(
                    painter = painterResource(R.drawable.splash_wordmark),
                    contentDescription = stringResource(R.string.app_name),
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxSize()
                        // 恒在合成层：对应设计稿的 `will-change:opacity`。这不是优化，是
                        // **判据要求**——静止帧与退场第一帧必须逐像素一致，否则「t=0」那张
                        // 取证帧和「静止」那张会差一点抗锯齿，md5 立刻不同。
                        // alpha 归零也**不摘除**：摘除会改布局，这里要改的只有不透明度。
                        .graphicsLayer { alpha = ink.value }
                )

                if (failing && failText != null) {
                    // 位置照抄 splash.html: `.fail{top:1001px;width:1920px;text-align:center}`
                    // —— 版面框下沿 945，余下 135 是留给这一行与呼吸的，22px 基线落在 1020。
                    Text(
                        text = failText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = FAIL_TOP * s)
                            .graphicsLayer { alpha = failInk.value },
                        color = SplashInk,
                        fontSize = 22.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Normal,
                        fontFamily = FontFamily.Default,
                        letterSpacing = 0.22.sp, // .01em @ 22px
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ---------- 常量：每一个都在 09 / splash.html 里有出处，别在这里「顺手改好看点」 ----------

/** 开屏底。与应用内底同值，只跟系统帧 `#231C16` 差 ΔL\* 4.076（08 §4，全案只出现一次）。 */
private val SplashGround = Color(0xFF1A140F)

/** 品牌白 = `--live`。异常行也用它（满值，不用灰、不用任何 alpha）。 */
private val SplashInk = Color(0xFFFFFDFA)

/**
 * 退场曲线 `--out: cubic-bezier(.4,0,1,1)`。
 *
 * 为什么不复用 `Ease.Focus`（`0.32,0.72,0,1`）：那是**焦点进出的曲线**，起手就快；
 * 这条是「一次性消隐」，`.4,0,1,1` 起手慢、尾巴长，是 `09` §6 给墨指定的那一条。
 * 也不是 `Ease.Switch`（`0.4,0,0.2,1`）——中间那个 `0.2` 会在中段加速，淡出一整行
 * 18:1 的墨时读作「抽走」而不是「退去」。**曲线是判据的一部分**：`09` §3 那个
 * alpha 0.672489 反解出 x=0.5024、与 500ms 采集差 0.5ms，就是拿这条曲线算的。
 */
private val SplashFadeOut = CubicBezierEasing(0.4f, 0f, 1f, 1f)

/** 设计画布。1920×1080 px，经 LiveHubTheme 的 density÷2 之后正好是 1920×1080 dp。 */
private val DESIGN_W = 1920.dp
private val DESIGN_H = 1080.dp

/** 异常行顶边（`splash.html` `.fail{top:1001px}`），不在版面框 135..945 之内，不抢字标。 */
private val FAIL_TOP = 1001.dp

/** 最短驻留。`09` §5 已裁不再议：静止不是代价，闪才是代价。 */
private const val SPLASH_MIN_DWELL_MS = 400L

/** 淡墨 200ms（`--dE`）。**A = T + 200**，本层在这一帧交出控制权。 */
private const val SPLASH_FADE_MS = 200

/** `09` §4/§7 默认刻度：site 层 1500ms 超时且无可用结论才出那一行。 */
private const val SPLASH_FAIL_AT_MS = 1500L

/** 异常行出现 80ms（`--dF`）：一次出现不成为一次闪烁的下限。 */
private const val SPLASH_FAIL_IN_MS = 80

/** reduced-motion 下「过程压到 1 帧」，但间隔与顺序不压（`09` §9）。 */
private const val REDUCED_FADE_MS = 16
