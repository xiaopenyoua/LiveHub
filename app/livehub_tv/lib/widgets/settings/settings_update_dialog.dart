import 'package:flutter/material.dart';
import 'package:flutter_screenutil/flutter_screenutil.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_style.dart';

/// 电视端更新说明弹窗
///
/// livehub_tv 的 Utils.showAlertDialog 内容不可滚动，而 release body 通常很长，
/// 这里单独给一个带滚动区的版本；按钮焦点由遥控器接管，确认键默认落在主操作上。
Future<bool> showUpdateConfirmDialog({
  required String title,
  required String message,
}) async {
  final result = await Get.dialog<bool>(
    AlertDialog(
      shape: RoundedRectangleBorder(
        borderRadius: AppStyle.radius16,
      ),
      backgroundColor: Get.theme.cardColor,
      surfaceTintColor: Colors.transparent,
      titlePadding: AppStyle.edgeInsetsA24.copyWith(left: 48.w, right: 48.w),
      contentPadding: AppStyle.edgeInsetsA24.copyWith(left: 48.w, right: 48.w),
      insetPadding: AppStyle.edgeInsetsA16,
      title: Text(
        title,
        style: AppStyle.titleStyleWhite,
      ),
      content: _buildScrollableBody(message),
      actions: [
        TextButton(
          onPressed: () => Get.back(result: false),
          child: Text(
            "稍后",
            style: AppStyle.textStyleWhite,
          ),
        ),
        TextButton(
          autofocus: true,
          onPressed: () => Get.back(result: true),
          child: Text(
            "立即更新",
            style: AppStyle.textStyleWhite,
          ),
        ),
      ],
    ),
  );
  return result ?? false;
}

/// 只有「确定」的单按钮提示（没有电视端安装包等情况）
Future<void> showUpdateMessageDialog({
  required String title,
  required String message,
}) async {
  await Get.dialog<bool>(
    AlertDialog(
      shape: RoundedRectangleBorder(
        borderRadius: AppStyle.radius16,
      ),
      backgroundColor: Get.theme.cardColor,
      surfaceTintColor: Colors.transparent,
      titlePadding: AppStyle.edgeInsetsA24.copyWith(left: 48.w, right: 48.w),
      contentPadding: AppStyle.edgeInsetsA24.copyWith(left: 48.w, right: 48.w),
      insetPadding: AppStyle.edgeInsetsA16,
      title: Text(
        title,
        style: AppStyle.titleStyleWhite,
      ),
      content: _buildScrollableBody(message),
      actions: [
        TextButton(
          autofocus: true,
          onPressed: () => Get.back(result: true),
          child: Text(
            "知道了",
            style: AppStyle.textStyleWhite,
          ),
        ),
      ],
    ),
  );
}

Widget _buildScrollableBody(String message) {
  return Builder(
    builder: (context) {
      final size = MediaQuery.of(context).size;
      return ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: size.width * 0.62,
          maxHeight: size.height * 0.46,
        ),
        child: SingleChildScrollView(
          child: Text(
            message,
            style: AppStyle.textStyleWhite,
          ),
        ),
      );
    },
  );
}
