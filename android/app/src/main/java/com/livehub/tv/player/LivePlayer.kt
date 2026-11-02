package com.livehub.tv.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaLoadData
import com.livehub.tv.core.model.PlayUrl
import com.livehub.tv.core.net.Net
import com.livehub.tv.core.store.Prefs
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
 *
 * 关于 [positionMs]：它**不是媒体时间轴**，而是「进这个房间后过了多久」（墙钟）。
 * 直播流没有绝对起点，旧实现读 `exo.currentPosition` 且只在 `isPlaying` 时写，
 * 断流后数字会冻在最后一个值——用户看到「0:10」不动，误以为卡死。
 * 墙钟不会因为流断而停，它回答的是「本场看了多久」。
 *
 * [firstFrameRendered] 是**真的渲染出第一帧**（`AnalyticsListener.onRenderedFirstFrame`），
 * 不是「播放器自认为在播」（`playing`）。起播兜底、换线、失败判定都以它为准。
 *
 * [stats] 是长时间稳定性测试唯一的仪表盘：真实分辨率、码率、丢帧、重缓冲次数/时长。
 * 没有它，所有平台问题只能测到「黑屏/不动」，无法归因（见 `test-plan.md` Q1/Q2）。
 */
data class PlayerStats(
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val bitrateKbps: Int = 0,
    val droppedFrames: Int = 0,
    val audioUnderruns: Int = 0,
    val rebufferCount: Int = 0,
    val rebufferMs: Long = 0L,
    /** 起播到首帧的墙钟延迟；0 表示还没出首帧。设置页的「首帧」统计用它 */
    val firstFrameMs: Long = 0L,
    /** 实际初始化的解码器名，用来验证「自动/硬件/软件」设置是否生效 */
    val decoderName: String = ""
)

data class PlayerUiState(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val error: String? = null,
    val quality: String = "",
    val lineIndex: Int = 0,
    val lineCount: Int = 0,
    /** 进房后的墙钟时长；换线路不清零，换房间清零 */
    val positionMs: Long = 0L,
    val durationMs: Long = C.TIME_UNSET,
    val live: Boolean = true,
    val muted: Boolean = false,
    /** 真的渲染出过首帧 */
    val firstFrameRendered: Boolean = false,
    /** 对端干净地结束了流（`STATE_ENDED`）。它与 [error] 是两件事，不能合并 */
    val ended: Boolean = false,
    /** 播起来之后 OVER 12 秒没有前进/一直缓冲，由 [LivePlayer] 的看门狗置位 */
    val stalled: Boolean = false,
    val urls: List<PlayUrl> = emptyList(),
    val stats: PlayerStats = PlayerStats()
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
 * 4. **处理 `STATE_ENDED`**：直播流被对端关掉时，ExoPlayer 认为「正常播完」，
 *    不产生 error。旧实现因此在 EOF 时毫无提示（DEF-10）。现在置 [PlayerUiState.ended]，
 *    由 `PlayerScreen` 决定换线还是停在终态。
 * 5. **看门狗**：首帧之后 OVER 12 秒既没有位置推进、又一直缓冲，置
 *    [PlayerUiState.stalled]。「播起来又断」是主要故障形态，不能只靠首帧时间线。
 */
class LivePlayer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var urls: List<PlayUrl> = emptyList()

    /** 进房/重连的墙钟锚点（`positionMs` 的基准） */
    private var watchAnchorMs = SystemClock.elapsedRealtime()

    /** 媒体位置上一次真正推进的时刻，只用于日志；直播 HLS 的 currentPosition 可能是 0 */
    private var lastProgressAtMs = SystemClock.elapsedRealtime()
    private var lastMediaPositionMs = -1L

    /** 最近一次真正渲染出帧的时刻。**看门狗只认这个**，不认 currentPosition */
    private var lastFrameAtMs = SystemClock.elapsedRealtime()
    private var framesRendered = 0L
    private var lastRenderedCount = -1

    /** 连续 403/404 的加载错误；用来发现「地址过期但播放器不报 error」 */
    private var loadErrorStreak = 0

    /** 本次缓冲开始的时刻；0 表示没在缓冲 */
    private var bufferingSinceMs = 0L

    /** 本次 play/retry/换线开始的时刻，用于「重连后一直没有首帧」的兜底 */
    private var awaitingFirstFrameSinceMs = SystemClock.elapsedRealtime()

    /** 首帧之后才开始计重缓冲，起播缓冲不算 */
    private var rebufferStartedAtMs = 0L
    private var rebufferCount = 0
    private var rebufferMs = 0L

    /** 每 30s 一条结构化统计，浸泡测试靠它读「真实在播」而不是靠 dumpsys（会诱发 GC） */
    private var lastStatsLogAtMs = 0L

    val exo: ExoPlayer = ExoPlayer.Builder(appContext)
        .setRenderersFactory(renderersFactory())
        // 请求音频焦点：来电/其它 App 播放时自动让路；后台停播由 PlayerScreen 的生命周期处理
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        .setLoadControl(
            DefaultLoadControl.Builder()
                // setBufferDurationsMs(min=5s, max=20s, playback=1.5s, afterRebuffer=3s)。
                // 5s 是「最小维持」，不是「起播缓冲」；起播阈值是 1.5s。
                .setBufferDurationsMs(5_000, 20_000, 1_500, 3_000)
                .build()
        )
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory(emptyMap())))
        .build()

    /**
     * 设置里的「解码方式」真的接到解码器选择上（旧实现只写 Prefs，没有任何读取点）。
     *
     * 自动 = 交给 Media3；硬件 = 只用 `softwareOnly == false` 的解码器；软件 = 反之。
     * 过滤后一个都不剩时**回落到全部**：宁可播起来，也不要把「这台设备没有软解」变成黑屏。
     * 这个回落是有意的，别改成抛错。
     */
    private fun renderersFactory(): DefaultRenderersFactory {
        val factory = DefaultRenderersFactory(appContext).setEnableDecoderFallback(true)
        val mode = Prefs.decoderMode
        if (mode == 0) return factory
        val wantSoftware = mode == 2
        return factory.setMediaCodecSelector { mimeType, secure, tunnel ->
            val all = MediaCodecUtil.getDecoderInfos(mimeType, secure, tunnel)
            val filtered = all.filter { if (wantSoftware) it.softwareOnly else !it.softwareOnly }
            filtered.ifEmpty { all }
        }
    }

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val now = SystemClock.elapsedRealtime()
                if (playbackState == Player.STATE_BUFFERING) {
                    if (bufferingSinceMs == 0L) bufferingSinceMs = now
                    if (_state.value.firstFrameRendered && rebufferStartedAtMs == 0L) {
                        rebufferStartedAtMs = now
                        rebufferCount++
                    }
                } else {
                    bufferingSinceMs = 0L
                    if (rebufferStartedAtMs != 0L) {
                        rebufferMs += now - rebufferStartedAtMs
                        rebufferStartedAtMs = 0L
                    }
                }
                _state.value = _state.value.copy(
                    buffering = playbackState == Player.STATE_BUFFERING,
                    playing = exo.isPlaying,
                    ended = playbackState == Player.STATE_ENDED,
                    // 离开缓冲态 / 结束态时清掉看门狗，重新计时
                    stalled = if (playbackState == Player.STATE_BUFFERING ||
                        playbackState == Player.STATE_ENDED
                    ) _state.value.stalled else false
                )
                if (playbackState == Player.STATE_READY) {
                    val d = exo.duration
                    _state.value = _state.value.copy(
                        durationMs = d,
                        live = d == C.TIME_UNSET || exo.isCurrentMediaItemLive
                    )
                }
                if (playbackState == Player.STATE_ENDED) {
                    // 这是 DEF-10 的直接修复点：EOF 不是 error，必须单独表达。
                    // 不自作主张重连——重连由 PlayerScreen 按线路数决定，避免四条线路死循环。
                    Log.w(TAG, "STATE_ENDED index=${exo.currentMediaItemIndex}")
                    _state.value = _state.value.copy(
                        playing = false,
                        buffering = false,
                        ended = true
                    )
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(playing = isPlaying)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {                if (videoSize.width > 0 && videoSize.height > 0) {
                    _state.value = _state.value.copy(
                        stats = _state.value.stats.copy(
                            videoWidth = videoSize.width,
                            videoHeight = videoSize.height
                        )
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 直播特有：解码/缓冲跟不上，播放位置落到直播窗口后面。
                // 这不是线路坏了，**换线路只会把能播的流扔掉**；正确动作是追到直播边缘。
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    Log.w(TAG, "behind live window, seek to live edge")
                    lastFrameAtMs = SystemClock.elapsedRealtime()
                    _state.value = _state.value.copy(
                        error = null,
                        buffering = true,
                        playing = false,
                        ended = false,
                        stalled = false
                    )
                    exo.seekToDefaultPosition()
                    // onPlayerError 后播放器处于 STATE_IDLE；只 seek 不会重新缓冲，
                    // 官方恢复序列是 seekToDefaultPosition() + prepare()。
                    exo.prepare()
                    exo.playWhenReady = true
                    return
                }
                Log.w(TAG, "player error code=${error.errorCode} name=${error.errorCodeName} msg=${error.message} cause=${error.cause}")
                _state.value = _state.value.copy(
                    error = readableError(error),
                    buffering = false,
                    playing = false,
                    ended = false,
                    stalled = false
                )
            }
        })

        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                Log.i(TAG, "first frame rendered renderTimeMs=$renderTimeMs latencyMs=${SystemClock.elapsedRealtime() - awaitingFirstFrameSinceMs}")
                _state.value = _state.value.copy(
                    firstFrameRendered = true,
                    // 首帧到了，首帧阶段的看门狗从这一刻重新计
                    stalled = false,
                    // 只认本场第一次出帧的延迟：换线路/重连也会触发 onRenderedFirstFrame，
                    // 覆盖成 150ms 会让设置页把「起播 1.3s」说成「0.1s」。
                    stats = if (_state.value.stats.firstFrameMs == 0L) {
                        _state.value.stats.copy(
                            firstFrameMs = (SystemClock.elapsedRealtime() - awaitingFirstFrameSinceMs)
                                .coerceAtLeast(0L)
                        )
                    } else {
                        _state.value.stats
                    }
                )
                lastProgressAtMs = SystemClock.elapsedRealtime()
                lastFrameAtMs = SystemClock.elapsedRealtime()
            }

            // 帧进度由 ticker 轮询 `videoDecoderCounters.renderedOutputBufferCount`，
            // 不走 `onVideoFrameProcessingOffset`：后者在 2026-10-30 的哔哩浸泡里约 9s
            // 才回调一次，拿 8s 阈值判停住会误杀健康流。

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                Log.i(TAG, "video decoder=$decoderName initMs=$initializationDurationMs")
                _state.value = _state.value.copy(
                    stats = _state.value.stats.copy(decoderName = decoderName)
                )
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                if (format.bitrate > 0) {
                    _state.value = _state.value.copy(
                        stats = _state.value.stats.copy(bitrateKbps = format.bitrate / 1000)
                    )
                }
            }

            override fun onDownstreamFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                mediaLoadData: MediaLoadData
            ) {
                val format = mediaLoadData.trackFormat ?: return
                if (format.bitrate > 0) {
                    _state.value = _state.value.copy(
                        stats = _state.value.stats.copy(bitrateKbps = format.bitrate / 1000)
                    )
                }
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                // 这个回调的 droppedFrames 是**自上次回调以来的增量**（DefaultRenderersFactory
                // 大约每 50 帧派发一次），不是累计值。累计值由 ticker 读
                // `videoDecoderCounters.droppedBufferCount` 统一写，避免把增量当累计。
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: java.io.IOException,
                wasCanceled: Boolean
            ) {
                val http = error as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                val code = http?.responseCode ?: -1
                Log.w(
                    TAG,
                    "load error uri=${loadEventInfo.uri} http=$code " +
                        "class=${error.javaClass.simpleName} msg=${error.message}"
                )
                // 地址过期时 ExoPlayer 往往只报 load error，不报 player error。
                // 连续 2 次 403/404/410 且首帧之后 2s 没有新帧 ⇒ 明确说出来，交给 PlayerScreen 重新取流。
                // 阈值从 3/3s 收到 2/2s：斗鱼的 TTL 约 60s，越早重取，空档越短；单次 404 仍不会触发。
                if (code == 403 || code == 404 || code == 410) {
                    loadErrorStreak++
                    val now = SystemClock.elapsedRealtime()
                    if (_state.value.firstFrameRendered && _state.value.error == null &&
                        loadErrorStreak >= 2 && now - lastFrameAtMs >= 2_000L
                    ) {
                        Log.w(TAG, "repeated HTTP $code and no new frame; treat as expired url")
                        _state.value = _state.value.copy(
                            error = "直播地址过期了（CDN 拒绝了分片）",
                            buffering = false,
                            playing = false,
                            ended = false,
                            stalled = false
                        )
                    }
                }
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long
            ) {
                _state.value = _state.value.copy(
                    stats = _state.value.stats.copy(
                        audioUnderruns = _state.value.stats.audioUnderruns + 1
                    )
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

    /**
     * 播放一组地址中的第 [index] 条。
     *
     * [resetWatch] = true 用于换房间（本场观看从头算）；换线路时传 false，
     * 不能因为换了一条 CDN 就把「看了多久」清零。
     *
     * [refreshUrls] = true：播放前让每条线路用 [PlayUrl.refresh] 本地重签一次
     * （虎牙的 seqid 只对第一次请求有效）。不为 null 的线路换新 URL，其余原样。
     */
    fun play(
        urls: List<PlayUrl>,
        index: Int = 0,
        resetWatch: Boolean = true,
        refreshUrls: Boolean = false
    ) {
        if (urls.isEmpty()) {
            _state.value = _state.value.copy(
                error = "没有拿到可用的播放地址",
                buffering = false,
                ended = false
            )
            return
        }
        val actual = if (refreshUrls) {
            urls.map { u -> u.refresh?.let { sign -> u.copy(url = sign()) } ?: u }
        } else {
            urls
        }
        this.urls = actual
        val i = index.coerceIn(0, actual.lastIndex)
        actual.forEachIndexed { idx, u ->
            // 只打 scheme+host+path；query 里有 wsSecret/token，别把凭据写进日志。
            // 首条额外打全串：排查 manifest/403 时没有 query 就复现不了（仅 debug 包）。
            Log.i(TAG, "line ${idx + 1}/${actual.size} ${u.format} quality=${u.quality} " +
                (if (idx == 0) u.url else u.url.substringBefore('?')))
        }
        val target = actual[i]
        Log.i(TAG, "playing line ${i + 1}/${actual.size} ${target.format} quality=${target.quality}")
        val now = SystemClock.elapsedRealtime()
        val keepMs = if (resetWatch) 0L else _state.value.positionMs
        if (resetWatch) watchAnchorMs = now else watchAnchorMs = now - keepMs
        lastProgressAtMs = now
        lastMediaPositionMs = -1L
        bufferingSinceMs = now
        awaitingFirstFrameSinceMs = now
        lastFrameAtMs = now
        lastRenderedCount = -1
        loadErrorStreak = 0
        if (resetWatch) {
            framesRendered = 0L
            rebufferStartedAtMs = 0L
            rebufferCount = 0
            rebufferMs = 0L
        }
        _state.value = _state.value.copy(
            quality = target.quality,
            lineIndex = i,
            lineCount = actual.size,
            urls = actual,
            error = null,
            buffering = true,
            ended = false,
            stalled = false,
            firstFrameRendered = false,
            positionMs = keepMs,
            // 换线路不清累计遥测：30 分钟 soak 要的是「整场重缓冲了几次」，
            // 不是「最后一条线路重缓冲了几次」。
            stats = if (resetWatch) PlayerStats() else _state.value.stats
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
        play(urls, index, resetWatch = false)
    }

    fun setMuted(muted: Boolean) {
        exo.volume = if (muted) 0f else 1f
        _state.value = _state.value.copy(muted = muted)
    }

    /**
     * 切房失败时用：停掉旧房间的视频并清空播放器状态。
     * 不调用的话，新房间取流失败时屏幕上会继续播上一个房间的画面，
     * 顶部元数据和错误却是新房间的——这是最容易被误判成「新房间能播」的假象。
     */
    fun stop() {
        urls = emptyList()
        runCatching {
            exo.stop()
            exo.clearMediaItems()
        }
        // 停住不是「在缓冲」：默认 PlayerUiState 的 buffering=true 会让 30s stats
        // 在取流失败、播放器已停之后仍打印 buffering/stalled，长跑汇总会把它误判成卡死。
        _state.value = PlayerUiState(buffering = false)
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

    /**
     * 每秒一次的状态汇总，同时承担三件事：
     * 1. 墙钟 `positionMs`；
     * 2. 轮询解码器计数器，判断「有没有真的渲染出新帧」；
     * 3. 首帧后 8s 没有新帧 / 重连后 12s 无首帧 → `stalled`。
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                // stop() 之后没有播放器可看门：此时再算 neverFirstFrame 会把
                // 「取流失败后主动停住」误报成 stalled。保持 stop() 写入的终态，不覆盖。
                if (urls.isEmpty()) {
                    delay(500)
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                val s = _state.value
                // 解码器计数器是**每秒可轮询**的「有没有真的渲染出新帧」，
                // 比 onVideoFrameProcessingOffset（这次实测约 9s 才回调一次）可靠得多。
                val counters = runCatching { exo.videoDecoderCounters }.getOrNull()
                val rendered = counters?.renderedOutputBufferCount ?: -1
                val droppedTotal = counters?.droppedBufferCount ?: 0
                if (rendered >= 0 && rendered != lastRenderedCount) {
                    // 换线路会让计数器归零：先认下新基线，别拿旧的大数当「没前进」
                    if (rendered < lastRenderedCount) lastRenderedCount = -1
                    lastRenderedCount = rendered
                    lastFrameAtMs = now
                    framesRendered = rendered.toLong()
                    loadErrorStreak = 0
                }
                val mediaPos = exo.currentPosition.coerceAtLeast(0L)
                if (exo.isPlaying && mediaPos > lastMediaPositionMs + 150L) {
                    lastMediaPositionMs = mediaPos
                    lastProgressAtMs = now
                }

                val pos = if (s.error != null || s.ended || s.stalled) s.positionMs
                else (now - watchAnchorMs).coerceAtLeast(0L)
                val bufferingNow = exo.playbackState == Player.STATE_BUFFERING
                if (bufferingNow && bufferingSinceMs == 0L) bufferingSinceMs = now
                if (!bufferingNow) bufferingSinceMs = 0L

                // 看门狗有两个入口，缺一不可：
                // 1. 首帧之后停住（播起来又断）；
                // 2. **重连/换线之后一直没有首帧**——旧实现只覆盖第 1 条，
                //    而重试一条已死的 FLV 地址会永远停在 buffering，屏上一个字都没有。
                val neverFirstFrame = !s.firstFrameRendered &&
                    now - awaitingFirstFrameSinceMs >= FIRST_FRAME_TIMEOUT_MS
                val stalledAfterStart = s.firstFrameRendered &&
                    now - lastFrameAtMs >= STALL_AFTER_START_MS
                val stalledNow = s.error == null && !s.ended && (neverFirstFrame || stalledAfterStart)
                if (stalledNow && !s.stalled) {
                    Log.w(
                        TAG,
                        "stalled: firstFrame=${s.firstFrameRendered} frames=$framesRendered " +
                            "noFrameFor=${now - lastFrameAtMs}ms since=${now - awaitingFirstFrameSinceMs}ms pos=$mediaPos"
                    )
                }
                _state.value = s.copy(
                    positionMs = pos,
                    playing = exo.isPlaying,
                    stalled = stalledNow,
                    stats = s.stats.copy(
                        droppedFrames = droppedTotal,
                        rebufferCount = rebufferCount,
                        rebufferMs = rebufferMs + if (rebufferStartedAtMs > 0L) now - rebufferStartedAtMs else 0L
                    )
                )
                if (now - lastStatsLogAtMs >= 30_000L) {
                    lastStatsLogAtMs = now
                    val st = _state.value
                    Log.i(
                        TAG,
                        "stats pos=${st.positionMs / 1000}s line=${st.lineIndex + 1}/${st.lineCount} " +
                            "quality=${st.quality} firstFrame=${st.firstFrameRendered} " +
                            "playing=${st.playing} buffering=${st.buffering} stalled=${st.stalled} " +
                            "ended=${st.ended} video=${st.stats.videoWidth}x${st.stats.videoHeight} " +
                            "frames=$framesRendered decoder=${st.stats.decoderName.ifBlank { "-" }} " +
                            "bitrateKbps=${st.stats.bitrateKbps} dropped=${st.stats.droppedFrames} " +
                            "rebuffer=${st.stats.rebufferCount}/${st.stats.rebufferMs}ms " +
                            "audioUnderruns=${st.stats.audioUnderruns} " +
                            "error=${st.error?.take(40) ?: "-"}"
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

    private companion object {
        const val TAG = "LiveHubPlayer"

        /** 首帧之后多久没有前进就判「播起来又断」。低于 8s 会误杀正常的长缓冲 */
        const val STALL_AFTER_START_MS = 8_000L

        /** 起播/重连后多久没有首帧就判失败。与 PlayerScreen 的起播时间线对齐 */
        const val FIRST_FRAME_TIMEOUT_MS = 12_000L
    }
}
