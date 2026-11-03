import 'package:livehub_tv/models/version_model.dart';

/// 通用的请求
class CommonRequest {
  /// LiveHub 不接入上游 Simple Live 的更新源。
  ///
  /// 上游 `tv_app_version.json` 指向的是 dart_simple_live 自己的安装包，
  /// 继续使用会让 LiveHub 提示下载 Simple Live，因此这里固定返回“无更新”。
  Future<VersionModel> checkUpdate() async {
    return VersionModel(
      version: "",
      versionNum: 0,
      versionDesc: "",
      downloadUrl: "",
    );
  }
}
