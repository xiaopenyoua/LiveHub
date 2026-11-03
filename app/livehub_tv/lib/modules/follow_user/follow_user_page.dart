import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/sites.dart';
import 'package:livehub_tv/models/db/follow_user.dart';
import 'package:livehub_tv/routes/app_navigation.dart';
import 'package:livehub_tv/routes/tv_tab.dart';
import 'package:livehub_tv/services/follow_user_service.dart';
import 'package:livehub_tv/widgets/button/highlight_button.dart';
import 'package:livehub_tv/widgets/card/person_card.dart';
import 'package:livehub_tv/widgets/tv_page_scaffold.dart';
import 'package:livehub_ui/livehub_ui.dart';

class FollowUserPage extends StatefulWidget {
  const FollowUserPage({super.key});

  @override
  State<FollowUserPage> createState() => _FollowUserPageState();
}

class _FollowUserPageState extends State<FollowUserPage> {
  final Map<String, AppFocusNode> _cardNodes = {};
  final AppFocusNode _refreshNode = AppFocusNode();

  AppFocusNode _nodeFor(String id) =>
      _cardNodes.putIfAbsent(id, () => AppFocusNode());

  @override
  void dispose() {
    for (final node in _cardNodes.values) {
      node.dispose();
    }
    _refreshNode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    final service = FollowUserService.instance;
    return TvPageScaffold(
      tab: TvTab.follow,
      child: Obx(() {
        final all = service.list;
        final living = all.where((e) => e.liveStatus.value == 2).toList();
        final offline = all.where((e) => e.liveStatus.value != 2).toList();
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
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text("关注", style: t.h1),
                        SizedBox(height: 12 * t.unit),
                        Text(
                          "${all.length} 位主播 · ${living.length} 位正在直播",
                          style: t.body.copyWith(color: t.muted),
                        ),
                      ],
                    ),
                  ),
                  HighlightButton(
                    focusNode: _refreshNode,
                    iconData: Icons.refresh,
                    text: "刷新",
                    autofocus: all.isEmpty,
                    onTap: () {
                      service.refreshData();
                    },
                  ),
                ],
              ),
            ),
            Expanded(
              child: all.isEmpty
                  ? _buildEmpty(context)
                  : ListView(
                      padding: EdgeInsets.fromLTRB(
                        80 * t.unit,
                        40 * t.unit,
                        80 * t.unit,
                        80 * t.unit,
                      ),
                      children: [
                        _buildGroupHead(context, "正在直播", living.length),
                        if (living.isEmpty)
                          _buildGroupEmpty(context, "暂无主播在播")
                        else
                          _buildGrid(
                            context,
                            living,
                            firstAutofocus: true,
                          ),
                        _buildGroupHead(
                          context,
                          "未开播",
                          offline.length,
                          topGap: true,
                        ),
                        if (offline.isEmpty)
                          _buildGroupEmpty(context, "所有关注的主播都在播")
                        else
                          _buildGrid(
                            context,
                            offline,
                            firstAutofocus: living.isEmpty,
                          ),
                      ],
                    ),
            ),
          ],
        );
      }),
    );
  }

  Widget _buildGroupHead(
    BuildContext context,
    String title,
    int count, {
    bool topGap = false,
  }) {
    final t = context.lh;
    return Padding(
      padding: EdgeInsets.only(
        top: topGap ? 56 * t.unit : 0,
        bottom: 34 * t.unit,
        left: 4 * t.unit,
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.baseline,
        textBaseline: TextBaseline.alphabetic,
        children: [
          Text(title, style: t.h2),
          SizedBox(width: 16 * t.unit),
          Text("$count 位", style: t.meta.copyWith(color: t.faint)),
        ],
      ),
    );
  }

  Widget _buildGroupEmpty(BuildContext context, String text) {
    final t = context.lh;
    return Padding(
      padding: EdgeInsets.only(bottom: 24 * t.unit),
      child: Text(text, style: t.body.copyWith(color: t.faint)),
    );
  }

  Widget _buildGrid(
    BuildContext context,
    List<FollowUser> items, {
    required bool firstAutofocus,
  }) {
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
        final live = item.liveStatus.value == 2;
        return PersonCard(
          avatar: item.face,
          name: item.userName,
          siteName: _siteName(item.siteId),
          status: live ? "直播中" : "未开播",
          liveStatus: live,
          dimmed: !live,
          cover: true,
          autofocus: firstAutofocus && i == 0,
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
            "还没有关注任何主播\n在手机端同步关注列表后，这里会显示他们是否在播",
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
