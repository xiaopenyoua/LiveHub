import 'package:livehub_core/livehub_core.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/sites.dart';

/// 直播间条目 + 它所属的平台。
///
/// 首页、搜索、分类详情现在都把四个平台的结果混在一起，所以每一条必须
/// 自己带着 [site]，不能像上游那样靠控制器里的“当前平台”隐式决定。
class LiveRoomItemExt extends LiveRoomItem {
  LiveRoomItemExt({
    required super.roomId,
    required super.title,
    required super.cover,
    required super.userName,
    required this.site,
    super.online = 0,
  });

  final Site site;

  String get siteName => site.shortName;

  AppFocusNode focusNode = AppFocusNode();
}
