import 'dart:async';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:livehub_tv/app/log.dart';
import 'package:path_provider/path_provider.dart';

/// 电视端在线更新服务
///
/// 与手机端同源（app/livehub/lib/services/update_service.dart），差异只有三处：
/// 1. 资产只认电视包：文件名必须包含 TV，避免把手机端的 LiveHub-vX.Y.Z-*.apk 装到电视上；
/// 2. 安装通道名是 com.livehub.tv/update（对应电视端 MainActivity）；
/// 3. GitHub 匿名 API 被限流（403 / 429）时，改走「发布页 302 → tag + HEAD 探测资产」的兜底，
///    因为电视端用户通常没有代理，匿名额度按出口 IP 共享，很容易打满。
///
/// 网络异常统一包装成 [UpdateException]（message 即中文提示），不向 UI 抛原始异常。
class UpdateService {
  UpdateService._();

  static final UpdateService instance = UpdateService._();

  /// GitHub 仓库归属
  static const String repoOwner = "xiaopenyoua";
  static const String repoName = "LiveHub";

  /// 最新 release 接口（GitHub 已排除 draft / prerelease）
  static const String releaseApiUrl =
      "https://api.github.com/repos/$repoOwner/$repoName/releases/latest";

  /// 发布页地址：既给「打开发布页」用，也是 API 限流时的兜底入口
  static const String releasePageUrl =
      "https://github.com/$repoOwner/$repoName/releases/latest";

  /// release 资产下载前缀（兜底时按约定名拼 URL）
  static const String releaseDownloadBaseUrl =
      "https://github.com/$repoOwner/$repoName/releases/download";

  /// 与 android/app/src/main/kotlin/com/livehub/tv/MainActivity.kt 中的名称保持一致
  static const String installChannelName = "com.livehub.tv/update";

  static const MethodChannel _installChannel =
      MethodChannel(installChannelName);

  /// 电视端 APK 文件名前缀；同时用来把手机端资产挡在外面
  static const String tvAssetPrefix = "LiveHubTV";

  /// 兜底探测时按优先级尝试的资产名后缀
  static const List<String> _tvAssetSuffixes = [
    "arm64-v8a.apk",
    "universal.apk",
    "armeabi-v7a.apk",
  ];

  /// 下载目录名（位于 getTemporaryDirectory() 下，同时被 file_paths.xml 覆盖）
  static const String downloadDirName = "apk_update";

  /// 请求 GitHub API 的客户端：短超时，失败要快
  late final Dio _apiDio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 15),
      sendTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 30),
    ),
  );

  /// 下载 / HEAD 探测用的客户端：APK 体积大，接收超时放宽到 5 分钟（按数据块计时）
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
    "User-Agent": "LiveHubTV-Android",
  };

  static const Map<String, String> _assetHeaders = {
    "Accept": "application/octet-stream",
    "User-Agent": "LiveHubTV-Android",
  };

  /// 当前平台是否支持在线安装（只有 Android 走 Intent 安装）
  static bool get canInstallOnline => Platform.isAndroid;

  /// 检查最新版本
  ///
  /// - [localVersion] package_info_plus 的 version，例如 1.0.0
  /// - [localBuildNumber] package_info_plus 的 buildNumber，例如 1
  ///
  /// 远端不高于本地时 [UpdateCheckResult.hasUpdate] = false；
  /// API 限流时会自动走发布页兜底，兜底也失败才抛出 [UpdateException]。
  Future<UpdateCheckResult> checkUpdate({
    required String localVersion,
    required String localBuildNumber,
  }) async {
    try {
      return await _checkFromApi(
        localVersion: localVersion,
        localBuildNumber: localBuildNumber,
      );
    } on UpdateRateLimitedException catch (e) {
      Log.w("GitHub API 限流，改用发布页兜底：${e.message}");
      try {
        return await checkUpdateFromReleasePage(
          localVersion: localVersion,
          localBuildNumber: localBuildNumber,
        );
      } catch (fallbackError, stack) {
        Log.e("发布页兜底失败：$fallbackError", stack);
        throw UpdateException(e.message);
      }
    }
  }

  /// 正常的 GitHub API 路径
  Future<UpdateCheckResult> _checkFromApi({
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
      return UpdateCheckResult(
        localVersion: localFull,
        remoteVersion: release.version,
        hasUpdate: isRemoteNewer(
          localVersion: localVersion,
          localBuildNumber: localBuildNumber,
          remoteTag: release.tagName,
        ),
        release: release,
      );
    } on UpdateException {
      rethrow;
    } on DioException catch (e) {
      final status = e.response?.statusCode;
      if (status == 403 || status == 429) {
        throw UpdateRateLimitedException(describeDioError(e));
      }
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

  /// 限流兜底：发布页 302 → 解析 tag → HEAD 探测电视端 APK
  ///
  /// 这条路径不经过 api.github.com，只依赖公开网页与下载 CDN，
  /// 因此在匿名额度打满时仍然可用。代价是拿不到 body（更新说明为空）。
  /// 公开出来是为了让「检查更新」在 API 反复受限时可以单独重试这条路径。
  Future<UpdateCheckResult> checkUpdateFromReleasePage({
    required String localVersion,
    required String localBuildNumber,
  }) async {
    final tagName = await _resolveLatestTag();
    if (tagName == null || tagName.isEmpty) {
      throw const UpdateException("GitHub 接口受限，无法从发布页确认最新版本，请稍后重试");
    }
    final assets = await _probeTvAssets(tagName);
    if (assets.isEmpty) {
      throw UpdateException("GitHub 接口受限，未在 $tagName 发布里探测到电视端安装包，请稍后重试");
    }
    final release = AppUpdateRelease(
      tagName: tagName,
      version: AppUpdateRelease.normalizeVersion(tagName),
      notes: "",
      pageUrl: releasePageUrl,
      assets: assets,
      apk: pickApkAsset(assets),
    );
    return UpdateCheckResult(
      localVersion: _joinLocalVersion(localVersion, localBuildNumber),
      remoteVersion: release.version,
      hasUpdate: isRemoteNewer(
        localVersion: localVersion,
        localBuildNumber: localBuildNumber,
        remoteTag: tagName,
      ),
      release: release,
    );
  }

  /// releases/latest 会 302 到 releases/tag/<tag>，从 Location 里取 tag
  Future<String?> _resolveLatestTag() async {
    try {
      final response = await _apiDio.get<dynamic>(
        releasePageUrl,
        options: Options(
          headers: const {
            "User-Agent": "LiveHubTV-Android",
            "Accept": "text/html",
          },
          followRedirects: false,
          responseType: ResponseType.plain,
          validateStatus: (status) =>
              status != null && status >= 200 && status < 400,
        ),
      );
      final fromHeader = parseTagFromRedirect(response.headers.value("location"));
      if (fromHeader != null) {
        return fromHeader;
      }
      // 某些网络环境会把 302 直接跟到底，用最终 URI 再试一次
      return parseTagFromRedirect(response.realUri.toString());
    } on DioException catch (e) {
      Log.logPrint("解析发布页跳转失败：${e.type}");
      return null;
    }
  }

  /// 从 .../releases/tag/v1.0.0 里解析 tag；解析不到返回 null
  static String? parseTagFromRedirect(String? url) {
    if (url == null || url.trim().isEmpty) {
      return null;
    }
    final uri = Uri.tryParse(url.trim());
    if (uri == null) {
      return null;
    }
    final segments = uri.pathSegments;
    for (var i = 0; i < segments.length - 1; i++) {
      if (segments[i] == "tag") {
        final tag = segments[i + 1].trim();
        return tag.isEmpty ? null : tag;
      }
    }
    return null;
  }

  /// 按约定名并发 HEAD，拿到真实大小；只有 200 且 content-length > 0 才算存在
  ///
  /// 三个候选并发探测：GitHub 的下载 CDN 单个请求可能要 10 秒左右，
  /// 串行会把兜底路径拖到 30 秒以上，电视上体验太差。
  Future<List<AppUpdateAsset>> _probeTvAssets(String tagName) async {
    final results = await Future.wait(
      _tvAssetSuffixes.map((suffix) => _probeTvAsset(tagName, suffix)),
    );
    return results.whereType<AppUpdateAsset>().toList();
  }

  Future<AppUpdateAsset?> _probeTvAsset(String tagName, String suffix) async {
    final name = "$tvAssetPrefix-$tagName-$suffix";
    final url = "$releaseDownloadBaseUrl/$tagName/$name";
    try {
      final response = await _downloadDio.head<dynamic>(
        url,
        options: Options(
          headers: _assetHeaders,
          followRedirects: true,
          validateStatus: (status) => status != null && status < 500,
        ),
      );
      if (response.statusCode != 200) {
        return null;
      }
      final size = int.tryParse(
            response.headers.value(Headers.contentLengthHeader) ?? "",
          ) ??
          0;
      if (size <= 0) {
        return null;
      }
      return AppUpdateAsset(name: name, size: size, downloadUrl: url);
    } on DioException catch (e) {
      Log.logPrint("探测资产失败 $name：${e.type}");
      return null;
    }
  }

  /// 下载 APK
  ///
  /// - [onProgress] received/total，total 可能为 -1（服务端未给长度）
  /// - 完成后返回本地文件路径与字节数
  Future<UpdateDownloadResult> downloadApk(
    AppUpdateAsset asset, {
    void Function(int received, int total)? onProgress,
    CancelToken? cancelToken,
  }) async {
    final fileName = _safeFileName(asset.name);
    final targetDir = await _prepareDownloadDir();
    final file = File("${targetDir.path}/$fileName");

    try {
      await _cleanDownloadDir(targetDir);
      final response = await _downloadDio.download(
        asset.downloadUrl,
        file.path,
        cancelToken: cancelToken,
        deleteOnError: true,
        options: Options(
          headers: _assetHeaders,
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

  /// 触发系统安装程序（Android 7.0+ 必须走 FileProvider 的 content:// URI）
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

  /// 语义化版本比较：返回 -1 / 0 / 1
  ///
  /// 支持 v1.2.3、1.2.3-beta.1、1.2.3+4，缺失的段按 0 处理。
  static int compareVersion(String a, String b) {
    return _SemVer.parse(a).compareTo(_SemVer.parse(b));
  }

  /// 远端版本是否高于本地版本（主版本相同后再比 tag 里的 +N）
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

  /// 从资产列表里挑电视端 APK
  ///
  /// 只认文件名里带 TV 的资产（手机端 LiveHub-vX.Y.Z-*.apk 会被排除），
  /// 优先级：arm64-v8a → universal → armeabi-v7a，同级取体积大的。
  static AppUpdateAsset? pickApkAsset(List<AppUpdateAsset> assets) {
    final candidates = assets.where((e) => e.isApk && isTvAsset(e.name)).toList();
    if (candidates.isEmpty) {
      return null;
    }
    candidates.sort((a, b) {
      final byAbi = _abiWeight(a.name) - _abiWeight(b.name);
      if (byAbi != 0) {
        return byAbi;
      }
      return b.size.compareTo(a.size);
    });
    return candidates.first;
  }

  /// 资产名是否是电视包（约定：文件名含 TV，例如 LiveHubTV-v1.0.0-arm64-v8a.apk）
  static bool isTvAsset(String name) {
    return name.toLowerCase().contains("tv");
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

  static String _joinLocalVersion(String version, String buildNumber) {
    if (version.contains("+")) {
      return version;
    }
    return "$version+$buildNumber";
  }

  /// 只保留安全字符，避免资产名里的空格/中文进入 FileProvider URI
  static String _safeFileName(String rawName) {
    final normalized = rawName.trim().replaceAll("\\", "/");
    final rawBase = normalized.substring(normalized.lastIndexOf("/") + 1);
    final base = rawBase.replaceAll(
      RegExp(r'[^0-9A-Za-z._+-]'),
      "_",
    );
    if (base.isEmpty || base == "_") {
      return "livehubtv-update.apk";
    }
    return base;
  }

  static Future<Directory> _prepareDownloadDir() async {
    final tempDir = await getTemporaryDirectory();
    final dir = Directory("${tempDir.path}/$downloadDirName");
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
  /// 原始 tag，例如 v1.0.0
  final String tagName;

  /// 去掉前缀 v 的版本号，用于展示与比较
  final String version;

  /// 更新说明（Markdown 原文；兜底路径下为空）
  final String notes;

  /// 发布时间
  final DateTime? publishedAt;

  /// 发布页地址
  final String pageUrl;

  /// 全部资产
  final List<AppUpdateAsset> assets;

  /// 选中的电视端 APK（null 表示没有可用安装包）
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

  /// v1.0.1 -> 1.0.1；1.0.1+2 原样返回
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
  /// 本地完整版本，例如 1.0.0+1
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

/// GitHub 匿名接口限流（403 / 429）：调用方据此切换到发布页兜底
class UpdateRateLimitedException extends UpdateException {
  const UpdateRateLimitedException(super.message);
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
      build = int.tryParse(text.substring(plusIndex + 1));
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
