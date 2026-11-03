import 'package:flutter/material.dart';
import 'package:livehub/app/app_style.dart';
import 'package:livehub_ui/livehub_ui.dart';

class SettingsAction extends StatelessWidget {
  final String title;
  final String? subtitle;
  final Function()? onTap;
  final String? value;
  final Widget? leading;

  const SettingsAction({
    required this.title,
    this.value,
    this.onTap,
    this.subtitle,
    this.leading,
    Key? key,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final tokens = context.lh;
    final faint = tokens.palette.faint;
    return ListTile(
      // visualDensity: VisualDensity.compact,
      leading: leading,
      title: Text(
        title,
        style: tokens.body.copyWith(color: tokens.text),
      ),
      shape: RoundedRectangleBorder(
        borderRadius: tokens.listRadius,
      ),
      contentPadding: AppStyle.edgeInsetsL16.copyWith(right: 8),
      subtitle: subtitle == null
          ? null
          : Text(
              subtitle!,
              style: tokens.small.copyWith(color: faint),
            ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (value != null)
            Text(
              value!,
              style: tokens.meta.copyWith(color: faint),
            ),
          AppStyle.hGap4,
          Icon(
            Icons.chevron_right,
            color: faint,
          ),
        ],
      ),
      onTap: onTap,
    );
  }
}
