import 'package:flutter/material.dart';
import 'package:livehub_tv/routes/tv_tab.dart';
import 'package:livehub_tv/widgets/app_scaffold.dart';
import 'package:livehub_tv/widgets/tv_top_nav.dart';

/// 一级页面的统一外壳：顶栏固定，内容区自己滚。
class TvPageScaffold extends StatelessWidget {
  const TvPageScaffold({super.key, required this.tab, required this.child});

  final TvTab tab;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: tab == TvTab.home,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop && tab != TvTab.home) {
          TvTabNav.go(TvTab.home);
        }
      },
      child: AppScaffold(
        child: Column(
          children: [
            TvTopNav(current: tab),
            Expanded(child: child),
          ],
        ),
      ),
    );
  }
}
