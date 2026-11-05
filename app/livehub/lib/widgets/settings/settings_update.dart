import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub/app/app_style.dart';
import 'package:livehub/app/controller/update_controller.dart';
import 'package:livehub/app/utils.dart';
import 'package:livehub/widgets/settings/settings_action.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 设置页的「检查更新」区块
///
/// 复用 [SettingsAction] 保持和其它设置行一致的外观，额外展示：
/// - 当前版本 / 检测结果（subtitle）
/// - 下载进度条与取消按钮
/// - 下载完成后的安装入口
class SettingsUpdate extends StatelessWidget {
  const SettingsUpdate({super.key});

  @override
  Widget build(BuildContext context) {
    final tokens = context.lh;
    final faint = tokens.palette.faint;
    final controller = UpdateController.instance;

    return Obx(() {
      final downloading = controller.downloading.value;
      final status = controller.statusText.value;
      final downloaded = controller.downloadedApkPath.value != null;
      final progress = controller.downloadProgress.value;
      final percent = (progress * 100).clamp(0, 100).toStringAsFixed(0);

      return Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SettingsAction(
            title: "检查更新",
            subtitle: status.isEmpty
                ? "当前版本 v${controller.localVersion.value}"
                : status,
            value: controller.checking.value
                ? "检查中…"
                : "v${controller.localVersion.value}",
            onTap: () {
              if (controller.checking.value || controller.downloading.value) {
                return;
              }
              controller.checkUpdate();
            },
          ),
          if (downloading)
            Padding(
              padding: AppStyle.edgeInsetsH16.copyWith(bottom: 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  ClipRRect(
                    borderRadius: AppStyle.radius4,
                    child: LinearProgressIndicator(
                      value: progress <= 0 ? null : progress,
                      minHeight: 4,
                      backgroundColor: tokens.palette.wash,
                      color: tokens.palette.glow,
                    ),
                  ),
                  AppStyle.vGap4,
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          "正在下载… $percent%",
                          style: tokens.small.copyWith(color: faint),
                        ),
                      ),
                      TextButton(
                        onPressed: controller.cancelDownload,
                        child: const Text("取消"),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          if (!downloading && !downloaded && controller.hasUpdate.value)
            Padding(
              padding: AppStyle.edgeInsetsH16.copyWith(bottom: 8),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      "新版本 v${controller.release.value?.version ?? ""} 可下载",
                      style: tokens.small.copyWith(color: faint),
                    ),
                  ),
                  TextButton.icon(
                    onPressed: controller.downloadApk,
                    icon: const Icon(Icons.download, size: 18),
                    label: const Text("下载更新"),
                  ),
                ],
              ),
            ),
          if (!downloading && downloaded)
            Padding(
              padding: AppStyle.edgeInsetsH16.copyWith(bottom: 8),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      "安装包已就绪（${Utils.parseFileSize(controller.downloadedSize.value)}）",
                      style: tokens.small.copyWith(color: faint),
                    ),
                  ),
                  TextButton.icon(
                    onPressed: controller.installApk,
                    icon: const Icon(Icons.install_mobile, size: 18),
                    label: const Text("安装"),
                  ),
                ],
              ),
            ),
        ],
      );
    });
  }
}
