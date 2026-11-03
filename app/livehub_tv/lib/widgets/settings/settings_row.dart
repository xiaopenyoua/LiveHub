import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/widgets/highlight_widget.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 设置页的一行：min-height 96、圆角 14、左右 padding 44/14，
/// 静置 .062、焦点 .215、焦点 scale 1.02（设计规范 §8.1）。
class SettingsRow extends StatelessWidget {
  const SettingsRow({
    super.key,
    required this.focusNode,
    required this.title,
    this.desc,
    this.leading,
    this.trailing,
    this.chevron = false,
    this.autofocus = false,
    this.onTap,
    this.onLeftKey,
    this.onRightKey,
  });

  final AppFocusNode focusNode;
  final String title;
  final String? desc;
  final Widget? leading;
  final Widget? trailing;
  final bool chevron;
  final bool autofocus;
  final VoidCallback? onTap;
  final KeyEventResult Function()? onLeftKey;
  final KeyEventResult Function()? onRightKey;

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    final description = desc;
    return HighlightWidget(
      focusNode: focusNode,
      autofocus: autofocus,
      borderRadius: t.listRadius,
      focusScale: 1.02,
      idleLevel: 0.062,
      focusLevel: 0.215,
      onLeftKey: onLeftKey,
      onRightKey: onRightKey,
      onTap: onTap == null
          ? null
          : () {
              onTap!();
              return KeyEventResult.handled;
            },
      child: ConstrainedBox(
        constraints: BoxConstraints(minHeight: 96 * t.unit),
        child: Padding(
          padding: EdgeInsets.symmetric(
            horizontal: 44 * t.unit,
            vertical: 14 * t.unit,
          ),
          child: Row(
            children: [
              if (leading != null) ...[
                leading!,
                SizedBox(width: 24 * t.unit),
              ],
              Expanded(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: t.title.copyWith(fontWeight: FontWeight.w500),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (description != null)
                      Padding(
                        padding: EdgeInsets.only(top: 6 * t.unit),
                        child: Obx(
                          () => Text(
                            description,
                            style: t.small.copyWith(
                              color:
                                  focusNode.isFoucsed.value ? t.muted : t.faint,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
              if (trailing != null) ...[
                SizedBox(width: 24 * t.unit),
                trailing!,
              ],
              if (chevron)
                Padding(
                  padding: EdgeInsets.only(left: 12 * t.unit),
                  child: Icon(
                    Icons.chevron_right,
                    size: 32 * t.unit,
                    color: t.muted,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
