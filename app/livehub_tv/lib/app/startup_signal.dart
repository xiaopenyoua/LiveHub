import 'package:flutter/foundation.dart';

/// 开屏与首页共用的启动信号。
///
/// 应用内开屏的作用是盖住首页首屏加载直播间的 loading，所以开屏不能
/// 「一家平台就绪就退」，而要等首页第一页有结论（成功或失败）。
/// 四行状态的单家探测上界仍按设计稿取 1500ms。
const Duration kSiteProbeTimeout = Duration(milliseconds: 1500);

enum HomeFirstPageState { loading, ready, failed }

class HomeFirstPage {
  HomeFirstPage._();

  static final ValueNotifier<HomeFirstPageState> state =
      ValueNotifier<HomeFirstPageState>(HomeFirstPageState.loading);
}
