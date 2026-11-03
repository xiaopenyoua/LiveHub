import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/widgets/highlight_widget.dart';
import 'package:livehub_ui/livehub_ui.dart';

class HighlightListTile extends StatelessWidget {
  final String title;
  final String? subtitle;
  final Widget? leading;
  final Widget? trailing;
  final AppFocusNode focusNode;
  final Function()? onTap;
  final bool autofocus;

  const HighlightListTile({
    this.subtitle,
    required this.title,
    this.leading,
    this.onTap,
    required this.focusNode,
    this.autofocus = false,
    this.trailing,
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return Obx(
      () => IconTheme(
        data: IconThemeData(color: t.text),
        child: HighlightWidget(
          onTap: onTap,
          autofocus: autofocus,
          focusNode: focusNode,
          borderRadius: t.listRadius,
          focusScale: 1.02,
          child: Padding(
            padding: EdgeInsets.all(24 * t.unit),
            child: Row(
              children: [
                if (leading != null) leading!,
                if (leading != null) SizedBox(width: 32 * t.unit),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Text(
                        title,
                        style: t.body.copyWith(fontWeight: FontWeight.w500),
                      ),
                      if (subtitle != null)
                        Text(
                          subtitle!,
                          style: t.small,
                        ),
                    ],
                  ),
                ),
                SizedBox(width: 12 * t.unit),
                if (onTap != null &&
                    focusNode.isFoucsed.value &&
                    trailing == null)
                  Icon(
                    Icons.chevron_right,
                    size: 40 * t.unit,
                    color: t.muted,
                  ),
                if (trailing != null) trailing!,
              ],
            ),
          ),
        ),
      ),
    );
  }
}
