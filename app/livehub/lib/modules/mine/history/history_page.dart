import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub/app/app_style.dart';
import 'package:livehub/app/sites.dart';
import 'package:livehub/app/utils.dart';
import 'package:livehub/modules/mine/history/history_controller.dart';
import 'package:livehub/routes/app_navigation.dart';
import 'package:livehub/widgets/net_image.dart';
import 'package:livehub/widgets/page_grid_view.dart';
import 'package:livehub_ui/livehub_ui.dart';

class HistoryPage extends GetView<HistoryController> {
  const HistoryPage({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    var rowCount = MediaQuery.of(context).size.width ~/ 500;
    if (rowCount < 1) rowCount = 1;
    return Scaffold(
      appBar: AppBar(
        title: const Text("观看记录"),
        actions: [
          TextButton.icon(
            onPressed: controller.clean,
            icon: const Icon(Icons.delete_outline),
            label: const Text("清空"),
          ),
        ],
      ),
      body: PageGridView(
        crossAxisSpacing: 12,
        crossAxisCount: rowCount,
        pageController: controller,
        firstRefresh: true,
        itemBuilder: (_, i) {
          var item = controller.list[i];
          var site = Sites.allSites[item.siteId]!;
          return Dismissible(
            key: ValueKey(item.id),
            direction: DismissDirection.endToStart,
            background: Container(
              color: context.lh.live,
              padding: AppStyle.edgeInsetsA12,
              alignment: Alignment.centerRight,
              child: Icon(
                Icons.delete,
                color: context.lh.onLive,
              ),
            ),
            confirmDismiss: (direction) async {
              return await Utils.showAlertDialog("确定要删除此记录吗?", title: "删除记录");
            },
            onDismissed: (_) {
              controller.removeItem(item);
            },
            child: ListTile(
              leading: NetImage(
                item.face,
                width: 48,
                height: 48,
                borderRadius: 24,
              ),
              title: Text(item.userName),
              subtitle: Row(
                children: [
                  Expanded(
                    child: Text(
                      site.shortName,
                      style: context.lh.cardMeta.copyWith(
                        color: context.lh.palette.faint,
                      ),
                    ),
                  ),
                  Text(
                    Utils.parseTime(item.updateTime),
                    style: context.lh.cardMeta.copyWith(
                      color: context.lh.palette.faint,
                    ),
                  ),
                ],
              ),
              onTap: () {
                AppNavigator.toLiveRoomDetail(site: site, roomId: item.roomId);
              },
              onLongPress: () async {
                var result =
                    await Utils.showAlertDialog("确定要删除此记录吗?", title: "删除记录");
                if (!result) {
                  return;
                }
                controller.removeItem(item);
              },
            ),
          );
        },
      ),
    );
  }
}
