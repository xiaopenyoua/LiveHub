import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/utils.dart';
import 'package:livehub_tv/models/live_room_item_ext.dart';
import 'package:livehub_tv/modules/home/home_controller.dart';
import 'package:livehub_tv/routes/app_navigation.dart';
import 'package:livehub_tv/routes/tv_tab.dart';
import 'package:livehub_tv/widgets/button/highlight_button.dart';
import 'package:livehub_tv/widgets/card/live_room_card.dart';
import 'package:livehub_tv/widgets/net_image.dart';
import 'package:livehub_tv/widgets/status/app_empty_widget.dart';
import 'package:livehub_tv/widgets/status/app_error_widget.dart';
import 'package:livehub_tv/widgets/status/app_loadding_widget.dart';
import 'package:livehub_tv/widgets/tv_page_scaffold.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 首页：一张主图 + 全平台混排的“正在直播”网格，整页一个滚动容器。
class HomePage extends GetView<HomeController> {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    return TvPageScaffold(
      tab: TvTab.home,
      child: Obx(() {
        if (controller.pageLoadding.value) {
          return const AppLoaddingWidget();
        }
        if (controller.pageError.value) {
          return AppErrorWidget(
            errorMsg: controller.errorMsg.value,
            onRefresh: controller.refreshData,
          );
        }
        if (controller.pageEmpty.value) {
          return AppEmptyWidget(
            text: "暂时没有可推荐的直播",
            onRefresh: controller.refreshData,
          );
        }
        return _buildContent(context);
      }),
    );
  }

  Widget _buildContent(BuildContext context) {
    final t = context.lh;
    final hero = controller.heroItem;
    return CustomScrollView(
      controller: controller.scrollController,
      slivers: [
        if (hero != null)
          SliverToBoxAdapter(
            child: Padding(
              // 主图是全宽 bleed（x=0..1920），只有主图内的文字从
              // 80px 起（设计规范对应的 .hero__body{left:80px}）。
              padding: EdgeInsets.only(bottom: 50 * t.unit),
              child: _buildHero(context, hero),
            ),
          ),
        SliverToBoxAdapter(
          child: Padding(
            padding: EdgeInsets.fromLTRB(
              80 * t.unit,
              0,
              80 * t.unit,
              34 * t.unit,
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.baseline,
              textBaseline: TextBaseline.alphabetic,
              children: [
                Text("正在直播", style: t.h2),
                SizedBox(width: 20 * t.unit),
                Text(
                  "全平台 ${controller.list.length} 个",
                  style: t.meta.copyWith(color: t.faint),
                ),
              ],
            ),
          ),
        ),
        SliverPadding(
          padding: EdgeInsets.fromLTRB(
            80 * t.unit,
            0,
            80 * t.unit,
            80 * t.unit,
          ),
          sliver: SliverGrid(
            gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 4,
              crossAxisSpacing: 24 * t.unit,
              mainAxisSpacing: 34 * t.unit,
              childAspectRatio: 16 / 9,
            ),
            delegate: SliverChildBuilderDelegate(
              (context, i) {
                final item = controller.list[i];
                return LiveRoomCard(
                  cover: item.cover,
                  anchor: item.userName,
                  title: item.title,
                  siteName: item.siteName,
                  focusNode: item.focusNode,
                  roomId: item.roomId,
                  online: item.online,
                  autofocus: hero == null && i == 0,
                  onTap: () => _openRoom(item),
                );
              },
              childCount: controller.list.length,
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildHero(BuildContext context, LiveRoomItemExt item) {
    final t = context.lh;
    return SizedBox(
      height: 597.5 * t.unit,
      child: Stack(
        fit: StackFit.expand,
        children: [
          NetImage(item.cover, cacheWidth: 1600),
          // 左侧与底部两段黑色遮罩：字要压在画面上，就得先有暗。
          const DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.centerLeft,
                end: Alignment.centerRight,
                colors: [
                  Color(0xEB000000),
                  Color(0xB8000000),
                  Color(0x00000000),
                  Color(0x00000000),
                ],
                stops: [0, 0.34, 0.66, 1],
              ),
            ),
          ),
          const DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.bottomCenter,
                end: Alignment.topCenter,
                colors: [Color(0xD9000000), Color(0x00000000)],
                stops: [0, 0.52],
              ),
            ),
          ),
          Positioned(
            left: 80 * t.unit,
            width: 900 * t.unit,
            bottom: 56 * t.unit,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(
                      item.siteName,
                      style: t.meta.copyWith(color: t.muted),
                    ),
                    SizedBox(width: 14 * t.unit),
                    Container(
                      width: 4 * t.unit,
                      height: 4 * t.unit,
                      decoration: const BoxDecoration(
                        color: Color(0xFF6E6E73),
                        shape: BoxShape.circle,
                      ),
                    ),
                    SizedBox(width: 14 * t.unit),
                    Container(
                      width: 12 * t.unit,
                      height: 12 * t.unit,
                      decoration: BoxDecoration(
                        color: t.live,
                        shape: BoxShape.circle,
                      ),
                    ),
                    SizedBox(width: 14 * t.unit),
                    Text(
                      "直播中",
                      style: t.meta.copyWith(
                        color: t.live,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ],
                ),
                SizedBox(height: 18 * t.unit),
                ConstrainedBox(
                  constraints: BoxConstraints(maxWidth: 900 * t.unit),
                  child: Text(
                    item.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: t.hero,
                  ),
                ),
                SizedBox(height: 18 * t.unit),
                Text(
                  "${item.userName} · ${Utils.onlineToString(item.online)} 人在看",
                  style: t.body.copyWith(color: t.muted),
                ),
                SizedBox(height: 30 * t.unit),
                HighlightButton(
                  text: "进入直播间",
                  focusNode: controller.heroFocusNode,
                  autofocus: true,
                  onTap: () => _openRoom(item),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void _openRoom(LiveRoomItemExt item) {
    AppNavigator.toLiveRoomDetail(site: item.site, roomId: item.roomId);
  }
}
