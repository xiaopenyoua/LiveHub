import 'package:flutter/material.dart';
import 'package:flutter_staggered_grid_view/flutter_staggered_grid_view.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/modules/history/history_controller.dart';
import 'package:livehub_tv/widgets/app_scaffold.dart';
import 'package:livehub_tv/widgets/button/highlight_button.dart';
import 'package:livehub_tv/widgets/card/anchor_card.dart';
import 'package:livehub_ui/livehub_ui.dart';

class HistoryPage extends GetView<HistoryController> {
  const HistoryPage({super.key});

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
                "观看记录",
                style: t.h2,
              ),
              SizedBox(width: 24 * t.unit),
              const Spacer(),
              HighlightButton(
                focusNode: AppFocusNode(),
                iconData: Icons.delete_outline_rounded,
                text: "清空",
                onTap: () {
                  controller.clean();
                },
              ),
              SizedBox(width: 48 * t.unit),
            ],
          ),
          SizedBox(height: 48 * t.unit),
          Expanded(
            child: Obx(
              () => MasonryGridView.count(
                padding: EdgeInsets.symmetric(horizontal: 48 * t.unit),
                itemCount: controller.list.length,
                crossAxisCount: 3,
                crossAxisSpacing: 48 * t.unit,
                mainAxisSpacing: 40 * t.unit,
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                itemBuilder: (_, i) {
                  var item = controller.list[i];
                  return AnchorCard(
                    face: item.face,
                    name: item.userName,
                    siteId: item.siteId,
                    liveStatus: 0,
                    roomId: item.roomId,
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
