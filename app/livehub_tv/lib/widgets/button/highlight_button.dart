import 'package:flutter/material.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/widgets/highlight_widget.dart';
import 'package:livehub_ui/livehub_ui.dart';

class HighlightButton extends StatelessWidget {
  final String text;
  final IconData? iconData;
  final Widget? icon;
  final AppFocusNode focusNode;
  final Function()? onTap;
  final bool autofocus;
  final bool selected;

  /// 上键交给调用方处理（首页主图要把焦点交回顶栏）。
  final KeyEventResult Function()? onUpKey;
  const HighlightButton({
    this.iconData,
    required this.text,
    this.icon,
    this.onTap,
    required this.focusNode,
    this.autofocus = false,
    this.selected = false,
    this.onUpKey,
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return HighlightWidget(
        focusNode: focusNode,
        borderRadius: t.pillRadius,
        focusScale: 1.06,
        idleLevel: selected ? 0.22 : 0.10,
        focusLevel: selected ? 0.36 : 0.34,
        onTap: onTap,
        onUpKey: onUpKey,
        autofocus: autofocus,
        selected: selected,
        child: Container(
          height: 64 * t.unit,
          padding: EdgeInsets.symmetric(horizontal: 24 * t.unit),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              buildIcon(context),
              Text(
                text,
                style: t.body.copyWith(
                  fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                  color: t.text,
                ),
              ),
            ],
          ),
        ),
    );
  }

  Widget buildIcon(BuildContext context) {
    if (icon != null || iconData != null) {
      final t = context.lh;
      return Padding(
        padding: EdgeInsets.only(right: 12 * t.unit),
        child: icon ??
            Icon(
              iconData,
              size: 32 * t.unit,
              color: t.text,
            ),
      );
    }
    return const SizedBox();
  }
}
