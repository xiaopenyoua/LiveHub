import 'package:flutter/material.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 设置分组：一块烟色玻璃面板，无描边。
class SettingsCard extends StatelessWidget {
  final Widget child;
  const SettingsCard({required this.child, Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return LiveHubGlass(
      level: 0.062,
      radius: BorderRadius.circular(LiveHubRadii.card),
      child: Material(
        color: Colors.transparent,
        child: child,
      ),
    );
  }
}
