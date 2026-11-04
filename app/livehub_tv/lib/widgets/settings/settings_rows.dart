import 'package:flutter/material.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/widgets/settings/settings_row.dart';
import 'package:livehub_tv/widgets/settings/settings_segment.dart';
import 'package:livehub_tv/widgets/settings/settings_switch.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 开关行：左键 / 右键 / OK 都是切换，不做二次确认。
///
/// 这是设置页与播放器设置面板共用的唯一布尔交互语义。
class SettingsSwitchRow extends StatelessWidget {
  const SettingsSwitchRow({
    super.key,
    required this.focusNode,
    required this.title,
    required this.value,
    required this.onChanged,
    this.desc,
    this.autofocus = false,
  });

  final AppFocusNode focusNode;
  final String title;
  final String? desc;
  final bool value;
  final ValueChanged<bool> onChanged;
  final bool autofocus;

  @override
  Widget build(BuildContext context) {
    // 左=关、右=开是绝对赋值，不是两键都翻转：连续按同一方向不会来回跳。
    // 值没变时不写回，避免触发无关副作用（例如重置关注刷新定时器）。
    KeyEventResult setValue(bool next) {
      if (value != next) onChanged(next);
      return KeyEventResult.handled;
    }

    return SettingsRow(
      focusNode: focusNode,
      title: title,
      desc: desc,
      autofocus: autofocus,
      trailing: SettingsSwitch(value: value),
      onTap: () => onChanged(!value),
      onLeftKey: () => setValue(false),
      onRightKey: () => setValue(true),
    );
  }
}

/// 枚举行：≤5 项用行内分段控件，>5 项用「‹ 当前值 ›」。
///
/// 左右键与 OK 键都只改变当前值，不打开任何模态弹层；到两端停住。
class SettingsChoiceRow extends StatelessWidget {
  const SettingsChoiceRow({
    super.key,
    required this.focusNode,
    required this.title,
    required this.items,
    required this.value,
    required this.onChanged,
    this.desc,
    this.autofocus = false,
  });

  final AppFocusNode focusNode;
  final String title;
  final String? desc;
  final Map<dynamic, String> items;
  final dynamic value;
  final ValueChanged<dynamic> onChanged;
  final bool autofocus;

  /// 选项较少时用行内分段控件，选项较多时用“当前值 + 左右箭头”。
  static const _maxSegmentOptions = 5;

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    final keys = items.keys.toList();
    final selected = keys.indexOf(value);

    KeyEventResult step(int delta) {
      if (keys.isEmpty) return KeyEventResult.ignored;
      if (selected < 0) {
        onChanged(keys.first);
        return KeyEventResult.handled;
      }
      final next = (selected + delta).clamp(0, keys.length - 1);
      if (next != selected) onChanged(keys[next]);
      return KeyEventResult.handled;
    }

    final inlineSegment = keys.length >= 2 && keys.length <= _maxSegmentOptions;

    return SettingsRow(
      focusNode: focusNode,
      title: title,
      desc: desc,
      autofocus: autofocus,
      trailing: inlineSegment
          ? SettingsSegment<dynamic>(
              options: [
                for (final key in keys)
                  SettingsOption<dynamic>(key, items[key] ?? ''),
              ],
              value: value,
            )
          : Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.chevron_left, size: 32 * t.unit, color: t.faint),
                SizedBox(width: 12 * t.unit),
                ConstrainedBox(
                  constraints: BoxConstraints(minWidth: 120 * t.unit),
                  child: Text(
                    items[value] ?? '',
                    textAlign: TextAlign.center,
                    style: t.body.copyWith(color: t.muted),
                  ),
                ),
                SizedBox(width: 12 * t.unit),
                Icon(Icons.chevron_right, size: 32 * t.unit, color: t.faint),
              ],
            ),
      onTap: () => step(1),
      onLeftKey: () => step(-1),
      onRightKey: () => step(1),
    );
  }
}
