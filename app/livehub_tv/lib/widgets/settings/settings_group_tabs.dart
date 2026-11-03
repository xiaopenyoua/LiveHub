import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/widgets/highlight_widget.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 设置页右栏顶部的分组条。
///
/// 高度 60、圆角 14，不是胶囊；静置 .10 / 选中 .22 / 焦点 .34，焦点 scale 1.04。
class SettingsGroupTabs extends StatelessWidget {
  const SettingsGroupTabs({
    super.key,
    required this.labels,
    required this.focusNodes,
    required this.selectedIndex,
    required this.onSelect,
  });

  final List<String> labels;
  final List<AppFocusNode> focusNodes;
  final int selectedIndex;
  final ValueChanged<int> onSelect;

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (var i = 0; i < labels.length; i++)
          Padding(
            padding: EdgeInsets.only(
                right: i == labels.length - 1 ? 0 : 12 * t.unit),
            child: _buildTab(context, i),
          ),
      ],
    );
  }

  Widget _buildTab(BuildContext context, int index) {
    final t = context.lh;
    final selected = index == selectedIndex;
    return HighlightWidget(
      focusNode: focusNodes[index],
      borderRadius: t.listRadius,
      focusScale: 1.04,
      idleLevel: selected ? 0.22 : 0.10,
      focusLevel: 0.34,
      onTap: () {
        onSelect(index);
        return KeyEventResult.handled;
      },
      child: SizedBox(
        height: 60 * t.unit,
        child: Padding(
          padding: EdgeInsets.symmetric(horizontal: 28 * t.unit),
          child: Center(
            child: Obx(() {
              // 必须无条件读一次：`selected || ...` 短路时选中页签不读 RxBool，
              // Obx 会因没注册任何可观察变量而抛 "improper use of a GetX"。
              final focused = focusNodes[index].isFoucsed.value;
              return Text(
                labels[index],
                style: t.small.copyWith(
                  color: selected || focused ? t.text : t.muted,
                  fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                ),
              );
            }),
          ),
        ),
      ),
    );
  }
}
