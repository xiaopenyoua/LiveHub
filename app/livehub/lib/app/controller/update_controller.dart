import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_smart_dialog/flutter_smart_dialog.dart';
import 'package:get/get.dart';
import 'package:livehub/app/log.dart';
import 'package:livehub/app/utils.dart';
import 'package:livehub/services/update_service.dart';
import 'package:url_launcher/url_launcher_string.dart';

/// 在线更新的状态控制器
///
/// 沿用项目现有的 GetX Controller 模式（与 AppSettingsController 一致）：
/// 在 main.dart 的 initServices 里 Get.put，设置页直接取 [instance]。
///
/// 职责：把 [UpdateService] 的结果翻译成 UI 状态，并在有更新时弹说明对话框；
/// 网络失败只落到 [statusText] + Toast，不向上抛异常。
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

  /// 状态文案（检测结果 / 下载阶段 / 错误信息）
  final statusText = "".obs;

  /// 远端是否有更新
  final hasUpdate = false.obs;

  /// 远端发布信息
  final release = Rxn<AppUpdateRelease>();

  /// 已下载的安装包路径，null 表示还没下载好
  final downloadedApkPath = RxnString();

  CancelToken? _cancelToken;

  @override
  void onInit() {
    localVersion.value = getLocalVersion();
    super.onInit();
  }

  @override
  void onClose() {
    _cancelToken?.cancel("页面已关闭");
    super.onClose();
  }

  /// 本机版本号，优先取 package_info_plus 的真实值
  String getLocalVersion() {
    return "${Utils.packageInfo.version}+${Utils.packageInfo.buildNumber}";
  }

  /// 检查更新
  ///
  /// [showDialog] 为 true 且远端更高时，弹出带更新说明的对话框。
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
    statusText.value = "发现新版本 v$remoteVersion";
    if (showDialog && result.release != null) {
      await _showReleaseDialog(result.release!);
    }
  }

  /// 弹出更新说明对话框，用户确认后开始下载
  Future<void> _showReleaseDialog(AppUpdateRelease item) async {
    final notes = item.notes.trim();
    final notesText = notes.isEmpty ? "该版本没有填写更新说明。" : notes;
    final apk = item.apk;

    if (apk == null) {
      final openPage = await Utils.showAlertDialog(
        "$notesText\n\n该版本未提供 APK 安装包，可前往发布页手动下载。",
        title: "发现新版本 v${item.version}",
        confirm: "打开发布页",
        cancel: "稍后",
        selectable: true,
      );
      if (openPage) {
        await launchUrlString(
          item.pageUrl,
          mode: LaunchMode.externalApplication,
        );
      }
      return;
    }

    final confirm = await Utils.showAlertDialog(
      "$notesText\n\n安装包：${apk.name}（${apk.sizeText}）",
      title: "发现新版本 v${item.version}",
      confirm: "立即更新",
      cancel: "稍后",
      selectable: true,
    );
    if (confirm) {
      await downloadApk();
    }
  }

  /// 下载当前 release 的 APK
  ///
  /// [autoInstall] 为 true 时下载完成后直接调起安装。
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
      statusText.value = "该版本没有可用的 APK 安装包";
      SmartDialog.showToast(statusText.value);
      return;
    }

    downloading.value = true;
    downloadProgress.value = 0;
    downloadedSize.value = 0;
    downloadedApkPath.value = null;
    statusText.value = "正在下载 ${apk.name}";
    _cancelToken = CancelToken();

    try {
      final result = await UpdateService.instance.downloadApk(
        apk,
        cancelToken: _cancelToken,
        onProgress: (received, total) {
          if (total > 0) {
            downloadProgress.value =
                (received / total).clamp(0.0, 1.0).toDouble();
          }
        },
      );
      downloadedApkPath.value = result.filePath;
      downloadedSize.value = result.size;
      downloadProgress.value = 1;
      statusText.value = "下载完成，可安装更新";
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

  /// 取消正在进行的下载
  void cancelDownload() {
    if (!downloading.value) {
      return;
    }
    _cancelToken?.cancel("用户取消下载");
    statusText.value = "已取消下载";
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
          "系统尚未允许 LiveHub 安装应用。\n请在「设置 - 应用 - 安装未知应用」中允许 LiveHub，然后返回重试。",
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
