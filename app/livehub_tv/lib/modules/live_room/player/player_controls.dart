import 'package:canvas_danmaku/canvas_danmaku.dart';
import 'package:flutter/material.dart';
import 'package:flutter_screenutil/flutter_screenutil.dart';
import 'package:get/get.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/controller/app_settings_controller.dart';
import 'package:livehub_tv/app/sites.dart';
import 'package:livehub_tv/app/utils.dart';
import 'package:livehub_tv/modules/live_room/live_room_controller.dart';
import 'package:livehub_tv/services/follow_user_service.dart';
import 'package:livehub_tv/widgets/button/highlight_button.dart';
import 'package:livehub_tv/widgets/card/anchor_card.dart';
import 'package:livehub_tv/widgets/settings/settings_rows.dart';
import 'package:livehub_tv/widgets/status/app_empty_widget.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// §9.1：不调曝光，只压两条带。顶部条从 .90 起笔，底栏收到 .94。
const _topBand = LinearGradient(
  begin: Alignment.topCenter,
  end: Alignment.bottomCenter,
  colors: [
    Color(0xE6000000),
    Color(0xBD000000),
    Color(0x70000000),
    Color(0x00000000),
  ],
  stops: [0, 0.12, 0.20, 0.36],
);

const _bottomBand = LinearGradient(
  begin: Alignment.topCenter,
  end: Alignment.bottomCenter,
  colors: [
    Color(0x00000000),
    Color(0x94000000),
    Color(0xD1000000),
    Color(0xF0000000),
  ],
  stops: [0, 0.26, 0.62, 1],
);

Widget playerControls(VideoState videoState, LiveRoomController controller) {
  return buildControls(videoState, controller);
}

Widget buildControls(VideoState videoState, LiveRoomController controller) {
  final t = Get.context!.lh;
  return Stack(
    children: [
      Container(),
      buildDanmuView(videoState, controller),
      // 点击播放器打开设置
      Positioned.fill(
        child: GestureDetector(onTap: () => showPlayerSettings(controller)),
      ),
      Center(
        child: // 中间
            StreamBuilder(
          stream: videoState.widget.controller.player.stream.buffering,
          initialData: videoState.widget.controller.player.state.buffering,
          builder: (_, s) => Visibility(
            visible: s.data ?? false,
            child: SizedBox(
              width: 64 * t.unit,
              height: 64 * t.unit,
              child: CircularProgressIndicator(
                strokeWidth: 8 * t.unit,
                color: t.text,
              ),
            ),
          ),
        ),
      ),

      // 顶部
      Obx(
        () => AnimatedPositioned(
          left: 0,
          right: 0,
          top: (controller.showControlsState.value &&
                  !controller.lockControlsState.value)
              ? 0
              : -200 * t.unit,
          duration: const Duration(milliseconds: 200),
          child: Container(
            padding: EdgeInsets.only(
              left: 32 * t.unit,
              right: 32 * t.unit,
              top: 24 * t.unit,
              bottom: 24 * t.unit,
            ),
            decoration: const BoxDecoration(
              gradient: _topBand,
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        controller.detail.value?.title ?? '正在读取直播信息...',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: t.body.copyWith(
                          shadows: const [
                            Shadow(color: Color(0x99000000), blurRadius: 8),
                          ],
                        ),
                      ),
                      Text(
                        "${controller.detail.value?.userName} · ${controller.followed.value ? "已关注" : "未关注"}",
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: t.meta.copyWith(
                          color: t.muted,
                          shadows: const [
                            Shadow(color: Color(0x99000000), blurRadius: 8),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                Obx(
                  () => Text(
                    controller.datetime.value,
                    style: t.h1.copyWith(
                      shadows: const [
                        Shadow(color: Color(0x99000000), blurRadius: 8),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
      // 底部
      Obx(
        () => AnimatedPositioned(
          left: 0,
          right: 0,
          bottom: (controller.showControlsState.value &&
                  !controller.lockControlsState.value)
              ? 0
              : -300 * t.unit,
          duration: const Duration(milliseconds: 200),
          child: Container(
            decoration: const BoxDecoration(
              gradient: _bottomBand,
            ),
            padding: EdgeInsets.only(
              left: 32 * t.unit,
              right: 32 * t.unit,
              bottom: 24 * t.unit,
              top: 24 * t.unit,
            ),
            child: Column(
              children: [
                Row(
                  children: [
                    //清晰度
                    Obx(
                      () => Text(
                        "清晰度: ${controller.currentQualityInfo.value}",
                        style: t.meta.copyWith(color: t.muted),
                      ),
                    ),
                    //线路
                    SizedBox(width: 32 * t.unit),
                    Obx(
                      () => Text(
                        "线路：${controller.currentLineInfo.value}",
                        style: t.meta.copyWith(color: t.muted),
                      ),
                    ),
                    //弹幕开关
                    SizedBox(width: 32 * t.unit),
                    Obx(
                      () => Text(
                        "弹幕: ${controller.showDanmakuState.value ? "开" : "关"}",
                        style: t.meta.copyWith(color: t.muted),
                      ),
                    ),
                    //分辨率
                    SizedBox(width: 32 * t.unit),
                    Obx(
                      () => Text(
                        "分辨率: ${controller.width.value}x${controller.height.value}",
                        style: t.meta.copyWith(color: t.muted),
                      ),
                    ),
                  ],
                ),
                SizedBox(height: 12 * t.unit),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    Icon(
                      Icons.arrow_circle_up_rounded,
                      color: t.text,
                      size: 40 * t.unit,
                    ),
                    SizedBox(width: 16 * t.unit),
                    Text("上一频道", style: t.body),
                    SizedBox(width: 32 * t.unit),
                    Icon(
                      Icons.arrow_circle_down_rounded,
                      color: t.text,
                      size: 40 * t.unit,
                    ),
                    SizedBox(width: 16 * t.unit),
                    Text("下一频道", style: t.body),
                    SizedBox(width: 32 * t.unit),
                    Icon(
                      Icons.arrow_circle_left_outlined,
                      color: t.text,
                      size: 40 * t.unit,
                    ),
                    SizedBox(width: 16 * t.unit),
                    Text("关注列表", style: t.body),
                    SizedBox(width: 32 * t.unit),
                    Icon(
                      Icons.arrow_circle_right_outlined,
                      color: t.text,
                      size: 40 * t.unit,
                    ),
                    SizedBox(width: 16 * t.unit),
                    Icon(Icons.menu, color: t.text, size: 44 * t.unit),
                    SizedBox(width: 16 * t.unit),
                    Text("设置", style: t.body),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    ],
  );
}

Widget buildDanmuView(VideoState videoState, LiveRoomController controller) {
  var padding = MediaQuery.of(videoState.context).padding;
  // 选项从控制器统一取（含 massiveMode / 描边），不再在这里拼第二份，
  // 否则「设置页改了、播放页没变」这种不一致会一直存在。
  controller.danmakuView ??= DanmakuScreen(
    key: controller.globalDanmuKey,
    createdController: controller.initDanmakuController,
    option: controller.danmakuOption,
  );
  return Positioned.fill(
    top: padding.top,
    bottom: padding.bottom,
    child: Obx(
      () => Offstage(
        offstage: !controller.showDanmakuState.value,
        child: Padding(
          padding: controller.fullScreenState.value
              ? EdgeInsets.only(
                  top: AppSettingsController.instance.danmuTopMargin.value,
                  bottom:
                      AppSettingsController.instance.danmuBottomMargin.value,
                )
              : EdgeInsets.zero,
          child: controller.danmakuView!,
        ),
      ),
    ),
  );
}

// void showLinesInfo(LiveRoomController controller) {
//   Utils.showRightDialog(
//     title: "线路",
//     useSystem: true,
//     child: ListView.builder(
//       padding: EdgeInsets.zero,
//       itemCount: controller.playUrls.length,
//       itemBuilder: (_, i) {
//         return ListTile(
//           selected: controller.currentLineIndex == i,
//           title: Text.rich(
//             TextSpan(
//               text: "线路${i + 1}",
//               children: [
//                 WidgetSpan(
//                     child: Container(
//                   decoration: BoxDecoration(
//                     borderRadius: AppStyle.radius4,
//                     border: Border.all(
//                       color: Colors.grey,
//                     ),
//                   ),
//                   padding: AppStyle.edgeInsetsH4,
//                   margin: AppStyle.edgeInsetsL8,
//                   child: Text(
//                     controller.playUrls[i].contains(".flv") ? "FLV" : "HLS",
//                     style: const TextStyle(
//                       fontSize: 12,
//                     ),
//                   ),
//                 )),
//               ],
//             ),
//             style: const TextStyle(fontSize: 14),
//           ),
//           minLeadingWidth: 16,
//           onTap: () {
//             Utils.hideRightDialog();
//             //controller.currentLineIndex = i;
//             //controller.setPlayer();
//             controller.changePlayLine(i);
//           },
//         );
//       },
//     ),
//   );
// }

// void showQualitesInfo(LiveRoomController controller) {
//   Utils.showRightDialog(
//     title: "清晰度",
//     useSystem: true,
//     child: ListView.builder(
//       padding: EdgeInsets.zero,
//       itemCount: controller.qualites.length,
//       itemBuilder: (_, i) {
//         var item = controller.qualites[i];
//         return ListTile(
//           selected: controller.currentQuality == i,
//           title: Text(
//             item.quality,
//             style: const TextStyle(fontSize: 14),
//           ),
//           minLeadingWidth: 16,
//           onTap: () {
//             Utils.hideRightDialog();
//             controller.currentQuality = i;
//             controller.getPlayUrl();
//           },
//         );
//       },
//     ),
//   );
// }

void showPlayerSettings(LiveRoomController controller) {
  final t = Get.context!.lh;
  // 移除焦点
  controller.focusNode.unfocus();
  final initialQuality = controller.currentQuality;
  final initialLine = controller.currentLineIndex;

  var followFocusNode = AppFocusNode();
  var qualityFoucsNode = AppFocusNode();
  var lineFoucsNode = AppFocusNode();
  var scaleFoucsNode = AppFocusNode();
  var danmakuFoucsNode = AppFocusNode();
  var danmakuSizeFoucsNode = AppFocusNode();
  var danmakuSpeedFoucsNode = AppFocusNode();
  var danmakuAreaFoucsNode = AppFocusNode();
  var danmakuOpacityFoucsNode = AppFocusNode();
  Utils.showSystemRightDialog(
    width: 800 * t.unit,
    // 对话框是独立路由，但播放页根部那个 KeyboardListener 一直占着主焦点；
    // 不主动把焦点搬进来，方向键/OK 会全落到播放页（它又会因为“dialog 已打开”
    // 直接 return），表现就是「设置项点不动」。
    child: FocusScope(
      autofocus: true,
      child: Column(
      children: [
        SizedBox(height: 24 * t.unit),
        Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            SizedBox(width: 48 * t.unit),
            HighlightButton(
              focusNode: AppFocusNode(),
              iconData: Icons.arrow_back,
              text: "返回",
              onTap: () {
                Get.back();
              },
            ),
            SizedBox(width: 32 * t.unit),
            Text(
              "设置",
              style: t.h2,
            ),
            SizedBox(width: 24 * t.unit),
            const Spacer(),
          ],
        ),
        Expanded(
          child: ListView(
            padding: EdgeInsets.all(48 * t.unit),
            children: [
              Obx(
                () => SettingsSwitchRow(
                  focusNode: followFocusNode,
                  autofocus: true,
                  title: "关注用户",
                  value: controller.followed.value,
                  onChanged: (e) {
                    if (e) {
                      controller.followUser();
                    } else {
                      controller.removeFollowUser();
                    }
                  },
                ),
              ),

              Divider(
                color: Colors.white.withValues(alpha: 0.10),
                height: 36 * t.unit,
              ),
              SizedBox(height: 24 * t.unit),
              Padding(
                padding: EdgeInsets.symmetric(horizontal: 20 * t.unit),
                child: Text(
                  "清晰度与线路",
                  style: t.title,
                ),
              ),
              SizedBox(height: 24 * t.unit),

              // 清晰度切换
              Obx(
                () => SettingsChoiceRow(
                  title: "清晰度",
                  focusNode: qualityFoucsNode,
                  items: controller.qualites
                      .asMap()
                      .map((i, e) => MapEntry(i, e.quality))
                      .cast<int, String>(),
                  value: controller.currentQuality,
                  onChanged: (e) {
                    controller.currentQuality = e as int;
                  },
                ),
              ),

              SizedBox(height: 32 * t.unit),
              // 线路切换
              Obx(
                () => SettingsChoiceRow(
                  title: "线路",
                  focusNode: lineFoucsNode,
                  // 方块里只放「线路N」。把 `·FLV / ·fMP4` 塞进来的话，
                  // 4 条线路时最长的那个标签会把分段控件顶出面板（就是那条黄黑警示条）；
                  // 类型改放这一行的副标题，既能看见，也不会撑宽。
                  desc: controller.currentLineInfo.value.isEmpty
                      ? null
                      : "当前：${controller.currentLineInfo.value}",
                  items: controller.playUrls
                      .asMap()
                      .map((i, e) => MapEntry(i, "线路${i + 1}"))
                      .cast<int, String>(),
                  value: controller.currentLineIndex,
                  onChanged: (e) {
                    controller.currentLineIndex = e as int;
                  },
                ),
              ),
              Divider(
                color: Colors.white.withValues(alpha: 0.10),
                height: 36 * t.unit,
              ),
              Padding(
                padding: EdgeInsets.symmetric(horizontal: 20 * t.unit),
                child: Text(
                  "播放器",
                  style: t.title,
                ),
              ),
              SizedBox(height: 24 * t.unit),
              Obx(
                () => SettingsChoiceRow(
                  focusNode: scaleFoucsNode,
                  title: "画面比例",
                  items: const {0: "适应", 1: "拉伸", 2: "铺满", 3: "16:9", 4: "4:3"},
                  value: AppSettingsController.instance.scaleMode.value,
                  onChanged: (e) {
                    AppSettingsController.instance.setScaleMode(e as int);
                    controller.updateScaleMode();
                  },
                ),
              ),
              Divider(
                color: Colors.white.withValues(alpha: 0.10),
                height: 36 * t.unit,
              ),
              Padding(
                padding: EdgeInsets.symmetric(horizontal: 20 * t.unit),
                child: Text(
                  "弹幕",
                  style: t.title,
                ),
              ),
              SizedBox(height: 24 * t.unit),
              Obx(
                () => SettingsSwitchRow(
                  focusNode: danmakuFoucsNode,
                  title: "弹幕开关",
                  value: controller.showDanmakuState.value,
                  onChanged: (e) {
                    controller.showDanmakuState.value = e;
                    AppSettingsController.instance.setDanmuEnable(e);
                  },
                ),
              ),
              SizedBox(height: 24 * t.unit),
              Obx(
                () => SettingsChoiceRow(
                  focusNode: danmakuSizeFoucsNode,
                  title: "弹幕大小",
                  items: {
                    24.0: "24",
                    32.0: "32",
                    40.0: "40",
                    48.0: "48",
                    56.0: "56",
                    64.0: "64",
                    72.0: "72",
                  },
                  value: AppSettingsController.instance.danmuSize.value,
                  onChanged: (e) {
                    AppSettingsController.instance.setDanmuSize(e as double);
                    controller.updateDanmuOption(
                      controller.danmakuController?.option.copyWith(
                        fontSize: e.w,
                      ),
                    );
                  },
                ),
              ),
              SizedBox(height: 24 * t.unit),
              Obx(
                () => SettingsChoiceRow(
                  focusNode: danmakuSpeedFoucsNode,
                  title: "弹幕速度",
                  items: {
                    18.0: "很慢",
                    14.0: "较慢",
                    12.0: "慢",
                    10.0: "正常",
                    8.0: "快",
                    6.0: "较快",
                    4.0: "很快",
                  },
                  value: AppSettingsController.instance.danmuSpeed.value,
                  onChanged: (e) {
                    AppSettingsController.instance.setDanmuSpeed(e as double);
                    controller.updateDanmuOption(
                      controller.danmakuController?.option.copyWith(
                        duration: e.toInt(),
                      ),
                    );
                  },
                ),
              ),

              SizedBox(height: 24 * t.unit),
              Obx(
                () => SettingsChoiceRow(
                  focusNode: danmakuAreaFoucsNode,
                  title: "显示区域",
                  items: {0.25: "1/4", 0.5: "1/2", 0.75: "3/4", 1.0: "全屏"},
                  value: AppSettingsController.instance.danmuArea.value,
                  onChanged: (e) {
                    AppSettingsController.instance.setDanmuArea(e as double);
                    controller.updateDanmuOption(
                      controller.danmakuController?.option.copyWith(
                        area: e,
                      ),
                    );
                  },
                ),
              ),
              SizedBox(height: 24 * t.unit),
              Obx(
                () => SettingsChoiceRow(
                  focusNode: danmakuOpacityFoucsNode,
                  title: "不透明度",
                  items: {
                    0.1: "10%",
                    0.2: "20%",
                    0.3: "30%",
                    0.4: "40%",
                    0.5: "50%",
                    0.6: "60%",
                    0.7: "70%",
                    0.8: "80%",
                    0.9: "90%",
                    1.0: "100%",
                  },
                  value: AppSettingsController.instance.danmuOpacity.value,
                  onChanged: (e) {
                    AppSettingsController.instance.setDanmuOpacity(e as double);
                    controller.updateDanmuOption(
                      controller.danmakuController?.option.copyWith(
                        opacity: e,
                      ),
                    );
                  },
                ),
              ),
            ],
          ),
        ),
        ],
      ),
    ),
  ).then((value) {
    // 清晰度/线路只在面板关闭时应用一次：连续左右步进不应触发多次重连。
    if (controller.currentQuality != initialQuality) {
      controller.getPlayUrl();
    } else if (controller.currentLineIndex != initialLine) {
      controller.changePlayLine(controller.currentLineIndex);
    }
    // 还原焦点
    controller.focusNode.requestFocus();
  });

  // 对话框完成首帧布局后再把焦点搬进去，避免和路由的 autofocus 抢。
  Future.delayed(const Duration(milliseconds: 150), () {
    if (followFocusNode.context != null && followFocusNode.canRequestFocus) {
      followFocusNode.requestFocus();
    }
  });
}

void showFollowUser(LiveRoomController controller) {
  final t = Get.context!.lh;
  controller.focusNode.unfocus();
  var currentIndex = 0;
  if (controller.followed.value) {
    currentIndex = FollowUserService.instance.livingList.indexWhere(
      (e) =>
          e.id ==
          "${controller.rxSite.value.id}_${controller.detail.value?.roomId}",
    );
    if (currentIndex == -1) {
      currentIndex = 0;
    }
  }

  Utils.showSystemRightDialog(
    width: 800 * t.unit,
    // 对话框是独立路由，但播放页根部那个 KeyboardListener 一直占着主焦点；
    // 不主动把焦点搬进来，方向键/OK 会全落到播放页（它又会因为“dialog 已打开”
    // 直接 return），表现就是「设置项点不动」。
    child: FocusScope(
      autofocus: true,
      child: Column(
      children: [
        SizedBox(height: 24 * t.unit),
        Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            SizedBox(width: 48 * t.unit),
            HighlightButton(
              focusNode: AppFocusNode(),
              iconData: Icons.arrow_back,
              text: "返回",
              autofocus: currentIndex == 0 &&
                  FollowUserService.instance.livingList.isEmpty,
              onTap: () {
                Get.back();
              },
            ),
            SizedBox(width: 32 * t.unit),
            Text(
              "我的关注",
              style: t.h2,
            ),
            SizedBox(width: 24 * t.unit),
            const Spacer(),
          ],
        ),
        Expanded(
          child: Stack(
            children: [
              Obx(
                () => ListView.separated(
                  itemCount: FollowUserService.instance.livingList.length,
                  separatorBuilder: (context, index) =>
                      SizedBox(height: 32 * t.unit),
                  padding: EdgeInsets.all(40 * t.unit).copyWith(
                    left: 48 * t.unit,
                    right: 48 * t.unit,
                  ),
                  itemBuilder: (_, i) {
                    var item = FollowUserService.instance.livingList[i];
                    var site = Sites.allSites[item.siteId]!;
                    return AnchorCard(
                      face: item.face,
                      name: item.userName,
                      siteId: item.siteId,
                      liveStatus: item.liveStatus.value,
                      roomId: item.roomId,
                      autofocus: i == currentIndex,
                      onTap: () {
                        controller.resetRoom(site, item.roomId);
                        Get.back();
                      },
                    );
                  },
                ),
              ),
              Obx(
                () => Visibility(
                  visible: FollowUserService.instance.list.isEmpty,
                  child: const AppEmptyWidget(text: "关注列表为空，快去关注一些主播吧"),
                ),
              ),
            ],
          ),
        ),
        ],
      ),
    ),
  ).then((value) {
    // 还原焦点
    controller.focusNode.requestFocus();
  });

}
