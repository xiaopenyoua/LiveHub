import 'package:flutter/material.dart';
import 'package:flutter_staggered_grid_view/flutter_staggered_grid_view.dart';

import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/modules/category/detail/category_detail_controller.dart';
import 'package:livehub_tv/routes/app_navigation.dart';
import 'package:livehub_tv/widgets/app_scaffold.dart';
import 'package:livehub_tv/widgets/button/highlight_button.dart';
import 'package:livehub_tv/widgets/card/live_room_card.dart';
import 'package:livehub_ui/livehub_ui.dart';

class CategoryDetailPage extends GetView<CategoryDetailController> {
  const CategoryDetailPage({Key? key}) : super(key: key);

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
                autofocus: true,
                onTap: () {
                  Get.back();
                },
              ),
              SizedBox(width: 32 * t.unit),
              Text(
                controller.subCategory.name,
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
          Expanded(
            child: Obx(
              () => MasonryGridView.count(
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
            ),
          ),
        ],
      ),
    );
  }
}
