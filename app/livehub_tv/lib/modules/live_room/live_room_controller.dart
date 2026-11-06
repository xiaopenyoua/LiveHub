import 'dart:async';

import 'package:canvas_danmaku/models/danmaku_content_item.dart';
import 'package:flutter/material.dart';
import 'package:flutter_smart_dialog/flutter_smart_dialog.dart';
import 'package:get/get.dart';
import 'package:media_kit/media_kit.dart';
import 'package:livehub_core/livehub_core.dart';
import 'package:livehub_tv/app/constant.dart';
import 'package:livehub_tv/app/controller/app_settings_controller.dart';
import 'package:livehub_tv/app/event_bus.dart';
import 'package:livehub_tv/app/log.dart';
import 'package:livehub_tv/app/sites.dart';
import 'package:livehub_tv/app/utils.dart';
import 'package:livehub_tv/models/db/follow_user.dart';
import 'package:livehub_tv/models/db/history.dart';
import 'package:livehub_tv/modules/live_room/player/player_controller.dart';
import 'package:livehub_tv/services/db_service.dart';
import 'package:livehub_tv/services/follow_user_service.dart';

class LiveRoomController extends PlayerController with WidgetsBindingObserver {
  final Site pSite;
  final String pRoomId;
  late LiveDanmaku liveDanmaku;
  LiveRoomController({
    required this.pSite,
    required this.pRoomId,
  }) {
    rxSite = pSite.obs;
    rxRoomId = pRoomId.obs;
    liveDanmaku = site.liveSite.getDanmaku();
  }
  final FocusNode focusNode = FocusNode();
  late Rx<Site> rxSite;
  Site get site => rxSite.value;
  late Rx<String> rxRoomId;
  String get roomId => rxRoomId.value;

  Rx<LiveRoomDetail?> detail = Rx<LiveRoomDetail?>(null);
  var online = 0.obs;
  var followed = false.obs;
  var liveStatus = false.obs;

  /// 清晰度数据
  RxList<LivePlayQuality> qualites = RxList<LivePlayQuality>();

  /// 当前清晰度
  /// 当前清晰度下标。用 Rx 存：面板里的分段控件绑在 Obx 上，
  /// 普通 int 改了不会重建，表现出来就是「左右键按了没反应」。
  final qualityIndex = (-1).obs;
  int get currentQuality => qualityIndex.value;
  set currentQuality(int value) => qualityIndex.value = value;
  var currentQualityInfo = "".obs;

  /// 线路数据
  RxList<String> playUrls = RxList<String>();

  /// 与 [playUrls] 一一对应的线路类型（FLV / HLS·fMP4 / HLS·TS）
  RxList<LivePlayLine> playLines = RxList<LivePlayLine>();

  Map<String, String>? playHeaders;

  /// 当前线路
  /// 当前线路下标，同上，必须是 Rx。
  final lineIndex = (-1).obs;
  int get currentLineIndex => lineIndex.value;
  set currentLineIndex(int value) => lineIndex.value = value;
  var currentLineInfo = "".obs;

  /// 是否处于后台
  var isBackground = false;

  var datetime = "00:00".obs;

  /// 时钟 Timer。必须是字段 —— 以前是 `Timer.periodic(...)` 裸调用，
  /// 离开播放页后每秒仍在跑，把整个 controller（连同 detail 里的房间 JSON、
  /// Player、弹幕 WebSocket）钉在内存里，换几个房间就是几条泄漏的播放器实例。
  Timer? _datetimeTimer;

  void initTimer() {
    _datetimeTimer?.cancel();
    _datetimeTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      var now = DateTime.now();
      datetime.value =
          "${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}";
    });
  }

  /// 双击退出Flag
  bool doubleClickExit = false;

  /// 双击退出Timer
  Timer? doubleClickTimer;

  // ---------------------------------------------------------------- 播放看门狗

  /// 每次 `player.open` 递增。所有异步回调都拿它做代次校验，
  /// 迟到的 open/重试不会再覆盖新的一次起播（以前并发 open 会让 mpv 起两个
  /// demuxer，表现为黑屏 + CPU 拉满）。
  int _playerGeneration = 0;

  /// 正在恢复中：`mediaError` 与 `mediaEnd` 常常同一次故障里先后触发，
  /// 没有这个闸门就会连开两次。
  bool _recovering = false;

  /// 本次 open 的时刻，看门狗据此判断「起播超时」。
  DateTime? _openStartedAt;

  /// 看门狗巡检
  Timer? _watchdogTimer;

  /// 累计卡死次数（诊断用，也用于判断要不要直接放弃当前线路）
  int stallCount = 0;

  @override
  void onInit() {
    // 趁 mpv 还空闲、video 输出也还没挂（`videoController` 是 late final，
    // 首次 build 才会创建），把直播参数一次下发完。
    preparePlayer();
    // 以前只是 `implements WidgetsBindingObserver` 却没注册，
    // didChangeAppLifecycleState 一次都不会回调 —— 退到后台后弹幕照样在解。
    WidgetsBinding.instance.addObserver(this);
    initTimer();
    showDanmakuState.value = AppSettingsController.instance.danmuEnable.value;
    followed.value = DBService.instance.getFollowExist("${site.id}_$roomId");

    loadData();

    super.onInit();
  }

  void refreshRoom() {
    //messages.clear();

    _stopWatchdog();
    liveDanmaku.stop();

    loadData();
  }

  /// 初始化弹幕接收事件
  void initDanmau() {
    liveDanmaku.onMessage = onWSMessage;
  }

  /// 接收到WebSocket信息
  void onWSMessage(LiveMessage msg) {
    if (msg.type == LiveMessageType.chat) {
      // 关键词屏蔽检查
      for (var keyword in AppSettingsController.instance.shieldList) {
        Pattern? pattern;
        if (Utils.isRegexFormat(keyword)) {
          String removedSlash = Utils.removeRegexFormat(keyword);
          try {
            pattern = RegExp(removedSlash);
          } catch (e) {
            // should avoid this during add keyword
            Log.d("关键词：$keyword 正则格式错误");
          }
        } else {
          pattern = keyword;
        }
        if (pattern != null && msg.message.contains(pattern)) {
          Log.d("关键词：$keyword\n已屏蔽消息内容：${msg.message}");
          return;
        }
      }

      if (!liveStatus.value || isBackground) {
        return;
      }

      addDanmaku([
        DanmakuContentItem(
          msg.message,
          color: Color.fromARGB(255, msg.color.r, msg.color.g, msg.color.b),
        ),
      ]);
    } else if (msg.type == LiveMessageType.online) {
      online.value = msg.data;
    } else if (msg.type == LiveMessageType.superChat) {
      //superChats.add(msg.data);
    }
  }

  /// 加载直播间信息
  void loadData() async {
    try {
      SmartDialog.showLoading(msg: "");
      pageLoadding.value = true;
      // 房间信息是三次串行请求（buvid → getInfoByRoom → getDanmuInfo）。
      // 原来没有任何总超时，一次卡在 DNS 上就是 20s×3 的 loading 遮罩：
      // 全屏遮罩吞掉所有遥控器输入，看起来就是"应用卡死了"。
      detail.value = await site.liveSite
          .getRoomDetail(roomId: roomId)
          .timeout(const Duration(seconds: 15));

      addHistory();
      online.value = detail.value!.online;
      liveStatus.value = detail.value!.status || detail.value!.isRecord;
      if (liveStatus.value) {
        getPlayQualites();
      }
      if (detail.value!.isRecord) {
        SmartDialog.showToast("当前主播未开播，正在轮播录像");
      }

      initDanmau();
      liveDanmaku.start(detail.value?.danmakuData);
    } catch (e) {
      Log.e("读取直播间失败: $e", StackTrace.current);
      errorMsg.value = "读取直播间失败：$e";
      SmartDialog.showToast(e.toString());
    } finally {
      SmartDialog.dismiss(status: SmartStatus.loading);
      pageLoadding.value = false;
    }
  }

  /// 初始化播放器
  void getPlayQualites() async {
    qualites.clear();
    currentQuality = -1;
    try {
      var playQualites =
          await site.liveSite.getPlayQualites(detail: detail.value!);

      if (playQualites.isEmpty) {
        SmartDialog.showToast("无法读取播放清晰度");
        return;
      }
      qualites.value = playQualites;
      var qualityLevel = AppSettingsController.instance.qualityLevel.value;
      if (qualityLevel == 2) {
        //最高
        currentQuality = 0;
      } else if (qualityLevel == 0) {
        //最低
        currentQuality = playQualites.length - 1;
      } else {
        //中间值
        int middle = (playQualites.length / 2).floor();
        currentQuality = middle;
      }

      getPlayUrl();
    } catch (e) {
      Log.logPrint(e);
      SmartDialog.showToast("无法读取播放清晰度");
    }
  }

  void getPlayUrl() async {
    playUrls.clear();
    playLines.clear();
    currentQualityInfo.value = qualites[currentQuality].quality;
    currentLineInfo.value = "";
    currentLineIndex = -1;
    final generation = ++_playerGeneration;
    try {
      var playUrl = await site.liveSite
          .getPlayUrls(detail: detail.value!, quality: qualites[currentQuality]);
      if (generation != _playerGeneration) {
        return;
      }
      if (playUrl.urls.isEmpty) {
        SmartDialog.showToast("无法读取播放地址");
        errorMsg.value = "无法读取播放地址";
        return;
      }
      playUrls.value = playUrl.urls;
      playLines.value = playUrl.lines;
      playHeaders = playUrl.headers;

      // 赛事/版权房会按「登录态 + 版权」压档：请求蓝光只签发超清。
      // 界面徽章必须跟着平台实际签发的那一档走，否则「720P 只有 1.3Mbps」
      // 这种矛盾读数会被当成播放器问题去查。
      final actual = playUrl.actualQuality;
      if (actual != null && actual > 0) {
        for (final q in qualites) {
          if (q.data == actual) {
            currentQualityInfo.value = q.quality;
            break;
          }
        }
      }

      currentLineIndex = 0;
      currentLineInfo.value = _lineLabel(0);
      //重置错误次数
      mediaErrorRetryCount = 0;
      stallCount = 0;
      await setPlayer();
    } catch (e) {
      Log.e("取流失败: $e", StackTrace.current);
      errorMsg.value = "取流失败：$e";
      SmartDialog.showToast("无法读取播放地址");
    }
  }

  String _lineLabel(int index) {
    final kind =
        index >= 0 && index < playLines.length ? playLines[index].kind : "";
    return kind.isEmpty ? "线路${index + 1}" : "线路${index + 1}·$kind";
  }

  void changePlayLine(int index) {
    if (index < 0 || index >= playUrls.length) {
      return;
    }
    currentLineIndex = index;
    //重置错误次数
    mediaErrorRetryCount = 0;
    _stopWatchdog();
    setPlayer();
  }

  /// 起播。[single-flight]：同一时刻只允许一次 open 在飞。
  Future<void> setPlayer() async {
    if (isClosed || playUrls.isEmpty) {
      return;
    }
    if (currentLineIndex < 0 || currentLineIndex >= playUrls.length) {
      currentLineIndex = 0;
    }
    final generation = ++_playerGeneration;
    currentLineInfo.value = _lineLabel(currentLineIndex);
    errorMsg.value = "";

    try {
      // 属性只在 onInit 里下发过一次；这里不做任何 mpv 属性写入 ——
      // 这是 09:36 / 10:12 那两次真机 ANR 的直接原因（见 player_controller.dart
      // 里 `preparePlayer` 的注释）。
      if (generation != _playerGeneration || isClosed) {
        return;
      }
      // 先 stop 再 open。并发 open 会在 libmpv 里留下两条 demuxer 线程，
      // 表面上是「黑屏 + 风扇狂转」，实际是上一个流的解码线程还没退出。
      await player
          .stop()
          .timeout(const Duration(seconds: 5), onTimeout: () {});
      if (generation != _playerGeneration || isClosed) {
        return;
      }
      await player
          .open(
            Media(
              playUrls[currentLineIndex],
              httpHeaders: playHeaders,
            ),
          )
          .timeout(const Duration(seconds: 12), onTimeout: () {});
      Log.d("播放链接\r\n：${playUrls[currentLineIndex]}");
    } catch (e) {
      Log.e("起播失败: $e", StackTrace.current);
      if (generation == _playerGeneration) {
        _recover("起播失败");
      }
      return;
    }

    if (generation == _playerGeneration) {
      _openStartedAt = DateTime.now();
      _startWatchdog();
    }
  }

  /// 卡死看门狗：直播最难受的不是报错，是**没有任何事件**的静止画面。
  ///
  /// 判据（每 2s 巡检一次）：
  /// - 起播 12s 还停在 buffering；
  /// - 起播 18s 仍没有视频尺寸（一帧都没解出来）；
  /// - 已经出过画面，但连续 buffering 超过 12s。
  /// 命中后走 `_recover` —— 先原地重开，再换线，最后才认输。
  void _startWatchdog() {
    _stopWatchdog();
    _watchdogTimer = Timer.periodic(const Duration(seconds: 2), (timer) {
      if (isClosed) {
        timer.cancel();
        return;
      }
      final startedAt = _openStartedAt;
      if (startedAt == null) {
        return;
      }
      final elapsed = DateTime.now().difference(startedAt).inSeconds;
      final buffering = player.state.buffering;
      final hasFrame = (player.state.width ?? 0) > 0;
      final stalled = (!hasFrame && elapsed >= 18) ||
          (!hasFrame && buffering && elapsed >= 12) ||
          (hasFrame && buffering && elapsed >= 12);
      if (!stalled) {
        return;
      }
      _openStartedAt = null;
      stallCount += 1;
      Log.w("看门狗：起播 ${elapsed}s 无画面（buffering=$buffering "
          "width=${player.state.width}），线路 ${currentLineIndex + 1}/${playUrls.length}");
      _recover("画面卡住 ${elapsed}s");
    });
  }

  void _stopWatchdog() {
    _watchdogTimer?.cancel();
    _watchdogTimer = null;
    _openStartedAt = null;
  }

  /// 视频输出起不来（mpv 只在日志里说，不走 error 流）
  @override
  void onVideoOutputFailure(String reason) {
    if (isClosed || compatVoFallbackApplied || _voFallbackInFlight) {
      return;
    }
    _voFallbackInFlight = true;
    Log.w("检测到视频输出失败：$reason");
    _handleError(reason).whenComplete(() => _voFallbackInFlight = false);
  }

  bool _voFallbackInFlight = false;

  /// 先试"换输出"，换不动再走普通的恢复流程。
  Future<void> _handleError(String error) async {
    if (isClosed || _recovering) {
      return;
    }
    _recovering = true;
    try {
      final switched = await switchVoToCompat(error);
      if (switched && !isClosed) {
        mediaErrorRetryCount = 0;
        errorMsg.value = "已切到兼容输出（mediacodec_embed）";
        await setPlayer();
        return;
      }
    } finally {
      _recovering = false;
    }
    if (!isClosed) {
      _recover(error.isEmpty ? "播放器错误" : error);
    }
  }

  /// 统一恢复路径：原地重开 → 换线 → 认输。
  ///
  /// 原实现里 `mediaError` 与 `mediaEnd` 各有一套计数，谁先到谁先跑，
  /// 一次断流能连开两三次；而且首帧根本没来过时没有任何东西会触发恢复。
  Future<void> _recover(String reason) async {
    if (isClosed || _recovering) {
      return;
    }
    _recovering = true;
    _stopWatchdog();
    try {
      Log.w("播放异常（$reason），第 ${mediaErrorRetryCount + 1} 次重试，"
          "线路 ${currentLineIndex + 1}/${playUrls.length}");
      if (mediaErrorRetryCount < 2) {
        mediaErrorRetryCount += 1;
        await Future.delayed(Duration(milliseconds: 500 * mediaErrorRetryCount));
        if (isClosed) {
          return;
        }
        await setPlayer();
        return;
      }
      // 这条线路已经救不回来了，换下一条
      mediaErrorRetryCount = 0;
      if (currentLineIndex < playUrls.length - 1) {
        final next = currentLineIndex + 1;
        currentLineIndex = next;
        await setPlayer();
        return;
      }
      liveStatus.value = false;
      errorMsg.value = "播放失败：$reason";
      SmartDialog.showToast("播放失败，已尝试全部线路");
    } finally {
      _recovering = false;
    }
  }

  int mediaErrorRetryCount = 0;

  /// 直播流出 EOF（FLV 被 CDN 掐断 / HLS 分片结束）走这里。
  /// 和 [mediaError] 共用一条恢复路径，不再各自计数。
  @override
  void mediaEnd() {
    Log.d("播放结束（EOF）");
    _recover("流结束");
  }

  @override
  void mediaError(String error) {
    Log.w("播放器错误：$error");
    // 显卡输出起不来时（`Failed initializing any suitable GPU context` /
    // `Could not create a GL context`），换线重开是没用的 —— 得换 vo。
    // 直接把话说清楚，别让用户看着一块黑屏猜。
    if (error.contains("GPU context") ||
        error.contains("EGL context") ||
        error.contains("video_out")) {
      errorMsg.value = "显卡输出初始化失败，正在切到兼容输出";
    }
    _handleError(error);
  }

  /// 添加历史记录
  void addHistory() {
    if (detail.value == null) {
      return;
    }
    var id = "${site.id}_$roomId";
    var history = DBService.instance.getHistory(id);
    if (history != null) {
      history.updateTime = DateTime.now();
    }
    history ??= History(
      id: id,
      roomId: roomId,
      siteId: site.id,
      userName: detail.value?.userName ?? "",
      face: detail.value?.userAvatar ?? "",
      updateTime: DateTime.now(),
    );

    DBService.instance.addOrUpdateHistory(history);
  }

  /// 关注用户
  void followUser() {
    if (detail.value == null) {
      return;
    }
    var id = "${site.id}_$roomId";
    DBService.instance.addFollow(
      FollowUser(
        id: id,
        roomId: roomId,
        siteId: site.id,
        userName: detail.value?.userName ?? "",
        face: detail.value?.userAvatar ?? "",
        addTime: DateTime.now(),
      ),
    );
    followed.value = true;
    EventBus.instance.emit(Constant.kUpdateFollow, id);
    SmartDialog.showToast("已关注");
  }

  /// 取消关注用户
  void removeFollowUser() async {
    if (detail.value == null) {
      return;
    }
    // if (!await Utils.showAlertDialog("确定要取消关注该用户吗？", title: "取消关注")) {
    //   return;
    // }

    var id = "${site.id}_$roomId";
    DBService.instance.deleteFollow(id);
    followed.value = false;
    EventBus.instance.emit(Constant.kUpdateFollow, id);
    SmartDialog.showToast("已取消关注");
  }

  void resetRoom(Site site, String roomId) async {
    if (this.site == site && this.roomId == roomId) {
      return;
    }

    rxSite.value = site;
    rxRoomId.value = roomId;

    // 清除全部消息
    _stopWatchdog();
    liveDanmaku.stop();

    danmakuController?.clear();

    // 重新设置LiveDanmaku
    liveDanmaku = site.liveSite.getDanmaku();

    // 停止播放
    await player.stop();

    // 刷新信息
    loadData();
  }

  void nextChannel() {
    //读取正在直播的频道
    var liveChannels = FollowUserService.instance.livingList;
    if (liveChannels.isEmpty) {
      SmartDialog.showToast("没有正在直播的频道");
      return;
    }
    var index = liveChannels
        .indexWhere((element) => element.id == "${site.id}_$roomId");
    // if (index == -1) {
    //   //当前频道不在列表中

    //   return;
    // }
    index += 1;
    if (index >= liveChannels.length) {
      index = 0;
    }
    var nextChannel = liveChannels[index];

    resetRoom(Sites.allSites[nextChannel.siteId]!, nextChannel.roomId);
  }

  void prevChannel() {
    //读取正在直播的频道
    var liveChannels = FollowUserService.instance.livingList;
    if (liveChannels.isEmpty) {
      SmartDialog.showToast("没有正在直播的频道");
      return;
    }
    var index = liveChannels
        .indexWhere((element) => element.id == "${site.id}_$roomId");
    // if (index == -1) {
    //   //当前频道不在列表中

    //   return;
    // }
    index -= 1;
    if (index < 0) {
      index = liveChannels.length - 1;
    }
    var nextChannel = liveChannels[index];

    resetRoom(Sites.allSites[nextChannel.siteId]!, nextChannel.roomId);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);

    if (state == AppLifecycleState.paused) {
      Log.d("进入后台");
      //进入后台，关闭弹幕
      danmakuController?.clear();
      isBackground = true;
    } else
    //返回前台
    if (state == AppLifecycleState.resumed) {
      Log.d("返回前台");
      isBackground = false;
    }
  }

  @override
  void onClose() {
    WidgetsBinding.instance.removeObserver(this);
    _datetimeTimer?.cancel();
    _datetimeTimer = null;
    doubleClickTimer?.cancel();
    _stopWatchdog();
    liveDanmaku.stop();

    danmakuController = null;
    super.onClose();
  }
}
