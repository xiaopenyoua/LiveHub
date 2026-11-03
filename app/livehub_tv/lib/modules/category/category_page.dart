import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/modules/category/category_controller.dart';
import 'package:livehub_tv/routes/app_navigation.dart';
import 'package:livehub_tv/routes/tv_tab.dart';
import 'package:livehub_tv/widgets/button/highlight_button.dart';
import 'package:livehub_tv/widgets/card/live_room_card.dart';
import 'package:livehub_tv/widgets/highlight_widget.dart';
import 'package:livehub_tv/widgets/tv_page_scaffold.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 分类页：左栏钉住平台，右栏 chips 选分类、4 列网格直接看直播间。
class CategoryPage extends StatefulWidget {
  const CategoryPage({super.key});

  @override
  State<CategoryPage> createState() => _CategoryPageState();
}

class _CategoryPageState extends State<CategoryPage> {
  CategoryController get controller => Get.find<CategoryController>();

  bool _focusScheduled = false;

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return TvPageScaffold(
      tab: TvTab.category,
      child: Padding(
        padding: EdgeInsets.only(left: 80 * t.unit, top: 44 * t.unit),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SizedBox(width: 320 * t.unit, child: _buildRail(context)),
            SizedBox(width: 44 * t.unit),
            Expanded(child: _buildContent(context)),
          ],
        ),
      ),
    );
  }

  Widget _buildRail(BuildContext context) {
    final t = context.lh;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text("分类", style: t.h1),
        SizedBox(height: 12 * t.unit),
        Text(
          "按平台筛，或在搜索里输入房间号。",
          style: t.small.copyWith(color: t.faint),
        ),
        SizedBox(height: 26 * t.unit),
        Obx(
          () => Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (final site in controller.platforms)
                Padding(
                  padding: EdgeInsets.only(bottom: 6 * t.unit),
                  child: _buildPlatformRow(
                    context,
                    id: site.id,
                    name: site.name.replaceAll("直播", ""),
                    focusNode: site.appFocusNode,
                    selected: controller.platformId.value == site.id,
                  ),
                ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildPlatformRow(
    BuildContext context, {
    required String id,
    required String name,
    required AppFocusNode focusNode,
    required bool selected,
  }) {
    final t = context.lh;
    return HighlightWidget(
      focusNode: focusNode,
      borderRadius: t.listRadius,
      focusScale: 1.02,
      idleLevel: 0.062,
      focusLevel: 0.215,
      onTap: () {
        controller.selectPlatform(id);
      },
      child: SizedBox(
        height: 84 * t.unit,
        child: Padding(
          padding: EdgeInsets.symmetric(horizontal: 32 * t.unit),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: t.title.copyWith(
                    color: selected ? t.text : t.muted,
                    fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildContent(BuildContext context) {
    final t = context.lh;
    return Obx(() {
      _scheduleFocus();
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            height: 72 * t.unit,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              padding: EdgeInsets.symmetric(horizontal: 6 * t.unit),
              itemCount: controller.categories.length + 1,
              separatorBuilder: (_, __) => SizedBox(width: 14 * t.unit),
              itemBuilder: (_, i) {
                return Center(
                  child: HighlightButton(
                    text: i == 0 ? "全部" : controller.categories[i - 1].name,
                    selected: controller.selectedCategoryIndex.value == i,
                    focusNode: controller.categoryFocusNode(i),
                    onTap: () {
                      controller.selectCategory(i);
                    },
                  ),
                );
              },
            ),
          ),
          SizedBox(height: 40 * t.unit),
          Expanded(child: _buildGrid(context)),
        ],
      );
    });
  }

  Widget _buildGrid(BuildContext context) {
    final t = context.lh;
    final items = controller.rooms;

    if (controller.loadingRooms.value) {
      return Center(
        child: SizedBox(
          width: 48 * t.unit,
          height: 48 * t.unit,
          child: CircularProgressIndicator(
            color: t.text,
            strokeWidth: 4 * t.unit,
          ),
        ),
      );
    }

    if (controller.errorMsg.value.isNotEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              controller.errorMsg.value,
              style: t.body.copyWith(color: t.muted),
            ),
            SizedBox(height: 24 * t.unit),
            HighlightButton(
              text: "重试",
              iconData: Icons.refresh,
              focusNode: controller.retryFocusNode,
              onTap: controller.retry,
            ),
          ],
        ),
      );
    }

    if (items.isEmpty) {
      return Center(
        child: Text("暂无直播", style: t.body.copyWith(color: t.muted)),
      );
    }

    // 左缘退 16px：卡片的焦点放大会越过滚动容器的裁剪线（设计规范 §8.2）。
    return Stack(
      clipBehavior: Clip.none,
      children: [
        Positioned(
          left: -16 * t.unit,
          top: 0,
          right: 0,
          bottom: 0,
          child: GridView.builder(
            controller: controller.scrollController,
            padding: EdgeInsets.only(
              left: 16 * t.unit,
              right: 80 * t.unit,
              top: 8 * t.unit,
              bottom: 60 * t.unit,
            ),
            gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 4,
              crossAxisSpacing: 20 * t.unit,
              mainAxisSpacing: 32 * t.unit,
              childAspectRatio: 16 / 9,
            ),
            itemCount: items.length,
            itemBuilder: (_, i) {
              final item = items[i];
              return LiveRoomCard(
                cover: item.cover,
                title: item.title,
                anchor: item.userName,
                siteName: item.siteName,
                roomId: item.roomId,
                online: item.online,
                focusNode: item.focusNode,
                onTap: () => AppNavigator.toLiveRoomDetail(
                  site: item.site,
                  roomId: item.roomId,
                ),
              );
            },
          ),
        ),
        // 追加下一页时的底部加载提示；不参与焦点。
        Positioned(
          left: 0,
          right: 80 * t.unit,
          bottom: 18 * t.unit,
          child: Obx(
            () => controller.loadingMore.value
                ? IgnorePointer(
                    child: Center(
                      child: SizedBox(
                        width: 40 * t.unit,
                        height: 40 * t.unit,
                        child: CircularProgressIndicator(
                          color: t.text,
                          strokeWidth: 4 * t.unit,
                        ),
                      ),
                    ),
                  )
                : const SizedBox.shrink(),
          ),
        ),
        Positioned(
          left: 0,
          right: 80 * t.unit,
          bottom: 18 * t.unit,
          child: Obx(
            () => (!controller.hasMore.value &&
                    !controller.loadingMore.value &&
                    controller.rooms.isNotEmpty)
                ? IgnorePointer(
                    child: Center(
                      child: Text(
                        "已经到底了",
                        style: t.small.copyWith(color: t.faint),
                      ),
                    ),
                  )
                : const SizedBox.shrink(),
          ),
        ),
      ],
    );
  }

  /// 默认焦点：第一张直播间卡；没有房间时落当前分类 chip；再退到左栏。
  void _scheduleFocus() {
    if (_focusScheduled) return;
    if (controller.loadingRooms.value && controller.rooms.isEmpty) return;
    _focusScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _focusScheduled = false;
      if (!mounted) return;
      final primary = FocusManager.instance.primaryFocus;
      if (primary != null && primary is! FocusScopeNode) return;

      if (controller.rooms.isNotEmpty) {
        controller.rooms.first.focusNode.requestFocus();
        return;
      }
      if (controller.errorMsg.value.isNotEmpty) {
        controller.retryFocusNode.requestFocus();
        return;
      }
      if (controller.categories.isNotEmpty) {
        controller
            .categoryFocusNode(controller.selectedCategoryIndex.value)
            .requestFocus();
        return;
      }
      (controller.site ?? controller.platforms.first)
          .appFocusNode
          .requestFocus();
    });
  }
}
