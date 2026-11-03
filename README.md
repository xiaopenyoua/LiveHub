# LiveHub

> 四个平台，一台电视。像换台一样换主播。

聚合哔哩哔哩、斗鱼、虎牙、抖音四家直播的跨平台客户端。功能、页面、路由与交互流程沿用
[dart_simple_live](https://github.com/xiaoyaocz/dart_simple_live)（GPL-3.0）；
视觉语言换成本项目的设计系统：**炭灰底 + 中央暖金光晕 + 烟色磨砂玻璃 + Inter 字族**，
应用名与图标也换成本项目的品牌资产。界面风格在所有平台上保持一致。

---

## 支持平台

| 包 | 平台 |
| --- | --- |
| `app/livehub` | Android · iOS · macOS · Windows · Linux |
| `app/livehub_tv` | Android TV · Windows |

手机端的底部导航、播放页控制条与设置页，和 TV 端的方向键焦点、横向卡片行、换台条，
共用同一套设计令牌与通用控件（`app/livehub_ui`），只在字号阶梯与间距单位上分档。

---

## 东西在哪

三个内容目录，一份判据：

| 目录 | 是什么 | 从这进去 |
| --- | --- | --- |
| `app/` | 开发侧：Flutter 五包工程、平台资源、脚本 | `app/README.md` |
| `design/` | 设计侧：原型、规格、品牌矢量源、稿子素材 | `design/README.md` |
| `docs/` | 判断依据：为什么做这个产品、参考的 App 是怎么做的 | `docs/README.md` |
| `AGENTS.md` | **归属判据**：东西该放哪、能不能放、怎么校验 | `AGENTS.md` |

```
app/
  livehub/          手机 / 桌面客户端（Android · iOS · macOS · Windows · Linux）
  livehub_tv/       大屏客户端（Android TV · Windows）
  livehub_core/     四平台取流与弹幕核心库
  livehub_ui/       共享设计系统：tokens / theme / glass / components
  livehub_console/  命令行探测工具
  tools/            sync-icons.mjs（图标同步）、verify-layout.sh（布局校验）
  LICENSE           GPL-3.0 全文
  NOTICE            上游归属与本仓库改动摘要
design/
  v1.0.0/           六屏可交互原型、开屏、图标稿、spec/、shots/、screenshots/
  brand/            wordmark/{src/*.svg, export/*.png} + build-icons.mjs
  system/           跨版本设计规则（设计规范、tvOS 对照）
  media/            稿子的运行时素材（封面、字体）
docs/               市场调研、技术方案调研
AGENTS.md           归属判据
```

**图标管线只有一条，方向不能反**（详见 `AGENTS.md` §0）：

```
design/brand/wordmark/src/*.svg
  ──node design/brand/build-icons.mjs──▶ design/brand/wordmark/export/*.png
  ──node app/tools/sync-icons.mjs --write──▶ app/livehub{,_tv}/…/mipmap-*/ic_launcher.png
```

---

## 环境

- Flutter **3.38**（`app/livehub/.fvmrc` 与 `app/livehub_tv/.fvmrc` 锁定 `3.38.3`）
- Dart **>= 3.10**（`livehub_core` 的 pubspec 下限）
- Android 构建需要 Android SDK；iOS / macOS 需要 Xcode；Windows / Linux 需要对应桌面工具链

```bash
flutter --version        # 3.38.x
flutter devices          # 确认目标设备
```

---

## 构建

### 手机 / 桌面客户端

```bash
cd app/livehub
flutter pub get
flutter run -d <device-id>
flutter build apk --release          # Android
flutter build ios --no-codesign      # iOS（无签名构建）
flutter build macos                  # macOS
flutter build windows                # Windows
flutter build linux                  # Linux
```

### 大屏客户端（Android TV / Windows）

```bash
cd app/livehub_tv
flutter pub get
flutter run -d <device-id>
flutter build apk --release          # Android TV
flutter build windows                # Windows
```

### 命令行工具

```bash
cd app/livehub_console
dart pub get
dart run bin/livehub_console.dart -i "[直播间地址]"
dart run bin/livehub_console.dart -d "[直播间地址]"
```

`-i` 输出直播间信息与播放直链；`-d` 持续输出弹幕。入口文件是 `livehub_console.dart`。

### 其他包

```bash
cd app/livehub_core && dart pub get && dart test
cd app/livehub_ui   && flutter pub get
cd app/livehub_console && dart test
```

---

## 与设计稿的关系

**原型先行、工程对齐原型。** 任何"界面上该长什么样"的争议，回到设计稿看，不看代码：

| 想看 | 去哪 |
| --- | --- |
| 六屏可交互原型（1920×1080） | `design/v1.0.0/ui.html`（浏览器直接打开） |
| 开屏动画 | `design/v1.0.0/splash.html` |
| 应用图标、五档尺寸、自适应三层 | `design/v1.0.0/icon.html` |
| 色彩 / 字阶 / 焦点 / 栅格 / 键位 | `design/system/设计规范.md` |
| 品牌字标的矢量真源与交付位图 | `design/brand/wordmark/src/`、`design/brand/wordmark/export/` |
| 定稿截图与开屏定稿帧 | `design/v1.0.0/screenshots/`、`design/v1.0.0/shots/` |
| 某版规格与品牌裁决 | `design/v1.0.0/spec/` |

设计令牌的代码实现是 `app/livehub_ui/lib/src/tokens.dart`；主题、玻璃材质与通用控件分别在
`app/livehub_ui/lib/src/theme.dart`、`glass.dart`、`components.dart`。改视觉时先改设计规格，
再改令牌，最后改页面 —— 不要在页面里写死颜色或字号。

定稿截图（文件名就是屏名，不编号）：

| 屏 | 截图 |
| --- | --- |
| 首页 | `design/v1.0.0/screenshots/home.png` |
| 分类 | `design/v1.0.0/screenshots/browse.png` |
| 关注 | `design/v1.0.0/screenshots/follow.png` |
| 搜索 | `design/v1.0.0/screenshots/search.png` |
| 搜索 · 房间号直达 | `design/v1.0.0/screenshots/search-rid.png` |
| 播放 | `design/v1.0.0/screenshots/player.png` |
| 设置 · 面板焦点 | `design/v1.0.0/screenshots/settings.png` |
| 设置 · 分组条焦点 | `design/v1.0.0/screenshots/settings-nav.png` |
| 换台条 | `design/v1.0.0/screenshots/channel-osd.png` |
| 退出确认 | `design/v1.0.0/screenshots/exit-dialog.png` |

---

## 上游归属与许可证

本项目是 [dart_simple_live](https://github.com/xiaoyaocz/dart_simple_live) 的二次开发，
上游作者 **xiaoyaocz**，许可证 **GPL-3.0**。

- 功能、页面、路由、GetX 控制器与服务、Hive 数据模型、播放器逻辑沿用上游；
- 本仓库的改动集中在：产品名与包名（`simple_live_*` → `livehub_*`）、共享设计系统、
  品牌图标与平台显示名、文档与工程工具；
- 代码中保留的上游 URL（版本检查、虎牙配置、项目主页链接）属于必须保留的上游引用；
- `app/LICENSE` 是 GPL-3.0 全文，`app/NOTICE` 记录归属与改动摘要。重新分发时必须继续以
  GPL-3.0 提供对应源码。

---

## 自检

```bash
bash app/tools/verify-layout.sh       # 结构契约自检，退出码 0 才算干净
node app/tools/sync-icons.mjs         # 图标字节：export ↔ 10 档平台图标
node design/brand/build-icons.mjs --check   # 设计源 ↔ 交付位图（抗锯齿差异是情报）
```
