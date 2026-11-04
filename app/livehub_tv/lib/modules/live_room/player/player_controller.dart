import 'dart:async';
import 'dart:io';

import 'package:canvas_danmaku/canvas_danmaku.dart';
import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter_screenutil/flutter_screenutil.dart';
import 'package:flutter/material.dart';
import 'package:livehub_tv/app/controller/base_controller.dart';
import 'package:get/get.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:livehub_tv/app/controller/app_settings_controller.dart';
import 'package:livehub_tv/app/log.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

mixin PlayerMixin {
  GlobalKey<VideoState> globalPlayerKey = GlobalKey<VideoState>();
  GlobalKey globalDanmuKey = GlobalKey();

  /// 播放器实例
  late final player = Player(
    configuration: const PlayerConfiguration(
      title: "LiveHub Player",
      // 直播只需要"下一秒"，不需要 mpv 的默认大缓存；
      // 日志只留 error，避免每行日志都在 release 里走一遍 print + logcat。
      logLevel: MPVLogLevel.error,
    ),
  );

  /// 直播场景下写死在每次 open 之前的 mpv 属性。
  ///
  /// 这些值是按「断流后要自己爬起来」定的，不是按点播定的：
  /// - `cache-secs` / `demuxer-readahead-secs` 压到 6s：默认值会把延迟堆到十几秒，
  ///   卡一下之后追不回来，观感就是"画面停住"；
  /// - `stream-lavf-o` 打开 ffmpeg 的自动重连：哔哩的赛事/版权房在整点或换机位时
  ///   会直接掐掉当前 CDN 连接，不重连就只能靠上层 `player.open` 重来一次；
  /// - `hls-bitrate=max`：HLS 线路现在排第二，命中时别再被降一档。
  /// 注意：这里**不再**设置 `force-seekable=yes`（上游的临时补丁）。
  /// 直播流本来不可 seek，强行声明可 seek 会让 `AndroidVideoController` 在
  /// surface 重新挂载时执行的 `seek(0)` 变成"从头重新解复用"——赛事房正好是
  /// 最容易触发 surface 重建的一类房间，这一步会把画面直接钉死。
  static const Map<String, String> _liveMpvProperties = {
    "cache": "yes",
    "cache-secs": "6",
    "demuxer-readahead-secs": "6",
    "demuxer-max-bytes": "16MiB",
    "demuxer-max-back-bytes": "4MiB",
    "network-timeout": "15",
    "hls-bitrate": "max",
    "stream-lavf-o":
        "reconnect=1,reconnect_streamed=1,reconnect_delay_max=2,timeout=10000000",
  };

  bool _livePropertiesApplied = false;

  /// 显卡输出兜底：`vo=gpu` 起不来时改走 `mediacodec_embed`（同"兼容模式"那条路）。
  /// 某些电视/模拟器拿不到 EGL 上下文 —— 那时候 mpv 会对每条线路报
  /// `Failed to open ...`，换线重开完全没用，只能换 vo。
  bool compatVoFallbackApplied = false;

  /// 一次会话里允许切换几次 vo。
  ///
  /// 不能只切一次：`AndroidVideoController` 在 surface 重新挂载时会把 `vo` 重置回
  /// `configuration.vo`（=gpu），那一瞬间如果 gpu 仍然不可用，失败会再来一次 ——
  /// 所以要允许复盘。但也不能无限重试，两次足够。
  int _voSwitchCount = 0;

  /// 运行期把输出切到 mediacodec_embed。返回 false 表示这次不适用。
  Future<bool> switchVoToCompat(String error) async {
    if (_voSwitchCount >= 2) {
      return false;
    }
    if (!error.contains("GPU context") &&
        !error.contains("EGL context") &&
        !error.contains("video_out")) {
      return false;
    }
    final pp = player.platform;
    if (pp is! NativePlayer) {
      return false;
    }
    compatVoFallbackApplied = true;
    _voSwitchCount += 1;
    Log.w("显卡输出起不来，切到 mediacodec_embed 兜底（第 $_voSwitchCount 次）");
    try {
      await pp.setProperty("hwdec", "mediacodec");
      await pp.setProperty("vo", "mediacodec_embed");
      await pp.setProperty("vid", "auto");
    } catch (e) {
      Log.e("切换 vo 失败: $e", StackTrace.current);
      return false;
    }
    return true;
  }

  /// 下发直播用的 mpv 属性。**只做一次，且必须在 video 输出挂上之前做。**
  ///
  /// 为什么不能放进 `setPlayer()` 的 open 路径：`mpv_set_property_string` 是同步
  /// FFI，调用时会去抢 mpv 的 core 锁；而 `AndroidVideoController` 在 surface 挂载
  /// 时（`widListener`）会把 `vo=null → wid → vo → vid` 重设一遍并接着执行
  /// `player.seek(Duration.zero)`。直播流不可 seek，若它又被 `force-seekable=yes`
  /// 声明成可 seek，这次 seek 会真的去重新解复用并卡在 demuxer 的网络读上，
  /// 于是 core 锁被长期占住 —— 属性设置跟着一起死等。
  ///
  /// 真机 ANR 堆栈（2026-11-04 09:36 / 10:12，旧版本）就是这个形状：
  /// `mpv_set_property_string → pthread_cond_wait`，主线程 5s 收不到 KeyEvent。
  /// 同步 FFI 卡住时 `Future.timeout` 不会触发，所以唯一的解法是**不在抢锁的时刻调用**。
  Future<void> preparePlayer() async {
    if (_livePropertiesApplied) {
      return;
    }
    _livePropertiesApplied = true;
    final pp = player.platform;
    if (pp is! NativePlayer) {
      return;
    }
    for (final entry in _liveMpvProperties.entries) {
      try {
        await pp.setProperty(entry.key, entry.value);
      } catch (e) {
        Log.w("设置播放器属性失败 ${entry.key}=${entry.value}: $e");
      }
    }
  }

  /// 视频控制器
  late final videoController = VideoController(
    player,
    configuration: AppSettingsController.instance.playerCompatMode.value
        ? const VideoControllerConfiguration(
            vo: 'mediacodec_embed',
            hwdec: 'mediacodec',
          )
        : VideoControllerConfiguration(
            enableHardwareAcceleration:
                AppSettingsController.instance.hardwareDecode.value,
            androidAttachSurfaceAfterVideoParameters: false,
          ),
  );
}
mixin PlayerStateMixin on PlayerMixin {
  /// 是否显示弹幕
  RxBool showDanmakuState = false.obs;

  /// 是否显示控制器
  RxBool showControlsState = false.obs;

  /// 是否显示设置窗口
  RxBool showSettingState = false.obs;

  /// 是否显示弹幕设置窗口
  RxBool showDanmakuSettingState = false.obs;

  /// 是否处于锁定控制器状态
  RxBool lockControlsState = false.obs;

  /// 是否处于全屏状态
  RxBool fullScreenState = false.obs;

  /// 显示手势Tip
  RxBool showGestureTip = false.obs;

  /// 手势Tip文本
  RxString gestureTipText = "".obs;

  /// 显示提示底部Tip
  RxBool showBottomTip = false.obs;

  /// 提示底部Tip文本
  RxString bottomTipText = "".obs;

  /// 自动隐藏控制器计时器
  Timer? hideControlsTimer;

  /// 自动隐藏提示计时器
  Timer? hideSeekTipTimer;

  Widget? danmakuView;

  var showQualites = false.obs;
  var showLines = false.obs;

  /// 隐藏控制器
  void hideControls() {
    showControlsState.value = false;
    hideControlsTimer?.cancel();
  }

  void setLockState() {
    lockControlsState.value = !lockControlsState.value;
    if (lockControlsState.value) {
      showControlsState.value = false;
    } else {
      showControlsState.value = true;
    }
  }

  /// 显示控制器
  void showControls() {
    showControlsState.value = true;
    resetHideControlsTimer();
  }

  /// 开始隐藏控制器计时
  /// - 当点击控制器上时功能时需要重新计时
  void resetHideControlsTimer() {
    hideControlsTimer?.cancel();

    hideControlsTimer = Timer(
      const Duration(
        seconds: 5,
      ),
      hideControls,
    );
  }

  void updateScaleMode() {
    var boxFit = BoxFit.contain;
    double? aspectRatio;
    if (player.state.width != null && player.state.height != null) {
      aspectRatio = player.state.width! / player.state.height!;
    }

    if (AppSettingsController.instance.scaleMode.value == 0) {
      boxFit = BoxFit.contain;
    } else if (AppSettingsController.instance.scaleMode.value == 1) {
      boxFit = BoxFit.fill;
    } else if (AppSettingsController.instance.scaleMode.value == 2) {
      boxFit = BoxFit.cover;
    } else if (AppSettingsController.instance.scaleMode.value == 3) {
      boxFit = BoxFit.contain;
      aspectRatio = 16 / 9;
    } else if (AppSettingsController.instance.scaleMode.value == 4) {
      boxFit = BoxFit.contain;
      aspectRatio = 4 / 3;
    }
    globalPlayerKey.currentState?.update(
      aspectRatio: aspectRatio,
      fit: boxFit,
    );
  }
}
mixin PlayerDanmakuMixin on PlayerStateMixin {
  /// 弹幕控制器
  DanmakuController? danmakuController;

  // ---------------------------------------------------------------- 限流

  /// canvas_danmaku 每收一条弹幕，都要在 UI isolate 上同步做两件事：
  /// 建 `TextPainter` 量宽、建 1~2 个 `ui.Paragraph`（描边是第二个）。
  /// 中文要过一遍字体整形，弱一些的电视 SoC 上单条 0.3~1ms。
  ///
  /// 赛事官方房在开赛/团战时的弹幕量是普通房间的一到两个数量级，
  /// 直接 `addDanmaku` 一条条灌进去 = 每秒几百次字体整形，
  /// 帧时间被吃掉 → 画面停住 → ANR → 系统直接把进程收掉。
  ///
  /// 所以这里做三件事：令牌桶限流、100ms 合批、队列背压。
  final List<DanmakuContentItem> _danmakuQueue = [];
  Timer? _danmakuFlushTimer;
  int _windowTokens = 0;
  DateTime _windowStart = DateTime.now();

  /// 被限流/背压丢掉的条数（设置页与日志里用来判断"是不是丢太多"）
  int danmakuDropped = 0;

  /// 真正上屏的条数
  int danmakuShown = 0;

  DanmakuOption get danmakuOption => DanmakuOption(
        fontSize: AppSettingsController.instance.danmuSize.value.w,
        area: AppSettingsController.instance.danmuArea.value,
        duration: AppSettingsController.instance.danmuSpeed.value.toInt(),
        opacity: AppSettingsController.instance.danmuOpacity.value,
        // 描边保住亮画面上的可读性。限流之后每秒最多 maxRate 条，
        // 第二个 Paragraph 的开销是可控的，这里不再省。
        showStroke: true,
        // 不开 massiveMode：它会把放不下的弹幕随机叠进已有轨道，
        // 赛事房那种密度下会叠成一片糊。让限流在**建 Paragraph 之前**丢，
        // 比让 painter 叠上去更划算，也更好看。
        massiveMode: false,
      );

  void initDanmakuController(DanmakuController e) {
    danmakuController = e;
    // 这里**不能**调 `updateOption`：canvas_danmaku 的 `createdController` 回调发生在
    // `_animationController` 创建之前（danmaku_screen.dart initState 里的顺序），
    // `updateOption` 第一行就摸 `_animationController.isAnimating` → LateInitializationError，
    // 抛在 initState 里会把元素树弄坏（debug 是红屏 `_elements.contains(element)`，
    // release 则是页面画不出来 —— 真机上那张"黑屏 + 弹幕正常"的截图就是这个形状）。
    // 初始选项已经在构造 DanmakuScreen 时通过 `option:` 传进去了，不需要再推一次。
    e.option = danmakuOption;
  }

  /// 设置项改了以后把新的 [DanmakuOption] 推给已挂载的控制器
  void applyDanmakuOption() {
    danmakuController?.updateOption(danmakuOption);
  }

  void updateDanmuOption(DanmakuOption? option) {
    if (danmakuController == null || option == null) return;
    danmakuController!.updateOption(option);
  }

  void disposeDanmakuController() {
    _danmakuFlushTimer?.cancel();
    _danmakuFlushTimer = null;
    _danmakuQueue.clear();
    danmakuController?.clear();
  }

  void addDanmaku(List<DanmakuContentItem> items) {
    if (!showDanmakuState.value || items.isEmpty) {
      return;
    }
    final budget = AppSettingsController.instance.danmuMaxRate.value;
    final now = DateTime.now();
    if (now.difference(_windowStart).inMilliseconds >= 1000) {
      _windowStart = now;
      _windowTokens = 0;
    }
    final room = budget - _windowTokens;
    if (room <= 0) {
      danmakuDropped += items.length;
      return;
    }
    final take = items.length <= room ? items.length : room;
    _windowTokens += take;
    if (take < items.length) {
      danmakuDropped += items.length - take;
    }
    _danmakuQueue.addAll(items.take(take));

    // 背压：队列再长也不会比 120 条更有意义，超出部分丢最旧的
    if (_danmakuQueue.length > 120) {
      final cut = _danmakuQueue.length - 120;
      _danmakuQueue.removeRange(0, cut);
      danmakuDropped += cut;
    }

    _danmakuFlushTimer ??=
        Timer(const Duration(milliseconds: 100), _flushDanmaku);
  }

  void _flushDanmaku() {
    _danmakuFlushTimer = null;
    if (_danmakuQueue.isEmpty) {
      return;
    }
    final items = List<DanmakuContentItem>.from(_danmakuQueue);
    _danmakuQueue.clear();
    if (!showDanmakuState.value) {
      danmakuDropped += items.length;
      return;
    }
    final controller = danmakuController;
    if (controller == null) {
      danmakuDropped += items.length;
      return;
    }
    // 同一帧内一次性交给 painter，少了 N 次 notifyListeners
    for (final item in items) {
      controller.addDanmaku(item);
    }
    danmakuShown += items.length;
  }
}
mixin PlayerSystemMixin on PlayerMixin, PlayerStateMixin, PlayerDanmakuMixin {
  final DeviceInfoPlugin deviceInfo = DeviceInfoPlugin();

  /// 初始化一些系统状态
  void initSystem() async {
    // 屏幕常亮
    WakelockPlus.enable();

    // 开始隐藏计时
    resetHideControlsTimer();
  }

  /// 释放一些系统状态
  Future resetSystem() async {
    await WakelockPlus.disable();
  }

  /// 是否是IOS16以下
  Future<bool> beforeIOS16() async {
    if (Platform.isIOS) {
      var info = await deviceInfo.iosInfo;
      var version = info.systemVersion;
      var versionInt = int.tryParse(version.split('.').first) ?? 0;
      return versionInt < 16;
    } else {
      return false;
    }
  }
}

class PlayerController extends BaseController
    with PlayerMixin, PlayerStateMixin, PlayerDanmakuMixin, PlayerSystemMixin {
  @override
  void onInit() {
    initSystem();
    initStream();
    super.onInit();
  }

  var width = 0.obs;
  var height = 0.obs;

  StreamSubscription<String>? _errorSubscription;
  StreamSubscription? _completedSubscription;
  StreamSubscription? _widthSubscription;
  StreamSubscription? _heightSubscription;
  StreamSubscription? _logSubscription;

  void initStream() {
    _errorSubscription = player.stream.error.listen((event) {
      Log.d("播放器错误：$event");
      if (event.contains('no sound.')) {
        return;
      }
      //SmartDialog.showToast(event);
      mediaError(event);
    });

    _completedSubscription = player.stream.completed.listen((event) {
      if (event) {
        mediaEnd();
      }
    });
    _logSubscription = player.stream.log.listen((event) {
      Log.d("播放器日志：$event");
      // mpv 在 `vo` 起不来时**不会**往 error 流里发东西：它会安静地把视频轨丢掉、
      // 只放声音（真机上就是"黑屏 + 弹幕正常 + 播放器显示在播"）。
      // 这类失败只能从日志里认：cplayer 的 `video_out` / `VO window`，
      // 或 vo/gpu 的 GPU context 失败。
      final text = event.text;
      if (text.contains("video_out") ||
          text.contains("VO window") ||
          text.contains("GPU context")) {
        onVideoOutputFailure(text);
      }
    });
    _widthSubscription = player.stream.width.listen((event) {
      Log.w(
          'width:$event  W:${(player.state.width)}  H:${(player.state.height)}');
      width.value = event ?? 0;
      // isVertical.value =
      //     (player.state.height ?? 9) > (player.state.width ?? 16);
    });
    _heightSubscription = player.stream.height.listen((event) {
      Log.w(
          'height:$event  W:${(player.state.width)}  H:${(player.state.height)}');
      height.value = event ?? 0;
      // isVertical.value =
      //     (player.state.height ?? 9) > (player.state.width ?? 16);
    });
  }

  void disposeStream() {
    _errorSubscription?.cancel();
    _completedSubscription?.cancel();
    _widthSubscription?.cancel();
    _heightSubscription?.cancel();
    _logSubscription?.cancel();
  }

  void mediaEnd() {}

  void mediaError(String error) {}

  /// 视频输出（vo）初始化失败。默认什么都不做，播放页可以覆写它去换输出。
  void onVideoOutputFailure(String reason) {}

  @override
  void onClose() async {
    Log.w("播放器关闭");
    disposeStream();
    disposeDanmakuController();
    await resetSystem();
    await player.dispose();
    super.onClose();
  }
}
