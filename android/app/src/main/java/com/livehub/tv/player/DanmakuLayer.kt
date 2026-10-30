package com.livehub.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.livehub.tv.core.danmaku.DanmakuEvent
import com.livehub.tv.core.store.Prefs
import kotlinx.coroutines.flow.Flow

/**
 * 弹幕层。把弹幕流接到 Canvas 上，自己不做节流——
 * DanmakuView 在轨道满了以后会主动丢包，丢包发生在绘制侧而不是网络侧，
 * 因为「这条弹幕该不该显示」是视觉判断，只有知道轨道占用的人能决定。
 */
@Composable
fun DanmakuLayer(
    events: Flow<DanmakuEvent>?,
    enabled: Boolean,
    sizeSp: Int,
    alpha: Float,
    modifier: Modifier = Modifier,
    onGift: ((DanmakuEvent.Gift) -> Unit)? = null,
    onSystem: ((String) -> Unit)? = null,
    // 密度与区域来自设置页，默认读 Prefs，调用方不用管
    maxOnScreen: Int = Prefs.danmakuMaxOnScreen,
    areaPercent: Int = Prefs.danmakuAreaPercent
) {
    var view by remember { mutableStateOf<DanmakuView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            DanmakuView(ctx).also {
                it.setDanmakuSize(sizeSp)
                it.setDanmakuAlpha(alpha)
                it.setLimits(maxOnScreen, areaPercent)
                view = it
            }
        },
        update = { v ->
            v.setDanmakuSize(sizeSp)
            v.setDanmakuAlpha(alpha)
            v.setLimits(maxOnScreen, areaPercent)
            v.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
        }
    )

    LaunchedEffect(events, enabled) {
        val v = view ?: return@LaunchedEffect
        // 先清屏：换台/换房间时 events 是新的 Flow 实例，但上一路房间的弹幕还在
        // DanmakuView 里飘。不清就会出现「虎牙房间的弹幕飘在斗鱼画面上」。
        v.clear()
        if (events == null || !enabled) {
            return@LaunchedEffect
        }
        // 屏蔽词：设计规范第十一节要求「默认开启关键词过滤」。
        // 规则放在渲染侧而不是网络侧——同一份弹幕流可以被不同设置复用。
        val shield = Prefs.danmakuKeywords
            .split(',', '，', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        events.collect { ev ->
            when (ev) {
                is DanmakuEvent.Chat -> {
                    if (shield.none { ev.text.contains(it, ignoreCase = true) }) {
                        v.push(ev.text, ev.color)
                    }
                }
                is DanmakuEvent.Gift -> onGift?.invoke(ev)
                is DanmakuEvent.System -> onSystem?.invoke(ev.text)
                DanmakuEvent.Connected -> Unit
                is DanmakuEvent.Failed -> onSystem?.invoke(ev.message)
            }
        }
    }

    LaunchedEffect(enabled) {
        view?.setPaused(!enabled)
    }
}
