import 'package:livehub_tv/services/local_storage_service.dart';

import 'package:get/get.dart';

class AppSettingsController extends GetxController {
  static AppSettingsController get instance =>
      Get.find<AppSettingsController>();

  /// 缩放模式
  var scaleMode = 0.obs;

  var firstRun = false;

  @override
  void onInit() {
    firstRun = LocalStorageService.instance
        .getValue(LocalStorageService.kFirstRun, true);
    danmuSize.value = LocalStorageService.instance
        .getValue(LocalStorageService.kDanmuSize, 40.0);
    danmuOpacity.value = LocalStorageService.instance
        .getValue(LocalStorageService.kDanmuOpacity, 1.0);
    danmuArea.value = LocalStorageService.instance
        .getValue(LocalStorageService.kDanmuArea, 0.25);
    danmuSpeed.value = LocalStorageService.instance
        .getValue(LocalStorageService.kDanmuSpeed, 10.0);
    danmuEnable.value = LocalStorageService.instance
        .getValue(LocalStorageService.kDanmuEnable, true);
    danmuMaxRate.value = LocalStorageService.instance
        .getValue(LocalStorageService.kDanmuMaxRate, 12);
    danmuStrokeWidth.value = LocalStorageService.instance
        .getValue(LocalStorageService.kDanmuStrokeWidth, 4.0);
    danmuTopMargin.value = LocalStorageService.instance
        .getValue(LocalStorageService.kDanmuTopMargin, 0.0);
    danmuBottomMargin.value = LocalStorageService.instance
        .getValue(LocalStorageService.kDanmuBottomMargin, 0.0);

    hardwareDecode.value = LocalStorageService.instance
        .getValue(LocalStorageService.kHardwareDecode, true);
    chatTextSize.value = LocalStorageService.instance
        .getValue(LocalStorageService.kChatTextSize, 14.0);

    chatTextGap.value = LocalStorageService.instance
        .getValue(LocalStorageService.kChatTextGap, 4.0);

    chatBubbleStyle.value = LocalStorageService.instance.getValue(
      LocalStorageService.kChatBubbleStyle,
      false,
    );

    qualityLevel.value = LocalStorageService.instance
        .getValue(LocalStorageService.kQualityLevel, 1);
    qualityLevelCellular.value = LocalStorageService.instance
        .getValue(LocalStorageService.kQualityLevelCellular, 1);

    autoExitEnable.value = LocalStorageService.instance
        .getValue(LocalStorageService.kAutoExitEnable, false);

    autoExitDuration.value = LocalStorageService.instance
        .getValue(LocalStorageService.kAutoExitDuration, 60);

    roomAutoExitDuration.value = LocalStorageService.instance
        .getValue(LocalStorageService.kRoomAutoExitDuration, 60);

    // 兼容模式 = `vo=mediacodec_embed` + `hwdec=mediacodec`（上游遗留的
    // SurfaceView 直渲路径）。它只在 gpu VO 出花屏的老设备上才有意义：
    // 走这条路时 AndroidVideoController 每次 surface 重挂都要重设 vo/vid 并
    // seek(0)，直播流被这一步钉死的概率很高。所以默认关，需要时再开。
    playerCompatMode.value = LocalStorageService.instance
        .getValue(LocalStorageService.kPlayerCompatMode, false);

    playerAutoPause.value = LocalStorageService.instance
        .getValue(LocalStorageService.kPlayerAutoPause, false);

    autoFullScreen.value = LocalStorageService.instance
        .getValue(LocalStorageService.kAutoFullScreen, false);

    // ignore: invalid_use_of_protected_member
    shieldList.value = LocalStorageService.instance.shieldBox.values.toSet();

    scaleMode.value = LocalStorageService.instance.getValue(
      LocalStorageService.kPlayerScaleMode,
      0,
    );

    pipHideDanmu.value = LocalStorageService.instance
        .getValue(LocalStorageService.kPIPHideDanmu, true);

    styleColor.value = LocalStorageService.instance
        .getValue(LocalStorageService.kStyleColor, 0xff3498db);

    isDynamic.value = LocalStorageService.instance
        .getValue(LocalStorageService.kIsDynamic, false);

    bilibiliLoginTip.value = LocalStorageService.instance
        .getValue(LocalStorageService.kBilibiliLoginTip, true);

    playerBufferSize.value = LocalStorageService.instance
        .getValue(LocalStorageService.kPlayerBufferSize, 32);

    autoUpdateFollowEnable.value = LocalStorageService.instance
        .getValue(LocalStorageService.kAutoUpdateFollowEnable, true);

    autoUpdateFollowDuration.value = LocalStorageService.instance
        .getValue(LocalStorageService.kUpdateFollowDuration, 10);

    updateFollowThreadCount.value = LocalStorageService.instance
        .getValue(LocalStorageService.kUpdateFollowThreadCount, 4);

    super.onInit();
  }

  void setNoFirstRun() {
    LocalStorageService.instance.setValue(LocalStorageService.kFirstRun, false);
  }

  var hardwareDecode = true.obs;
  void setHardwareDecode(bool e) {
    hardwareDecode.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kHardwareDecode, e);
  }

  var chatTextSize = 14.0.obs;
  void setChatTextSize(double e) {
    chatTextSize.value = e;
    LocalStorageService.instance.setValue(LocalStorageService.kChatTextSize, e);
  }

  var chatTextGap = 4.0.obs;
  void setChatTextGap(double e) {
    chatTextGap.value = e;
    LocalStorageService.instance.setValue(LocalStorageService.kChatTextGap, e);
  }

  var chatBubbleStyle = false.obs;
  void setChatBubbleStyle(bool e) {
    chatBubbleStyle.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kChatBubbleStyle, e);
  }

  var danmuSize = 40.0.obs;
  void setDanmuSize(double e) {
    danmuSize.value = e;
    LocalStorageService.instance.setValue(LocalStorageService.kDanmuSize, e);
  }

  var danmuSpeed = 10.0.obs;
  void setDanmuSpeed(double e) {
    danmuSpeed.value = e;
    LocalStorageService.instance.setValue(LocalStorageService.kDanmuSpeed, e);
  }

  var danmuArea = 0.25.obs;
  void setDanmuArea(double e) {
    danmuArea.value = e;
    LocalStorageService.instance.setValue(LocalStorageService.kDanmuArea, e);
  }

  var danmuOpacity = 1.0.obs;
  void setDanmuOpacity(double e) {
    danmuOpacity.value = e;
    LocalStorageService.instance.setValue(LocalStorageService.kDanmuOpacity, e);
  }

  var danmuEnable = true.obs;
  void setDanmuEnable(bool e) {
    danmuEnable.value = e;
    LocalStorageService.instance.setValue(LocalStorageService.kDanmuEnable, e);
  }

  var danmuStrokeWidth = 4.0.obs;
  void setDanmuStrokeWidth(double e) {
    danmuStrokeWidth.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kDanmuStrokeWidth, e);
  }

  /// 弹幕每秒上屏上限。
  ///
  /// 1080p + 40 号字 + 1/4 显示区域时轨道只有 5 条，每条轨道大约 0.5 条/秒，
  /// 也就是**看得见的上限约 3 条/秒**。超出的部分 canvas_danmaku 会整条丢掉，
  /// 但丢掉之前已经付过「量宽 + 建 Paragraph」的钱。默认 12/s 是"看得见的上限"
  /// 的 4 倍余量：正常房间完全不受影响，赛事房少做 90% 的无用排版。
  var danmuMaxRate = 12.obs;
  void setDanmuMaxRate(int e) {
    danmuMaxRate.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kDanmuMaxRate, e);
  }

  var qualityLevel = 1.obs;
  void setQualityLevel(int level) {
    qualityLevel.value = level;
    LocalStorageService.instance
        .setValue(LocalStorageService.kQualityLevel, level);
  }

  var qualityLevelCellular = 1.obs;
  void setQualityLevelCellular(int level) {
    qualityLevelCellular.value = level;
    LocalStorageService.instance
        .setValue(LocalStorageService.kQualityLevelCellular, level);
  }

  var autoExitEnable = false.obs;
  void setAutoExitEnable(bool e) {
    autoExitEnable.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kAutoExitEnable, e);
  }

  var autoExitDuration = 60.obs;
  void setAutoExitDuration(int e) {
    autoExitDuration.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kAutoExitDuration, e);
  }

  var roomAutoExitDuration = 60.obs;
  void setRoomAutoExitDuration(int e) {
    roomAutoExitDuration.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kRoomAutoExitDuration, e);
  }

  var playerCompatMode = false.obs;
  void setPlayerCompatMode(bool e) {
    playerCompatMode.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kPlayerCompatMode, e);
  }

  var playerBufferSize = 32.obs;
  void setPlayerBufferSize(int e) {
    playerBufferSize.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kPlayerBufferSize, e);
  }

  var playerAutoPause = false.obs;
  void setPlayerAutoPause(bool e) {
    playerAutoPause.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kPlayerAutoPause, e);
  }

  var autoFullScreen = false.obs;
  void setAutoFullScreen(bool e) {
    autoFullScreen.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kAutoFullScreen, e);
  }

  RxSet<String> shieldList = <String>{}.obs;
  void addShieldList(String e) {
    shieldList.add(e);
    LocalStorageService.instance.shieldBox.put(e, e);
  }

  void removeShieldList(String e) {
    shieldList.remove(e);
    LocalStorageService.instance.shieldBox.delete(e);
  }

  Future clearShieldList() async {
    shieldList.clear();
    await LocalStorageService.instance.shieldBox.clear();
  }

  void setScaleMode(int value) {
    scaleMode.value = value;
    LocalStorageService.instance.setValue(
      LocalStorageService.kPlayerScaleMode,
      value,
    );
  }

  RxList<String> siteSort = RxList<String>();
  void setSiteSort(List<String> e) {
    siteSort.value = e;
    LocalStorageService.instance.setValue(
      LocalStorageService.kSiteSort,
      siteSort.join(","),
    );
  }

  RxList<String> homeSort = RxList<String>();
  void setHomeSort(List<String> e) {
    homeSort.value = e;
    LocalStorageService.instance.setValue(
      LocalStorageService.kHomeSort,
      homeSort.join(","),
    );
  }

  var pipHideDanmu = true.obs;
  void setPIPHideDanmu(bool e) {
    pipHideDanmu.value = e;
    LocalStorageService.instance.setValue(LocalStorageService.kPIPHideDanmu, e);
  }

  var styleColor = 0xff3498db.obs;
  void setStyleColor(int e) {
    styleColor.value = e;
    LocalStorageService.instance.setValue(LocalStorageService.kStyleColor, e);
  }

  var isDynamic = false.obs;
  void setIsDynamic(bool e) {
    isDynamic.value = e;
    LocalStorageService.instance.setValue(LocalStorageService.kIsDynamic, e);
  }

  var danmuTopMargin = 0.0.obs;
  void setDanmuTopMargin(double e) {
    danmuTopMargin.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kDanmuTopMargin, e);
  }

  var danmuBottomMargin = 0.0.obs;
  void setDanmuBottomMargin(double e) {
    danmuBottomMargin.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kDanmuBottomMargin, e);
  }

  var bilibiliLoginTip = true.obs;
  void setBiliBiliLoginTip(bool e) {
    bilibiliLoginTip.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kBilibiliLoginTip, e);
  }

  var autoUpdateFollowEnable = false.obs;
  void setAutoUpdateFollowEnable(bool e) {
    autoUpdateFollowEnable.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kAutoUpdateFollowEnable, e);
  }

  var autoUpdateFollowDuration = 10.obs;
  void setAutoUpdateFollowDuration(int e) {
    autoUpdateFollowDuration.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kUpdateFollowDuration, e);
  }

  var updateFollowThreadCount = 4.obs;
  void setUpdateFollowThreadCount(int e) {
    updateFollowThreadCount.value = e;
    LocalStorageService.instance
        .setValue(LocalStorageService.kUpdateFollowThreadCount, e);
  }
}
