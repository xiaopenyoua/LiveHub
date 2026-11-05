import 'dart:async';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:livehub/app/log.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

/// 在线更新服务
///
/// 数据源固定为 GitHub Releases（仓库 [repoOwner]/[repoName]）：
/// 1. [checkUpdate] 请求 `releases/latest`，解析 tag_name / body / assets；
/// 2. [downloadApk] 把 .apk 资产下载到应用缓存目录，并校验 HTTP 200、非空、大小；
/// 3. [installApk] 通过 MethodChannel 交给 Android 原生层，用 FileProvider + Intent 安装。
///
/// 所有网络异常统一包装为 [UpdateException]，由调用方（[UpdateController]）降级成中文提示，
/// 不会把 Dio 的原始异常抛到 UI。
class UpdateService {
  UpdateService._();

  static final UpdateService instance = UpdateService._();

  /// GitHub 仓库归属
  static const String repoOwner = "xiaopenyoua";
  static const String repoName = "LiveHub";

  /// 最新 release 接口（GitHub 已排除 draft / prerelease）
  static const String releaseApiUrl =
      "https://api.github.com/repos/$repoOwner/$repoName/releases/latest";

  /// 发布页地址，桌面端或无 APK 资产时作为兜底入口
  static const String releasePageUrl =
      "https://github.com/$repoOwner/$repoName/releases/latest";

  /// 与 android/app/src/main/kotlin/com/livehub/app/MainActivity.kt 中的名称保持一致
  static const String installChannelName = "com.livehub.app/update";

  static const MethodChannel _installChannel =
      MethodChannel(installChannelName);

  /// 下载目录名（位于 getTemporaryDirectory() 下，同时被 FileProvider 的 file_paths.xml 覆盖）
  static const String downloadDirName = "apk_update";

  /// 请求 GitHub API 的客户端：短超时，失败要快，别让用户干等
  late final Dio _apiDio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 15),
      sendTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 30),
    ),
  );

  /// 下载客户端：APK 体积大，接收超时放宽到 5 分钟（按数据块计时）
  late final Dio _downloadDio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 15),
      sendTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(minutes: 5),
    ),
  );

  static const Map<String, String> _githubHeaders = {
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "LiveHub-Android",
  };

  /// 当前平台是否支持在线安装（只有 Android 走 Intent 安装）
  static bool get canInstallOnline => Platform.isAndroid;

  /// 检查最新版本
  ///
  /// - [localVersion] package_info_plus 的 version，例如 `1.0.0`
  /// - [localBuildNumber] package_info_plus 的 buildNumber，例如 `1`
  ///
  /// 远端不高于本地时返回 [UpdateCheckResult.hasUpdate] = false，
  /// 网络失败抛出 [UpdateException]（message 已经是中文提示）。
  Future<UpdateCheckResult> checkUpdate({
    required String localVersion,
    required String localBuildNumber,
  }) async {
    final localFull = _joinLocalVersion(localVersion, localBuildNumber);
    try {
      final response = await _apiDio.get<dynamic>(
        releaseApiUrl,
        options: Options(
          headers: _githubHeaders,
          responseType: ResponseType.json,
          validateStatus: (status) =>
              status != null && status >= 200 && status < 300,
        ),
      );
      final data = response.data;
      if (data is! Map) {
        throw const UpdateException("GitHub 返回的数据格式无法识别");
      }
      final release = AppUpdateRelease.fromJson(
        data.cast<String, dynamic>(),
      );
      if (release.tagName.trim().isEmpty) {
        throw const UpdateException("发布信息缺少版本号，请稍后重试");
      }
      final newer = isRemoteNewer(
        localVersion: localVersion,
        localBuildNumber: localBuildNumber,
        remoteTag: release.tagName,
      );
      return UpdateCheckResult(
        localVersion: localFull,
        remoteVersion: release.version,
        hasUpdate: newer,
        release: release,
      );
    } on UpdateException {
      rethrow;
    } on DioException catch (e) {
      Log.e("检查更新失败：${e.type} ${e.message}", e.stackTrace);
      throw UpdateException(describeDioError(e));
    } on SocketException catch (e) {
      Log.logPrint(e);
      throw const UpdateException("网络不可用，请检查网络连接后重试");
    } catch (e, s) {
      Log.e("检查更新异常：$e", s);
      throw const UpdateException("检查更新失败，请稍后重试");
    }
  }

  /// 下载 APK
  ///
  /// - [onProgress] received/total，total 可能为 -1（服务端未给长度），调用方自行处理
  /// - 完成后返回本地文件路径与字节数
  Future<UpdateDownloadResult> downloadApk(
    AppUpdateAsset asset, {
    void Function(int received, int total)? onProgress,
    CancelToken? cancelToken,
  }) async {
    final fileName = _safeFileName(asset.name);
    final targetDir = await _prepareDownloadDir();
    final file = File(p.join(targetDir.path, fileName));

    try {
      // 清理上一次下载残留，避免同名文件被当作本次结果
      await _cleanDownloadDir(targetDir);
      final response = await _downloadDio.download(
        asset.downloadUrl,
        file.path,
        cancelToken: cancelToken,
        deleteOnError: true,
        options: Options(
          headers: const {
            "Accept": "application/octet-stream",
            "User-Agent": "LiveHub-Android",
          },
          followRedirects: true,
          // 只接受 200，301/302 由 Dio 跟随，403 等直接进入错误分支
          validateStatus: (status) => status == 200,
        ),
        onReceiveProgress: (received, total) {
          onProgress?.call(received, total);
        },
      );

      if (response.statusCode != 200) {
        throw UpdateException(
          "下载失败（HTTP ${response.statusCode ?? "未知"}），请稍后重试",
        );
      }

      final length = await file.length();
      if (length <= 0) {
        await _deleteQuietly(file);
        throw const UpdateException("下载失败：安装包内容为空");
      }

      final contentLength = int.tryParse(
            response.headers.value(Headers.contentLengthHeader) ?? "",
          ) ??
          0;
      if (contentLength > 0 && length != contentLength) {
        await _deleteQuietly(file);
        throw const UpdateException("下载不完整：文件大小与服务器返回不一致，请重试");
      }
      // 发布信息里 size 为 0 时跳过二次校验
      if (asset.isSizeUsable && length != asset.size) {
        await _deleteQuietly(file);
        throw const UpdateException("下载不完整：文件大小与发布信息不一致，请重试");
      }

      return UpdateDownloadResult(
        filePath: file.path,
        fileName: fileName,
        size: length,
      );
    } on UpdateException {
      await _deleteQuietly(file);
      rethrow;
    } on DioException catch (e) {
      await _deleteQuietly(file);
      if (CancelToken.isCancel(e)) {
        throw const UpdateException("下载已取消");
      }
      Log.e("下载安装包失败：${e.type} ${e.message}", e.stackTrace);
      throw UpdateException(describeDioError(e));
    } on FileSystemException catch (e) {
      await _deleteQuietly(file);
      Log.logPrint(e);
      throw const UpdateException("写入安装包失败，请检查设备存储空间");
    } catch (e, s) {
      await _deleteQuietly(file);
      Log.e("下载安装包异常：$e", s);
      throw const UpdateException("下载安装包失败，请稍后重试");
    }
  }

  /// 触发系统安装程序
  ///
  /// Android 7.0+ 必须通过 FileProvider 提供 content:// URI，原生层已经配置好。
  Future<void> installApk(String filePath) async {
    if (!Platform.isAndroid) {
      throw const UpdateException("当前平台不支持在线安装，请前往发布页手动下载");
    }
    final file = File(filePath);
    if (!await file.exists() || await file.length() <= 0) {
      throw const UpdateException("安装包不存在或已损坏，请重新下载");
    }
    try {
      await _installChannel.invokeMethod<void>("installApk", {
        "path": file.path,
      });
    } on PlatformException catch (e) {
      Log.logPrint(e);
      throw UpdateException(e.message ?? "无法调起系统安装程序");
    } on MissingPluginException catch (e) {
      Log.logPrint(e);
      throw const UpdateException("当前安装包未包含安装通道，请前往发布页手动下载");
    }
  }

  /// 是否允许安装未知来源应用（Android 8.0+ 需要用户在系统设置里授权）
  Future<bool> canInstallPackages() async {
    if (!Platform.isAndroid) {
      return false;
    }
    try {
      return await _installChannel.invokeMethod<bool>("canInstallPackages") ??
          false;
    } on PlatformException catch (e) {
      Log.logPrint(e);
      return false;
    } on MissingPluginException catch (e) {
      // 通道缺失时交给系统自己判断，不要因为查询失败阻止安装
      Log.logPrint(e);
      return true;
    }
  }

  /// 打开「安装未知应用」授权页
  Future<void> openInstallSettings() async {
    if (!Platform.isAndroid) {
      return;
    }
    try {
      await _installChannel.invokeMethod<void>("openInstallSettings");
    } on PlatformException catch (e) {
      Log.logPrint(e);
      throw UpdateException(e.message ?? "无法打开系统设置页面");
    } on MissingPluginException catch (e) {
      Log.logPrint(e);
      throw const UpdateException("无法打开系统设置页面，请手动到系统设置中授权");
    }
  }

  /// 语义化版本比较：返回 -1 / 0 / 1，分别表示 [a] 小于 / 等于 / 大于 [b]
  ///
  /// 支持 `v1.2.3`、`1.2.3-beta.1`、`1.2.3+4` 这类写法，缺失的段按 0 处理。
  static int compareVersion(String a, String b) {
    return _SemVer.parse(a).compareTo(_SemVer.parse(b));
  }

  /// 远端版本是否高于本地版本
  ///
  /// 先比 major.minor.patch 与预发布标识；完全相同时再比构建号（本地 buildNumber 与
  /// tag 里的 `+N`）。远端没有构建号时视为不高于本地，避免同一个 tag 反复提示。
  static bool isRemoteNewer({
    required String localVersion,
    required String localBuildNumber,
    required String remoteTag,
  }) {
    final local = _SemVer.parse(
      _joinLocalVersion(localVersion, localBuildNumber),
    );
    final remote = _SemVer.parse(remoteTag);
    return remote.compareTo(local) > 0;
  }

  /// 从资产列表里挑 APK：优先 arm64-v8a，其次 universal，再退 armeabi-v7a
  ///
  /// 明确排除 TV / 桌面端安装包（同一 release 可能同时挂多端产物），
  /// 找不到可用 APK 时返回 null。
  static AppUpdateAsset? pickApkAsset(List<AppUpdateAsset> assets) {
    final candidates = assets
        .where((e) => e.isApk && !_isOtherPlatformAsset(e.name))
        .toList();
    if (candidates.isEmpty) {
      return null;
    }
    candidates.sort((a, b) {
      final byAbi = _abiWeight(a.name) - _abiWeight(b.name);
      if (byAbi != 0) {
        return byAbi;
      }
      // 同优先级时选体积大的（通常是完整包）
      return b.size.compareTo(a.size);
    });
    return candidates.first;
  }

  /// 把网络层异常翻译成中文提示
  static String describeDioError(DioException e) {
    switch (e.type) {
      case DioExceptionType.connectionTimeout:
        return "连接 GitHub 超时，请检查网络后重试";
      case DioExceptionType.sendTimeout:
        return "网络发送超时，请稍后重试";
      case DioExceptionType.receiveTimeout:
        return "网络响应超时，请稍后重试";
      case DioExceptionType.transformTimeout:
        return "网络请求超时，请稍后重试";
      case DioExceptionType.badCertificate:
        return "HTTPS 证书校验失败，请检查网络环境";
      case DioExceptionType.cancel:
        return "操作已取消";
      case DioExceptionType.connectionError:
        return "无法连接 GitHub，请检查网络或代理设置";
      case DioExceptionType.badResponse:
        final status = e.response?.statusCode ?? 0;
        // 未登录访问 GitHub API 的额度是每 IP 每小时 60 次，打满后返回 403/429
        final quotaExhausted =
            e.response?.headers.value("x-ratelimit-remaining") == "0";
        if (status == 403) {
          if (quotaExhausted) {
            return "GitHub 拒绝访问（403）：匿名接口每小时 60 次额度已用完，请稍后再试";
          }
          return "GitHub 拒绝访问（403），可能是请求过于频繁，请稍后再试";
        }
        if (status == 429) {
          return quotaExhausted
              ? "GitHub 接口额度已用完（429），请稍后再试"
              : "请求过于频繁（429），请稍后再试";
        }
        if (status == 404) {
          return "未找到发布版本（404），可能还没有发布过 Release";
        }
        if (status == 401) {
          return "GitHub 鉴权失败（401）";
        }
        if (status >= 500) {
          return "GitHub 服务异常（HTTP $status），请稍后再试";
        }
        return "请求失败（HTTP $status）";
      case DioExceptionType.unknown:
        if (e.error is SocketException) {
          return "网络不可用，请检查网络连接";
        }
        return "网络请求失败，请稍后重试";
    }
  }

  static int _abiWeight(String name) {
    final n = name.toLowerCase();
    if (n.contains("arm64") || n.contains("aarch64")) {
      return 0;
    }
    if (n.contains("universal")) {
      return 1;
    }
    if (n.contains("armeabi") || n.contains("armv7") || n.contains("arm-v7")) {
      return 2;
    }
    if (n.contains("x86")) {
      return 4;
    }
    return 3;
  }

  /// 同一 release 里可能同时挂 TV / 桌面端产物，这些不能装到手机上
  static bool _isOtherPlatformAsset(String name) {
    final n = name.toLowerCase();
    return n.contains("tv") ||
        n.contains("console") ||
        n.contains("desktop") ||
        n.contains("windows");
  }

  static String _joinLocalVersion(String version, String buildNumber) {
    if (version.contains("+")) {
      return version;
    }
    return "$version+$buildNumber";
  }

  static String _safeFileName(String rawName) {
    final base = p.basename(rawName.trim()).replaceAll(
          RegExp(r'[^0-9A-Za-z._+-]'),
          "_",
        );
    if (base.isEmpty || base == "_") {
      return "livehub-update.apk";
    }
    return base;
  }

  static Future<Directory> _prepareDownloadDir() async {
    final tempDir = await getTemporaryDirectory();
    final dir = Directory(p.join(tempDir.path, downloadDirName));
    if (!await dir.exists()) {
      await dir.create(recursive: true);
    }
    return dir;
  }

  static Future<void> _cleanDownloadDir(Directory dir) async {
    try {
      await for (final entity in dir.list()) {
        if (entity is File && entity.path.toLowerCase().endsWith(".apk")) {
          await entity.delete();
        }
      }
    } catch (e) {
      Log.logPrint(e);
    }
  }

  static Future<void> _deleteQuietly(File file) async {
    try {
      if (await file.exists()) {
        await file.delete();
      }
    } catch (e) {
      Log.logPrint(e);
    }
  }
}

/// 发布版本信息
class AppUpdateRelease {
  /// 原始 tag，例如 `v1.0.1`
  final String tagName;

  /// 去掉前缀 v 的版本号，用于展示与比较，例如 `1.0.1`
  final String version;

  /// 更新说明（Markdown 原文）
  final String notes;

  /// 发布时间
  final DateTime? publishedAt;

  /// 发布页地址
  final String pageUrl;

  /// 全部资产
  final List<AppUpdateAsset> assets;

  /// 选中的 APK（可能为 null，表示这次发布没有可用安装包）
  final AppUpdateAsset? apk;

  const AppUpdateRelease({
    required this.tagName,
    required this.version,
    required this.notes,
    required this.assets,
    required this.apk,
    this.publishedAt,
    this.pageUrl = UpdateService.releasePageUrl,
  });

  factory AppUpdateRelease.fromJson(Map<String, dynamic> json) {
    final tagName = (json["tag_name"] ?? "").toString();
    final rawAssets = json["assets"];
    final assets = <AppUpdateAsset>[];
    if (rawAssets is List) {
      for (final item in rawAssets) {
        if (item is Map) {
          assets.add(
            AppUpdateAsset.fromJson(item.cast<String, dynamic>()),
          );
        }
      }
    }
    return AppUpdateRelease(
      tagName: tagName,
      version: AppUpdateRelease.normalizeVersion(tagName),
      notes: (json["body"] ?? "").toString(),
      publishedAt: DateTime.tryParse((json["published_at"] ?? "").toString()),
      pageUrl: (json["html_url"] ?? UpdateService.releasePageUrl).toString(),
      assets: assets,
      apk: UpdateService.pickApkAsset(assets),
    );
  }

  /// `v1.0.1` -> `1.0.1`；`1.0.1+2` 原样返回
  static String normalizeVersion(String tagName) {
    var text = tagName.trim();
    if (text.startsWith("v") || text.startsWith("V")) {
      text = text.substring(1);
    }
    return text;
  }
}

/// 发布资产
class AppUpdateAsset {
  final String name;
  final int size;
  final String downloadUrl;

  const AppUpdateAsset({
    required this.name,
    required this.size,
    required this.downloadUrl,
  });

  factory AppUpdateAsset.fromJson(Map<String, dynamic> json) {
    return AppUpdateAsset(
      name: (json["name"] ?? "").toString(),
      size: _toInt(json["size"]),
      downloadUrl: (json["browser_download_url"] ?? "").toString(),
    );
  }

  static int _toInt(dynamic value) {
    if (value is int) {
      return value;
    }
    if (value is num) {
      return value.toInt();
    }
    return int.tryParse(value?.toString() ?? "") ?? 0;
  }

  bool get isApk => name.toLowerCase().endsWith(".apk");

  /// 发布信息里 size 是否可信（为 0 时跳过二次校验）
  bool get isSizeUsable => size > 0;

  /// 人类可读体积
  String get sizeText {
    if (size <= 0) {
      return "未知大小";
    }
    if (size < 1024 * 1024) {
      return "${(size / 1024).toStringAsFixed(1)} KB";
    }
    return "${(size / 1024 / 1024).toStringAsFixed(1)} MB";
  }
}

/// 检查更新的结果
class UpdateCheckResult {
  /// 本地完整版本，例如 `1.0.0+1`
  final String localVersion;

  /// 远端版本（已去掉 v 前缀）
  final String? remoteVersion;

  /// 是否有更新
  final bool hasUpdate;

  /// 远端发布信息
  final AppUpdateRelease? release;

  const UpdateCheckResult({
    required this.localVersion,
    required this.hasUpdate,
    this.remoteVersion,
    this.release,
  });
}

/// 下载完成的结果
class UpdateDownloadResult {
  final String filePath;
  final String fileName;
  final int size;

  const UpdateDownloadResult({
    required this.filePath,
    required this.fileName,
    required this.size,
  });
}

/// 更新流程里可以直接展示给用户的异常
class UpdateException implements Exception {
  final String message;

  const UpdateException(this.message);

  @override
  String toString() => message;
}

/// 内部使用的语义化版本，只负责比较
class _SemVer implements Comparable<_SemVer> {
  final List<int> numbers;
  final List<String> prerelease;
  final int? build;

  const _SemVer(this.numbers, this.prerelease, this.build);

  static _SemVer parse(String raw) {
    var text = AppUpdateRelease.normalizeVersion(raw).trim();

    int? build;
    final plusIndex = text.indexOf("+");
    if (plusIndex >= 0) {
      final buildText = text.substring(plusIndex + 1);
      build = int.tryParse(buildText);
      text = text.substring(0, plusIndex);
    }

    final List<String> prerelease;
    final dashIndex = text.indexOf("-");
    if (dashIndex >= 0) {
      prerelease = text
          .substring(dashIndex + 1)
          .split(".")
          .where((e) => e.isNotEmpty)
          .toList();
      text = text.substring(0, dashIndex);
    } else {
      prerelease = const [];
    }

    final numbers = <int>[];
    for (final part in text.split(".")) {
      final value = int.tryParse(part.trim());
      if (value != null) {
        numbers.add(value);
      }
    }
    if (numbers.isEmpty) {
      numbers.add(0);
    }
    return _SemVer(numbers, prerelease, build);
  }

  @override
  int compareTo(_SemVer other) {
    final length = numbers.length > other.numbers.length
        ? numbers.length
        : other.numbers.length;
    for (var i = 0; i < length; i++) {
      final a = i < numbers.length ? numbers[i] : 0;
      final b = i < other.numbers.length ? other.numbers[i] : 0;
      if (a != b) {
        return a > b ? 1 : -1;
      }
    }

    // 正式版高于同号预发布版
    if (prerelease.isEmpty && other.prerelease.isEmpty) {
      return _compareBuild(other);
    }
    if (prerelease.isEmpty) {
      return 1;
    }
    if (other.prerelease.isEmpty) {
      return -1;
    }
    final preLength = prerelease.length > other.prerelease.length
        ? prerelease.length
        : other.prerelease.length;
    for (var i = 0; i < preLength; i++) {
      if (i >= prerelease.length) {
        return -1;
      }
      if (i >= other.prerelease.length) {
        return 1;
      }
      final a = prerelease[i];
      final b = other.prerelease[i];
      final aNum = int.tryParse(a);
      final bNum = int.tryParse(b);
      if (aNum != null && bNum != null) {
        if (aNum != bNum) {
          return aNum > bNum ? 1 : -1;
        }
      } else if (aNum != null) {
        // 数字标识符低于字母标识符
        return -1;
      } else if (bNum != null) {
        return 1;
      } else {
        final result = a.compareTo(b);
        if (result != 0) {
          return result > 0 ? 1 : -1;
        }
      }
    }
    return _compareBuild(other);
  }

  int _compareBuild(_SemVer other) {
    final a = build ?? 0;
    final b = other.build ?? 0;
    if (a == b) {
      return 0;
    }
    return a > b ? 1 : -1;
  }
}
