package com.livehub.tv.core.device

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 设备档位与媒体能力探测。
 *
 * 这个项目要覆盖 API 21 到 34，跨度十年：2014 年的 Nexus Player 和 2024 年的
 * Google TV Streamer 在内存、解码器、GPU 上不是一个物种。同一条代码路径不可能
 * 对两者都最优，所以要有一个**集中的**判据，让降级只写在一处。
 *
 * 判据刻意不用 `Build.MODEL`：机型列表永远不全，而且盒子的 model 字符串经常是
 * 厂商乱填的。用 SDK_INT + 内存 + 是否 TV 设备，这三个在各版本都可靠。
 */
object DeviceProfile {

    private const val TAG = "DeviceProfile"

    /**
     * A 档：老盒子 / 低内存。降级焦点动效、降低弹幕密度、关闭封面淡入、跳过背景光晕。
     *
     * 判据（任一命中即为低配，按「命中即降级」处理）：
     * - `SDK_INT < 26`：Android 7 及以下，Compose 在这类设备上掉帧明显。
     * - 物理内存 ≤ 1200MB：覆盖 512MB 与 1GB 两种盒子（1GB 机型 `totalMem` 实测约 950–1000MB，
     *   不是精确的 1024）。**这里曾经写成 `< 192`，是把「MB」当成了「GB 的百分之一」，
     *   实际永远不成立——一个永远为 false 的降级开关比没有更糟，因为它看起来在工作。**
     * - `ActivityManager.isLowRamDevice`：系统自己的官方低内存标记，比自己数更可信。
     * - 堆上限 ≤ **128MB**：只有真正的 512MB 级设备才会掉到这一档。
     *
     * **为什么把堆判据从 `≤ 192` 收到 `≤ 128`（这是一次真实误判驱动的修改）**：
     * 原来写的是 `heapLimitMb <= 192`，注释里的理由是「1GB 机型给 192MB heap」。那个前提
     * **在现代设备上不成立**——Android 给 App 的堆上限长期停在 192MB，和物理内存早就脱钩：
     * 本轮验收用的 Google TV 模拟器 `mem=1471MB / heap=192MB / sdk=36`，被判成了
     * `lowEnd=true`。后果不是"降级过头"，而是**设计稿最显眼的那一批效果在这台机器上全都不出现**
     * （三层背景光晕、焦点放大 1.08、切屏 scale(.985)），于是"效果没做"和"被开关吃掉"
     * 在屏幕上长得一模一样——这正是上面那条 `false` 开关错误的**镜像形态**：
     * 一个是永远不降级，一个是永远降级。两者都比没有更糟，因为都看起来在工作。
     *
     * [tierOverride] 是给验收用的强制档位：`adb shell settings put global debug_livehub_tier b`
     * 就能在低配机上看到完整效果。没有它，A 档设备上的一切视觉改动都无法被肉眼验证。
     */
    val isLowEnd: Boolean by lazy {
        when (tierOverride) {
            "a" -> true
            "b" -> false
            else -> Build.VERSION.SDK_INT < 26 || isLowRamDevice || totalMemMb <= 1200 || heapLimitMb <= 128
        }
    }

    /**
     * 强制档位：`settings put global debug_livehub_tier a|b`。空/其它值 = 走自动判据。
     *
     * 用 `Settings.Global` 而不是 `SystemProperties`：后者是隐藏 API，在 API 28+ 上属于
     * 灰名单，哪天被移出灰名单就是线上崩溃。`Settings.Global` 全程公开、`adb shell settings`
     * 一行就能改，验收和线上是同一份代码路径。
     */
    private val tierOverride: String? by lazy {
        val ctx = appContext ?: return@lazy null
        runCatching {
            Settings.Global.getString(ctx.contentResolver, "debug_livehub_tier")
        }.getOrNull()?.trim()?.lowercase()?.takeIf { it == "a" || it == "b" }
    }

    /** 系统自己的低内存标记。取不到时按「不低端」，宁可不降级也不要误降 */
    private val isLowRamDevice: Boolean by lazy {
        val ctx = appContext ?: return@lazy false
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice ?: false
    }

    /** 这一档是谁判的。写进日志，避免「效果没做」和「被开关吃掉」混为一谈 */
    private fun lowEndReason(): String = when {
        tierOverride == "a" -> "forced:A"
        tierOverride == "b" -> "forced:B"
        Build.VERSION.SDK_INT < 26 -> "sdk<26"
        isLowRamDevice -> "isLowRamDevice"
        totalMemMb <= 1200 -> "mem<=1200"
        heapLimitMb <= 128 -> "heap<=128"
        else -> "none"
    }

    /** B 档：TV 设备（有遥控器、无触摸）。决定是否显示键位提示 */
    val isTv: Boolean by lazy {
        (appContext?.packageManager?.hasSystemFeature("android.software.leanback") == true) ||
            (appContext?.packageManager?.hasSystemFeature("android.hardware.type.television") == true)
    }

    val sdkInt: Int get() = Build.VERSION.SDK_INT

    /** 总内存 MB。取不到时返回 Int.MAX_VALUE，即「当作不低端」——宁可不降级，也不要误降 */
    val totalMemMb: Int by lazy {
        val ctx = appContext ?: return@lazy Int.MAX_VALUE
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return@lazy Int.MAX_VALUE
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        (info.totalMem / (1024 * 1024)).toInt()
    }

    /** 堆上限 MB。低配机 Android 只给 128–192MB heap，这个值比自己数内存更可靠 */
    val heapLimitMb: Int by lazy {
        (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
    }

    /** 能解的硬解码格式。启动时探测一次，取流时据此降级（如 HEVC 不行就请求 H.264） */
    val hevcDecodable: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder.not() && info.supportedTypes.any {
                    it.equals("video/hevc", ignoreCase = true) ||
                        it.equals("video/h265", ignoreCase = true)
                }
            }
        }.onFailure { Log.w(TAG, "probe hevc failed: ${it.message}") }
            .getOrDefault(false)
    }

    val av1Decodable: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder.not() && info.supportedTypes.any {
                    it.equals("video/av01", ignoreCase = true)
                }
            }
        }.getOrDefault(false)
    }

    /** 弹幕默认密度：低端机少几条，避免丢帧 */
    val defaultDanmakuMax: Int get() = if (isLowEnd) 8 else 12

    // Application.onCreate 里注入，避免每个调用点都传 Context
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        Log.i(
            TAG,
            "sdk=${Build.VERSION.SDK_INT} tv=$isTv lowEnd=$isLowEnd(${lowEndReason()}) " +
                "mem=${totalMemMb}MB heap=${heapLimitMb}MB lowRam=$isLowRamDevice " +
                "hevc=$hevcDecodable av1=$av1Decodable"
        )
    }

    /** 给「关于」页用的一行摘要。不显示内部细节，只说用户能理解的 */
    fun summary(): String = buildString {
        append(if (isTv) "Android TV" else "Android")
        append(" · API ").append(Build.VERSION.SDK_INT)
        if (isLowEnd) append(" · 低配模式")
    }
}
