import 'package:flutter/widgets.dart';
import 'package:flutter_screenutil/flutter_screenutil.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/controller/base_controller.dart';
import 'package:livehub_tv/app/sites.dart';
import 'package:livehub_tv/app/startup_signal.dart';
import 'package:livehub_tv/models/live_room_item_ext.dart';
import 'package:livehub_tv/widgets/tv_top_nav.dart';

/// 首页：四个平台的推荐直播混排。
class HomeController extends BasePageController<LiveRoomItemExt> {
  /// 主图主按钮自己一枚焦点。主图条目同时会出现在下面的网格里，
  /// 不能和那张卡片共用 `item.focusNode`。
  final AppFocusNode heroFocusNode = AppFocusNode();

  /// 第一页是否还在加载（成功后 currentPage 会先 +1，不能用它判断）。
  bool _firstPagePending = false;

  @override
  void onInit() {
    scrollController.addListener(scrollListener);
    // 开屏等的是首页第一页的结论，不是某一家的结论。
    ever(pageLoadding, (loading) {
      if (loading) {
        if (currentPage == 1) {
          _firstPagePending = true;
          HomeFirstPage.state.value = HomeFirstPageState.loading;
        }
        return;
      }
      if (!_firstPagePending) return;
      _firstPagePending = false;
      HomeFirstPage.state.value = pageError.value
          ? HomeFirstPageState.failed
          : HomeFirstPageState.ready;
    });
    refreshData();
    super.onInit();
  }

  void scrollListener() {
    if (!canLoadMore.value) return;
    if (scrollController.position.pixels >=
        scrollController.position.maxScrollExtent - 100.w) {
      loadData();
    }
  }

  /// 网格第一行按「上」：先把主图滚回来，再把焦点交给主图按钮；
  /// 已经在顶部了就继续往上，交给顶栏的「首页」页签。
  Future<void> focusHeroOrNav() async {
    if (scrollController.hasClients && scrollController.offset > 1) {
      await scrollController.animateTo(
        0,
        duration: const Duration(milliseconds: 220),
        curve: Curves.linear,
      );
    }
    if (heroItem != null && heroFocusNode.context != null) {
      heroFocusNode.requestFocus();
      return;
    }
    TvTopNav.focusFirstTab();
  }

  /// 第一页里第一条有封面的条目作为主图。
  LiveRoomItemExt? get heroItem {
    for (final item in list) {
      if (item.cover.isNotEmpty) return item;
    }
    return null;
  }

  @override
  Future<List<LiveRoomItemExt>> getData(int page, int pageSize) async {
    // 四个平台并发；单平台失败只丢它自己，不能让整页失败。
    var failures = 0;
    final perSite = await Future.wait(
      Sites.supportSites.map((site) async {
        try {
          final result = await site.liveSite.getRecommendRooms(page: page);
          return result.items
              .map(
                (e) => LiveRoomItemExt(
                  roomId: e.roomId,
                  title: e.title,
                  cover: e.cover,
                  userName: e.userName,
                  online: e.online,
                  site: site,
                ),
              )
              .toList();
        } catch (_) {
          failures++;
          return <LiveRoomItemExt>[];
        }
      }),
    );

    // 全部平台都失败时不能当成“确实没有内容”：
    // 抛出去让 BasePageController 置 pageError，首页才会显示错误与重试。
    if (failures == Sites.supportSites.length) {
      throw Exception("所有平台的推荐都加载失败");
    }

    // 四个平台轮流取一条，保证首屏就能看到四个平台的混排。
    // 如果直接按人气全局排序，斗鱼的观看人数会把首页前几屏全占满，
    // 看起来和“只选了斗鱼”没有区别。
    final perSiteSorted = perSite
        .map((rooms) =>
            (List.of(rooms)..sort((a, b) => b.online.compareTo(a.online))))
        .toList();
    final longest = perSiteSorted.fold<int>(
      0,
      (max, rooms) => rooms.length > max ? rooms.length : max,
    );
    final merged = <LiveRoomItemExt>[];
    for (var i = 0; i < longest; i++) {
      for (final rooms in perSiteSorted) {
        if (i < rooms.length) merged.add(rooms[i]);
      }
    }
    return merged;
  }

  @override
  void onClose() {
    scrollController.removeListener(scrollListener);
    super.onClose();
  }
}
