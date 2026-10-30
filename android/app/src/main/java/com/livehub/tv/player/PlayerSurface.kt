package com.livehub.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * 视频表面。UI 层不该知道 PlayerView 的存在，所以这层很薄：
 * 只负责把 LivePlayer.exo 挂到一个不带的控制条的 PlayerView 上。
 * 控制条是我们自己画的（播放页控制条），因此 useController = false。
 */
@Composable
fun PlayerSurface(player: LivePlayer, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                keepScreenOn = true
                this.player = player.exo
            }
        },
        update = { view ->
            if (view.player !== player.exo) view.player = player.exo
        },
        onRelease = { view ->
            view.player = null
        }
    )
    DisposableEffect(player) {
        onDispose { /* 生命周期由播放页的 LivePlayer.release() 统一处理 */ }
    }
}
