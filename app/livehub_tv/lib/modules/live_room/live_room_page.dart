import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_smart_dialog/flutter_smart_dialog.dart';
import 'package:get/get.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:livehub_tv/app/controller/app_settings_controller.dart';
import 'package:livehub_tv/app/log.dart';
import 'package:livehub_tv/modules/live_room/live_room_controller.dart';
import 'package:livehub_tv/modules/live_room/player/player_controls.dart';
import 'package:livehub_ui/livehub_ui.dart';

class LiveRoomPage extends GetView<LiveRoomController> {
  const LiveRoomPage({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) {
          //双击返回键退出
          if (controller.doubleClickExit) {
            controller.doubleClickTimer?.cancel();
            Get.back();
            return;
          }
          controller.doubleClickExit = true;
          SmartDialog.showToast("再按一次退出播放器");
          controller.doubleClickTimer = Timer(const Duration(seconds: 2), () {
            controller.doubleClickExit = false;
            controller.doubleClickTimer!.cancel();
          });
        }
      },
      child: KeyboardListener(
        focusNode: controller.focusNode,
        autofocus: true,
        onKeyEvent: onKeyEvent,
        child: Scaffold(
          backgroundColor: Colors.black,
          body: Obx(
            () => buildMediaPlayer(),
          ),
        ),
      ),
    );
  }

  void onKeyEvent(KeyEvent key) {
    if (key is KeyUpEvent) {
      return;
    }
    Log.logPrint(key);

    // 面板打开时，方向键 / OK 归面板内控件；全局快捷键不响应。
    if (Get.isDialogOpen == true) {
      return;
    }

    // if (key.logicalKey == LogicalKeyboardKey.escape ||
    //     key.logicalKey == LogicalKeyboardKey.backspace ||
    //     key.logicalKey == LogicalKeyboardKey.goBack) {
    //   // Get.back();
    //   return;
    // }
    // 点击OK、Enter、Select键时显示/隐藏控制器
    if (key.logicalKey == LogicalKeyboardKey.select ||
        key.logicalKey == LogicalKeyboardKey.enter ||
        key.logicalKey == LogicalKeyboardKey.space) {
      if (!controller.showControlsState.value) {
        controller.showControls();
      } else {
        controller.hideControls();
      }
      return;
    }

    // 点击Menu打开/关闭设置
    if (key.logicalKey == LogicalKeyboardKey.keyM ||
        key.logicalKey == LogicalKeyboardKey.contextMenu ||
        key.logicalKey == LogicalKeyboardKey.arrowRight) {
      showPlayerSettings(controller);
      return;
    }

    // 点击左键显示关注用户
    if (key.logicalKey == LogicalKeyboardKey.arrowLeft) {
      showFollowUser(controller);
      return;
    }

    // // 点击右键关注/取消关注
    // if (key.logicalKey == LogicalKeyboardKey.arrowRight) {
    //   if (controller.followed.value) {
    //     controller.removeFollowUser();
    //   } else {
    //     controller.followUser();
    //   }

    //   return;
    // }

    // 点击上键切换上一个直播
    if (key.logicalKey == LogicalKeyboardKey.arrowUp) {
      controller.prevChannel();
      return;
    }

    // 点击下键切换下一个直播
    if (key.logicalKey == LogicalKeyboardKey.arrowDown) {
      controller.nextChannel();
      return;
    }
  }

  Widget buildMediaPlayer() {
    final t = Get.context!.lh;
    var boxFit = BoxFit.contain;
    double? aspectRatio;
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
    return Stack(
      children: [
        Video(
          key: controller.globalPlayerKey,
          controller: controller.videoController,
          pauseUponEnteringBackgroundMode:
              AppSettingsController.instance.playerAutoPause.value,
          resumeUponEnteringForegroundMode:
              AppSettingsController.instance.playerAutoPause.value,
          controls: (state) {
            return playerControls(state, controller);
          },
          aspectRatio: aspectRatio,
          fit: boxFit,
        ),
        Obx(
          () => Visibility(
            visible:
                !controller.liveStatus.value && !controller.pageLoadding.value,
            child: Center(
              child: Text(
                "未开播",
                style: t.body.copyWith(color: t.muted),
              ),
            ),
          ),
        ),
        // 播放链路出错时得说出来。以前 errorMsg 只写不读，
        // 取流失败 / 换线换完 / 看门狗放弃 —— 三种结局在屏幕上长得一模一样：
        // 黑屏或一张静止的画面，没有任何提示。
        Obx(
          () => Visibility(
            visible: controller.errorMsg.value.isNotEmpty &&
                !controller.pageLoadding.value,
            child: Center(
              child: Container(
                padding: EdgeInsets.symmetric(
                  horizontal: 40 * t.unit,
                  vertical: 28 * t.unit,
                ),
                decoration: BoxDecoration(
                  color: const Color(0xCC1A140F),
                  borderRadius: BorderRadius.circular(16 * t.unit),
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text("播放异常", style: t.title),
                    SizedBox(height: 12 * t.unit),
                    Text(
                      controller.errorMsg.value,
                      textAlign: TextAlign.center,
                      style: t.body.copyWith(color: t.muted),
                    ),
                    SizedBox(height: 12 * t.unit),
                    Text(
                      "按 → 打开设置换线路，按 ↑ / ↓ 换台",
                      style: t.meta.copyWith(color: t.muted),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}
