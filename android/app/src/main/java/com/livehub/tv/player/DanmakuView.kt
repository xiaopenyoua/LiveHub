package com.livehub.tv.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.max

/**
 * 弹幕画布。
 *
 * 为什么不用 Compose 的 Text 列表：一条弹幕在屏幕上存活 7 秒，热门房间每秒 20+ 条，
 * 一屏同时 60–100 条在动。Compose 的每帧重组 + 布局在这种量级下会掉帧，
 * 而 Canvas 只需要一次 drawText，电视盒子的 GPU 吃得住。
 *
 * 轨道分配用「本轨道最后一条的右边缘」判断：同一轨道速度恒定，因此只要保证
 * 后一条入场时前一条已经完全进入画面且留出间距，就不会追尾。
 */
class DanmakuView(context: Context) : View(context) {

    private data class Item(
        val text: String,
        val color: Int,
        var x: Float,
        val y: Float,
        val width: Float,
        val lane: Int
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = designPx(40f)
        setShadowLayer(6f, 0f, 2f, Color.argb(200, 0, 0, 0))
    }

    private val items = ArrayList<Item>(128)
    private var laneEnd = FloatArray(0)
    private var lastFrameNanos = 0L
    private var running = false

    /**
     * 像素/秒。设计规范第十一节：24 秒穿屏——大屏比手机慢得多，
     * 因为 3 米外读完一句话的时间和 30cm 外不是一个量级。
     */
    private var speedPxPerSec = 80f
    private var laneHeight = 0f

    /** 同屏上限与占屏比例可配（设置页「同屏密度」「显示区域」），默认 12 条 / 上 1/3 */
    private var maxOnScreen = 12
    private var areaFraction = 1f / 3f
    private var alphaFactor = 0.85f

    fun setDanmakuSize(spSize: Int) {
        paint.textSize = designPx(spSize.toFloat())
        laneHeight = paint.textSize * 1.55f
        recomputeLanes()
        invalidate()
    }

    /** 密度与区域。areaPercent 取 20-100，超范围夹住 */
    fun setLimits(maxOnScreen: Int, areaPercent: Int) {
        this.maxOnScreen = maxOnScreen.coerceIn(4, 30)
        this.areaFraction = (areaPercent.coerceIn(20, 100)) / 100f
        recomputeLanes()
        invalidate()
    }

    fun setDanmakuAlpha(alpha: Float) {
        alphaFactor = alpha.coerceIn(0.2f, 1f)
        invalidate()
    }

    fun push(text: String, color: Long) {
        if (text.isBlank() || width == 0) return
        // 同屏上限 12 条：超出直接丢，不排队。排队会让弹幕延迟越滚越大，
        // 直播场景里「晚 30 秒看到一句话」比「看不到这句话」更糟。
        if (items.size >= maxOnScreen) return
        val w = paint.measureText(text)
        val lane = pickLane(w)
        if (lane < 0) return // 轨道全满就丢掉这一条，宁可少一条也不叠字
        val c = if (color == 0L) Color.WHITE else color.toInt()
        items.add(
            Item(
                text = text,
                color = c,
                x = width.toFloat(),
                y = lane * laneHeight + paint.textSize,
                width = w,
                lane = lane
            )
        )
        laneEnd[lane] = width + w
        ensureRunning()
    }

    fun clear() {
        items.clear()
        laneEnd.fill(-1f)
        invalidate()
    }

    fun setPaused(paused: Boolean) {
        if (paused) {
            running = false
        } else {
            ensureRunning()
        }
    }

    private fun pickLane(textWidth: Float): Int {
        if (laneEnd.isEmpty()) recomputeLanes()
        // 优先找「已经完全空出来」的轨道：上一条的右边缘已经离开右边界一段距离
        var best = -1
        var bestGap = 0f
        for (i in laneEnd.indices) {
            val gap = width - laneEnd[i]
            if (gap > max(textWidth * 0.35f, designPx(48f)) && gap > bestGap) {
                best = i
                bestGap = gap
            }
        }
        return best
    }

    private fun recomputeLanes() {
        if (height == 0 || laneHeight <= 0f) return
        // 默认只占上 1/3 屏：不遮主播面部与比分。设置页「显示区域」可改
        val usable = height * areaFraction
        val count = max(1, (usable / laneHeight).toInt())
        val old = laneEnd
        laneEnd = FloatArray(count) { i -> if (i < old.size) old[i] else -1f }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeLanes()
        // 速度按宽度算，保证「24 秒横穿一屏」是恒定的观感，不随分辨率变化
        speedPxPerSec = w / 24f
    }

    private fun ensureRunning() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) {
            running = false
            return
        }
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now

        val a = (255 * alphaFactor).toInt().coerceIn(0, 255)
        val it = items.iterator()
        while (it.hasNext()) {
            val item = it.next()
            item.x -= speedPxPerSec * dt
            if (item.x + item.width < 0f) {
                it.remove()
                continue
            }
            paint.color = item.color
            paint.alpha = a
            canvas.drawText(item.text, item.x, item.y, paint)
        }
        if (items.isNotEmpty()) postInvalidateOnAnimation() else running = false
    }

    /**
     * 设计稿 1920 宽画布 → 真实像素。
     *
     * DanmakuView 是原生 View，拿不到 Compose 里被 /2 的 density，所以自己算：
     * 字号与间距在设计稿里就是 px（40 = 40px @1920），直接按宽度比例放大即可。
     * 用 sp()/dp() 会得到 2 倍大小——1080p 电视的 density 是 2.0，而设计基准是 1.0。
     */
    private fun designPx(v: Float): Float =
        v * (resources.displayMetrics.widthPixels / 1920f)
}
