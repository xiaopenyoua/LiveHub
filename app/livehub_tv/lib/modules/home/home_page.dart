import 'package:flutter/material.dart';
import 'package:flutter_staggered_grid_view/flutter_staggered_grid_view.dart';
import 'package:get/get.dart';
import 'package:lottie/lottie.dart';
import 'package:remixicon/remixicon.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/utils.dart';
import 'package:livehub_tv/modules/home/home_controller.dart';
import 'package:livehub_tv/services/follow_user_service.dart';
import 'package:livehub_tv/widgets/app_scaffold.dart';
import 'package:livehub_tv/widgets/button/highlight_button.dart';
import 'package:livehub_tv/widgets/button/highlight_list_tile.dart';
import 'package:livehub_tv/widgets/card/anchor_card.dart';
import 'package:livehub_tv/widgets/button/home_big_button.dart';
import 'package:livehub_tv/widgets/net_image.dart';
import 'package:livehub_tv/widgets/status/app_empty_widget.dart';
import 'package:livehub_ui/livehub_ui.dart';

class HomePage extends GetView<HomeController> {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return AppScaffold(
      child: Column(
        children: [
          SizedBox(height: 32 * t.unit),
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              SizedBox(width: 48 * t.unit),
              Text(
                "LiveHub TV",
                style: t.h1,
              ),
              SizedBox(width: 24 * t.unit),
              const Spacer(),
              Obx(
                () => Text(
                  controller.datetime.value,
                  style: t.h1.copyWith(fontSize: t.px(36)),
                ),
              ),
              SizedBox(width: 32 * t.unit),
              HighlightButton(
                focusNode: AppFocusNode(),
                iconData: Icons.settings,
                text: "设置",
                onTap: () {
                  controller.toSettings();
                },
              ),
              SizedBox(width: 48 * t.unit),
            ],
          ),
          Expanded(
            child: ListView(
              padding: EdgeInsets.symmetric(vertical: 32 * t.unit),
              children: [
                Padding(
                  padding: EdgeInsets.symmetric(horizontal: 48 * t.unit),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.center,
                    children: [
                      Expanded(
                        child: HomeBigButton(
                          autofocus: true,
                          focusNode: AppFocusNode(),
                          text: "热门直播",
                          iconData: Remix.fire_line,
                          onTap: controller.toHotLive,
                        ),
                      ),
                      SizedBox(width: 48 * t.unit),
                      Expanded(
                        child: HomeBigButton(
                          autofocus: true,
                          focusNode: AppFocusNode(),
                          text: "直播类目",
                          iconData: Remix.apps_line,
                          onTap: controller.toCategory,
                        ),
                      ),
                      SizedBox(width: 48 * t.unit),
                      Expanded(
                        child: HomeBigButton(
                          autofocus: true,
                          focusNode: AppFocusNode(),
                          text: "搜索直播",
                          iconData: Remix.search_2_line,
                          onTap: showSearchDialog,
                        ),
                      ),
                      SizedBox(width: 48 * t.unit),
                      Expanded(
                        child: HomeBigButton(
                          focusNode: AppFocusNode(),
                          text: "观看记录",
                          iconData: Icons.history,
                          onTap: controller.toHistory,
                        ),
                      ),
                      SizedBox(width: 48 * t.unit),
                      Expanded(
                        child: HomeBigButton(
                          focusNode: AppFocusNode(),
                          text: "数据同步",
                          iconData: Icons.devices,
                          onTap: controller.toSync,
                        ),
                      ),
                    ],
                  ),
                ),
                SizedBox(height: 32 * t.unit),
                Padding(
                  padding: EdgeInsets.symmetric(horizontal: 48 * t.unit),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.center,
                    children: [
                      Icon(
                        Icons.favorite_border,
                        color: t.muted,
                        size: 56 * t.unit,
                      ),
                      SizedBox(width: 24 * t.unit),
                      Expanded(
                        child: Text(
                          "我的关注",
                          style: t.h1,
                        ),
                      ),
                      Obx(
                        () => Visibility(
                          visible: FollowUserService.instance.updating.value,
                          child: Row(
                            children: [
                              SizedBox(
                                width: 48 * t.unit,
                                height: 48 * t.unit,
                                child: CircularProgressIndicator(
                                  color: t.text,
                                  strokeWidth: 4 * t.unit,
                                ),
                              ),
                              SizedBox(width: 16 * t.unit),
                              Text(
                                "更新状态中...",
                                style: t.body.copyWith(color: t.muted),
                              ),
                            ],
                          ),
                        ),
                      ),
                      SizedBox(width: 16 * t.unit),
                      HighlightButton(
                        focusNode: AppFocusNode(),
                        iconData: Icons.settings,
                        text: "管理",
                        onTap: showManageDialog,
                      ),
                      SizedBox(width: 32 * t.unit),
                      HighlightButton(
                        focusNode: AppFocusNode(),
                        iconData: Icons.refresh,
                        text: "刷新",
                        onTap: () {
                          FollowUserService.instance.refreshData();
                        },
                      ),
                    ],
                  ),
                ),
                SizedBox(height: 32 * t.unit),
                Obx(
                  () => MasonryGridView.count(
                    padding: EdgeInsets.symmetric(horizontal: 48 * t.unit),
                    itemCount: FollowUserService.instance.list.length,
                    crossAxisCount: 3,
                    crossAxisSpacing: 48 * t.unit,
                    mainAxisSpacing: 48 * t.unit,
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    itemBuilder: (_, i) {
                      var item = FollowUserService.instance.list[i];
                      return Obx(
                        () => AnchorCard(
                          face: item.face,
                          name: item.userName,
                          siteId: item.siteId,
                          liveStatus: item.liveStatus.value,
                          roomId: item.roomId,
                        ),
                      );
                    },
                  ),
                ),
                Obx(
                  () => Visibility(
                    visible: FollowUserService.instance.list.isEmpty,
                    child: Column(
                      children: [
                        SizedBox(height: 24 * t.unit),
                        SizedBox(
                          width: 160 * t.unit,
                          height: 160 * t.unit,
                          child: LottieBuilder.asset(
                            'assets/lotties/empty.json',
                            repeat: false,
                          ),
                        ),
                        SizedBox(height: 24 * t.unit),
                        Text(
                          "暂无任何关注\n您可以从其他端同步数据到此处",
                          textAlign: TextAlign.center,
                          style: t.body.copyWith(color: t.muted),
                        ),
                        SizedBox(height: 16 * t.unit),
                        HighlightButton(
                          focusNode: AppFocusNode(),
                          iconData: Icons.devices,
                          text: "同步数据",
                          onTap: () {
                            controller.toSync();
                          },
                        ),
                      ],
                    ),
                  ),
                )
              ],
            ),
          ),
        ],
      ),
    );
  }

  void showManageDialog() {
    final t = Get.context!.lh;
    Utils.showSystemRightDialog(
      width: 700 * t.unit,
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
                "关注管理",
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
                    itemCount: FollowUserService.instance.list.length,
                    separatorBuilder: (_, __) => SizedBox(height: 24 * t.unit),
                    padding: EdgeInsets.all(40 * t.unit),
                    itemBuilder: (_, i) {
                      var item = FollowUserService.instance.list[i];
                      var foucsNode = AppFocusNode();
                      return HighlightListTile(
                        autofocus: i == 0,
                        leading: NetImage(
                          item.face,
                          width: 64 * t.unit,
                          height: 64 * t.unit,
                          borderRadius: 64 * t.unit,
                        ),
                        title: item.userName,
                        focusNode: foucsNode,
                        trailing: Obx(
                          () => Icon(
                            Icons.delete_outline_outlined,
                            size: 40 * t.unit,
                            color: t.muted,
                          ),
                        ),
                        onTap: () {
                          FollowUserService.instance
                              .removeItem(item, refresh: false);
                        },
                      );
                    },
                  ),
                ),
                Obx(
                  () => Visibility(
                    visible: FollowUserService.instance.list.isEmpty,
                    child: const AppEmptyWidget(
                      text: "关注列表为空，快去关注一些主播吧",
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void showSearchDialog() {
    final t = Get.context!.lh;
    var textController = TextEditingController();
    var mode = 0.obs;
    var roomFocusNode = AppFocusNode()..isFoucsed.value = true;
    var anchorFocusNode = AppFocusNode();
    showDialog(
      context: Get.context!,
      builder: (_) => AlertDialog(
        backgroundColor: LiveHubPalette.dark.raised,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: t.panelRadius,
        ),
        contentPadding: EdgeInsets.all(48 * t.unit),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.start,
              children: [
                Obx(
                  () => HighlightButton(
                    text: "直播间",
                    iconData: Icons.live_tv,
                    selected: mode.value == 0,
                    focusNode: roomFocusNode,
                    autofocus: roomFocusNode.isFoucsed.value,
                    onTap: () {
                      mode.value = 0;
                    },
                  ),
                ),
                SizedBox(width: 40 * t.unit),
                Obx(
                  () => HighlightButton(
                    text: "主播",
                    selected: mode.value == 1,
                    iconData: Icons.person,
                    focusNode: anchorFocusNode,
                    autofocus: anchorFocusNode.isFoucsed.value,
                    onTap: () {
                      mode.value = 1;
                    },
                  ),
                ),
              ],
            ),
            SizedBox(height: 48 * t.unit),
            SizedBox(
              width: 700 * t.unit,
              child: TextField(
                controller: textController,
                style: t.body,
                textInputAction: TextInputAction.search,
                onSubmitted: (e) {
                  Get.back();
                  if (e.isEmpty) {
                    return;
                  }
                  if (mode.value == 0) {
                    controller.toSearchRoom(textController.text);
                  } else {
                    controller.toSearchAnchor(textController.text);
                  }
                },
                decoration: InputDecoration(
                  hintText: mode.value == 0 ? "点击输入关键字搜索" : "点击主播昵称搜索",
                  hintStyle: t.body.copyWith(color: t.faint),
                  border: OutlineInputBorder(
                    borderRadius: t.inputRadius,
                    borderSide: BorderSide.none,
                  ),
                  filled: true,
                  isDense: true,
                  fillColor: t.glassTint,
                  contentPadding: EdgeInsets.all(32 * t.unit),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
