import 'package:flutter/material.dart';
import 'package:livehub/app/app_style.dart';
import 'package:livehub_ui/livehub_ui.dart';

class SettingsSwitch extends StatelessWidget {
  final bool value;
  final String title;
  final String? subtitle;
  final Function(bool) onChanged;
  const SettingsSwitch({
    required this.value,
    required this.title,
    this.subtitle,
    required this.onChanged,
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    final tokens = context.lh;
    final faint = tokens.palette.faint;
    return SwitchListTile(
      title: Text(
        title,
        style: tokens.body.copyWith(color: tokens.text),
      ),
      shape: RoundedRectangleBorder(
        borderRadius: tokens.listRadius,
      ),
      //visualDensity: VisualDensity.compact,
      contentPadding: AppStyle.edgeInsetsL16.copyWith(right: 8),
      subtitle: subtitle != null
          ? Text(
              subtitle!,
              style: tokens.small.copyWith(color: faint),
            )
          : null,
      value: value,
      onChanged: onChanged,
    );
  }
}
