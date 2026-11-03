import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub/app/app_style.dart';
import 'package:livehub_ui/livehub_ui.dart';

class SettingsNumber extends StatelessWidget {
  final String title;
  final String? subtitle;
  final String unit;
  final int value;
  final int step;
  final int min;
  final int max;
  final String? displayValue;
  final Function(int)? onChanged;
  const SettingsNumber(
      {required this.title,
      required this.value,
      required this.max,
      this.subtitle,
      this.onChanged,
      this.step = 1,
      this.min = 0,
      this.unit = '',
      this.displayValue,
      Key? key})
      : super(key: key);

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
      subtitle: subtitle == null
          ? null
          : Text(
              subtitle!,
              style: tokens.small.copyWith(color: faint),
            ),
      contentPadding: AppStyle.edgeInsetsL16.copyWith(right: 12),
      trailing: LiveHubGlass(
        level: 0.10,
        radius: tokens.pillRadius,
        child: Material(
          color: Colors.transparent,
          child: SizedBox(
            height: 36,
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                IconButton(
                  padding: AppStyle.edgeInsetsA4,
                  constraints: const BoxConstraints(
                    minHeight: 32,
                  ),
                  onPressed: () {
                    int newValue = value - step;
                    if (newValue < min) {
                      newValue = min;
                    }
                    onChanged?.call(newValue);
                  },
                  icon: Icon(
                    Icons.remove,
                    color: tokens.muted,
                  ),
                ),
                Text(
                  displayValue ?? "$value$unit",
                  textAlign: TextAlign.center,
                  style: tokens.meta.copyWith(color: faint),
                ),
                IconButton(
                  padding: AppStyle.edgeInsetsA4,
                  constraints: const BoxConstraints(
                    minHeight: 32,
                  ),
                  onPressed: () {
                    int newValue = value + step;
                    if (newValue > max) {
                      newValue = max;
                    }
                    onChanged?.call(newValue);
                  },
                  icon: Icon(
                    Icons.add,
                    color: tokens.muted,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
      onTap: () => openSilder(context),
    );
  }

  void openSilder(BuildContext context) {
    var newValue = value.obs;
    showModalBottomSheet(
      context: context,
      showDragHandle: true,
      useSafeArea: true, //useSafeArea似乎无效
      builder: (_) => SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: AppStyle.edgeInsetsH16,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    title,
                    style: Get.textTheme.titleMedium,
                  ),
                  Obx(
                    () => Text(
                      "${newValue.value}$unit",
                      style: Get.textTheme.titleMedium,
                    ),
                  ),
                ],
              ),
            ),
            Obx(
              () => Slider(
                value: newValue.value.toDouble(),
                min: min.toDouble(),
                max: max.toDouble(),
                onChanged: (e) {
                  newValue.value = e.toInt();
                },
              ),
            ),
            Padding(
              padding: AppStyle.edgeInsetsH16,
              child: TextButton(
                onPressed: () {
                  onChanged?.call(newValue.value);
                  Get.back();
                },
                child: const Text("确定"),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
