import 'package:get/get.dart';
import 'package:livehub_tv/routes/route_path.dart';

/// TV 顶栏的六个一级页签。顺序即设计稿顺序，记录页是设计稿之外的补充。
enum TvTab { home, follow, category, search, history, settings }

extension TvTabX on TvTab {
  String get label {
    switch (this) {
      case TvTab.home:
        return "首页";
      case TvTab.follow:
        return "关注";
      case TvTab.category:
        return "分类";
      case TvTab.search:
        return "搜索";
      case TvTab.history:
        return "记录";
      case TvTab.settings:
        return "设置";
    }
  }

  String get route {
    switch (this) {
      case TvTab.home:
        return RoutePath.kHome;
      case TvTab.follow:
        return RoutePath.kFollow;
      case TvTab.category:
        return RoutePath.kCategory;
      case TvTab.search:
        return RoutePath.kSearch;
      case TvTab.history:
        return RoutePath.kHistory;
      case TvTab.settings:
        return RoutePath.kSettings;
    }
  }
}

class TvTabNav {
  const TvTabNav._();

  /// 一级页签之间用 offNamed 切换：不叠返回栈，顶栏始终是唯一入口。
  static void go(TvTab tab) {
    if (Get.currentRoute == tab.route) return;
    Get.offNamed(tab.route);
  }
}
