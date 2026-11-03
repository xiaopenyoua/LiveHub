import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/sites.dart';
import 'package:livehub_tv/app/utils.dart';
import 'package:livehub_tv/models/db/history.dart';
import 'package:livehub_tv/modules/history/history_controller.dart';
import 'package:livehub_tv/routes/app_navigation.dart';
import 'package:livehub_tv/routes/tv_tab.dart';
import 'package:livehub_tv/widgets/button/highlight_button.dart';
import 'package:livehub_tv/widgets/card/person_card.dart';
import 'package:livehub_tv/widgets/tv_page_scaffold.dart';
import 'package:livehub_ui/livehub_ui.dart';

class HistoryPage extends StatefulWidget {
  const HistoryPage({super.key});

  @override
  State<HistoryPage> createState() => _HistoryPageState();
}

class _HistoryPageState extends State<HistoryPage> {
  final HistoryController controller = Get.find<HistoryController>();
  final Map<String, AppFocusNode> _cardNodes = {};
  final AppFocusNode _clearNode = AppFocusNode();

  AppFocusNode _nodeFor(String id) =>
      _cardNodes.putIfAbsent(id, () => AppFocusNode());

  @override
  void dispose() {
    for (final node in _cardNodes.values) {
      node.dispose();
    }
    _clearNode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return TvPageScaffold(
      tab: TvTab.history,
      child: Obx(() {
        final items = controller.list;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: EdgeInsets.fromLTRB(
                80 * t.unit,
                40 * t.unit,
                80 * t.unit,
                0,
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Expanded(child: Text("记录", style: t.h1)),
                  HighlightButton(
                    focusNode: _clearNode,
                    iconData: Icons.delete_outline_rounded,
                    text: "清空",
                    onTap: () {
                      controller.clean();
                    },
                  ),
                ],
              ),
            ),
            Expanded(
              child: items.isEmpty
                  ? _buildEmpty(context)
                  : ListView(
                      padding: EdgeInsets.fromLTRB(
                        80 * t.unit,
                        40 * t.unit,
                        80 * t.unit,
                        80 * t.unit,
                      ),
                      children: [
                        _buildGrid(context, items),
                      ],
                    ),
            ),
          ],
        );
      }),
    );
  }

  Widget _buildGrid(BuildContext context, List<History> items) {
    final t = context.lh;
    return GridView.builder(
      shrinkWrap: true,
      padding: EdgeInsets.zero,
      physics: const NeverScrollableScrollPhysics(),
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 4,
        crossAxisSpacing: 24 * t.unit,
        mainAxisSpacing: 34 * t.unit,
        childAspectRatio: 16 / 9,
      ),
      itemCount: items.length,
      itemBuilder: (_, i) {
        final item = items[i];
        return PersonCard(
          avatar: item.face,
          name: item.userName,
          siteName: _siteName(item.siteId),
          status: "观看于 ${Utils.parseTime(item.updateTime)}",
          cover: true,
          autofocus: i == 0,
          focusNode: _nodeFor(item.id),
          onTap: () => _openRoom(item.siteId, item.roomId),
        );
      },
    );
  }

  Widget _buildEmpty(BuildContext context) {
    final t = context.lh;
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            "还没有记录\n看过的直播间会出现在这里",
            textAlign: TextAlign.center,
            style: t.body.copyWith(color: t.muted),
          ),
          SizedBox(height: 16 * t.unit),
          Text(
            "在「设置 → 数据同步」中与手机端同步",
            textAlign: TextAlign.center,
            style: t.small.copyWith(color: t.faint),
          ),
        ],
      ),
    );
  }

  String _siteName(String siteId) =>
      Sites.allSites[siteId]?.shortName ?? siteId;

  void _openRoom(String siteId, String roomId) {
    final site = Sites.allSites[siteId];
    if (site == null) return;
    AppNavigator.toLiveRoomDetail(site: site, roomId: roomId);
  }
}
