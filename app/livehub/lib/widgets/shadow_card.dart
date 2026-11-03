import 'package:flutter/material.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 卡片外壳：一块烟色玻璃，没有描边、没有投影。
///
/// 卡片之间的分隔靠明度差，不靠 1px 线（设计规范 §2.2 / §8.4）。
class ShadowCard extends StatelessWidget {
  final Widget child;
  final double radius;
  final Function()? onTap;
  const ShadowCard({
    required this.child,
    this.radius = LiveHubRadii.card,
    this.onTap,
    Key? key,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final borderRadius = BorderRadius.circular(radius);
    return LiveHubGlass(
      level: 0.062,
      radius: borderRadius,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: borderRadius,
          onTap: onTap,
          child: child,
        ),
      ),
    );
  }
}
