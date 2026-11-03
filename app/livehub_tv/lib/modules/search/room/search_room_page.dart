import 'package:flutter/material.dart';
import 'package:flutter_staggered_grid_view/flutter_staggered_grid_view.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/sites.dart';
import 'package:livehub_tv/modules/search/room/search_room_controller.dart';
import 'package:livehub_tv/routes/app_navigation.dart';
import 'package:livehub_tv/widgets/app_scaffold.dart';
import 'package:livehub_tv/widgets/button/highlight_button.dart';
import 'package:livehub_tv/widgets/card/live_room_card.dart';
import 'package:livehub_tv/widgets/status/app_empty_widget.dart';
import 'package:livehub_tv/widgets/status/app_error_widget.dart';
import 'package:livehub_ui/livehub_ui.dart';

class SearchRoomPage extends GetView<SearchRoomController> {
  const SearchRoomPage({super.key});

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
                "搜索：${controller.keyword}",
                style: t.h2,
              ),
              SizedBox(width: 24 * t.unit),
              const Spacer(),
              Obx(
                () => Visibility(
                  visible: controller.loadding.value,
                  child: SizedBox(
                    width: 48 * t.unit,
                    height: 48 * t.unit,
                    child: CircularProgressIndicator(
                      color: t.text,
                      strokeWidth: 4 * t.unit,
                    ),
                  ),
                ),
              ),
              SizedBox(width: 48 * t.unit),
            ],
          ),
          SizedBox(height: 24 * t.unit),
          Wrap(
            alignment: WrapAlignment.center,
            spacing: 36 * t.unit,
            children: Sites.supportSites
                .map(
                  (e) => Obx(
                    () => HighlightButton(
                      text: e.shortName,
                      selected: controller.siteId.value == e.id,
                      focusNode: AppFocusNode(),
                      onTap: () {
                        controller.setSite(e.id);
                      },
                    ),
                  ),
                )
                .toList(),
          ),
          SizedBox(height: 24 * t.unit),
          Expanded(
            child: Obx(
              () => Stack(
                children: [
                  MasonryGridView.count(
                    padding: EdgeInsets.all(48 * t.unit),
                    itemCount: controller.list.length,
                    crossAxisCount: 5,
                    crossAxisSpacing: 48 * t.unit,
                    mainAxisSpacing: 40 * t.unit,
                    controller: controller.scrollController,
                    itemBuilder: (_, i) {
                      var item = controller.list[i];

                      if (i == 0) {
                        Future.delayed(Duration.zero, () {
                          if (controller.currentPage == 2) {
                            item.focusNode.requestFocus();
                          }
                        });
                      }
                      return LiveRoomCard(
                        cover: item.cover,
                        anchor: item.userName,
                        title: item.title,
                        siteName: controller.site.shortName,
                        focusNode: item.focusNode,
                        roomId: item.roomId,
                        online: item.online,
                        onTap: () {
                          AppNavigator.toLiveRoomDetail(
                            site: controller.site,
                            roomId: item.roomId,
                          );
                        },
                      );
                    },
                  ),
                  Obx(
                    () => Visibility(
                      visible: controller.list.isEmpty &&
                          !controller.loadding.value &&
                          !controller.pageError.value,
                      child: AppEmptyWidget(
                        onRefresh: () {
                          controller.refreshData();
                        },
                      ),
                    ),
                  ),
                  Obx(
                    () => Visibility(
                      visible: controller.pageError.value &&
                          !controller.loadding.value,
                      child: AppErrorWidget(
                        errorMsg: controller.errorMsg.value,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
