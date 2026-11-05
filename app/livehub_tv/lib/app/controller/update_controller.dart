import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_smart_dialog/flutter_smart_dialog.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/log.dart';
import 'package:livehub_tv/app/utils.dart';
import 'package:livehub_tv/services/update_service.dart';
import 'package:livehub_tv/widgets/settings/settings_update_dialog.dart';

/// 电视端在线更新控制器
///
/// 与手机端 UpdateController 同构（GetX + Obx），差异在交互：
/// 电视没有「按钮」概念，设置页只有一行可聚焦的「检查更新」，
/// OK 键根据当前状态依次执行：检查 → 下载 → 安装 / 下载中取消。
class UpdateController extends GetxController {
  static UpdateController get instance => Get.find<UpdateController>();

  /// 当前版本，形如 1.0.0+1
  final localVersion = "".obs;

  /// 是否正在请求 GitHub API
  final checking = false.obs;

  /// 是否正在下载 APK
  final downloading = false.obs;

  /// 下载进度 0.0 ~ 1.0
  final downloadProgress = 0.0.obs;

  /// 下载完成后安装包的字节数
  final downloadedSize = 0.obs;

  /// 设置行副标题：检测结果 / 下载进度 / 错误信息
  final statusText = "".obs;

  /// 远端是否有更新
  final hasUpdate = false.obs;

  /// 远端发布信息
  final release = Rxn<AppUpdateRelease>();

  /// 已下载的安装包路径，null 表示还没下载好
  final downloadedApkPath = RxnString();

  CancelToken? _cancelToken;

  /// 进度文案只在百分比变化时刷新，避免每块数据都重建整行
  int _lastPercent = -1;

  @override
  void onInit() {
    localVersion.value =
        "${Utils.packageInfo.version}+${Utils.packageInfo.buildNumber}";
    super.onInit();
  }

  @override
  void onClose() {
    _cancelToken?.cancel("页面已关闭");
    super.onClose();
  }

  /// 设置页「检查更新」行的 OK 键：按当前状态决定动作
  Future<void> handleTap() async {
    if (checking.value) {
      return;
    }
    if (downloading.value) {
      cancelDownload();
      return;
    }
    if (downloadedApkPath.value != null) {
      await installApk();
      return;
    }
    if (hasUpdate.value && release.value != null) {
      await _showReleaseDialog(release.value!);
      return;
    }
    await checkUpdate();
  }

  /// 检查更新
  ///
  /// [showDialog] 为 true 且远端更高时弹出更新说明。
  Future<void> checkUpdate({bool showDialog = true}) async {
    if (checking.value || downloading.value) {
      return;
    }
    checking.value = true;
    statusText.value = "正在检查更新…";

    UpdateCheckResult? result;
    try {
      result = await UpdateService.instance.checkUpdate(
        localVersion: Utils.packageInfo.version,
        localBuildNumber: Utils.packageInfo.buildNumber,
      );
    } on UpdateException catch (e) {
      statusText.value = e.message;
      SmartDialog.showToast(e.message);
      Log.logPrint("检查更新失败：${e.message}");
    } catch (e, s) {
      statusText.value = "检查更新失败，请稍后重试";
      SmartDialog.showToast(statusText.value);
      Log.e("检查更新异常：$e", s);
    } finally {
      checking.value = false;
    }

    if (result == null) {
      return;
    }

    release.value = result.release;
    hasUpdate.value = result.hasUpdate;
    if (!result.hasUpdate) {
      statusText.value = "已是最新版本（v${result.localVersion}）";
      SmartDialog.showToast("当前已是最新版本");
      return;
    }

    final remoteVersion = result.remoteVersion ?? "";
    statusText.value = "发现新版本 v$remoteVersion，按 OK 下载";
    if (showDialog && result.release != null) {
      await _showReleaseDialog(result.release!);
    }
  }

  /// 更新说明弹窗（电视端按钮由焦点系统接管，确认键默认落在「立即更新」）
  Future<void> _showReleaseDialog(AppUpdateRelease item) async {
    final apk = item.apk;
    if (apk == null) {
      await showUpdateMessageDialog(
        title: "发现新版本 v${item.version}",
        message: "该版本没有提供电视端安装包（需要 LiveHubTV-*.apk），"
            "请稍后重试或前往发布页查看。",
      );
      return;
    }

    final notes = item.notes.trim();
    final message = notes.isEmpty
        ? "本次未获取到更新说明（GitHub 接口受限时会走发布页兜底），可直接安装。\n\n"
            "安装包：${apk.name}（${apk.sizeText}）"
        : "$notes\n\n安装包：${apk.name}（${apk.sizeText}）";

    final confirm = await showUpdateConfirmDialog(
      title: "发现新版本 v${item.version}",
      message: message,
    );
    if (confirm) {
      await downloadApk();
    }
  }

  /// 下载当前 release 的电视端 APK
  Future<void> downloadApk({bool autoInstall = false}) async {
    if (downloading.value) {
      return;
    }
    final item = release.value;
    if (item == null) {
      SmartDialog.showToast("请先检查更新");
      return;
    }
    final apk = item.apk;
    if (apk == null) {
      statusText.value = "该版本没有可用的电视端安装包";
      SmartDialog.showToast(statusText.value);
      return;
    }

    downloading.value = true;
    downloadProgress.value = 0;
    downloadedSize.value = 0;
    downloadedApkPath.value = null;
    _lastPercent = -1;
    statusText.value = "正在下载 ${apk.name}（按 OK 取消）";
    _cancelToken = CancelToken();

    try {
      final result = await UpdateService.instance.downloadApk(
        apk,
        cancelToken: _cancelToken,
        onProgress: (received, total) {
          if (total <= 0) {
            return;
          }
          final value = (received / total).clamp(0.0, 1.0).toDouble();
          downloadProgress.value = value;
          final percent = (value * 100).floor();
          if (percent != _lastPercent) {
            _lastPercent = percent;
            statusText.value = "正在下载 $percent%（按 OK 取消）";
          }
        },
      );
      downloadedApkPath.value = result.filePath;
      downloadedSize.value = result.size;
      downloadProgress.value = 1;
      statusText.value =
          "安装包已就绪（${_formatSize(result.size)}），按 OK 安装";
      SmartDialog.showToast("安装包下载完成");
    } on UpdateException catch (e) {
      statusText.value = e.message;
      SmartDialog.showToast(e.message);
      Log.logPrint("下载更新失败：${e.message}");
    } catch (e, s) {
      statusText.value = "下载更新失败，请稍后重试";
      SmartDialog.showToast(statusText.value);
      Log.e("下载更新异常：$e", s);
    } finally {
      downloading.value = false;
      _cancelToken = null;
    }

    if (autoInstall && downloadedApkPath.value != null) {
      await installApk();
    }
  }

  /// 电视端 Utils 没有文件大小格式化，这里补一份（与手机端 Utils.parseFileSize 同口径）
  static String _formatSize(int size) {
    if (size <= 0) {
      return "未知大小";
    }
    if (size < 1024 * 1024) {
      return "${(size / 1024).toStringAsFixed(1)} KB";
    }
    return "${(size / 1024 / 1024).toStringAsFixed(1)} MB";
  }

  /// 取消正在进行的下载（下载中再按 OK）
  void cancelDownload() {
    if (!downloading.value) {
      return;
    }
    _cancelToken?.cancel("用户取消下载");
    statusText.value = "已取消下载，按 OK 重新检查";
  }

  /// 调起系统安装程序
  ///
  /// Android 8.0+ 需要「安装未知应用」权限，未授权时引导用户去系统设置。
  Future<void> installApk() async {
    final path = downloadedApkPath.value;
    if (path == null) {
      SmartDialog.showToast("请先下载安装包");
      return;
    }
    if (!Platform.isAndroid) {
      statusText.value = "当前平台不支持在线安装，请前往发布页手动下载";
      SmartDialog.showToast(statusText.value);
      return;
    }

    try {
      final allowed = await UpdateService.instance.canInstallPackages();
      if (!allowed) {
        statusText.value = "需要授予「安装未知应用」权限";
        final go = await Utils.showAlertDialog(
          "系统尚未允许 LiveHub TV 安装应用。\n"
          "请在「设置 - 应用 - 安装未知应用」中允许 LiveHub TV，然后返回重试。",
          title: "需要安装权限",
          confirm: "去设置",
          cancel: "取消",
        );
        if (go) {
          await UpdateService.instance.openInstallSettings();
        }
        return;
      }
      await UpdateService.instance.installApk(path);
      statusText.value = "已调起系统安装程序，请按提示完成安装";
    } on UpdateException catch (e) {
      statusText.value = e.message;
      SmartDialog.showToast(e.message);
    } catch (e, s) {
      statusText.value = "无法调起系统安装程序";
      SmartDialog.showToast(statusText.value);
      Log.e("调起安装失败：$e", s);
    }
  }
}
