import 'package:flutter/material.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 设置页左栏的一行 key-value。
class SettingsStatusItem {
  const SettingsStatusItem(this.label, this.value);

  final String label;
  final String value;
}

/// 设置页左栏：只读状态卡。
///
/// 它不参与焦点——没有按钮、没有可聚焦元素，只回答“现在是什么状态”。
/// 视觉用 `--wash` 的平面，不是玻璃（设计规范 §8.1）。
class SettingsStatusCard extends StatelessWidget {
  const SettingsStatusCard({
    super.key,
    required this.kicker,
    required this.title,
    required this.subtitle,
    required this.items,
    required this.note,
  });

  final String kicker;
  final String title;
  final String subtitle;
  final List<SettingsStatusItem> items;
  final String note;

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return Container(
      padding: EdgeInsets.fromLTRB(
        40 * t.unit,
        38 * t.unit,
        40 * t.unit,
        34 * t.unit,
      ),
      decoration: BoxDecoration(
        color: t.wash,
        borderRadius: t.panelRadius,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            kicker,
            style: t.small.copyWith(
              color: t.faint,
              letterSpacing: 1.2 * t.unit,
            ),
          ),
          SizedBox(height: 14 * t.unit),
          Text(
            title,
            style: t.h1.copyWith(
              height: 1.12,
              letterSpacing: -1.1 * t.unit,
            ),
          ),
          SizedBox(height: 12 * t.unit),
          Text(
            subtitle,
            style: t.small.copyWith(
              color: t.muted,
              height: 1.55,
            ),
          ),
          SizedBox(height: 30 * t.unit),
          for (var i = 0; i < items.length; i++) ...[
            if (i > 0) SizedBox(height: 18 * t.unit),
            Row(
              crossAxisAlignment: CrossAxisAlignment.baseline,
              textBaseline: TextBaseline.alphabetic,
              children: [
                Expanded(
                  child: Text(
                    items[i].label,
                    style: t.small.copyWith(color: t.faint),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                SizedBox(width: 24 * t.unit),
                Flexible(
                  child: Text(
                    items[i].value,
                    style: t.meta.copyWith(color: t.text),
                    textAlign: TextAlign.right,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
            ),
          ],
          SizedBox(height: 32 * t.unit),
          Text(
            note,
            style: t.small.copyWith(
              color: t.faint,
              height: 1.6,
            ),
          ),
        ],
      ),
    );
  }
}
