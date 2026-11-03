import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub/app/app_style.dart';
import 'package:livehub_ui/livehub_ui.dart';

class SettingsMenu<T> extends StatelessWidget {
  final String title;
  final String? subtitle;
  final Map<T, String> valueMap;
  final T value;

  final Function(T)? onChanged;
  const SettingsMenu({
    required this.title,
    required this.value,
    required this.valueMap,
    this.subtitle,
    this.onChanged,
    Key? key,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final tokens = context.lh;
    final faint = tokens.palette.faint;
    return ListTile(
      visualDensity: VisualDensity.compact,
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
          Text(
            valueMap[value]!.tr,
            style: tokens.meta.copyWith(color: faint),
          ),
          AppStyle.hGap4,
          Icon(
            Icons.chevron_right,
            color: faint,
          ),
        ],
      ),
      onTap: () => openMenu(context),
    );
  }

  void openMenu(BuildContext context) {
    showModalBottomSheet(
      context: context,
      showDragHandle: true,
      useSafeArea: true, //useSafeArea似乎无效
      builder: (_) => SafeArea(
        top: false,
        child: SingleChildScrollView(
          child: RadioGroup(
            groupValue: value,
            onChanged: (e) {
              Get.back();
              onChanged?.call(e as T);
            },
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: valueMap.keys
                  .map(
                    (e) => RadioListTile(
                      value: e,
                      title: Text(
                        (valueMap[e]?.tr) ?? "???",
                        style: Get.textTheme.bodyMedium,
                      ),
                    ),
                  )
                  .toList(),
            ),
          ),
        ),
      ),
    );
  }
}
