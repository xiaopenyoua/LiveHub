# livehub

基于核心库实现的Flutter APP客户端。

## TODO

- [ ] 支持桌面平台
- [ ] iOS播放问题
- [ ] 全屏、非全屏弹幕样式分离
- [ ] 重写直播间 

## 在线更新

手机端从 GitHub Release 拉取最新 APK 并安装：

- 服务：`lib/services/update_service.dart`；状态与交互：`lib/app/controller/update_controller.dart`；入口：设置 → 其他设置 → 版本 → 检查更新。
- 检查：`GET https://api.github.com/repos/xiaopenyoua/LiveHub/releases/latest`（`Accept: application/vnd.github+json`），
  用 tag_name 与 package_info_plus 的 version+buildNumber 做语义化比较，只有远端更高才提示。
- 下载：存到 `getTemporaryDirectory()/apk_update/`，校验 HTTP 200、文件非空、大小与 content-length / 资产大小一致。
- 安装：Android 原生 `MainActivity` 通过 FileProvider（authority `${applicationId}.fileProvider`）+ `Intent.ACTION_VIEW` 调起系统安装器；
  Android 8.0+ 未授权时会引导到「安装未知应用」设置页。
- 失败降级：超时、无网、403（匿名接口限流）、404 都只给中文提示，不向 UI 抛异常。

发布时请按下面的文件名上传 APK（同一 release 里按 arm64-v8a → universal → armeabi-v7a 的顺序挑第一个）：

| 资产名 | 说明 |
| --- | --- |
| `LiveHub-vX.Y.Z-arm64-v8a.apk` | 首选，主流真机 |
| `LiveHub-vX.Y.Z-universal.apk` | 兜底，包含全部 ABI |
| `LiveHub-vX.Y.Z-armeabi-v7a.apk` | 旧设备 |

- tag 形如 `vX.Y.Z`；同版本需要重发时用 `vX.Y.Z+N`，同时把 pubspec 的 `version:` 改成 `X.Y.Z+N`。
- TV / 桌面端产物请另起文件名（含 `TV` / `desktop`），会被手机端的选择逻辑排除。
