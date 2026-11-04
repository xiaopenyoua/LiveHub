import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/controller/base_controller.dart';
import 'package:livehub_tv/app/utils.dart';
import 'package:livehub_tv/routes/app_navigation.dart';
import 'package:livehub_tv/services/bilibili_account_service.dart';

class SettingsController extends BaseController {
  static const int groupCount = 5;

  /// 当前设置分组：播放 / 弹幕 / 关注 / 账号与同步 / 关于。
  var groupIndex = 0.obs;

  AppFocusNode hardwareDecodeFocusNode = AppFocusNode();
  AppFocusNode compatibleModeFocusNode = AppFocusNode();
  AppFocusNode scaleFoucsNode = AppFocusNode();
  AppFocusNode defaultQualityFocusNode = AppFocusNode();
  AppFocusNode danmakuFoucsNode = AppFocusNode();
  AppFocusNode danmakuSizeFoucsNode = AppFocusNode();
  AppFocusNode danmakuSpeedFoucsNode = AppFocusNode();
  AppFocusNode danmakuAreaFoucsNode = AppFocusNode();
  AppFocusNode danmakuOpacityFoucsNode = AppFocusNode();
  AppFocusNode danmakuStorkeFoucsNode = AppFocusNode();
  AppFocusNode danmakuRateFoucsNode = AppFocusNode();

  AppFocusNode autoUpdateFollowEnableFocusNode = AppFocusNode();
  AppFocusNode autoUpdateFollowDurationFocusNode = AppFocusNode();
  AppFocusNode updateFollowThreadFocusNode = AppFocusNode();

  AppFocusNode bilibiliFoucsNode = AppFocusNode();
  AppFocusNode syncFocusNode = AppFocusNode();
  AppFocusNode versionFocusNode = AppFocusNode();

  /// 5 个分组页签、3 个无需登录账号行的焦点节点。
  /// 这些控件会在 build 里被 Obx 重建，节点必须持久化，不能在 build 内 new。
  /// [syncTopFocusNode] 保留：原顶栏“数据同步”按钮已移除，但焦点节点不删。
  AppFocusNode syncTopFocusNode = AppFocusNode();
  AppFocusNode tabPlayFocusNode = AppFocusNode();
  AppFocusNode tabDanmakuFocusNode = AppFocusNode();
  AppFocusNode tabFollowFocusNode = AppFocusNode();
  AppFocusNode tabAccountFocusNode = AppFocusNode();
  AppFocusNode tabAboutFocusNode = AppFocusNode();
  AppFocusNode douyuAccountFocusNode = AppFocusNode();
  AppFocusNode huyaAccountFocusNode = AppFocusNode();
  AppFocusNode douyinAccountFocusNode = AppFocusNode();

  @override
  void onReady() {
    super.onReady();
    // 进设置页是想改设置：默认焦点落在第一条设置行，不是分组条，也不是左栏。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (isClosed) return;
      hardwareDecodeFocusNode.requestFocus();
    });
  }

  List<AppFocusNode> get groupTabFocusNodes => [
        tabPlayFocusNode,
        tabDanmakuFocusNode,
        tabFollowFocusNode,
        tabAccountFocusNode,
        tabAboutFocusNode,
      ];

  /// 每个分组的第一条设置行。换组后焦点落到这里。
  AppFocusNode firstRowFocusNode(int index) {
    switch (index) {
      case 0:
        return hardwareDecodeFocusNode;
      case 1:
        return danmakuFoucsNode;
      case 2:
        return autoUpdateFollowEnableFocusNode;
      case 3:
        return syncFocusNode;
      default:
        return versionFocusNode;
    }
  }

  void selectGroup(int index) {
    if (index < 0 || index >= groupCount) return;
    if (groupIndex.value == index) {
      firstRowFocusNode(index).requestFocus();
      return;
    }
    groupIndex.value = index;
    // 新分组的行要等这一帧 build 完才挂上 Focus，下一帧再请求焦点。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (isClosed) return;
      firstRowFocusNode(index).requestFocus();
    });
  }

  @override
  void onClose() {
    hardwareDecodeFocusNode.dispose();
    compatibleModeFocusNode.dispose();
    scaleFoucsNode.dispose();
    defaultQualityFocusNode.dispose();
    danmakuFoucsNode.dispose();
    danmakuSizeFoucsNode.dispose();
    danmakuSpeedFoucsNode.dispose();
    danmakuAreaFoucsNode.dispose();
    danmakuOpacityFoucsNode.dispose();
    danmakuStorkeFoucsNode.dispose();
    autoUpdateFollowEnableFocusNode.dispose();
    autoUpdateFollowDurationFocusNode.dispose();
    updateFollowThreadFocusNode.dispose();
    bilibiliFoucsNode.dispose();
    syncFocusNode.dispose();
    versionFocusNode.dispose();
    syncTopFocusNode.dispose();
    tabPlayFocusNode.dispose();
    tabDanmakuFocusNode.dispose();
    tabFollowFocusNode.dispose();
    tabAccountFocusNode.dispose();
    tabAboutFocusNode.dispose();
    douyuAccountFocusNode.dispose();
    huyaAccountFocusNode.dispose();
    douyinAccountFocusNode.dispose();
    super.onClose();
  }

  void bilibiliTap() async {
    if (BiliBiliAccountService.instance.logined.value) {
      var result = await Utils.showAlertDialog("确定要退出哔哩哔哩账号吗？", title: "退出登录");
      if (result) {
        BiliBiliAccountService.instance.logout();
      }
    } else {
      AppNavigator.toBiliBiliLogin();
    }
  }
}
