package com.livehub.tv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.livehub.tv.core.model.PlayUrl
import com.livehub.tv.core.net.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 播放器对外暴露的状态。UI 只读这个，不直接碰 ExoPlayer。
 * 以后要换内核（某些盒子 ExoPlayer 解不了 FLV 要换 IJK），只改这一个类。
 */
data class PlayerUiState(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val error: String? = null,
    val quality: String = "",
    val lineIndex: Int = 0,
    val lineCount: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = C.TIME_UNSET,
    val live: Boolean = true,
    val muted: Boolean = false
)

/**
 * Media3 封装。
 *
 * 关键决定：
 * 1. 用 OkHttpDataSource 而不是默认的 HttpURLConnection —— 四家 CDN 都校验 Referer/UA，
 *    而且每家要求的头不一样，所以每条线路建一个 MediaSource（带自己的 header 集）。
 * 2. 直播缓冲压小（3s 起）：电视盒子内存小，默认 50s 缓冲没有意义还会拖慢起播。
 * 3. 出错不自动无限重试：把可读错误交给 UI，让用户按「重试」或「换线路」——
 *    指数退避重连会把「未开播」这种确定性失败变成无限转圈。
 */
class LivePlayer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var urls: List<PlayUrl> = emptyList()

    val exo: ExoPlayer = ExoPlayer.Builder(appContext)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(3_000, 30_000, 800, 1_500)
                .build()
        )
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory(emptyMap())))
        .build()

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.value = _state.value.copy(
                    buffering = playbackState == Player.STATE_BUFFERING,
                    playing = exo.isPlaying
                )
                if (playbackState == Player.STATE_READY) {
                    val d = exo.duration
                    _state.value = _state.value.copy(
                        durationMs = d,
                        live = d == C.TIME_UNSET || exo.isCurrentMediaItemLive
                    )
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(playing = isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.value = _state.value.copy(
                    error = readableError(error),
                    buffering = false,
                    playing = false
                )
            }
        })
        startTicker()
    }

    private fun dataSourceFactory(headers: Map<String, String>): OkHttpDataSource.Factory {
        // 超时与跨协议重定向都不在这里配：
        //   media3 的 OkHttpDataSource.Factory 把这两件事交给了背后的 OkHttpClient，
        //   超时在 Net.streamClient（连接 8s / 读 30s），跨协议跳转在 client.followRedirects。
        val factory = OkHttpDataSource.Factory(Net.streamClient)
            .setUserAgent(headers["User-Agent"] ?: Net.UA_PC)
        val extra = headers.filterKeys { it != "User-Agent" }
        if (extra.isNotEmpty()) factory.setDefaultRequestProperties(extra)
        return factory
    }

    fun play(urls: List<PlayUrl>, index: Int = 0) {
        if (urls.isEmpty()) {
            _state.value = _state.value.copy(error = "没有拿到可用的播放地址", buffering = false)
            return
        }
        this.urls = urls
        val i = index.coerceIn(0, urls.lastIndex)
        val target = urls[i]
        _state.value = _state.value.copy(
            quality = target.quality,
            lineIndex = i,
            lineCount = urls.size,
            error = null,
            buffering = true
        )
        val builder = MediaItem.Builder().setUri(target.url)
        if (target.isHls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        val source = DefaultMediaSourceFactory(dataSourceFactory(target.headers))
            .createMediaSource(builder.build())
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
    }

    fun switchLine(index: Int) {
        if (urls.isEmpty()) return
        play(urls, index)
    }

    fun retry() {
        if (urls.isEmpty()) return
        _state.value = _state.value.copy(error = null, buffering = true)
        exo.prepare()
        exo.playWhenReady = true
    }

    fun setMuted(muted: Boolean) {
        exo.volume = if (muted) 0f else 1f
        _state.value = _state.value.copy(muted = muted)
    }

    fun pause() = exo.pause()

    fun resume() = exo.play()

    private fun readableError(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "CDN 拒绝了这个地址（防盗链或已过期），换一条线路试试"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "连不上直播服务器，检查网络"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "连接超时，稍后重试"
        PlaybackException.ERROR_CODE_DECODING_FAILED -> "这台设备解不了这个编码，试试换线路或调低清晰度"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败，去设置里换解码方式"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "流格式异常，可能未开播或地址已失效"
        else -> "播放失败：${e.errorCodeName}"
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                if (exo.isPlaying) {
                    _state.value = _state.value.copy(
                        positionMs = exo.currentPosition.coerceAtLeast(0),
                        playing = true
                    )
                }
                delay(1_000)
            }
        }
    }

    fun release() {
        ticker?.cancel()
        exo.release()
    }
}
