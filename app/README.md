# app/ — Flutter 工程

LiveHub 的开发侧 monorepo，五个 Flutter / Dart 包 + 工程脚本。产品定位、支持平台与
上游归属见仓库根 `README.md`；目录归属判据见 `AGENTS.md`。

---

## 包与依赖

```
livehub ─────────┬── livehub_core
                 └── livehub_ui

livehub_tv ──────┬── livehub_core
                 └── livehub_ui

livehub_console ───── livehub_core
```

| 包 | 类型 | 职责 | 入口 |
| --- | --- | --- | --- |
| `livehub` | Flutter app | 手机 / 桌面客户端（Android · iOS · macOS · Windows · Linux） | `livehub/lib/main.dart` |
| `livehub_tv` | Flutter app | 大屏客户端（Android TV · Windows） | `livehub_tv/lib/main.dart` |
| `livehub_core` | Dart package | 四平台（哔哩哔哩 / 斗鱼 / 虎牙 / 抖音）取流、房间信息、弹幕 | `livehub_core/lib/livehub_core.dart` |
| `livehub_ui` | Flutter package | 共享设计系统：设计令牌、主题、玻璃材质、通用控件 | `livehub_ui/lib/livehub_ui.dart` |
| `livehub_console` | Dart CLI | 用核心库探测直播间信息、播放直链与弹幕 | `livehub_console/bin/livehub_console.dart` |

`livehub` 与 `livehub_tv` 的页面、路由、GetX 控制器、服务与 Hive 数据模型沿用上游
`dart_simple_live`；两端共用 `livehub_ui`，只在字号阶梯与间距单位上分档
（TV 使用 1920×1080 设计画布，手机/桌面使用逻辑像素）。

---

## 环境

- Flutter **3.38**（`livehub/.fvmrc` 与 `livehub_tv/.fvmrc` 锁定 `3.38.3`）
- Dart **>= 3.10**（`livehub_core`、`livehub_console` 的 pubspec 下限）

```bash
flutter --version
flutter devices
```

---

## 构建与运行

### livehub

```bash
cd app/livehub
flutter pub get
flutter run -d <device-id>
flutter build apk --release
flutter build ios --no-codesign
flutter build macos
flutter build windows
flutter build linux
```

### livehub_tv

```bash
cd app/livehub_tv
flutter pub get
flutter run -d <device-id>
flutter build apk --release
flutter build windows
```

### livehub_core

```bash
cd app/livehub_core
dart pub get
dart test
```

### livehub_ui

```bash
cd app/livehub_ui
flutter pub get
flutter analyze
```

### livehub_console

```bash
cd app/livehub_console
dart pub get
dart run bin/livehub_console.dart -i "[直播间地址]"
dart run bin/livehub_console.dart -d "[直播间地址]"
dart test
```

---

## 工程工具

```bash
bash app/tools/verify-layout.sh             # 目录契约自检
node app/tools/sync-icons.mjs               # 图标字节比对（默认 --check，零写入）
node app/tools/sync-icons.mjs --write       # 把交付位图同步进两个包的 mipmap 目录
node design/brand/build-icons.mjs --check   # 设计源 → 交付位图的光栅化情报
```

图标管线、覆盖范围与手工档（TV banner）的边界见 `design/brand/README.md`；
`sync-icons.mjs` 只覆盖两个包各 5 档 `ic_launcher.png`，不碰 iOS / macOS / Windows
的图标尺寸集与手机端自适应分层。

---

## 版本与锁文件

- 应用版本：各包 `pubspec.yaml` 的 `version:`（Android 侧由 Flutter 写入 `versionName`）。
- `pubspec.lock` 当前由各包 `.gitignore` 忽略，不提交；需要可复现构建时，在包内放行并提交。
- `.dart_tool/`、`build/`、平台 ephemeral 目录都是构建产物，不进版本库。

---

## 许可证

`app/LICENSE` 是 GPL-3.0 全文，`app/NOTICE` 记录上游归属与本仓库改动摘要。
本工程基于 [dart_simple_live](https://github.com/xiaoyaocz/dart_simple_live) 二次开发。
