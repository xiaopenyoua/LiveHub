# LiveHub Android 架构

> 面向对象：写这个 App 的人（包括三个月后的你自己）
> 基线：`com.livehub.tv` / Kotlin 2.0.21 / Compose + tv-material 1.0.0 / Media3 1.4.1 / minSdk 21 / targetSdk 34 / compileSdk 34
> 协议事实来源：[`05-逆向分析报告.md`](05-逆向分析报告.md)（对 `com.mystyle.purelive` v1.7.4 的逆向 + 实测）
> 本文管工程结构与技术取舍，不管像素。视觉细节在 [`02-设计规范.md`](02-设计规范.md)。

---

## 〇、先看这一页

### 0.1 六条判断

| # | 判断 | 代价（我们放弃了什么） |
| --- | --- | --- |
| 1 | **minSdk 21，targetSdk 34**，不为 21 单独写分支 | `java.time`/Stream 只能靠 desugaring（已开，dex 变大），且 `core/model` 连 desugaring 都不依赖；不能假设 `RenderEffect`、通知渠道、新版 `MediaCodecInfo` 存在 |
| 2 | **单个 Gradle module（`:app`）+ 包边界 + 一个架构守卫单测** | 没有编译器强制的模块隔离；越界靠测试拦，越界成本比多 module 低，但守卫测试被删掉就全裸 |
| 3 | **不集成 FFmpeg / ijkplayer 软解** | 极端 HEVC 变体（10bit/HDR、非标 profile）不可播；换来 APK 少 8–15MB×ABI、少一套 C++ 生命周期 |
| 4 | **播放器只认 `PlayUrl`，不认识平台** | 每家的 Header/Cookie 规则上浮到 `core/site`，player 层失去"顺手修一下"的能力——这是故意的 |
| 5 | **抖音 v1 全免签：详情与取流走 `reflow/info`，不写任何签名器；列表/搜索才要签名，那是 v2** | ①只覆盖"已知房间（19 位 room_id 或 `web_rid`，后者靠页面 HTML 配对换算）"，搜索与推荐都没有；**① 一旦被关，v1 没有兜底**（WebView 方案已降为应急、不写代码），只能发版或等 v2 |
| 6 | **`core/*` 零 `android.*` 依赖** | 一些顺手的地方（`Context`、`Log`、`Color`）要多写一层接口；换来 tvOS 版不用重做协议层 |

### 0.2 一句总结

这个 App 的技术风险**不在 UI，不在播放器，在四家没有契约的逆向接口**。所以架构的第一目标是：**接口坏了的时候，改动范围能收敛到一个文件里，且不用发版就能证实"是哪一家坏了"**。下面所有分层都是为这件事服务的。

**"现在到哪一步了"不看这六条，看 §6.4「能力落地进度」**——那一节每行都带一条现在就能跑的复查命令，跑一遍 30 秒。本页六条是**决定**，不是进度。

---

## 一、Android TV 版本矩阵

### 1.1 版本 × API level

| Android 版本 | API | 有 TV 版本 | 消费级 TV 端实况 | 我们支持 | 说明 |
| --- | --- | --- | --- | --- | --- |
| 5.0 Lollipop | 21 | ✅（Android TV 首发） | 极少（2014–2015 早期盒子，多为 5.1） | ✅ 最低面 | Compose for TV 与 Media3 的库下限 |
| 5.1 Lollipop | 22 | ✅ | 少但有：2015–2016 的 1GB 盒子 | ✅ | 与 21 合并同一套策略，不做区分 |
| 6.0 Marshmallow | 23 | ✅ | 少 | ✅ | 运行时权限从这一档开始真实生效 |
| 7.0 Nougat | 24 | ✅ | 中 | ✅ | |
| 7.1 Nougat | 25 | ✅ | 中（Fire TV 生态主力之一） | ✅ | |
| 8.0 Oreo | 26 | ✅ | 中 | ✅ | 通知渠道起点（我们不用） |
| 8.1 Oreo | 27 | ✅ | 少 | ✅ | |
| 9 Pie | 28 | ✅ | **大**（2018–2020 国产盒子主力） | ✅ | 主力档之一 |
| 10 | 29 | ✅ | 中 | ✅ | 分区存储起点（我们只写 cacheDir，无感） |
| 11 | 30 | ⚠️ 有，消费级几乎跳过 | 极少 | ✅ | |
| 12 | 31 | ✅（2022） | 中（2021–2022 Google TV 电视） | ✅ | `RenderEffect`（真模糊）起点；2022 年 Google TV 机型的基座 |
| 12L | 32 | — | 无 TV 设备 | ✅ | 只影响平板，TV 侧无差异 |
| 13 | 33 | ✅（2022-12） | **极少**（厂商跳过） | ✅ | 只对开发者有意义 |
| 14 | 34 | ✅（2024-05） | **大**（Google TV Streamer 首发基座，2025 起批量 OTA） | ✅ 目标档 | 我们的 `targetSdk` |
| 15 | 35 | ❌ **被跳过** | 无 | — | TV 改为两年一版 |
| 16 | 36 | ✅（2025-05 随 Android 16 发布，TV AVD 有镜像） | **基本没有**（截至 2026-10，Google TV 端推送未发生） | ⏳ 只做兼容性验证 | 见 §1.4 |

**关于"占比"的诚实说明**：四家平台的 TV 端都没有公开按 OS 版本的装机分布，本文的大/中/小是**按出厂年份和厂商 OTA 记录推的量级，不是抽样统计**。第一个版本就带版本埋点（§6.3），上线一个月后用真实数据校准这张表——现在不要拿它当精确依据。

### 1.2 为什么 minSdk = 21

三个硬事实把它压到 21，而不是我们主动想支持 2014 年的设备：

| 事实 | 出处 | 结论 |
| --- | --- | --- |
| Compose for TV 官方要求 Android 5.0 / API 21 以上 | Android Developers《Use Jetpack Compose on Android TV》兼容性一节 | 用 Compose 就下不去 21 |
| Media3（ExoPlayer）minSdk = 21 | Media3 release notes："Increase minSdk to 21 (Android Lollipop)，aligned with all other AndroidX libraries" | 播放内核下不去 21 |
| `androidx.tv:tv-material:1.0.0`（2024-08 首个稳定版）随 Compose 走 | 同上 | UI 组件库下不去 21 |

**为什么不再往上抬到 23**（运行时权限更省事）：因为抬到 23 会把 2015–2016 那批 1GB 盒子整体排除，而它们的使用者恰好是"客厅老人 + 只看一个主播"的低要求人群，也正是这个产品最不需要性能的地方。抬档省下的工作量（两三处权限判断）不值这个覆盖面。

**21 上的三个真实代价，必须提前付**：

1. **TLS**：API 21 的系统 TLS 栈只到 TLS 1.2（TLS 1.3 要 API 29），且老固件的证书链与 SNI 实现有已知缺陷，系统 `HttpURLConnection` 在这些设备上会偶发握手失败。**我们不踩这个坑，因为全局只有一个 HTTP 栈：OkHttp 自带 TLS/Conscrypt，与系统版本解耦。** 这条是 §2.2 里"`core/net` 必须唯一"的直接理由。
2. **API 差值**：`java.time`、`java.util.stream`、`Objects.requireNonNull` 一类要靠 core library desugaring（已开，`desugar_jdk_libs:2.0.4`），代价是 dex 增大。**但 `core/model` 一律用 `Long` epoch 秒存时间，不用 `java.time`**——为了 tvOS（§5）。
3. **Compose 在 1GB 机上的抖动**：不是崩溃，是掉帧。对策在 §1.5 的档位策略里，不在这里做技术降级。

### 1.3 为什么 targetSdk = 34（而不是 36）

| 依据 | 内容 |
| --- | --- |
| Google Play 目标 API 政策（2026-08-31 生效） | 新应用/更新必须以 **Android 16（API 36）**为目标；**但 Android TV 应用例外，只需 Android 14（API 34）以上** |
| 同政策的存量条款 | 已在架 TV 应用目标 33 以上即可保持可发现 |
| 现状 | 截至 2026-10，消费级 Google TV 设备实际运行以 **API 31 / 34** 为主，API 36 的 TV 推送尚未发生 |

所以 targetSdk 34 **不是"我们选的"，是政策允许的最低合规档**。选它的收益：不必面对 35/36 的行为变更（强制 edge-to-edge、返回手势/预测性返回、前台服务类型收紧、`onBackPressed` 失效），这些对我们的收益接近零，风险全在低端机。

**升级触发条件（两条，任一成立就升 36）**：
1. Play 政策把 TV 应用的下限抬到 35/36（按两年节奏，预计 2027-08 前后评估）；
2. 自有埋点显示 API 36 设备占月活 > 5%。

**现在就要写对的两处**（避免将来升级时返工）：
- 返回键处理走 `OnBackPressedDispatcher` / Compose `BackHandler`，**不覆写 `onBackPressed()`**（36 上 Fire OS/Android 16 明确会失效）。
- 所有 `startActivity` 的 `PendingIntent` 显式声明可变性（31 起强制）；我们目前没有 PendingIntent、没有前台服务、没有通知——**这是刻意的简化，记在 ADR-006**。

### 1.4 需要运行时判断的 API

不要用 `Build.VERSION.SDK_INT >= x` 一把梭，能力要用能力查询。下表是**必须判断**的点：

| 能力 | 判断方式 | 缺失/不支持时的行为 |
| --- | --- | --- |
| HEVC 硬解 | `MediaCodecList(REGULAR_CODECS)` 找 `video/hevc` 的 decoder（API 21 起可用） | 请求 H.264 线路；该平台若无 H.264 档，卡片标"设备不支持"而不是让用户点进去黑屏 |
| AV1 解码 | 同上查 `video/av01` | 一律回落 H.264/HLS（不要假设软解存在） |
| HLS | **不需要判断**：Media3 自带 HLS 解析，不依赖系统 MediaPlayer | — |
| FLV | **不需要判断**：Media3 有 FlvExtractor（官方标注 *supported, not seekable*） | 直播本来不需要 seek；若用户按快进，直接忽略按键而不是报错 |
| 真模糊（玻璃材质） | `Build.VERSION.SDK_INT >= 31`（`RenderEffect`） | 用"半透明白底 + 三层 inset 顶沿高光"近似，**这是设计材质里权重最高的两层，模糊只是第三层**（见 §1.5 档位策略） |
| 系统 WebView | `WebView.getCurrentWebViewPackage()`（API 26+） | **主路径完全不依赖 WebView**（§4，抖音取流全免签）；只有诊断页用它显示版本，缺 WebView 时写"本机无 WebView 组件" |
| 32/64 位 | `Build.SUPPORTED_ABIS` | 双 ABI 打包（`armeabi-v7a` + `arm64-v8a`），x86_64 只进 debug |
| 网络类型 | `ConnectivityManager.getNetworkCapabilities`（21+） | 移动网络/弱网提示"当前网络较差，已降到 720P" |
| 低内存 | `ActivityManager.MemoryInfo.totalMem`（16+）/ `Runtime.maxMemory()` / `isLowRamDevice()` | 前两条定 A 档（`totalMemMb ≤ 1200` 或 `heapLimitMb ≤ 192`）；`isLowRamDevice()` **只用来看，不参与判定**（厂商常不设 `ro.config.low_ram`，1GB 机也报 false，见 §1.5） |
| 遥控器结构 | `PackageManager.hasSystemFeature(FEATURE_LEANBACK)` | 只有 `LEANBACK_LAUNCHER` 时隐藏"手机调试入口"相关提示 |

**不需要判断的（因为不打算用）**：通知渠道（26）、前台服务（29/34）、画中画（26，TV 上 14 起才支持且需硬件）、`MediaSession`、`TvProvider`（首页频道，需要 Play 服务与上架）、`Modifier.blur` 之外的所有 RenderEffect 效果。

### 1.5 目标设备档位表

四档，判断依据是 **API + 内存 + 解码能力**，不是品牌、不是机型名单。档位在**首帧渲染前**确定一次（`DeviceProfile.init`），全 App 只读、不落盘。

| 档 | 典型设备 | OS / API | RAM | 档位判据（`DeviceProfile` 里就这几条） |
| --- | --- | --- | --- | --- |
| **A 老盒子** | 2015 运营商盒子、小米盒子3、天猫魔盒 | 5.1 / **22** | 1GB | `sdkInt < 26` **或** `totalMemMb ≤ 1200` **或** `heapLimitMb ≤ 192` |
| **B 主流** | 2018–2020 国产盒子、老款 Sony/小米电视 | 9 / **28** | 2GB | `26 ≤ SDK < 31` |
| **C Google TV** | 2021–2023 电视、Chromecast with Google TV | 12 / **31** | 2GB | `31 ≤ SDK < 34` |
| **D 新机** | 2024+ 电视、Google TV Streamer | 14 / **34** | ≥2GB | `SDK ≥ 34` |

| 策略 | A 老盒子 | B 主流 | C Google TV | D 新机 | 谁读它 |
| --- | --- | --- | --- | --- | --- |
| **焦点动效时长** | 120ms（去 1.08 缩放，只留明度+高光） | 200ms | 200ms | 200ms | `ui` 读 `isLowEnd` |
| **玻璃真模糊** | 关（API<31 本来也没有） | 关（同） | 开（24dp blur） | 开 | `ui` 读 `sdkInt ≥ 31` |
| **封面解码宽** | ≤ 480px | ≤ 640px | ≤ 960px | ≤ 1280px | `ui` |
| **图片缓存** | 不分档：`crossfade(false)` + 内存 15% + 磁盘 64MB（理由见下） | 同 | 同 | 同 | `ui`（`ImageLoaderFactory` 全局一份） |
| **预取** | 关（只加载可视区 ±0 行） | 可视区 ±1 行 | 可视区 ±2 行 | 可视区 ±2 行 | `ui` |
| **弹幕同屏上限** | **8** 条（`defaultDanmakuMax`） | 12 条 | 12 条 | 12 条 | `player` 的 `DanmakuLayer` |
| **弹幕礼物提示** | 关 | 开 | 开 | 开 | `player` |
| **网格列数** | 4（同设计稿，只降解码尺寸不降列数） | 4 | 4 | 4 | `ui` |
| **解码策略** | H.264 必选；`hevcDecodable == true` 才用 HEVC | 同 A，优先 HEVC | 优先 HEVC，`av1Decodable` 才用 AV1 | HEVC/AV1 | `player` 读 `hevcDecodable`/`av1Decodable` |
| **默认清晰度** | 720P | 1080P | 1080P | 1080P（原画） | `ui` |
| **首帧预算** | 8s | 6s | 5s | 5s | `player` |
| **布局复杂度档** | `--fs`/间距不变（设计稿是 4 列），只关效果 | 全开 | 全开 | 全开 | `ui` |

> **A 档判据为什么是这三条（每条各管一件事，不是三条冗余）**：
>
> | 判据 | 回答的问题 | 为什么是它 |
> | --- | --- | --- |
> | `sdkInt < 26` | 这套渲染管线还存不存在 | Android 7 及以下，Compose 掉帧明显，不必等探测结果 |
> | `totalMemMb ≤ 1200` | 能不能同时装下这些帧 | 覆盖 512MB 与 1GB 两种盒子。**阈值是 1200 不是 1024**：1GB 机型 `totalMem` 实测约 950–1000MB，写 `<= 1024` 是"看起来对"的阈值，落到真机上会有一批 1GB 盒子恰好卡在线上 |
> | `heapLimitMb ≤ 192` | 系统实际打算给我们多少 | Android 按物理内存分 heap（512MB 机 128MB、1GB 机 192MB）。少数盒子的 `totalMem` 上报值被厂商改过，而"按物理内存给 heap"是系统行为，更可信 |
>
> `isLowRamDevice()` 不在判据里：国产盒子常因厂商没设 `ro.config.low_ram` 而返回 false，1GB 的机器照样报"不低内存"，只能用来看，不能用来判。
>
> **`totalMemMb` 与 `heapLimitMb` 是不同量纲，永远不能互相替换。** 这里踩过一次：`isLowEnd` 曾写成 `totalMemMb < 192`，把 MB 当成了"GB 的百分之一"，在任何机型上都不成立。教训写在 ADR-015（§8.1）——**一个永远为 `false` 的降级开关比没有这个开关更糟，因为它看起来在工作**。

**图片缓存不分档，是统一值**：`crossfade(false)`（焦点切换时几十张封面同时淡入，1GB 盒子肉眼掉帧）+ 内存 15% + 磁盘 64MB（电视盒子存储常常只剩几百 MB，别把缓存当无底洞）。这张表原来写过"8/16/32MB 分档"，那是设计期估算，落地时判断不成立——**分档省下的几十兆，换不来一套需要单独验证的配置矩阵**。

> **这张表现在只有一部分在代码里，别把它当已实现清单。** `DeviceProfile`（`core/device`）提供的是 `isLowEnd` / `isTv` / `sdkInt` / `totalMemMb` / `heapLimitMb` / `hevcDecodable` / `av1Decodable` / `defaultDanmakuMax`——**没有 `tier` 枚举，也没有 `when(tier)`**。A/B/C/D 只是这份文档讨论用的叫法，代码里的事实只有两个：`sdkInt` 与 `isLowEnd`。表里的"封面解码宽 / 预取行数 / 首帧预算"目前还没有消费方（`player` 未接完），写在表里是**给后面接的人一个目标值**，不是既有行为。谁要引用这张表做验收，先看这一行。

**为什么列数不降**：设计已经论证过 400px 是"能认出是谁"的下限（`02-设计规范.md` §8）。降列会让 A 档用户看到 3 列超大卡——信息密度反而下降，且和 B 档截图对不上，QA 无法用一套断言。**降级只降"每帧的计算量"，不降"信息结构"**。

**RAM 预算目标（A 档 1GB）**：常驻 ≤ 180MB（Compose + Media3 + OkHttp + 图片缓存），播放峰值 ≤ 280MB，冷启动 ≤ 3.5s 出首屏。这三个数字进 §6.2 的达标线。

### 1.6 明确"不做"的版本与理由

| 不做 | 理由 |
| --- | --- |
| **API < 21（Android 4.4 及更早）** | Compose for TV 与 Media3 的库下限，跨过去要放弃两者的全部收益，回到 2013 年的 View 体系。4.x 的 TV 盒子剩下的量不足以付这份代价 |
| **Android TV 15（事实上不存在）** | 平台跳过，我们不需要为不存在的版本做适配 |
| **Android 16（API 36）作为 targetSdk** | 见 §1.3，政策只要求 34；升 36 是 2027 年的任务。但**会把 36 列进真机 / TV AVD 兼容性验证矩阵**——不改 `targetSdk` 也要保证在 36 上不崩 |
| **Fire TV（Fire OS）作为一等公民** | Fire OS 5/6/7 分别是 Android 5.1/7.1/9，能装能跑；但它的商店、遥控器、语音键都不同，且 2026 起 Amazon 转向 VegaOS。策略：**能装、能跑、不单独适配**，Fire TV 用户是侧载用户，与我们的分发渠道一致 |
| **手机/平板 UI** | `LAUNCHER` 入口只为"装到手机上做调试"保留，不做触摸交互。两套交互的成本会吃掉整个项目 |
| **国内 OTT 商店上架** | 见 §6 风险 5：无牌照，不进任何应用商店 |

---

## 二、分层与模块边界

### 2.1 依赖方向

```
   ui  (六屏 + ViewModel + theme/Tokens)
    │
    ├─▶ player ─────────┐
    ├─▶ core/site ──────┼─▶ core/net (OkHttp/Cookie/UA)
    ├─▶ core/danmaku ───┘         │
    └─▶ core/store ───────────────┴─▶ core/model
                                      Room / PlayUrl / Platform / SiteError
                                      ↑ 谁都能依赖它，它不依赖任何人
```

规则只有三条：

1. **`core/model` 在最底，且不 import 任何 `android.*`**（唯一的例外是零：连 `android.graphics.Color` 都不许，颜色用 `Long` ARGB）。
2. **横向不许有依赖**：`core/site` 不认识 `player`，`player` 不认识 `Platform` 之外的东西，`core/danmaku` 不认识 `core/site`。
3. **`core/store` 是唯一摸 `Context` 的 core 包**（`SharedPreferences` 需要它）。`core/net` 不需要 `Context`，它只持有一个 `OkHttpClient`。

### 2.2 模块职责与依赖表

| 模块 | 装什么 | 允许依赖 | 禁止依赖 | 切这一刀的理由 |
| --- | --- | --- | --- | --- |
| `core/model` | `Platform`、`Room`、`PlayUrl`、`RoomDetail`、`LiveCategory`、`StreamFormat`，以及错误枚举 `SiteError` | 只有 Kotlin 标准库 | `android.*`、`org.json`、OkHttp、Compose、协程以外的一切 | 这批类型是**唯一要跨到 tvOS 的东西**。它干净，协议逻辑才有可能复用（§5） |
| `core/net` | 单例 `Net`：三个 OkHttpClient（普通/流/长连）、CookieJar、UA 常量、`get/getJson/postForm` | `core/model` 不需要；OkHttp、协程 | 任何 `Platform` 分支、任何平台域名 | net 只做传输。**它是唯一能保证 API 21 上 TLS 正常的地方**（§1.2），所以必须唯一 |
| `core/site` | `LiveSite` 接口 + 四家实现 + `Sites` 聚合 + `Endpoints`（域名/路径常量）+ `Signer`（签名可替换点） | `core/model`、`core/net` | `player`、`ui`、`core/danmaku` | 平台协议只在这里出现。接口改版 → 只改这里，**这是全项目最高频的改动点，所以它的半径必须最小** |
| `core/danmaku` | `DanmakuClient` 接口 + `DanmakuEvent` + 四家协议客户端（WebSocket/Tars/protobuf） | `core/model`、`core/net`、协程 | `player`、`ui`、`core/site` | 弹幕是**独立失效面**：取流挂了弹幕还能连，反之亦然。两者耦合会让故障定位成本翻倍 |
| `core/store` | `Prefs`（SharedPreferences + JSON）：关注、历史、设置、上次选的清晰度 | `core/model` | `ui`、`player`、`core/site` | 唯一做持久化的 core 包，边界清晰。**档位不在这里**——档位是每次冷启探测的事实，落盘只会让换机器/刷机的用户读到错的值 |
| `core/device` | `DeviceProfile`（**已落地**）：`isLowEnd` / `isTv` / `sdkInt` / `totalMemMb` / `heapLimitMb` / `hevcDecodable` / `av1Decodable` / `defaultDanmakuMax` / `init(context)` / `summary()` | `android.*`（`ActivityManager`、`MediaCodecList`、`Build`） | `core/site`、`core/danmaku`、`ui` | 它是**唯一被 ui 与 player 共同依赖的 android 感知层**。不进 `core/model`（必须零 android）、不进 `ui`（player 也要读）、不进 `core/store`（不落盘）。**判据是能力与资源（SDK + 内存 + 解码器探测），不是机型名单**——`Build.MODEL` 永远不全，盒子厂商还爱乱填 |
| `player` | `LivePlayer`（接口 + Media3 实现）、`PlayerSurface`（Compose）、`DanmakuLayer`（Canvas 绘制） | `core/model`、`core/device` | `core/site`、`core/store`、`ui` | 播放器只接受 `PlayUrl(url, headers)` + `DecoderMode`。**它不该知道"虎牙"存在**。解码探测归 `core/device`，播放器只读结论 |
| `ui` | `MainActivity` + 六屏 Composable + 每屏 ViewModel + `theme/Tokens` | 全部（它是叶子） | — | 唯一允许"什么都知道"的地方，因为它是装配点 |

### 2.3 为什么不是多 Gradle module

四家平台、六个包、一个人（或两个人）写、没有下游消费者——把 `core:*` 拆成独立 module 的收益只有一个：**编译器强制依赖方向**。成本是：每次改依赖要动 `settings.gradle.kts`、配置阶段变长、CI 缓存变复杂、`api`/`implementation` 要选对。

**取舍**：包级边界 + 一道 30 行的架构守卫测试（`app/src/test/java/.../ArchGuardTest.kt`，纯 JUnit，扫源码文件里的 `import android.` / `import androidx.compose.` 是否出现在 `core/model|core/site|core/danmaku|core/store` 下）。编译期拦不住，但 CI 拦得住，且不需要额外依赖（不引 Konsist/ArchUnit）。

守卫测试要放行三处**明知会 `import android.`** 的地方，放行必须进白名单、不能靠"整个包跳过"：

| 白名单 | 允许 import | 为什么不算破例 |
| --- | --- | --- |
| `core/store/Prefs.kt` | `android.content.*` | SharedPreferences 本身就带 `Context` |
| `core/device/**` | `android.*` | 档位与解码探测的定义就是读系统能力（§2.2）。反过来它不许被 `core/site` 依赖 |

**白名单里现在只有这两条。** v2 若真做出 WebView 版签名器，再加 `core/site/**/sign/WebViewSigner.kt`（`android.webkit.*`、`android.os.*`）一条——**v1 里出现这个文件，说明有人提前写了应急方案**（§4），review 要拦下来问清是不是 ① 已经坏了。

**这道测试的两种失败要分清**：白名单里多出一个文件 = 有人在绕边界，review 时问"为什么不能换个位置"；白名单**外**冒出 `import android.` = 分层已经漏了，不是测试写错。

> 这条决策有一个明确的失效条件：**当包边界开始被"就这一次"的 import 突破，且守卫测试被注释掉时，立刻拆 module**。写在这里，是为了将来有人想关掉那道测试时能看到这句。

### 2.4 骨架的三处修正（已完成，记录在案）

骨架在写 UI 之前已经过一轮修正。留档在这里，避免后来的人又"顺手优化"回去：

| 位置 | 原问题 | 改法 | 状态 |
| --- | --- | --- | --- |
| `Sites.homeFeed` / `searchAll` | `runCatching { }` 包住 `withTimeoutOrNull`，**把 `CancellationException` 一起吞了**：切屏/取消后协程继续跑，弱网下同时挂着 8 个请求 | 抽出 `fetchOrEmpty`：只吞普通 `Throwable`，`CancellationException` 原样重抛 | ✅ 已改。**遗留**：失败归属（"哪一家挂了"）仍未上抛到 UI，目前只能整体失败。要满足 `02-设计规范.md` §10 的"状态诚实"，后续应换成 `FeedResult(rooms, failures: Map<Platform, SiteError>)` |
| `Prefs.follows()` / `history()` | 每次调用都 `JSONArray` 全量解析；关注页 8 张卡 × 每次重组都走一遍 → 1GB 机上直接掉帧 | 加内存缓存，写路径同步更新缓存，读只读缓存 | ✅ 已改 |
| `AndroidManifest` `largeHeap="true"` | 1GB 盒子上拿不到多少额外堆，却放宽了 OOM 前的压力信号，让"缓慢泄漏"变成"被系统直接杀" | 删除，改用显式内存预算（§1.5 的图片缓存 + 弹幕对象池） | ✅ 已改 |

**保留的一处"不安全"配置，以及它的说辞**：`usesCleartextTraffic="true"` 继续保留——斗鱼 `hdl` 有 `http://` 流、部分 CDN 的 FLV 也没全上 TLS，禁了等于砍掉可用线路。代价是中间人可篡改"首帧地址"（最多让你看不了），因为**没有任何账号凭证走明文**（Cookie 只落在 TLS 域名上）。这条必须写进"关于"页，文案定为：**「部分直播源为 HTTP 明文传输」**——用户有权知道。

### 2.5 三个关键接口（以现有代码为准，补新增部分）

```kotlin
// core/site —— 已存在，补上错误语义
interface LiveSite {
    val platform: Platform
    suspend fun recommend(page: Int = 1): List<Room>
    suspend fun categories(): List<LiveCategory>
    suspend fun categoryRooms(categoryId: String, page: Int = 1): List<Room>
    suspend fun search(keyword: String, page: Int = 1): List<Room>
    suspend fun detail(roomId: String): RoomDetail?
    /** 按「我们希望的播放顺序」返回，player 从上往下试。失败抛 SiteException(kind, retryable) */
    suspend fun playUrls(roomId: String, preferQuality: String? = null): List<PlayUrl>
    /**
     * 本平台在**这个版本**真实具备的能力。
     * 是 `val` 不是 `suspend fun`：它是编译期事实，UI 要在 composition 里同步读，套一层协程只是多一次挂起。
     */
    val capabilities: SiteCapabilities get() = SiteCapabilities()
}

data class SiteCapabilities(
    val hls: Boolean = true,              // 能否给 HLS 线路（tvOS 的硬门槛）
    val flv: Boolean = true,
    val recommend: Boolean = true,        // 抖音首版 false：推荐流要签名
    val categories: Boolean = true,       // 抖音首版 false：分区列表也要签名
    val search: Boolean = true,           // 抖音首版 false：搜索要签名
    val danmaku: Boolean = true,          // 抖音首版 false：握手要签名，v1 没有签名器
    val idInput: IdInput = IdInput.RoomId,
    val maxQualityLabel: String? = null
)

/**
 * 输入框能接受什么。UI 据此决定占位文案；**解析归 core/site，不归 UI**——
 * "19 位 / web_rid / 半条 URL" 都是协议细节，UI 里再写一遍正则就会与平台实现漂移。
 */
enum class IdInput {
    RoomId,                  // 哔哩 / 斗鱼 / 虎牙：平台原生房间号
    RoomIdOrWebRidOrLink,    // 抖音：19 位 room_id、web_rid（直播页地址里的号）、直播页/分享链接
}

// core/danmaku —— 已存在，语义不变
interface DanmakuClient {
    val platform: Platform
    /** 冷流：collect 建连，取消断开。内部自带心跳与重连，上层不管 */
    fun stream(roomId: String): Flow<DanmakuEvent>
}

// player —— 新增，全项目最重要的抽象边界
interface LivePlayer {
    val state: StateFlow<PlaybackState>   // Idle/Buffering/Playing/Degraded(line, quality)/Failed(SiteError)
    val videoSize: StateFlow<IntSize>     // 弹幕层要按真实画幅决定字幕区，不能假定 16:9
    suspend fun play(lines: List<PlayUrl>, startAtLine: Int = 0)
    fun setDecoderMode(mode: DecoderMode)
    fun release()
}
```

**`capabilities()` 为什么带默认值**：四家里三家确实全都支持，默认 `SiteCapabilities()`（全 true）让它们一行都不用写，只有抖音显式覆盖成 `recommend=false / categories=false / search=false / danmaku=false`。代价是"新平台忘了声明"会静默变成"什么都支持"，所以配一条约束：**任何返回空列表的 `LiveSite` 实现，必须在 `capabilities` 里把对应位声明为 false**——这一条进 `ArchGuardTest`（§2.3），用反射或源码扫描都能查。空列表 + `capabilities=true` 是欺骗，空列表 + `capabilities=false` 是诚实，`02-设计规范.md` 的"状态诚实"要求的就是后者。

**`idInput` 存在的原因**：UI 只需要知道"这一家的输入框该写什么占位、要不要提示用户粘贴链接"，**不需要知道怎么解析**。抖音的输入归一化（19 位 / web_rid / 半条 URL → 19 位 room_id）全部在 `DouyinSite` 内部完成，见 §4.1。

`LivePlayer.play(lines)` 的语义是契约的一部分：**按顺序试、单条首帧超预算就换下一条、全部失败才进 Failed**。这条逻辑放在 `player` 里而不是 `ui` 里，因为换线路时不该重建 Compose 树。

**弹幕为什么不用一条一个 Composable**：同屏 12 条、每条每秒横移数十像素，若每条是一个 `Text`，每秒几十次重组与布局，A 档机上肉眼可见抖动。`DanmakuLayer` 用**单个 `Canvas` + `TextMeasurer` 预测量**，逐帧只在 `DrawScope` 里画，播放期间组合树保持不变。代价是不能对单条弹幕加点击与富文本——我们本来也不做弹幕发送与互动（`01-市场调研.md` 已把"弹幕输入法"列为不做）。

---

## 三、取流层的稳定性设计

四家接口都无公开契约。设计目标不是"让它不坏"，是"**坏了之后 30 分钟内知道是哪一家、哪一段坏了，并且能远端修的就远端修**"。

### 3.1 线路回落顺序

`playUrls()` 返回的是**已经排好序的候选列表**，player 从 0 开始试。排序原则：先协议（**默认 HLS 优先，例外见下面硬顺序第 1 条**），再码率，最后 CDN。下表里的"实测"= `05-逆向分析报告.md` 的静态证据 + `06-接口实测记录.md` 的接口复验 + §4 记录的真拉流结果（2026-10-29）。**三者冲突时，以最新的真拉流结果为准**——静态证据说明"它怎么写的"，真拉流说明"它现在还活着没"。

| 平台 | 取流链路 | 回落顺序 | 已知坑 |
| --- | --- | --- | --- |
| 哔哩哔哩 | `Room/room_init` 转真实房号 → `/xlive/web-room/v2/index/getRoomPlayInfo`（`protocol=0` flv / `1` ts / `2` fmp4，`qn` 清晰度码） | fmp4/ts（HLS）原画 → HLS 次档 → FLV 原画 → FLV 次档 | 实测未登录 `current_qn=250`、`accept_qn=[10000,400,250]`：**原画要 `SESSDATA`**。清晰度胶囊必须显示服务端返回的真实值，不能撒谎 |
| 斗鱼 | `hlsH5Preview/{rid}`（`auth = md5(rid + 毫秒时间戳)`）→ 返回 `rtmp_url` + `rtmp_live`（含 `txSecret`） | HLS（唯一免签线路）→ 失败才考虑 `getH5Play`（要跑混淆 JS 算 sign，首版不做） | 只有一路可用；`auth` 有时效，签名要现算现拼；`getH5Play` 是**后备手段，不是默认** |
| 虎牙 | `mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=&showSecret=1` → `sFlvUrl` + `sFlvAntiCode`，另带多 CDN 的 HLS | **HLS 仅 HS 系**（`hs.hls`/`HS24.hls`）→ FLV（AL / TX / HS 三线都可） | ① **`wsSecret` 已由接口算好**，客户端只拼字符串，不需要 JS（网上"自己 md5 算 anti-code"的实现是老接口的）。② **HLS 只有 HS 系出真流**：AL / TX / AL13 / TX15 的 `.m3u8` 恒 403（跨 4 个房间复验），所以按 `sCdnType.startsWith("HS")` 过滤，否则每次播放先撞一次 403 再回落。③ `profileRoom` 会**间歇性返回 nginx 404**（同一 URL 隔几秒再拉就 200，桌面/移动 UA 都会遇到）→ 非 2xx 重试一次即可，**这不是风控，别写成"虎牙开始封号"** |
| 抖音 | 路线① `webcast.amemv.com/webcast/room/reflow/info/?app_id=1128&room_id={19位}` → `stream_url` 内 HLS / FLV | **FLV 优先**（douyincdn 稳）→ HLS（留在列表尾部，404 先原样重试一次）→ 降档 | ① 需要 **19 位内部 room_id**；用户给的 `web_rid` 靠直播间页 HTML 里 `roomId` 与 `web_rid` **严格配对**换算（免签，见 §4.1），抖音号不行。② **HLS 的 `.m3u8` 在部分边缘节点间歇 404**：同一 URL 连拉 3 次可能前两次 404、第三次 206（http/1.1 与 h2 都复现），FLV 稳定 → 这就是抖音破例 FLV 优先的原因（§3.2 有单独的重试规则）。③ 列表/搜索要 `a_bogus`，v1 不做 |

> **证据等级**：上表结合 [`05-逆向分析报告.md`](05-逆向分析报告.md) 的 AOT 字符串池与本机实测，以及 [`06-接口实测记录.md`](06-接口实测记录.md) 的真拉流复验（2026-10-29）。`Endpoints.kt` 每条也标注验证日期，两者要能对上（C3）。

**硬顺序（违反即 bug）**：
1. **HLS 优先于 FLV——带一条写死的例外。** 理由：HLS 是 tvOS 唯一能播的格式（§5.3），如果 Android 端习惯性选 FLV，HLS 线路会长期无人验证，等做 tvOS 时才发现它一直是坏的。**Android 端实际承担 HLS 线路的看护责任。** 例外只有一家——抖音（douyincdn 的 `.m3u8` 边缘节点间歇 404，见上表），它 FLV 优先，但**必须把 HLS 留在候选列表里**：**"排在后面"和"不在列表里"是两件事**，后者会让 HLS 彻底失活，而这正是 tvOS 移植时最贵的一种死法。虎牙的 HLS 只保留 HS 系 CDN，AL/TX 那几条已知恒 403 的**不进列表**（进了就是每次播放先白白撞一次 403）。
2. **同清晰度内按 CDN 域名轮换**：上一次失败的域名排到队尾（进程内记忆，不落盘）。
3. **不在 `playUrls` 里做"探测"**：不预拉流验证可用性——4 个平台并发探测会让首页从 6s 变成 20s。可用性由首次播放的真实失败来发现（404 是例外，见 §3.2：它便宜到值得立刻重试一次）。

### 3.2 超时预算

| 场景 | 单次预算 | 总预算（用户可感知） | 超时后的动作 | 用户看到 |
| --- | --- | --- | --- | --- |
| 首页信息流（4 家并发） | 每平台 **6s** | ≤ 6.5s（含解析） | 谁超时谁被裁掉，其余照常渲染 | 该平台卡片缺席；若 ≥3 家失败，整屏换成"网络异常 + 重试" |
| 分类 rooms | 5s | ≤ 5.5s | 保留左栏分类，右栏显示"该分类加载失败 · 重试" | 可重试的一行说明，不显示骨架 |
| 搜索（全平台并发） | 6s | ≤ 6.5s | 同上（按平台裁） | 结果区顶部一行"虎牙无响应" |
| 房间详情 | 5s | — | 用列表页已有的封面与标题兜底进播放页 | 顶栏信息不全，但画面能出来 |
| `playUrls` | 5s | — | 进 `Failed(SIGN_INVALID/STREAM_OFFLINE)` | "线路异常 · 按 OK 重试" |
| **播放首帧** | 单线路 **4s**（3s 出提示），全部线路 **10s** | — | 3s 无首帧 → **角落先出"正在换线路"**，画面不动；4s 仍无 → 才真正切下一条；10s → Failed | 3s 看到"正在换线路"，4s 画面变化 + "已切到备用线路 · 720P" |
| 弹幕建连 | 3s | — | 静默重试（1s/3s/9s 退避），不打扰播放 | 弹幕不出现，控制条"弹幕"按钮显示"连接中" |
| 弹幕心跳 | 20s 间隔 / 30s 无回包断连 | — | 重连（退避 1/3/9s，上限 5 次） | 同上 |

> **首帧阈值的两段式（已拍板，工程取值覆盖设计稿）**：设计稿（`01-市场调研.md` §5.3）写的是"3 秒无首帧自动切备用线路"，那是**交互预期**——用户等 3 秒会不安，所以 3 秒必须给反馈。但 3 秒作为**切换判据**是错的：实测虎牙 FLV 在弱网下要 **3.2–3.8s** 才出第一帧，3 秒切会把"本来能播的线路"判死，用户看到的是"一直在换线路"。
>
> 所以两件事都保留、各管一段：
>
> | 时刻 | 发生什么 | 目的 |
> | --- | --- | --- |
> | 3s 无首帧 | 角落出"正在换线路"提示，**不切** | 消除"是不是卡住了"的疑虑（交互预期） |
> | 4s 仍无首帧 | 真正切下一条线路 | 判据留出实测余量（工程阈值） |
>
> 这条写在这里是为了将来别再改回去：**3 秒是给人看的，4 秒是给网络用的。** 两侧文字已同步（`01-市场调研.md` §5.3 与本节一致）。

**"没有响应"和"明确报错"不能用同一套阈值。** 两段式解决的是前者；后者等 3 秒纯属浪费——404 不会自己变好，但它**可能只是 CDN 边缘节点抖动**（抖音 douyincdn 实测：同一 URL 前两次 404、第三次 206）。所以失败分四类处理：

| 失败形态 | 典型 | 动作 | 为什么不套 3s/4s |
| --- | --- | --- | --- |
| **无响应**（连上了不来数据 / 首帧不出） | 弱网、丢包 | 3s 提示、4s 换线路 | 这就是两段式要解决的问题 |
| **404（瞬时）** | 抖音 HLS `.m3u8` **在播房间**的边缘节点 | **原样重试 1 次（约 300ms）**，仍 404 才换线路 | 重试比换线路便宜一个数量级（300ms vs 2–4s），而它确实有很高概率自己好 |
| **404（恒定）** | **下播房间**（抖音 `room.status == 4`） | **不重试**，直接 `STREAM_OFFLINE` | 房间已经结束了，重试只是白等 300ms，而且"再试"永远不会好 |
| **403 / 防盗链** | 斗鱼 `auth` 过期、CDN 拒 Referer | **不原地重试**，回上层重新 `playUrls()`（重算签名）或直接换线路，标 `SIGN_INVALID` | 同一 URL 再撞一次只会更快撞同一堵墙；签名过期必须重算，不是重发 |

> **先判"房间在不在播"，再判"HTTP 回什么"。** 直播间状态一律看**业务字段**：抖音 `room.status == 2` 才算在播；虎牙房间不存在是 HTTP **200** + `status:422`（不是 404）。**下播房间的地址是满配的**——抖音 `status:4` 的房间 `reflow` 照样回 4 路 FLV + 4 路 HLS，一条不少，只是 FLV 拉起来恒定 404（空 body）、HLS 超时。所以：**只按状态码决定重试，播放器会在一个已经结束的房间上重试到天亮**；判的是"这个房间有没有画面"，不是"这个 URL 回什么"。
>
> 这条对接口层是硬要求，不是建议：`detail` 的"是否在播"**不能**由 `streamUrl` 是否为空推导（下播房间也非空，会显示成"直播中"），必须 `room.status == 2`；`playUrls` 也要先判 status 再返回，**不把注定失败的地址交给播放器**——否则 404→重试→换线路 这套本来为抖动能自愈的机制，会全力工作在一个永远不会好的房间上。

> **"在播"是两层，不要合成一个 `&&`。** `live = (status == 2)` 是**态度**（平台说在不在播）；`hasPullUrl(stream)` 是**能力**（平台有没有给线路）。模型里只有一个 `live` bit，只能填态度；能力缺失交给 `playUrls` 抛 `PARSE_BROKEN`，这样三种状态三句话，互不冒充：
>
> | 状态 | `live` | 用户看到 |
> | --- | --- | --- |
> | 已下播 / 未开播 | false | 主播未开播 |
> | 在播且有线路 | true | 正常播放 |
> | **在播但没线路** | **true** | **已开播，但取不到画面（接口可能改版）** |
>
> 三种错法各错在哪：**只信 `stream_url` 存在** → 下播房间显示"直播中"（下播房间地址是满配的）；**`live = status == 2 && hasPullUrl(...)`** → 把我们的解析失败说成"未开播"，用户以为是主播没开，诊断页也不留痕；**`playUrls` 返回空列表** → UI 分不清"没线路"和"没数据"，等于静默失败。

**404 的重试只给一次，且写死在 player 里**：两次就换线路。给"再试几次"留口子的人情，最后都会变成一个页面转 10 秒的 bug。**前提是房间在播**（见上）——这个前提不写在 player 里，写在 `playUrls` 的返回契约里。

**三套"在播"判据不是同一个真相来源。** 代码里会冒出三个"这个房间在不在播"的判断，来源、字段、时机全不一样。抄在一张表里，免得后来人（包括三个月后的我们）以为自己看到的是同一个字段派生的：

| 来源 | 谁在用 | 具体字段与接口 | 时机 |
| --- | --- | --- | --- |
| `detail()` | 列表卡片、播放页准入 | 哔哩 `Room/get_info` 的 `live_status == 1`（`BilibiliSite.kt:185`）；虎牙 `mp.huya.com/.../profileRoom` 的 `liveStatus == "ON"`（`HuyaSite.kt:124` → `:144`）；斗鱼 `betard` 的 `room.show_status == 1`（`DouyuSite.kt:181`）；抖音 `reflow` 的 `room.status == 2`（`DouyinSite.kt:118`） | 进播放页之前一次 |
| 虎牙弹幕 | `HuyaDanmaku` 建连前 | **自己再打一次** `mp.huya.com/cache.php?m=Live&do=profileRoom`，`liveStatus == "ON"`（`HuyaDanmaku.kt:152`）；为假则发「直播已结束」并终止（`:97-100`） | 建连前 |
| 斗鱼弹幕 | `DouyuDanmaku` 连接中 | 弹幕流里的 `rss` 消息，`ss == "0"` → 「直播已结束」（`DouyuDanmaku.kt:229-230`） | 连接建立之后 |

**是三个，不是四个**：哔哩弹幕层不判在播，抖音 v1 弹幕不可用。这个数也要写清，否则下次有人照着"每层都该自己判一次"去补，就真的补出第四套。

**为什么现在不打架**：`detail` 判不在播时播放页根本进不去（UI 走"主播未开播"那条路），弹幕层没有被 collect 的机会；能进去的房间，`detail` 刚说过在播。虎牙那两处读的是**同一个字段**，但不是**同一次请求**——`mp.huya.com/cache.php` 是缓存层，两次请求之间最多差一个缓存周期，所以"字段相同"不等于"答案相同"。

**将来出现分歧，以 `detail` 的 `live` 为准**，因为它决定播放页给不给进。弹幕层的判断只允许影响**弹幕区那一行提示**，不许回写 `Room.live`、不许触发退出播放页。反过来的情形（`detail.live=true` 而虎牙弹幕读到 OFF）现在会让弹幕**永久停掉**——`:97-100` 直接 `return@channelFlow`。v1 接受这个行为：缓存滞后通常只有几秒，用户重开一次播放页就好。但这条约束不是洁癖：**一个终态决定不该建立在一个二手字段上**。将来要修，改法是"OFF 时退避重试一次、仍 OFF 才终止"，**不是让弹幕层去改 `live`**。

**由此三条规矩**（跨层的，写在这里，免得弹幕层各自发明一套）：

1. **弹幕层不复述"在播"的文案。** 「已下播 / 未开播 / 在播但没线路」这三句只说一次（取流层），弹幕层只在自己的链路上说「直播已结束」，而且不加「重新开关弹幕可恢复」那种后半句——下播不是连接问题，给了这个提示就是把人往错方向引。
2. **`playable == false` 不构成拒连弹幕的理由。** 那是"我们的线路坏了"，和"平台没播"是两件事、两句话。跟着拒连，用户会同时看到「在播但取不到画面」和「弹幕不可用」，归因直接乱掉。
3. **不合并这两套判据。** 真要合并，前提是先定"在播状态归谁所有"——现在这个所有权不存在，任何"顺手统一一下"都是在造一个跨层依赖。

### 3.3 失败可观测：错误码映射

错误必须分类，不能一律 `Exception("HTTP 403")`。分类的唯一目的是**让 UI 说出人话，让人 10 秒内判断是本地问题还是远端问题**。

| 内部码 | 触发条件 | 用户文案 | 可重试 | 上报 |
| --- | --- | --- | --- | --- |

| `NET_OFFLINE` | `ConnectivityManager` 报告无网 | 网络未连接 | 是 | 否 |
| `NET_TIMEOUT` | 超出 §3.2 预算 | 网络超时，点按重试 | 是 | 是（去重） |
| `HTTP_4XX` | 4xx（**不含下播房间的恒定 404**——那归 `STREAM_OFFLINE`，重试无意义） | 接口拒绝（可能是平台改版） | 是 | 是 |
| `HTTP_5XX` | 5xx | 平台服务异常 | 是 | 是（去重） |
| `RATE_LIMITED` | 429 / 返回体含风控标记 | 请求太频繁，稍后再试 | 是（退避） | 是 |
| `SIGN_INVALID` | 签名/防盗链校验失败（403 + CDN 特征，或返回体含校验字段） | 线路签名失效，请稍后再试 | 是 | **是（最高优先）** |
| `PARSE_BROKEN` | JSON 结构不符预期（必填字段缺失/为空）、`playUrls` 解析不出任何地址 | 列表/搜索：「接口返回异常，稍后再试」<br>播放页：**「已开播，但取不到画面（接口可能改版）」** | 否 | **是（最高优先）** |
| `ROOM_NOT_FOUND` | 用户指定的**目标**在平台上没了：虎牙 HTTP **200** + `status:422`；抖音 `reflow` 回 `10011`（**抖音侧：进 `reflow` 的 id 一定是 19 位纯数字**，由 `resolveRoomId` 的两处守卫保证——**这条以步 4 收紧到 `== 19` 为前提，落地前它是预言不是事实**，见本节第三张表与「10011 这一格」；成立之后 10011 只剩"这个号不存在"一种解释，`detail` 留原始 body 供诊断页计数）。"位数不对/参数不合法也回 10011"是**页面**上的现象，不是 `reflow` 的 | 直播间不存在或已结束 | 否 | 否 |
| `ID_UNRESOLVED` | 用户给的**标识**定位不到直播间：抖音空输入、纯数字但位数不是 19（`room_id` 19 位、`web_rid` **4–12 位**——实测有 4 位的，下限不能按 6 写）、短链/整链落地页配不上唯一配对 | 请检查房间号，或换一种链接（改用 `live.douyin.com/房间号`） | 否 | **否** |
| `STREAM_OFFLINE` | 业务字段说未开播/已下播：抖音 `room.status != 2`（**地址仍满配，但拉不动**）；**HTTP 可能仍是 200** | 主播未开播 · 预计 {time} | 否 | 否 |
| `GEO_BLOCKED` | 地区限制（**目前无落点**：四家的地区限制返回码都还没确认，确认前归 `HTTP_4XX` + `detail`） | 该内容在当前地区不可用 | 否 | 是（去重） |
| `DECODE_UNSUPPORTED` | 所有线路编码都不被设备支持 | 当前设备无法播放该清晰度（已在设置里提供"强制 H.264"） | 否 | 是 |
| `DANMAKU_PROTOCOL` | 弹幕建连/解包失败 | 弹幕连接失败（不影响画面） | 是（静默） | 是（去重） |

**13 个码，不是 12 个。** `ID_UNRESOLVED` 是后来加的，加它的理由**不是文案**——是 `report`：「短链定位失败」怪用户（粘错链接，`report=false`），「页面配不出 roomId」怪我们（解析器坏了，`report=true`），而**一个 `kind` 只能携带一个 `report` 值**。一份数据没法同时表达"不是 bug"和"是 bug"。这条已固化成硬规矩 6（§3.3.1 四）。

**"可重试"这一列的判据**：不是"技术上能不能重发"，而是**"用户点一下重试有没有意义"**——它决定播放页给「重试」按钮，还是给「换线路 / 回首页」。`STREAM_OFFLINE` 那格写"否"，就是为了不让 UI 在一个已经结束的房间上给一个按了没用的按钮。

**实现约定**：`core/model/SiteError.kt` 里是枚举（**已落地，142 行，零 import、零 android 依赖**），`SiteException(kind, url, detail, cause)`，`message = detail ?: kind.name`。**中文文案不进 `core`**（它要跨到 tvOS，文案属于平台资源），文案在 `ui/strings` 的映射表里，一份错误码对应一行。

> **落地状态（2026-10-29 晚）：步 1–4 已完成；步 5 未做；步 6 开始了三分之一。** 逐条进度与复查命令见 §6.4，那里每一行都能当场跑一遍。
>
> - **已有**：`core/model/SiteError.kt` **13 个码** + `SiteException`；`Net` 的 HTTP → `kind` 映射收口（13 行只映射 7 个码，业务码一个都不在这里抛）；四家 site 的 **51 个**抛点全部换成 `SiteException` 且**全部带 `detail`**；`core/` 全量编译 `0 error`。
> - **还没有**：`player/LivePlayer.kt:159` 的 `readableError` 仍返回 `String`（步 5）；`Async.Failed` 仍装 `String`（`ui/components/Bits.kt:49`，步 6）。**所以现在不能宣布「错误码体系已交付」**——闸门的具体形态在 §6.4。
> - **`ui/` 里 `SiteError` 现在有 5 命中，全在 `PlayerScreen.kt`**（1 个 import + 4 个码的文案）。这是步 6 的**第一支**，也是最容易变成"第二份真源"的一支：那 4 句文案写在了 screen 里的 `when (e.kind)` 上，不在 `ui/strings`。**第二屏开始写 `when (kind)` 的那天，两处文案就会不一致**，所以闸门是"在建表之前不许加第二个 `when`"，判据见 §6.4。
> - **`Sites.describe()` 还在**（`Sites.kt:128`）。它**没有 ui 调用点**：那 5 处 ui 读的是 `Async.Failed(e.message)`，即 `SiteException.message`。所以删它不会打断 build；挂到步 6 的真实理由是——删了之后 `SiteFailure.detail` 只能填 `kind.name`，用户屏幕上就是英文码。
> - 本节开头列的三条后果，**第一条只消失了一半**：聚合层已经能给出 `kind`，播放页也开始读它，但列表/搜索/首页还没读（步 6 剩余部分）；第二条（诊断页无处存错误码）随步 1–4 解决；第三条（§3.5 靠码计数）要等诊断页接上 `kind` 才成立。
>
> 落地成本是一次性的（替换 `Net` 与四家 site 的 `throw` 点），**必须在发版前做完**：它是「用户看到人话」和「诊断页能分清本地/远端」这两件事的唯一实现路径。**逐个码的触发点、文案、可重试标志、验收方法和改动顺序，见 §3.3.1**——那一节是可以直接派活和验收的形态，本节只定原则。

**可观测的最小闭环**（不上第三方统计也能做到）：
- 每个平台的**最后一次错误码 + 时间戳**写进 `Prefs`（`diag_bilibili_last` 等 4 个 key）。
- 设置页隐藏入口（"关于"连按 OK 5 次）打开**诊断页**：4 家最近错误、最近一次 `playUrls` 的线路顺序、设备档位、解码能力 dump、rulePack 版本。
- **一个跨平台请求计数**（`Net.requestCount`，公开只读，`AtomicLong`）：诊断页显示「今天共发出 N 次请求」，键名 `net_requests`。
  它不只是给测试用的：**「重试一次」这类逻辑一旦 `catch` 落空，编译通过、没有 warning、日志也看不出来——只有请求次数会从 2 变 1**。所以这个数字是那条规矩唯一的外部证据。
- 这个页面是**售后支持的全部**：用户报"虎牙看不了"，请他进诊断页截图，我们能立刻区分是签名失效还是他家网络。

### 3.3.1 错误码落地清单（实现照着改，验收照着查）

**为什么这件事排在哨兵前面**：哨兵红不红是给我们看的，错误码是给用户看的。现在的状态是——`core` 抛 `IllegalStateException("抖音：房间 X 已下播")`，`Sites.describe()` 取 `t.message`，UI 直接 `Async.Failed(e.message)` 显示出来。**整条链上唯一的"类型"是中文字符串。** 谁改一个字，文案映射就静默退化成通用兜底，而测试看不出来——因为改的人以为自己在改文案。

> **一段实证，给后来人看这类错的传染性。** 这套东西上线之前，我们自己写的两份文档里就已经出现过**两次「用不可靠信号反推类型」**：
>
> 1. 清单第一版明令禁止「由消息字符串反推 `kind`」（`SiteError.valueOf(msg)`、`msg.contains("超时")`），
> 2. 同一份清单的另一处，又用没人验证过的 `status_code` 反推房间状态（"抖音 `status_code` 指向房间不存在"）——10011 同时也是"参数不合法"。
>
> 禁令写在文档第 1 条，违规出现在第 3 条。**这不是自嘲，是结论**：反推之所以诱人，是因为它不需要额外证据就能让代码通过编译、让文档看起来完整。所以规矩不能只写"不许反推"，还得写"证据从哪来"——下面每一条判据都带它的来源（实测响应、代码结构、或现场取证）。

#### 一、形状（已落地，别再设计一遍）

```kotlin
// core/model/SiteError.kt —— 零 android 依赖，能跨到 tvOS
// 2026-10-29 已落地：142 行、零 import、core/ 全量编译 0 error
enum class SiteError(val retryable: Boolean, val report: Boolean) { /* 先 12 个，ID_UNRESOLVED 待补成 13 */ }

class SiteException(
    val kind: SiteError,
    val url: String? = null,
    val detail: String? = null,      // 原始接口消息/状态码，只进诊断页，不进主文案
    cause: Throwable? = null
) : Exception(detail ?: kind.name, cause)
```

**这三样是既成事实，后面所有代码照它写，不要改名**：`kind` 这个字段名、`url`/`detail` 这两个可选参数、`message = detail ?: kind.name`。改名的代价是四家 site + `Net` + 诊断页全部返工，收益为零。

`retryable` 不是"技术上能不能重试"，是**"用户点一下重试有没有意义"**——它决定播放页给「重试」按钮还是给「换线路 / 回首页」。这一列写错，UI 就会在一个永远不好的房间上给用户一个按了没用的按钮。

#### 二、`Net` 的 HTTP → kind 映射（唯一收口点，写错一处四家全错）

| # | 现象（`Net` 能看到） | `kind` | 为什么这么归 |
| --- | --- | --- | --- |
| 1 | 请求前 `ConnectivityManager` 报告无网 | `NET_OFFLINE` | 省掉一次 8s 连接超时，用户立刻知道是网 |
| 2 | `UnknownHostException`（系统有网） | `NET_TIMEOUT` | DNS 失败/污染对用户的可做动作与超时相同；报"未连接"会让他去查路由器 |
| 3 | `SocketTimeoutException` / `InterruptedIOException` | `NET_TIMEOUT` | — |
| 4 | `ConnectException` / `SocketException`（含 `ECONNRESET`） | `NET_TIMEOUT` | 直播 CDN 边缘常见，重试有效 |
| 5 | `SSLException` / `SSLHandshakeException` | `NET_TIMEOUT` + `detail="TLS"` | **不再为它单开码**：用户可做的动作与非超时完全一样；机型相关（API 21 TLS）靠 `detail` 在诊断页区分。这是规矩 5 的典型（动作相同） |
| 6 | 401 / 403 | `SIGN_INVALID` | 未登录、风控、防盗链，对我们都是"这条路要换" |
| 7 | 403 **且** body 命中风控词（`-352` / `risk` / `frequen`） | `RATE_LIMITED` | 退避有效、原地重试无效，两者动作相反 |
| 8 | 429 | `RATE_LIMITED` | — |
| 9 | 404 | `HTTP_4XX` | 域名/路径改了。**不是房间下播**——那由业务字段判（§3.2） |
| 10 | 其他 4xx | `HTTP_4XX` | — |
| 11 | 5xx | `HTTP_5XX` | 平台自身故障 |
| 12 | 200 但 body 空 / 非 JSON / 必填字段缺失 | `PARSE_BROKEN` | "200 不等于成功"：哔哩 `-352`、抖音 `Request params error` 都是 200 |
| 13 | 其他（含重定向落到首页，如失效短链） | `PARSE_BROKEN` + `detail` | 兜底归 `PARSE_BROKEN`，**不许静默吞掉** |

**规矩**：HTTP 码只在 `Net` 里判一次（`Net.kt:88`、`Net.kt:113` 两个点）。**四家 site 不许再出现 `resp.code` 判断**——现在没有，将来也别加。

**"13 行"和"13 个码"是两件事，别混**：这 13 行是 `Net` 看到的现象分支，只映射到 7 个 `kind`（`NET_OFFLINE` / `NET_TIMEOUT` / `SIGN_INVALID` / `RATE_LIMITED` / `HTTP_4XX` / `HTTP_5XX` / `PARSE_BROKEN`）。剩下 6 个码（`ID_UNRESOLVED` / `ROOM_NOT_FOUND` / `STREAM_OFFLINE` / `GEO_BLOCKED` / `DECODE_UNSUPPORTED` / `DANMAKU_PROTOCOL`）**`Net` 一个都不该抛**——它们全是业务语义，只有 site 层和 `player`/`danmaku` 层判得出来。`Net` 里出现 `ROOM_NOT_FOUND` 就是要拿 HTTP 码冒充业务判断了。

#### 三、每个抛点该变成哪个码（文案/可重试见 §3.3 的表，这里只补"在哪儿抛"）

**这张表的主键是「抛点」，不是「错误码」。** 上一版按错误码做主键，同一个抛点就被抄进了两行（`DouyinSite.kt` 那两处「缺 data / 缺 room」同时出现在 `PARSE_BROKEN` 和 `ROOM_NOT_FOUND`），而一个抛点不可能有两种 `kind`——实现的人只会挑先看到的那一行。**形状错了，抄写就会错**，所以改成按抛点组织：一个位置只可能出现一次，写错只能写错内容，不可能写成二义。

**行号是快照，不是主键**：下表行号以 2026-10-29 的代码为准，backend 每改一次文案就整体下移（写这份表的过程里抖音那批已经从 `:92/:94/:148/…` 漂到 `:95/:97/:151/…`）。**真正的定位器是第二列那句字符串**——`grep -n '接口返回里没有 data'` 比数行号可靠，也永远不会过期。行号只是给人翻文件时省一次搜索。**新增或删除抛点必须同步这张表**——不是「行号挪了」，是「多了一个说法」或「少了一个说法」；后者更危险：删掉一个分支而表里还留着，下一个人会去找一个根本不存在的抛点。

**四家 site（`core/site/`）**

| 抛点 | 现在的字符串 | → `kind` |
| --- | --- | --- |
| `BilibiliSite.kt:228` 取流响应缺 `data` | 取流响应缺少 data | `PARSE_BROKEN` |
| `BilibiliSite.kt:230` `live_status != 1` | 房间未开播 | `STREAM_OFFLINE` |
| `BilibiliSite.kt:233` 在播但缺 `playurl_info` | 未取到播放地址（房间可能限制观看或需要登录） | `PARSE_BROKEN` + `detail="playurl_info 缺失"`。**不是 `SIGN_INVALID`**：未登录时哔哩是把 `qn` 压到 250，不是不给 `playurl_info`；`live_status==1` 却读不到地址，就是结构变了。原句那半句"或需要登录"是我们的猜测，不进用户文案 |
| `BilibiliSite.kt:282` 排序后为空 | 没有可用播放线路 | `PARSE_BROKEN`（硬规矩 4） |
| `BilibiliSite.kt:293/295` `room_id` 解析为 0 | 房间不存在 | `ROOM_NOT_FOUND` |
| `BilibiliSite.kt:336` `requireOk` 业务码非 0 | 接口异常 code=… | `-352`/`-412`/命中风控词 → `RATE_LIMITED`；其余 → `PARSE_BROKEN` + `detail="code=…"` |
| `HuyaSite.kt:61/90/285` 列表/分区/搜索 `status != 0` | 接口异常 status=… | `PARSE_BROKEN`。**`status` 是浏览器端业务码，不是 HTTP 码**，别拿它当 5xx |
| `HuyaSite.kt:121/156` `data` 为 null | 房间不存在 | `ROOM_NOT_FOUND` |
| `HuyaSite.kt:159` `liveStatus != ON` | 未开播 / 正在回放 | `STREAM_OFFLINE`（回放也没有可看的直播流，用户动作一样） |
| `HuyaSite.kt:164` 缺 `stream` | 未开播（无 stream 字段） | `STREAM_OFFLINE` |
| `HuyaSite.kt:166` 缺 `baseSteamInfoList` | 取流响应缺少 baseSteamInfoList | `PARSE_BROKEN` |
| `HuyaSite.kt:210` 排序后为空 | 没有可用播放线路 | `PARSE_BROKEN` |
| `HuyaSite.kt:219` `status == 422`（HTTP 仍是 200） | 房间号不存在 | `ROOM_NOT_FOUND` |
| `HuyaSite.kt:220` 其他 `status` | 房间状态异常 status=… | `PARSE_BROKEN` + `detail` |
| `HuyaSite.kt:240` 重试两次后仍失败 | 详情获取失败（…） | **透传**：`last` 存 `SiteException?`，`throw last`；两次都不是 `SiteException` 才兜底 `NET_TIMEOUT`。现在这行把 `last` 拼进新消息，**`kind` 在这里被丢一次**（硬规矩 7） |
| `DouyuSite.kt:84/90` 分区接口 `code != 0` / 缺 `data` | 分区接口异常 code=… | `PARSE_BROKEN` |
| `DouyuSite.kt:134` 未知 `categoryId` | 无（`?: return emptyList()`） | **保持返回 `emptyList()`，不抛**。分区 id 是 UI 从我们给的列表里选的，过期就是"这个分类现在没房间"。与硬规矩 4 不冲突：4 管的是"平台给了数据我们读不出"（我们的故障），这里是"这个目标本来就不存在"。塞进 `ROOM_NOT_FOUND` 会让分类页显示"直播间不存在"，比空列表更糟 |
| **`DouyuSite.betard()` 的非 JSON 响应（判据在 `betardFailureKind()` `:444`，抛点 `:268`）** | 斗鱼：房间 X 不存在 / 斗鱼：betard 返回了 HTML 页面而不是数据 / …返回了空响应 | 按**形状**分档，不再"解析失败就说不存在"：非 JSON 且 `<4000B` → `ROOM_NOT_FOUND`（`report=false`）；非 JSON 且 `>=4000B` → `PARSE_BROKEN` + `bytes=`；空白 → `PARSE_BROKEN`。**本节最脆的一处，见下面单独一段**。判据不能靠 HTTP 码：实测不存在的房间回 **HTTP 200 + `text/html` + 1355 字节**，真实房间回 `application/json` + 119588 字节（`docs/06` §2.4） |
| `DouyuSite.kt:169` `betard` 缺 `room` 字段 | 斗鱼：房间 X 不存在 | `ROOM_NOT_FOUND` |
| `DouyuSite.kt:196` `preview` 业务码非 0 | — | 由 `previewError` 决定：已知码各自映射，未知码 → `PARSE_BROKEN` + `detail` |
| `DouyuSite.kt:199` 取流响应缺 `data` | 取流响应缺少 data | `PARSE_BROKEN` |
| `DouyuSite.kt:204` 未开播 | 斗鱼：房间 X 未开播 | `STREAM_OFFLINE` |
| `DouyuSite.kt:235` 取流返回非 JSON | 取流接口返回了非 JSON 内容（接口可能已改版） | `PARSE_BROKEN`，"接口可能已改版"这句搬进 `detail` |
| `DouyuSite.webMixList()` `:290` / `mobileRoomList()` `:323` 列表接口 `code != 0` | 列表接口异常 code=… | `PARSE_BROKEN` |
| `DouyinSite.kt:95` 缺 `data` | 接口返回里没有 data（接口可能改版） | `PARSE_BROKEN`。**依据是代码结构不是判断**：`reflow()` 在 `status_code != 0` 时已经抛过了（`:225` 那个 `if`），所以能走到 `:95` 必然 `status_code == 0`——平台说成功、结构却不对，只能是我们坏了。原句把它说成"房间不存在"，正是本节要拆的那类冒充 |
| `DouyinSite.kt:97` 缺 `room` | 接口返回里没有 room（接口可能改版） | 同上 |
| `DouyinSite.kt:151` `playUrls` 缺 `data.room` | 接口返回里没有 room（接口可能改版） | 同上 |
| `DouyinSite.kt:156` `status == 4` | 房间 X 已下播 | `STREAM_OFFLINE` |
| `DouyinSite.kt:158` `status` 是其他值 | 房间 X 未开播 | `STREAM_OFFLINE`（`status != 2` 一律不在播，不写 `== 4`） |
| `DouyinSite.kt:165/169` 缺 `stream_url` / 无任何线路 | 已开播，但取不到画面（接口可能改版） | `PARSE_BROKEN` + `detail`（`stream_url` 缺失 / `hasPullUrl=false`）。文案已经是产品定稿那句，别改 |
| `DouyinSite.kt:204` 排序后为空 | 房间 X 没有可用播放线路 | `PARSE_BROKEN` |
| `DouyinSite.reflow()` 的 `status_code != 0`（`:226` 是 `10011` 分支，`:230` 是其他非 0 码） | 由 `reflowError` 拼句 | **裸映射，不加前缀条件**：`10011` → `ROOM_NOT_FOUND` + `detail` 带 `status_code=10011`；**其他任何非 0 码 → `PARSE_BROKEN`**（没见过的业务码＝协议变了，第一个陌生码就红）。为什么不用"是否经过页面配对"当前缀，见下面「10011 这一格」 |
| `DouyinSite.kt:251` 空输入 | 抖音：房间号为空 | `ID_UNRESOLVED` + 明确文案（UI 该拦没拦，用户动作是重输） |
| **`DouyinSite.kt:265` / `:282` 纯数字、但形状既不是 19 位 room_id 也不是 4–12 位 `web_rid`（13–18 位 / <4 位 / >19 位）** | 无（`douyinIdShape` 判 `NEITHER`） | `ID_UNRESOLVED` + `detail="位数=…"`，**本地就抛、一个请求都不发**。**判据不能写成"不是 19 位就拒绝"**——那样会把 4–12 位的 `web_rid` 一起判死，而它是抖音在这个 App 里**唯一的常规入口**（`capabilities.idInput = RoomIdOrWebRidOrLink` 承诺了它；真机实测 4 位 `8888` 能配出 19 位）。形状三分类在 `douyinIdShape`（`:422`），**数字规则只有那一处**，两处守卫（`:262` 直接输入、`:278` 链接末段）都调它 |
| `DouyinSite.kt:322` 短链/整链落地页配不上唯一配对 | 无法从这条链接定位直播间，请改用… | `ID_UNRESOLVED`（已实测：失效短链 302 到 `www.douyin.com`、HTTP 200、`roomId` 出现 0 次）。**怪用户，`report=false`** |
| `DouyinSite.kt:328` 房间页里一个配对都没有 | 没有从 X 的页面里解析到房间号（房间可能已注销或页面结构变化） | `PARSE_BROKEN` + `detail="pairing-miss"`。**这一支宁可误报**：红一次的代价是查 15 分钟，漏一次的代价是抖音整条通道静默失效（§6.1 的单点依赖）。"页面里没配对"是**我们的解析前提失效**，不是平台告诉我们房间没了——平台没说话时不许替它说话。**它和 `:322` 看起来是同一句"定位不到"，但 `:322` 怪用户、它怪我们，所以不能共用 `ID_UNRESOLVED`**——这是硬规矩 6 的第一个实例 |

**斗鱼「解析失败即不存在」和抖音「页面里一个配对都没有」（`pairing-miss`）曾经对同一个歧义做了相反的裁决——这段留着，因为「当初为什么会相反」是判断依据。**

两家都有"HTTP 200 + 一个不是数据的页面"这种情形，而**改动前**两处给了相反的答案：

| 平台 | 现象 | 改动前 | 方向 |
| --- | --- | --- | --- |
| 抖音 `resolveRoomId()` 末尾（`pairing-miss`，`:328`） | 房间页里配不出 roomId | `PARSE_BROKEN`（`report=true`） | **宁可误报**：红一次的代价是查 15 分钟 |
| 斗鱼 `betardFailureKind()`（`:444`） | `betard` 的响应不是 JSON | 曾经一律 `ROOM_NOT_FOUND`（`report=false`）；**现已按形状分档** | **曾经宁可漏报**：误报一次的代价是用户看到"房间不存在" |

**这一段的指法本身就是一条规矩**：按「函数名 + 文案片段」指认，行号只是线索。它原来写的是 `抖音 :307`，而 `:307` 早就是 `resolveRoomId` 里一行正则了——真正的抛点在 `:328`。**行号只有一处真源，就是本节上面的抛点表**；别的地方抄行号，抄的那一刻是对的，之后就是一句没人核的话，而且**漂了之后两处读得都通**（这正是 `docs/06` §4.6 那段「虎牙两处都叫 `liveStatus`」的同一个形状：不是谁写错了，是没人能判断哪个是真的）。

两边都不是随手写的，但它们**不可能同时最优**，差别在于判错的代价落在谁身上：

> 斗鱼这一支如果判错——比如斗鱼改版、维护页、WAF 拦截页、运营商劫持页——**全平台所有房间都会显示「房间不存在」，`report=false`，诊断页一声不响**。用户报"斗鱼全挂了"，我们会答"是你房间号不对"。这是本节最不能接受的一种失败：**把我们的故障说成用户输入错误**，而且它是静音的。

**裁决已经落地（2026-10-29 晚）：斗鱼改成形状匹配才说不存在**——这正是 §4.1 抖音页面配对那条原则的同一句话，两边现在同向，都宁可误报。**而且没有动 `Net` 签名**，因为实测两种响应的体积差两个数量级（1355B vs 119588B），而 `Net.get` 返回的 `String` 自带 `.length`。`betardFailureKind()`（`:444`）三档：

1. 空白 → `PARSE_BROKEN`（网关给了一个空体，不是用户的错）；
2. `trimStart().removePrefix("\uFEFF").trimStart()` 后以 `{` 开头 → 不是失败，交给 JSON 解析（解析失败另算 `PARSE_BROKEN`）；
3. 不以 `{` 开头且 `length < 4000`（`DOUYU_HTML_ERROR_MAX`，`:457`）→ 那张 1355 字节的错误页 → `ROOM_NOT_FOUND`；
4. 不以 `{` 开头且 `length >= 4000` → 门户 / 维护 / 劫持页 = 外部依赖变了 → `PARSE_BROKEN` + `detail` 带 `bytes=`。

**照这段写代码时有一条会直接编不过的坑**：别写成 `when (betardFailureKind(text)) { ROOM_NOT_FOUND -> …; PARSE_BROKEN -> …; null -> … }`。Kotlin 对**可空枚举**的 `when` 要求穷尽 13 个值或给 `else`，列两三个分支是 `must be exhaustive` 编译错误。写成 `val kind = betardFailureKind(text); if (kind != null) { … }`。

**另外：这三档是我给的草案，执行时被补了两处，两处都是我的漏。** ① `""` 在草案里会落进 `length < 4000` → `ROOM_NOT_FOUND`，等于**把"网关抽风"说成"你房间号不对"**——同一类错误换了个入口，所以空体必须单独归 `PARSE_BROKEN`；② `trimStart()` 不去 `\uFEFF`，带 BOM 的正常 JSON 不以 `{` 开头。**判据是别人执行的，漏掉的那一半只有执行的人看得见**——这不是客气话，是流程结论：写判据的人只能想到自己见过的形状，而"漏了什么"只有在真数据上跑一遍才会露出来。所以验收里"三种形状都要断言"那条（第 11 条）不是谨慎，是唯一能让漏掉的那一半自己现形的办法。

门限 4000 不是拍的：实测 1355B，留三倍余量；到了 4KB 以上，它已经不可能是那张错误页了。**这条改动自己带来了一个新的失败模式，值得单独记一句**：`startsWith("{")` 会被 BOM 骗过去——带 BOM 的 119KB 正常响应不以 `{` 开头，于是走第 4 档，在全站范围内把"一切正常"报成 `PARSE_BROKEN`。**修一个静默失效时，新判据本身就是下一个静默失效的候选**，所以判据要能自证：`detail` 里必须带 `bytes=`，误判时一眼看出是体积判错了，而不是再猜一遍。

**跨层（`core/net` / 聚合层 / `player` / `danmaku`）**

| 抛点 | → `kind` |
| --- | --- |
| `Net.kt:88`、`Net.kt:113` 非 2xx | 按第二节 13 行映射；**四家 site 不许再判 `resp.code`** |
| `Sites.kt:86` 的 `SiteTimeout` | `NET_TIMEOUT`（聚合层超时与连接超时同码：用户动作一样） |
| `Sites.kt:128` `describe()` | `SiteFailure` 改带 `kind`；`describe()` 降级为 shim + `TODO(步6)`，**步 6 同一个 PR 删掉**（硬规矩 2） |
| `LivePlayer.kt:159` `readableError()` | 返回 `SiteError`：`IO_BAD_HTTP_STATUS` → `SIGN_INVALID`、`DECODING_FAILED`/`DECODER_INIT_FAILED` → `DECODE_UNSUPPORTED`、`PARSING_CONTAINER_MALFORMED` → `PARSE_BROKEN` |
| `DanmakuClient.kt:28` `Failed(message)` | 加 `kind: SiteError`。11 个构造点全部 `DANMAKU_PROTOCOL`（`BilibiliDanmaku.kt:76/99/108/240/243`、`DouyuDanmaku.kt:72/81`、`HuyaDanmaku.kt:90/123/134`、`DouyinDanmaku.kt:98`），**两个例外**是"房间号解析不出来" → `ID_UNRESOLVED`（`BilibiliDanmaku.kt:203`、`HuyaDanmaku.kt:60`）——它们和抖音那三处是同一件事：用户给的标识定位不到，怪用户、不叫醒我们 |
| `Tars.kt` 的 `TarsException` | 内部 catch 后转 `DANMAKU_PROTOCOL`，不许出 `core` |

**没有落点的两个码**：`NET_OFFLINE`（`Net` 请求前新增，映射表第 1 行）、`GEO_BLOCKED`（四家的地区限制返回码都还没确认，确认前归 `HTTP_4XX` + `detail`，**不上报成新码**）。

**`ui/` 侧要跟着动的**（不在上表里，但漏了这一步整件事会退化成 `toString()`）：`Async.Failed` 现在装的是 `String`，UI 有 5 处直接读 `.message`——`HomeScreen.kt:70`、`BrowseScreen.kt:208`、`Bits.kt:77`、`SearchScreen.kt:149/150/566`。删掉 `describe()` 之后这些地方拿到的是 `SiteException` 的 `toString()`。`Async.Failed` 必须改成携带 `kind` + 可选 `detail`，文案进 `ui/strings`。另外 `SearchScreen.kt:547` 自己又定义了一个 `private class SiteTimeout`（与 `Sites.kt:51` 重复）——两处都要归到 `NET_TIMEOUT`，否则从搜索页进来的超时落到兜底码。**这两条是 frontend 的活**，backend 不碰 `ui/`。

**10011 这一格：为什么不加前缀条件（2026-10-29 定案，别改回去）**

取证结果（原始响应在 `docs/06` §4.1）：不存在的 19 位号 `1234567890123456789`、12 位错位号 `773103753553`、另外两个不存在的 19 位号，返回**一字不差**——`{"data":{"message":"Request params error","prompts":"Request params error\t"},…,"status_code":10011}`。所以「`10011` = 房间不存在」单独看是假的，它同时也是"参数不合法"。

围绕这一格，三个人各错了半句，值得记下来，因为错误的方向比结论有用：

| 谁 | 主张 | 错在哪 |
| --- | --- | --- |
| 我（第一版文档） | 抖音 `status_code` 指向房间不存在 | 把猜测写成类型。10011 是两义码 |
| backend | 加前缀条件「id 由页面严格配对得出」才算 `ROOM_NOT_FOUND` | 前缀选错了：收藏/最近观看的 19 位号在 `DouyinSite.kt:256` 直接 `return`，从不过页面配对，前缀恒 false → **最常见的失败永远进不了这个码** |
| 我（第二版文档） | 那就按码分、不看来源 | 方向对了，但**仍然在解一个不存在的题**：我把"来源"当成需要额外标记的东西，其实"形状"已经写在入口守卫里 |

**定案：裸映射 `10011 → ROOM_NOT_FOUND`，不加任何前缀标记。** 理由是入参形状本身就是那个前缀——`resolveRoomId` 决定能不能走到 `reflow`，走到 `reflow` 的一定是 19 位纯数字（配合 `:256`/`:264` 那条守卫改动，见上表）。既然 id 形状合法，`10011` 就只剩"这个号不存在"一种解释。"位数不对也回 10011"是**页面**上看到的现象，不是 `reflow` 的。

**这条结论的前置条件现在是结构保证，不再是纪律。** 它曾经是预言（守卫是 `>= 15`，15–18 位会以「房间不存在」的名义显示出来，真相是「你少打了几位」）；2026-10-29 已由 `douyinIdShape()` 三分类改成断言：**19 位直连 `reflow`；4–12 位按 `web_rid` 走页面配对；其余长度（13–18 / <4 / >19）本地抛 `ID_UNRESOLVED` 且不发请求**。数字规则只有 `:422` 一处，两处守卫都调它——**这就是「把前提写进代码」：不满足时它走不到 `reflow`，而不是靠文档提醒**。

**这一段还留了一个更值得记的教训：写代码的人照着能编译的那半句做了。** 定案是"裸映射、不加前缀"，但同一份规格里的前提（"守卫是 `>= 15`，放行 15–18 位"）与结论不兼容，而前提是**代码里已经成立的事实**、结论只是**文档里的一句要求**。于是 `reflow()` 里出现了 `code == 10011 && resolved.fromPage -> ROOM_NOT_FOUND`，另加一个 `fromPage` 标记从 `resolveRoomId` 一路传下来——**这是把"补前提"这件事写成了代码**，而且写得很合理：如果守卫真的只放行 19 位，前缀就是多余的；守卫没做到，前缀看起来就是必要的补丁。**这不是实现的人不听话，是规格没把前提和结论钉在一起。**

所以规矩补一条，比"文档里再强调一遍"有效：

> **能被写成断言的前提，就不要写成文档里的强调。** 守卫写成 `length == 19` 是断言（不满足就抛，编译器与运行时替你守）；写成"进 `reflow` 的一定是 19 位"是叙述（满足与否要靠人去挨个读）。**同一条约束，一旦它以叙述的形式存在，每个读它的人都会自己决定信到几分**——`fromPage` 就是这个决定的产物。守卫收紧之后，`fromPage` 在这个位置就成了第二份真相，必须删掉：它当初存在的唯一理由，是补一个本该由守卫保证的前提。

**残余风险的覆盖方式必须写清**：「抖音把整个 `reflow` 改成全员回 `10011`」这个洞，`report=false` 兜不住。它靠的是**测量**，不是**推断**——

> **诊断页加一个「抖音取流通道自检」按钮**：点一下，拿**一个固定已知在播的 `web_rid`** 打一次 `reflow`。
> - 回 `ok` → `10011` 是个别房间的事，通道活着；
> - 也回 `10011` → **抖音取流通道可能已被关闭**，诊断页顶栏红条，用户截图即证据。
>
> **这个 `web_rid` 必须与 `android/tools/probe-sites.sh` 里那个是同一个值、同一个来源**——这是整个按钮存在的理由：它是和哨兵**同一个探针**，只是执行者从我们换成用户。半年后有人改了脚本里的常量、忘了改按钮，两边就会各说各话：脚本绿、按钮红（或反过来），而没人能判断哪个是真的。**要么同一个常量，要么别做这个按钮。**
>
> 它落实的是 §6.1「抖音取流是本产品单点依赖」的可执行版本，以及 §4.1 那句「生产实现与哨兵必须用同一套判据」：**同一个常量、同一条请求、两个调用者**。

**推断和测量不能互相替代**，这条界线要写死：

| 想回答的问题 | 谁回答 | 用什么 |
| --- | --- | --- |
| 某一次请求为什么失败 | `kind` + `detail` | **推断**（码映射），必须在本地即时给出 |
| 这条通道整体还活着吗 | 哨兵 + 自检按钮 | **测量**（真打一次已知在播的房间），不能用码分布去猜 |

把后者塞进前者就是"用不可靠信号反推类型"的第三次发作：`10011` 计数升高可能是抖音挂了，也可能是用户今天粘错了很多号——**码分布区分不了这两件事，只有测量能**。

#### 四、硬规矩（这几条不满足，等于没做）

1. **`kind` 自带，禁止由字符串反推**。`SiteError.valueOf(msg)`、`msg.contains("超时")` 一律算没做——这正是要消灭的东西。
2. **`describe()` 必须死，但死在步 6。** `SiteFailure(platform, reason: String)` 现在改成 `SiteFailure(platform, kind: SiteError, detail: String?)`，`FeedResult.note()` 的文案由 UI 用 `kind` 查表拼。`Sites.kt:71` 那个 `describe()` 这一批**先留成 shim**（加 `TODO(步6)`）——它的调用点在 `ui/`，删早了会打断 build。**但它的死亡条件是硬的**：frontend 改完 `Async.Failed` 之后同一个 PR 删掉，不许以"以后清理"形式留下——它是"按字符串传错误"的现成通道，留着就会有下一个页面理所当然地用回去。
3. **`player/` 不许有文案**：`LivePlayer.readableError()`（`:159`）改成返回 `SiteError`——`ERROR_CODE_IO_BAD_HTTP_STATUS` → `SIGN_INVALID`、`DECODING_FAILED`/`DECODER_INIT_FAILED` → `DECODE_UNSUPPORTED`、`PARSING_CONTAINER_MALFORMED` → `PARSE_BROKEN`；文案进 `ui/strings`。`DanmakuEvent.Failed` 同样带 `kind`（`DanmakuLayer.kt:77` 现在直接拿 `message` 当系统提示显示）。
4. **空列表不是错误，也不能当空列表返回**：`playUrls` 拿不到地址 → `PARSE_BROKEN`。现有三处「没有可用播放线路」（`BilibiliSite.kt:282`、`HuyaSite.kt:210`、`DouyinSite.kt:204`）要归到 `PARSE_BROKEN`，**不是"未开播"**——它们是"我们解析不出地址"，不是"平台没播"。
5. **加码门槛之一：用户该做的动作不同。** 同一动作、不同原因 → 放 `detail`。（这就是 TLS 握手失败没单列的原因：用户能做的和超时完全一样。）
6. **加码门槛之二：`retryable` 或 `report` 必须取不同值，则必须拆码，不许靠文案区分。** 规矩 5 管的是"用户动作"，这条管的是"责任归属"——**后者连文案都救不了**，因为一个 `kind` 只能携带一组布尔值，一行数据没法同时表达"不是 bug"和"是 bug"。`ID_UNRESOLVED`（怪用户，`report=false`）与 `PARSE_BROKEN`（怪我们，`report=true`）就是这条的第一个实例：两处抛点看起来都是"定位不到直播间"，但一个不该叫醒我们、一个必须叫醒。
7. **换掉异常类型之前，先把 `catch` 全搜一遍——否则重试会静默失效。** 现有三处按类型写的重试/包装：

   | 位置 | 换成 `SiteException` 之后会发生什么 |
   | --- | --- |
   | `HuyaSite.kt:235` `catch (e: IllegalStateException)` | `SiteException` 不是 `IllegalStateException` 的子类，**catch 不再命中**：`fetchProfile` 的"非 2xx 重试一次"直接不执行了。编译通过、无日志、行为悄悄变了 |
   | `HuyaSite.kt:240` 把 `last` 拼进新消息 | `kind` 被字符串化后丢掉（而且它上面那个 catch 落空之后，这行根本走不到） |
   | `DouyuSite.kt:216/225` `catch (e: Exception)` | 会把 `SiteException` 一起接住再包成 `PARSE_BROKEN`，`kind` 也被降级 |

   改法统一：三个 `catch` 里都先 `is SiteException -> throw it`，`last` 存 `SiteException?` 而不是 `String?`。**验收时搜 `catch (` 逐个看一遍**——编译器不会提醒这一类退化，这正是它能活到线上的原因。

#### 五、验收（照着敲；每条写清谁跑、跑什么、看什么）

**前置（一次性，约 10 分钟，必须排在步 2 之前）**：`app/src` 现在**只有 `main`，没有 `test`**；`build.gradle.kts` 里也只有 `junit` 和 `org.json:json`（`:76-77`），没有 MockWebServer。所以先做三件事：

1. 建目录 `android/app/src/test/java/com/livehub/tv/`；
2. `build.gradle.kts` 的 `dependencies` 加 `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")`（**与 `okhttp:4.12.0` 同版本，别顺手升**）；
3. `cd android && ./gradlew :app:testDebugUnitTest` 先空跑一次，确认测试源集被识别。

第 3 步不做，后面所有"13 条断言全绿"都可能是"根本没跑"——**和恒为 `false` 的降级开关一模一样的失败形态**。

| # | 验收项 | 谁跑 | 照着做 | 通过判据 |
| --- | --- | --- | --- | --- |
| 1 | 没有字符串异常残留 | backend，每次提交前 | ① `cd android && grep -rn "IllegalStateException\|IllegalArgumentException" app/src/main/java/com/livehub/tv/core/`；② `grep -rn "class TarsException" app/src/main/java/com/livehub/tv/core/` | ① 输出**恰好 1 行**，且这一行是 `Sites.kt` 里的 `unknown platform`——**判据里不要写行号**，backend 每改一次文案它就漂一次（写这份表的过程里它已经从 `:49` 漂到 `:80`）。**不是 2 行**——`TarsException` 继承的是 `Exception`，这条命令抓不到它（我第一版写成"2 行"，照着查会误判成没过）；`BilibiliDanmaku.kt:203/240/243` 会被这条抓到，不许加白名单。② 恰好 1 行，且 `TarsException` 只在本文件里 `catch` 后转 `DANMAKU_PROTOCOL`，不出 `core` |
| 2 | HTTP → `kind` 的 13 行 | backend | ① `./gradlew :app:testDebugUnitTest --tests '*NetErrorMapping*'`；② **故意把 SSL 那行改成 `NET_OFFLINE` 再跑一次** | ① 13 条全绿；② 必须红。第二次仍绿说明断言只测了"抛异常"、没测出哪个 `kind`——这类测试比没有更危险 |
| 3 | 文案与类型解耦 | frontend 改、team-lead 验 | ① `ui/strings` 里把 `PARSE_BROKEN` 播放页那句「已开播，但取不到画面（接口可能改版）」改成「测试文案XYZ」；② 在 `DouyinSite.playUrls` 开头临时加一行 `throw SiteException(PARSE_BROKEN, detail="ui-test")`（只为触达这句文案）；③ `./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`，进任意抖音房间；④ 把两处改动都撤回 | 三件事**同时**成立：屏幕上是「测试文案XYZ」；**诊断页仍显示 `PARSE_BROKEN`**；撤回后立刻恢复原句。只看到新文案不算过——那只证明文案来自资源；诊断页码不变才证明没有按字符串反推 |
| 4 | `PARSE_BROKEN` 真的会触发 | backend | 单测：取 `docs/06` 里那份 `getRoomPlayInfo` 原始响应，`jq 'del(.data.playurl_info)'` 裁掉后喂 `BilibiliSite.playUrls` | 抛 `SiteException(kind = PARSE_BROKEN)`；真机上播放页显示产品定稿那句（不是"未开播"） |
| 5 | 三态不混 | team-lead 真机 | a. 开飞行模式 → 进首页；b. 抖音下播房间 `7701710981495720704`（`status:4`，**地址满配**）→ 房间号直达；c. `playUrls` 里临时换一条已知 403 的 CDN 地址 → 播放页按 OK | a → `NET_OFFLINE`「网络未连接」，**不出现"超时"字样**；b → `STREAM_OFFLINE`「主播未开播」，**不出现"正在换线路"循环**（出现就是又犯了 §3.2 那条"只按状态码决定重试"）；c → `SIGN_INVALID`「线路签名失效」。三个码互不相同、无一落 `PARSE_BROKEN` |
| 6 | 重试没被静默关掉 | backend | ① `grep -rn "catch (" app/src/main/java/com/livehub/tv/core/site/`，逐个看有没有 `is SiteException -> throw it`；② MockWebServer 让 `profileRoom` 头两次回 500，数 `Net` 实际请求次数 | 请求数 == **2**（不是 1、不是 3）。这是硬规矩 7 那四处 `catch` 的唯一证据——编译器和单测都不会替你发现这个退化 |
| 7 | `10011` 分得开 | backend 命令行 + team-lead 真机 | ① `curl -s "https://webcast.amemv.com/webcast/room/reflow/info/?type_id=0&live_id=1&room_id=1234567890123456789&app_id=1128" \| jq -r '.status_code'` 应回 `10011`；② App 里房间号输入 `1234567890123456789` | 用户看到「直播间不存在或已结束」；诊断页是 `ROOM_NOT_FOUND` + detail 含 `status_code=10011`；**诊断页不出现最高优先告警**（证明 `report=false` 生效，用户粘错号不会污染 `PARSE_BROKEN` 的计数） |
| 8 | 通道自检按钮三态都可达 | team-lead 真机 | ① 点按钮（常量 `715855351148`）；② 把常量临时改成 `999999999999` 重新装包再点；③ 飞行模式下点 | ① 绿；② 红条 = 「抖音取流通道可能已被关闭」（与 §6.1 同一句）；③ 网络失败。三态都可达，改回常量恢复绿。**只测绿的一态等于没测**——一个永远绿的按钮也能"通过"验收 |
| 9 | 13 个码每个都有文案 | backend 写测试、frontend 提供映射表 | 单测：`SiteError.entries.forEach { assertNotNull(labels[it]) }`，`labels` 就是 `ui/strings` 那张表 | 13 个全中；**再临时加一个假的第 14 个枚举值，测试必须红**。红了才算"枚举驱动"；仍绿说明有人手抄了 13 行 `when`，第 14 个码来的时候会静默走 `else` 兜底——那正是这轮要消灭的东西 |
| 10 | `describe()` 真的死了 | frontend + backend 收尾 | `grep -rn "describe(" app/src/main/java/com/livehub/tv/`；`grep -rn "\.message" app/src/main/java/com/livehub/tv/ui/` | 前者 **0 命中**；后者错误路径 0 命中（日志里可以有）。**这条只能在步 6 之后查，现在查必红**——它是步 6 的收尾条件，不是现在的验收项 |
| 11 | 「进 `reflow` 的一定是 19 位」变成**结构保证** | backend | ① `grep -nE 'length >= 15' .../core/site/DouyinSite.kt` → **0 命中**；② `grep -rn 'fromPage' .../core/site/DouyinSite.kt` → **0 命中**；③ 跑 `android/tools/error-mapping-check/run.sh`，其中抖音的断言必须覆盖**三种形状**：**15 位 → `Net.requestCount` 增加 0**、**4 位 `8888` → 1 次且 url 落在 `live.douyin.com/8888`**、**19 位 → 1 次且 url 含 `reflow/info`** | ①② 都是 0 命中；③ **31 通过 / 0 失败**（2026-10-29 实跑）。**第三条里 4 位那条不能省**：我第一版把判据写成「两处守卫改成 `== 19`」，backend 照着做完，把 4–12 位的 `web_rid` 一起判死了——而它是抖音唯一的常规入口（`capabilities.idInput` 承诺了它，真机 4 位 `8888` 能配出 19 位）。**判据写窄了和判据写错一样危险：它会让人精确地做错事**。断言抓的是「不该发的请求没发、该发的发了」，不是「某一行长得像不像」 |
| 12 | `detail` 回到「诊断细节」语义 | frontend 改、team-lead 真机 | 做完步 6 后：① 抖音输一个不存在的 19 位号 `1234567890123456789`；② 进诊断页看这条失败的 `detail` | 两处**同时**看：屏幕文案来自 `ui/strings`（人话）；`detail` 里是**原始信息**——含 `HTTP` / `status_code=10011` / `body=` 之类，**不含「暂不可用」「超时」这种成品文案**。只查一处会漏：把 `detail` 改成空字符串也能"通过"文案检查，而诊断页从此没有证据 |
| 13 | 文案表只有一张 | frontend | `grep -rln 'SiteError\.' ui/` → 列出的文件**只应出现在 `ui/strings/`**；`grep -rln 'SiteError\.' ui/screens/` → **0 个文件** | 屏幕上同一句文案必须只有一处可改。**这不是整洁问题，是"哪句是现役"的问题**：`PlayerScreen` 现在已有一支内联 `when (e.kind)`（4 句），下一屏再抄一遍之后，改文案的人只会改他看见的那一份，而**两份都读得通**。判据判的是"命中位置"而不是"命中数量"——数量会随码增减，位置不会撒谎 |

**验收脚本自己的六条硬规矩（步 7 起，所有门禁脚本都适用）**

上面每一条都是"判据对不对"，下面这四条是"**判据测的是不是它**"。这一类错比判据写错更坏：判据写错会红，测错对象**永远绿**，而且事后从输出里看不出来。

1. **退出码必须来自被测命令本身。** `cmd | tail -30; echo "EXIT=$?"` 取到的是 `tail` 的状态，恒为 0；`/bin/zsh -c '…; exit 0'` 更彻底——**等于把被测对象排除掉再宣布通过**。管道下用 `${pipestatus[1]}`（zsh）/`PIPESTATUS[0]`（bash），或者干脆不接管道。
   **这不是假想**：同一天在两个人身上各发生了一次——一次在门禁脚本里写成了恒真，一次是 `./gradlew assembleDebug -q 2>&1 | tail -30; echo "EXIT=$?"`，把一次可能失败的构建记成了成功。两次都是"退出码不是被测命令的退出码"。
2. **脚本第一行打印被测对象**：`echo "被测: $app_path"`。因为最常见的失败不是判据写错，是**被测的东西和以为的不一样**——装的是旧 APK、打的是另一个 variant、常量改在了另一个文件里。让它在输出的第一行就可见，比事后回忆便宜一百倍。
3. **最小环境用 `env -i` 重建，不靠"我这儿能跑"。** `PWD`、`ANDROID_HOME`、`JAVA_HOME` 从父 shell 继承来的值，会让脚本在作者机器上绿、在别人机器上红，或者反过来。**「我这儿能跑」是环境证据，不是代码证据**——它只证明那台机器的某个变量还对，不证明代码能跑。
4. **同一个参数走两条路径，判据不许一处严一处松。** 同一个 `app_path` 从"刚 build 出来的"进来和从"别处装的"进来，如果一条断言了 `versionCode`、另一条只看文件存在，失败会永远从松的那条漏过去。**只要允许两处，两处就会不一致，而人会信对自己有利的那一处**——这和"一份结论写两处"是同一个病（见本节开头那段实证）。
5. **`grep` 的方言要当场自证，尤其是「0 命中」这类判据。** 这台机器上的 `grep` 是 **toybox 0.8.13，不是 GNU grep**，它不认 `\|`：`grep -rho 'throw SiteException(\|return SiteException('` 返回 **0**，而真实值是 48。**用 `\|` 写的「0 命中」判据在这台机器上永远绿**——它不报错、不警告，只是永远找不到东西，和规矩 1 的假退出码是同一件事：**判据没有测到被测对象，却宣布通过**。两个动作：① 正则一律写 `-E`；② 每条"0 命中"判据旁边放一条**已知必须命中的对照**（`grep -rc 'class SiteException(' core/` → **1**），跑出 0 就先怀疑工具，别先怀疑代码。
   **这一条是当场栽出来的，不是预防性写的**：核 `docs/06` 时我跑 `grep -n "251\|解析失败\|形状匹配\|betard" docs/06-接口实测记录.md` → 0，据此差点得出「`docs/06` 里没有斗鱼 `betard` 实测」这个结论，而它 §2.4 整节都在（`grep -c '斗鱼'` → 13 行）。**几乎误报的内容是"别人没写"，比误报代码更贵**——它会让两个人在同一件事上各写一遍，然后两份不一样。所以「0 命中」必须先被证明是"真的没有"，而不是"工具没找到"。

6. **验证工具也要被验一次，尤其是它"报告失败"那条路径。** 这不是假想：backend 的哨兵里有一行 `echo "…（exit=$status）"`，**全角 `）` 被 bash 吃进变量名**，展开成 `unbound variable`——而这段只在 `else`（失败）分支里执行，**前三次全绿都走不到它**。结果是：工具在需要喊"红了"的那一刻自己崩掉，失败被报成"脚本挂了"，或者干脆没报。两个动作：① 变量一律写 `${status}`，别让下一个字符有机会被当成名字的一部分；② **故意制造一次红**（把某条断言改成必失败、或临时把期望值改错），确认它能正确报告，再改回来。**「只在失败时才坏」是验证工具最典型的失效模式**，和 `catch` 落空、哨兵软失败、`EXIT=$?` 取错状态是同一族：它们都只在出问题的时候才出问题，所以永远不会被日常使用发现。

#### 六、顺序（有依赖，不能乱序）

| 步 | 改什么 | 依赖 | 谁 |
| --- | --- | --- | --- |
| 0 | 建 `app/src/test` 源集 + 加 `mockwebserver` 依赖（§3.3.1 五的「前置」） | — | backend |
| 1 | `core/model/SiteError.kt`（enum + `SiteException`）——**已完成：13 个码全部落地，`core/` 编译 0 error** | — | backend |
| 2 | `Net` 收口：13 行映射（只做非 2xx 那一层）+ 请求前连通性判定——**已完成**（`Net.httpError()` 是映射主体，`:181` 起那个 `when`；`:146` 是 `NET_OFFLINE`；`Net.ioError()` `:206` 是 IO 四合一） | 1 | backend |
| 3 | `Sites`：`SiteFailure` 带 `kind`——**已完成**；`describe()` **目前还在**（`Sites.kt:128`，带 `TODO(步6)`）。**注意：它没有 ui 调用点**，那 5 处 ui 读的是 `Async.Failed(e.message)` 上的 `SiteException.message`，不是这个 `describe`。所以删它不会打断 build——真正的代价是 `failureOf` 必须给 `SiteFailure.detail` 换个替代值，而唯一现成的替代是 `kind.name`，等于把中文换成英文码。**删除动作因此挂在步 6，理由不是"会打断 build"，是"会泄露英文码"** | 1 | backend |
| 4 | 四家 site：按上表逐个换抛点，**先改硬规矩 7 那四处 `catch`**；抖音另有两件配套——① 入参形状抽成 `douyinIdShape()` 三分类（**19 位直连 reflow / 4–12 位按 `web_rid` 走页面配对 / 其余长度本地抛 `ID_UNRESOLVED` 且不发请求**），数字规则只留一处；② 删掉 `reflow()` 里「是否来自页面配对」那个前缀分叉与 `Resolved.fromPage` 字段（前提由守卫保证之后，它就是第二份真相）。**已完成**（2026-10-29）：31 条离线断言全绿 | 1 | backend |
| 5 | `LivePlayer.readableError` + `DanmakuEvent.Failed` 带 `kind` | 1 | backend |
| 6 | `ui/`：`Async.Failed` 带 `kind`、`ui/strings` 文案表、删 `SearchScreen.kt:547` 那个重复的 `SiteTimeout`；**同一个 PR 里删掉 `core/site/Sites.describe()` 与 `SiteFailure.reason`**（`reason` 是"把错误重新字符串化"的现成后门，它和 `describe()` 必须一起死，分两次删就会有人在新页面里用第二次） | 1、3 | frontend + backend |
| 7 | 诊断页「抖音取流通道自检」按钮（委托 `core/site` 的探针，UI 只显示三态） | 1、2 | frontend + backend |
| 8 | 上表十三个验收项（第 11 条随步 4 验；第 10、12、13 条只能在步 6 之后查，现在查必红） | 2–7 | — |

步 1 是唯一必须先定的；2–6 之间只有"都要有 1"这一个依赖，可以并行。步 0 是为验收服务的，不产出行为变化，但**没有它，第 1/2/4/6 条验收都只是"我觉得应该没问题"**。

**`describe()` 的 shim 有一个明确的死亡条件，不是"以后清理"**：步 6 落地、`ui/` 那 5 处改读 `kind` 之后，`describe()` 必须**在同一个 PR 里删掉**。留着它比没有更糟——它是"按字符串传错误"的现成通道，下一个新写的页面会理所当然地用它，`kind` 就又绕回字符串了。同理，这一步也解释了为什么顺序不能反过来：**跨 `core`/`ui` 的改动必须由边界那一侧的人先落地，另一侧才敢拆旧路**。

**步 6 不是可选的收尾**：`Async.Failed` 还装 `String` 的话，删掉 `describe()` 的那一刻，四家平台的错误在首页会变成 `com.livehub.tv.core.model.SiteException: ...`——功能全对，用户看到的是一行 Java 类名。这一步恰好跨 `core`/`ui` 边界，是本清单里唯一必须两个人对接的地方。

### 3.4 远端可替换点：签名与规则

四家的签名/防盗链算法会变，而每次变都要用户手动更新 APK 是最贵的失败方式。设计上留一个**窄的**可替换点，不搞通用插件框架：

```kotlin
// core/site/Signer.kt
interface Signer {
    val id: String                       // "douyin.a_bogus.v3" / "huya.anticode.v2"
    /** 只产出要追加的 query 参数或 header，不产出 URL。返回空 map 表示本请求不需要签 */
    fun sign(input: SignInput): SignOut
}

data class SignInput(
    val path: String,                    // "/webcast/room/web/enter/"
    val query: Map<String, String>,      // 已排序，签名算法对顺序敏感
    val method: String,
    val body: String?,
    val userAgent: String,
    val cookies: Map<String, String>
)

data class SignOut(val query: Map<String, String>, val headers: Map<String, String>)
```

四个实现，从便宜到贵——**但这张表是 v2 的形状，不是 v1 的代码**：

| 实现 | 形态 | 用在哪 | v1 有吗 |
| --- | --- | --- | --- |
| `NoopSigner` | 空 | 哔哩（`room_init`/`getRoomPlayInfo` 都不签）、虎牙（`wsSecret` 由接口算好，只拼字符串）、抖音（路线① 全免签） | 逻辑上等于"什么都不做"，**不需要类** |
| `KotlinSigner` | 纯 Kotlin（MD5/HMAC/时间戳） | 斗鱼 `hlsH5Preview` 的 `auth = md5(rid + 毫秒时间戳)`，20 行 | **没有单独类，就写在 `DouyuSite` 里**——为一个 20 行的算法造接口是负收益 |
| `JsSigner` | 执行一份 JS（v2：QuickJS；应急：系统 WebView） | 抖音 `a_bogus`/`X-Bogus`（`webmssdk.js` 58 万字节，入口 `get_sign(md5)`），用于**列表/搜索** | **没有**（抖音 v1 免签，见 §4） |
| `RemoteSigner` | 从 `rules/v1/{platform}.json` 拉脚本/参数，sha256 校验，失败回落内置 | 想不发版修签名时 | **没有**（无服务端，ADR-004） |

**所以要说实话：`Signer` 这个抽象在 v1 里没有任何实现。** 四家三家免签、斗鱼一次 md5、抖音取流全免签——v1 用不上它。它出现在这份文档里，只因为它是我判断"**抖音列表/搜索（v2）与将来某家加签时**唯一值得的那一层"，而不是因为现在需要。`core/site/douyin/DouyinSigner.kt` 现在**31 行、纯注释、零类型声明**，就是这个状态最诚实的证据（接口没落地，先写具名类型会和将来的声明重复）。

这条留在这里的用处只有一个：**v2 别重做设计**。判定标准也写清楚——如果 v2 真接上签名时发现"直接把 JS 塞进 `DouyinSite` 更快"，那这个接口就该删掉，别为了保住抽象给项目加一层。

> **一处必须说明的架构妥协（v2 才用得上）**：WebView 版 `JsSigner` 需要 `Context` 与主线程，与"纯函数"约定冲突。处理办法是**接口不变、实现分层**：`Signer` 仍是纯函数，`WebViewSigner` 内部持有一个 `MainThreadExecutor` 适配器（放在 `core/site` 的 android 源集），对外只暴露 `sign(input): SignOut`。**哪天发现 `Context` 顺着 `Signer` 漏进了 `core/model`，说明这层妥协已经失守，立刻改。**

**约束（写死，否则这个口子会失控）**：
- `Signer` 是**纯函数**：不碰网络、不碰文件、不碰 `Context`。它不知道 `Net` 存在。
- 规则包只允许替换 `Signer` 实现与 `Endpoints` 里的域名/路径字符串，**不允许执行任意代码去改 UI 或读本地数据**（无论 JS 跑在 WebView 还是 QuickJS 里，沙箱只暴露 `Date`、`Math`、`String` 和两个字符串入参，页面不挂 `WebViewClient` 之外的任何桥）。
- 规则包有版本号与回滚：新包导致连续 3 次 `SIGN_INVALID` → 自动恢复内置版本并在诊断页标注。
- 首版**不实现** `RemoteSigner`，只留形状（ADR-004）。

### 3.5 接口改版的定位成本

这张表是"架构是否值得"的唯一验收标准：**每种改版应该只需要改一个文件、发不发版要可判断**。

| 改版类型 | 触及文件 | 是否需要发版 | 定位手段 | 预计工时 |
| --- | --- | --- | --- | --- |
| 字段改名 / 嵌套层级变化 | `core/site/XxxSite.kt` 一个文件 | 是（除非已有规则包） | `PARSE_BROKEN` + 诊断页的原始 JSON 片段 | 0.5–2h |
| 新增签名参数 | `core/site/Signer.kt` + 1 个 `XxxSigner` | 是（首版）/ 否（有规则包后） | `SIGN_INVALID` + 抓包对比 | 2h–2d（取决于算法） |
| CDN 域名切换 | `core/site/Endpoints.kt` 一行 | 是 | `HTTP_4XX` 集中在某域名 | 15min |
| 防盗链（Referer/UA/时效） | `XxxSite.playUrls` 返回的 `headers` | 是 | `SIGN_INVALID` 且仅播放失败、列表正常 | 1h |
| 清晰度档位重命名 | `XxxSite` 里档位映射表 | 是 | 用户反馈"没有原画" | 30min |
| **直播状态语义变化**（如 `room.status` 取值改了） | `XxxSite.detail` 的 live 判据（`status == 2`） | 是 | 用户反馈"列表显示在播、点进去没画面"，且 `playUrls` 返回非空 | 1h |
| 接口下线 / 换域名 | `Endpoints.kt` + `XxxSite` | 是 | `HTTP_404` 全量 | 0.5–1d |
| 平台改整套协议（如抖音加签） | `XxxSite` + `Signer` + 可能新增 JS | 是 | `SIGN_INVALID` 全量 + 网页可播 | 2–5d |

**关键冗余设计**：`Endpoints.kt` 里每个常量都带注释 `// 验证日期 2026-10-29 · 来源：APK 抓包 / 网页`。改版时第一件事是**看每个常量最后一次被验证的日期**，而不是从头猜。

> 前提提醒：本表"定位手段"那一列大多靠错误码（`PARSE_BROKEN` / `SIGN_INVALID` / `HTTP_4XX`）在诊断页计数，而**错误码体系还没落地**（见 §3.3 的落地状态）。没落地之前，这张表的工时是"有错误码之后"的工时；现在只能靠用户描述 + 复现。

---

## 四、抖音签名的路线选择

抖音是四家里唯一"取流本身要过签名"的平台，也是唯一可能整块不可用的。先把事实摆出来（全部来自 [`05-逆向分析报告.md`](05-逆向分析报告.md)，不是推断）：

- **签名不是算法，是一段必须由 JS 引擎执行的黑盒。** 参考 APK 打包了 `assets/js/webmssdk.js`（58 万字节，2024-06 版，文件头自述 *extracted from douyin webmssdk.es5.js*）与 `libfastdev_quickjs_runtime.so`，脚本末尾留的入口是：

  ```js
  function get_sign(md5) { return crawler({ 'X-MS-STUB': md5 })['X-Bogus'] }
  ```

- **参考实现用的是 QuickJS，不是 WebView。** 这证明"在 Android 上跑 `webmssdk.js`"这条路走得通，且不必依赖系统 WebView。v1 两条都不做（免签够用），它只是 v2 的技术验证，见下面第 4 条。
- **免签入口在移动端 reflow**：`webcast.amemv.com/webcast/room/reflow/info/?app_id=1128&room_id={19位}`，一个 GET 就拿到 `stream_url`；而 `live.douyin.com/webcast/room/web/enter/`、推荐流、搜索都要 `a_bogus`/`X-Bogus`。

| | ① 免签 mobile reflow 接口 | ② 执行 `webmssdk.js` 算签名 | ③ 服务端代签 |
| --- | --- | --- | --- |
| **做法** | `webcast.amemv.com/.../reflow/info/?app_id=1128&room_id={19位}` | 隐藏 WebView（系统组件）跑脚本取 `X-Bogus`；或 v2 换 QuickJS | 自建服务跑 acrawler / 无头浏览器 |
| **依赖** | 无（纯 HTTP，一个 GET） | 系统 WebView（0 体积，但版本不可控）；或 58 万字节 JS + QuickJS so（约 3–6MB） | 服务器 + 运维 + 持续维护 |
| **能覆盖** | 已知房间的详情 + 取流（**实测 8 条线路全可用**） | 全部（含 `web/enter`、推荐流、搜索） | 全部 |
| **失效风险** | **中**：接口多年未改，但覆盖面窄（只认已知房间：19 位 room_id 或 `web_rid`） | WebView 中（跟官方脚本同源，运行时不可控）；QuickJS 中低 | 低（改一处），但风险集中到自己身上 |
| **跨端复用（tvOS）** | **直接复用**（v1 无签名器，tvOS 拿得走） | WebView **不可复用**（tvOS 没有）；QuickJS 是 C 库，tvOS 可编 | 直接复用（但 tvOS 也要连我们的服务） |
| **合规观感** | 与其它三家一致（都是读公开页面接口） | 在本地执行平台的 JS，行为上仍是"客户端" | 最差：从"客户端取流"变成"绕过平台风控的服务" |
| **工期** | 已做完（含 `web_rid` 换算） | WebView 1d；QuickJS 3–5d（含 so 集成、ABI 分包、沙箱） | 5d+ 且长期运维 |
| **我们的选择** | ✅ **v1 唯一路径** | ⚠️ 应急（v1 不写代码）+ v2（目标是列表/搜索） | ❌ 不做（ADR-004） |

**决策（已定，按真拉流实测收窄过一次）：v1 只走 ①，全免签，不写任何签名器。WebView 兜底降为"应急方案、v1 不写代码"；QuickJS 排 v2，且 v2 的目标是列表/搜索，不是兜底。**

实测依据（2026-10-29，`06-接口实测记录.md`）：`web_rid=715855351148 → room_id=7701975308739611407`，`detail` 拿到 title / host / `live=true` / viewers，`playUrls` 出 8 条（4 FLV + 4 HLS），其中 FLV 3/4 返回 200 `video/x-flv`（`464c5601` 帧头正常）、HLS 4/4 返回 206 `application/vnd.apple.mpegurl`。**换句话说：详情和取流都不需要签，之前以为"抖音必须跑 JS"，是把"列表/搜索要签"错记成了"取流要签"。**

理由：

1. **① 的覆盖面对这个产品足够。** 我们不要抖音的推荐流（首页是四家聚合，抖音缺席就少一行），也要不了它的搜索（同样要签）。我们要的只有：**知道房间时能进去、能拉流、能连弹幕**。①全给了。
2. **没有签名器，就没有 WebView 依赖，也就没有那条"老盒子 WebView 停在 83"的雷。** 这条原本是 v1 最大的隐患（Android 9 的 S905 盒子无 GMS 时 WebView 无法更新），现在整条消失——**不是我们解决了它，而是不再踩上去**。同时 §5.1 那行从"⚠️ 要等 v2"变成"✅ 直接复用"：tvOS 复用抖音协议不再有前置条件。
3. **代价换了一条，而且更硬**：**① 一旦线上被关，v1 没有任何兜底**——原来"WebView 一晚接上"的退路没了，只能发版改走 v2。接受它的前提是 §4 的四条触发信号真有监控（`reflow` 返回 200 但 `stream_url` 空、出现风控提示），否则我们是"撤了降落伞，又没装高度表"。**这是这笔交易最该被记住的一句。**
4. **③ 仍然不做。** 对不上架、无账号、用户自行安装的工具是过度设计：多一台要运维的机器、多一个被投诉就全体失效的单点，还把性质从"客户端"变成"代取流服务"。触发条件写清：只有真做"关注列表云同步"、已经有服务端时，才顺带评估 ③。

**④ 应急方案：WebView 跑 `webmssdk.js`（v1 不写代码，写清楚是为了真出事时不用重新调研）**
系统 WebView 载入 `webmssdk.js` 取 `X-Bogus`，零体积、零 so、不用分 ABI，一晚能跑通。它是"①被关且来不及做 v2"时唯一的过桥手段。**它不进 v1 交付范围**，理由：为一堵还没出现的墙先砌门，是拿可维护性赌一个概率。

**从 ① 掉下来时怎么办（任一信号即启动 v2，顺序不要搞反）**：

| 信号 | 含义 | 先做什么 |
| --- | --- | --- |
| `reflow/info` 返回 200 但 `stream_url` 为空；或返回风控提示 | **① 被关**（最严重） | 立刻上应急 WebView 撑一个补丁版，同时开 v2 |
| 诊断页抖音 `PARSE_BROKEN` 持续升高 | 页面结构或字段改了 | 先修 §4.1 的配对正则（1 小时内），不是签名问题 |
| WebView 版本 < 90 的抖音用户 > 20% | 应急方案的覆盖面不足 | 应急方案对这批机器无效 → 直接 v2 |
| 开始做 tvOS 版 | 需要列表/搜索、或需要与 Android 同一套签名 | v2 的 QuickJS（C 库，tvOS 可编） |

**v1 就要留好的东西（前两条是"形状"，第三条已落地）**：

- `core/site/douyin/DouyinSigner.kt` 已建：**31 行、纯注释、零类型声明**。注释里写了形状、注入点（`get_sign(md5) → X-Bogus`）、WebView 的 `MainThreadExecutor` 折衷、"`Context` 绝不许漏进 `core/model`"的验收口径。**先写具名类型会和将来的 `Signer` 声明重复（Kotlin 同包同名直接编译失败）**，所以只写注释是对的。
- `Signer` 契约（§3.4）**v1 不落代码**——四家都不需要。等 v2 真要签的时候再定义，那时才知道形状对不对。
- ✅ `capabilities` **已落地**（`core/site/SiteCapabilities.kt` + `LiveSite` 默认实现）：抖音是 `recommend/categories/search/danmaku = false`、`idInput = RoomIdOrWebRidOrLink`，其余三家全走默认 `true`。UI 据此把入口换成"粘链接/输房间号"、隐藏弹幕按钮——v2 接上后只需把这几个布尔改 `true`，并删掉一条文案。

### 4.1 输入形态（已定，按实测校正过两次）

`reflow/info` 这个免签接口只吃 **19 位内部 `room_id`**。用户手上通常不是这个值，所以中间需要一步归一化——**这一步是免签的，不需要 JS**：

| 用户给的 | 归一化怎么做（全在 `DouyinSite` 内） | 状态 |
| --- | --- | --- |
| 19 位纯数字 | 直接透传给 `reflow` | ✅ 可用 |
| **13–18 位纯数字**（既不是 `room_id` 也不是 `web_rid`） | **本地抛 `ID_UNRESOLVED`，一个请求都不发**。这是「少打/多打了几位」，不是「房间没了」 | ❌ 拒绝，但用户能看懂 |
| **<4 位 / >19 位纯数字** | 同上 | ❌ 拒绝 |
| **`web_rid`**（**4–12 位**数字，就是 `live.douyin.com/{web_rid}` 里那串；实测 4 位 `8888` 可用，所以下限取 4 不取 6） | `GET live.douyin.com/{web_rid}` 取页面 HTML，只认**两种相邻顺序**的配对：`"roomId":"19位","web_rid":"这个号"` 或反序 `"web_rid":"这个号","roomId":"19位"` | ✅ 可用 |
| 整条 `live.douyin.com/{web_rid}`（带不带 scheme、带 `?enter_from=…` 都算） | 先 `substringAfterLast('/')` 再 `substringBefore('?')`，剩下同上 | ✅ 可用（四种形态实测命中同一房间） |
| `v.douyin.com/{code}` 短链 | 跟随 302 后用**同一套严格配对**；配不上就报错，**绝不"页面里随便找个 19 位"** | ⚠️ 能识别、失败可诊断，**不承诺可用** |
| 抖音号（`xiaojiuguan` 这类字符串） | v1 不做：反查要么签名、要么页面里不一定带 | ❌ 不支持 |

**短链的实测结论（2026-10-29，本节最重要的一段）**：有效短链没拿到（它由分享动作生成，无法自造）；但**失效短链的行为测到了**——`v.douyin.com/{code}` → 302 → `https://www.douyin.com/`，落地页 **HTTP 200**，而 `"roomId"` 与 `"web_rid"` 各出现 **0 次**。

这是最坏的失败形态：**跟完跳转是个正常首页**。如果按"页面里找第一个 19 位数字"写兜底，就会在首页/分区页上抓到一个**别人的、看起来合法的房间**——用户莫名进到不相关的直播间，而我们收不到任何报错。所以规矩定死：

1. 短链跳转后**必须恰好命中一个** `"roomId…","web_rid…"` 严格配对；
2. 配不上就抛「`抖音：<链接> 跳转后不是直播间页面（短链可能已失效），请改用 live.douyin.com/{房间号}`」；
3. **产品文案保留**「复制 `live.douyin.com/数字` 更稳」——它不再是临时道歉，而是对当前能力的准确描述。

> **为什么只认"相邻"，连"往前找最近的 `roomId`"都不要**（这条反驳过一次，留档别改回去）：一个直播间页实测有 **78 个 19 位数字**，其中 **77 个不是 room_id**（推荐位、作品、专题卡）。"往前找最近的"没有任何保证是这一个房间的，**配错的后果不是报错，是给用户放另一个人的直播且一声不响——比失败更糟**。这套写法还有第二个问题：在 879KB 的页面上它会卡死（实测 >12s 无返回、进程被 SIGTERM 杀掉 `exit 137`），所以它不是"慢一点"，是不可用。两种相邻顺序都不中 → 按"页面结构变了"处理，宁可报错。
>
> 生产实现（`DouyinSite.resolveRoomId`）与哨兵脚本（`probe-sites.sh`）必须用**同一套判据**，否则脚本会假绿：脚本说"抖音还好"，App 却配不上。

**探针是"测量"，`10011` 的判据是"推断"，两者不能互相替代（2026-10-29 定案）。** 这一节和 §3.3.1 那个「抖音取流通道自检」按钮共用一个 `web_rid`，正因为它们回答的是两个不同的问题：

| 问题 | 谁回答 | 性质 | 不能省的理由 |
| --- | --- | --- | --- |
| 这一条链接/这个号为什么进不去 | `SiteException.kind` + `detail` | **推断**：由码映射和代码结构定，必须在本地即时给出，不能等网络 | 用户在看屏幕，等不了我们跑脚本 |
| 抖音这条免签通道整体还活着吗 | 哨兵 `probe-sites.sh` + 诊断页自检按钮 | **测量**：真打一次已知在播的房间，看回什么 | 码分布区分不了"通道关了"和"今天用户粘错了很多号" |

所以：**`10011` 计数升高不等于通道挂了，通道挂了也不一定表现为 `10011` 计数升高**。把后者塞进前者，就是用不可靠信号反推类型——那件事我们在这轮已经犯了两次（见 §3.3.1 开头）。探针存在的全部意义，是让"通道还在不在"这个问题**有一份独立于码映射的证据**；它也因此必须和哨兵用同一个常量，否则两份证据会各说各话。

**所以 v1 的输入形态是「19 位 room_id / web_rid / `live.douyin.com` 链接」，短链是"尽力而为"的第四种。** 这个差别不是措辞：`web_rid` 是用户能从分享文案里直接看到的 4–12 位数字，比 19 位内部 ID 好拿得多；把它算进来，等于把 v1 抖音从"几乎没人进得去"提到"照抄就能进"。

| 影响 | 处理 |
| --- | --- |
| 输入框 | `capabilities.idInput = RoomIdOrWebRidOrLink`；占位写"房间号或直播页链接"，**不写"19 位"**（用户不会去数位数） |
| UI 不做解析 | 把用户原样输入透传给 `detail()` / `playUrls()`。**UI 里不要再写一遍"19 位数字"的正则**——两套解析规则一定漂移，而且平台改协议时只改一处才叫分层 |
| 搜索页 | 抖音不参与关键词搜索（`capabilities.search = false`）。空结果文案按平台分：其它三家「未找到」，含抖音「抖音请粘贴直播页链接或房间号」，不说「未找到」 |
| 展示 ≠ 寻址 | 卡片与播放页**继续显示"抖音号"是对的**（显示的是数据），错的只是"用抖音号寻址"这个能力。别把展示一起改掉 |
| 设计文档 | 已按此改：`02-设计规范.md` §1（抖音行）、§8.3 末尾（v1 约束） |

**这条路的唯一脆弱点**：抖音直播间页的 JSON 结构。它一变，`resolveRoomId` 就会抛"没有从 X 的页面里解析到房间号"——这句文案本身就是诊断信息，但**别指望用户区分"房间注销"和"页面改版"**：那要靠 §6.2 的哨兵脚本在发版前发现，不是靠线上用户猜。

> **错误码归属别搞错**（§3.3）：短链落到首页、位数不对、页面里配不出 roomId 分属两个码——**短链/位数不对 → `ID_UNRESOLVED`**（用户给错了，`report=false`）；**页面里一个配对都没有 → `PARSE_BROKEN`**（我们坏了，最高优先上报）。"房间号合法但平台说没有"才是 `ROOM_NOT_FOUND`。三者症状都是"进不去"，但一个该改文案、一个该连夜修代码。混成一个码，诊断页就会在用户粘错链接时告诉我们"接口挂了"。

### 4.2 抖音 v2 路线（已定案，写清是为了 v2 不重做调研）

**v2 的目标是"列表 + 搜索 + 分区"，不是"让取流能跑"**——取流 v1 已经通了（§4）。这一点必须先说清，否则 v2 会误以为自己在救火，然后为了"顺手兜底"多引一个运行时。

这一节只记录**已经验证过的事实**，不做调研，v2 直接照做。

| 项 | 内容 | 来源 |
| --- | --- | --- |
| 脚本 | `assets/js/webmssdk.js`，约 58 万字节（2024-06 版，文件头自述 *extracted from douyin webmssdk.es5.js*） | 参考 APK 文件清单 |
| 执行入口 | `function get_sign(md5) { return crawler({ 'X-MS-STUB': md5 })['X-Bogus'] }` | 脚本末尾 |
| 入参 | 待签串（请求 query 规范化后）的 MD5 | 同上 |
| 出参 / 注入点 | `X-Bogus` 字符串 → 作为 **query 参数**追加（web 端 `a_bogus` 在同一位置；少数接口也接受同名 header，以抓包为准） | 同上 + 抓包 |
| 引擎（v2） | QuickJS，思路同参考 APK 的 `libfastdev_quickjs_runtime.so`；沙箱只暴露 `Date`/`Math`/`String` | 参考 APK |
| 需要签的接口 | `live.douyin.com/webcast/room/web/enter/`、`/webcast/web/partition/detail/room/`（推荐流）、`/aweme/v1/web/live/search/`（搜索） | 逆向报告 §三 |
| 工期 | 3–5d（含 so 集成、ABI 分包、沙箱、调试） | 估算 |
| 验收 | 抖音搜索与推荐能出真实结果；`capabilities.recommend/search` 改 `true`；诊断页无 `SIGN_INVALID` | — |
| v2 不许动的东西 | `Signer` 接口签名（§3.4）、`LiveSite` 六个方法、`PlayUrl` 结构、UI 的"该平台不支持"分支形态 | 本文 C1/C7 |


---

## 五、Apple TV 版的前向兼容边界

用户明确说 tvOS 版在后面。这一节的用处只有一个：**今天写的代码，将来不要重写第二遍**。协议逆向是这个项目最贵的部分，重做一次等于项目重启。

### 5.1 可复用 / 必须重写

| 资产 | tvOS | 说明 |
| --- | --- | --- |
| 接口协议知识（URL、参数、Header、Cookie 规则、签名算法） | ✅ **复用** | 前提是它们活在 `core/site` 而不是散在 UI 里。**这是本文最重要的一条约束**（C1/C3） |
| 数据模型（`Room`/`PlayUrl`/`RoomDetail`/`LiveCategory`/`SiteError`） | ✅ 复用 | 纯 Kotlin data class → 将来抽 Kotlin Multiplatform shared 模块，Swift 直接调 |
| 弹幕协议封装（建连、心跳、解包、事件归一） | ✅ 复用 | 有四家的手写二进制/协议实现，是最不该重做的部分。`DanmakuEvent` 里不能有 Android 类型（C6） |
| 抖音取流（免签 `reflow` + `web_rid` 页面配对） | ✅ **复用** | v1 没有签名器、没有 WebView，tvOS 拿得走同一套 URL、正则与解析（§4）。**这是"v1 全免签"带来的最大一笔跨端收益**，比省一天工期值钱 |
| 签名器 / JS 引擎 | ⚠️ v1 没有，v2 才有 | v2 若上 QuickJS（C 库）tvOS 可编；WebView 版在 tvOS 不存在。**v1 的抖音不依赖它，所以"tvOS 能播抖音"不必等 v2**——只有列表/搜索要等 |
| 设计令牌（颜色、字号、间距、时长） | ✅ 复用 | 数值就是数值。但**令牌的载体要能导出**：`Tokens.kt` 保持纯 `Long`/`Int`，不要在 core 里出现 `Color`/`Dp` |
| 错误码与人话文案的映射表 | ✅ 复用（重写资源文件） | 枚举是 code，文案是 `strings.xml` → `Localizable.strings` |
| **UI 层（六屏）** | ❌ **重写** | Compose → SwiftUI；焦点引擎是两套完全不同的模型（Compose TV 的 `focusRestorer` vs tvOS 的 `focusSection`/`prefersDefaultFocus`） |
| **播放器** | ❌ **重写** | Media3 → `AVPlayer`。AVPlayer 不接受自定义 DataSource 之外的东西，FLV 完全不支持 |
| **存储** | ❌ **重写** | `SharedPreferences` → `UserDefaults`。但**JSON 形状必须一致**：关注/历史在两个端之间能互相读懂是加分项，也方便排障 |
| 焦点/滚动/键位逻辑 | ❌ 重写 | 设计规范里的键位是行为契约，实现是各自平台的 |
| 崩溃/诊断页 | ❌ 重写 | 版权页文案可复用 |

### 5.2 今天就要守住的九条约束（可验收）

每一条都配一个"怎么查"，不写不可验收的愿望。

| # | 约束 | 为什么 | 怎么查（CI 可跑） |
| --- | --- | --- | --- |
| **C1** | `core/model`、`core/site`、`core/danmaku` 不得出现 `import android.` / `import androidx.` | 协议层一旦沾上 Android，M(K)MP 抽不出来，tvOS 必然重写 | `ArchGuardTest`：扫这三个包下所有 `.kt` 文件 |
| **C2** | 模型层只用 `data class` + 基本类型（`String`/`Long`/`Int`/`Boolean`/`List`/`Map`），不用注解、`Parcelable`、`org.json`、`Color`、`Dp`、`Instant` | 注解与平台类型会让 KMP 抽取变成改写 | 同上（正则禁 `@Serializable`/`Parcelable`/`java.time`/`androidx.compose.ui.graphics`/`unit.Dp`） |
| **C3** | **接口文档与代码同源**：`docs/protocol/{platform}.md` 里每条接口的路径，必须与 `Endpoints.kt` 的常量逐字一致，且带"验证日期/来源" | 防止代码改了文档没改，将来 tvOS 照着错的文档重做一遍 | `EndpointsDocTest`：解析 `Endpoints.kt` 常量 + 解析 md 代码块，做集合相等断言 |
| **C4** | 错误分类是枚举，文案在资源层；一个错误码一行文案，不许有第二个 `when` 分支去猜 | tvOS 侧只换资源文件，不改逻辑 | Code review + 单测：`SiteError.entries` 全部在文案表里有 key |
| **C5** | `PlayUrl` 必须带 `format`；四家的 `playUrls()` **必须都含至少一条 HLS**（抖音可以排在 FLV 之后，但不许没有）；除抖音外 HLS 的 index 必须小于任何 FLV；`capabilities.hls == false` 的平台在 tvOS 侧标"不可用"而不是崩溃 | AVPlayer 不播 FLV（§5.3），Android 端不舒服一次，换 tvOS 端不用改协议。抖音是唯一例外，理由见 ADR-007 | 单测：① 四家返回列表里 `any { it.format == HLS }` 必须为 true；② 非抖音家 `firstHlsIndex < firstFlvIndex`；③ 抖音的列表里 HLS 仍存在（只是靠后） |
| **C6** | `DanmakuEvent` 不含 UI 概念：颜色是 `Long` ARGB，位置/轨道由渲染层决定 | 弹幕协议是复用重点，事件模型一脏就白复用 | 同上（正则 + review） |
| **C7** | `Signer` 是纯函数接口，入参出参只有 `String`/`Map`/`Boolean`，不出现 OkHttp/`Context` | 签名算法要能在 tvOS 侧用同一份 JS | 接口签名 review（它只有 5 个字段，改起来显眼） |
| **C8** | `roomId` 永远 `String`；时间永远 `Long` epoch 秒；不用 `Number` 转换、不用 `java.time` | 哔哩 8–10 位、斗鱼 4–8 位、抖音是字母串；`java.time` 在 tvOS 不存在 | 已有约定（`Models.kt` 注释），加一条单测：把四家的真实 ID 形态都过一遍 `Room` 构造 |
| **C9** | 存储的 JSON 字段名是契约：`platform`/`roomId`/`title`/`host`/`cover`/`area`/`viewers`/`live`，两端一致；改动需同改两端 | 关注列表跨端可读是低成本加分项 | 黄金样例测试：`golden/follows.json` 解析后重新序列化必须字节等价 |

### 5.3 tvOS 的硬约束（现在就影响 Android 的实现细节）

| 约束 | 对 Android 端的影响 |
| --- | --- |
| **AVPlayer 不支持 FLV**，只吃 HLS（和部分 MP4） | Android 端必须让四家的 HLS 线路保持"被真实使用"，否则等做 tvOS 才发现坏了（§3.1 硬顺序第 1 条） |
| 无 `WebView` 等价物（`WKWebView` 在 tvOS 上不能用于执行平台 JS） | **v1 不受影响**（抖音取流全免签，§4）。受影响的是**列表/搜索**——它们要签名，tvOS 上只能等 v2 的 QuickJS。所以"tvOS 版抖音只有详情+播放、没有列表"是一个可接受的中间态 |
| 无 `SharedPreferences`、无 `OkHttp` 的 `CookieJar`（`HTTPCookieStorage` 行为不同） | Cookie 管理要能被替换：`Net.cookieHeader(host)` 收在 `core/net`，不在 `core/site` 里直接读 `Cookie` 对象 |
| tvOS 上**没有"返回键"**（Menu 键 ≠ Back） | 设计规范里的"返回键即上一级"在 tvOS 侧要重新映射；不影响 Android 代码，但要提前知道"键位契约"不是跨端的 |
| 无侧载之外的渠道，同样不能上架 App Store 做聚合类内容 | 分发策略一致（自己的渠道），不是技术差异 |

---

## 六、风险与验证计划

### 6.1 五条最大风险

| # | 风险 | 触发信号（可观测） | 影响面 | 应对 |
| --- | --- | --- | --- | --- |
| **1** | **四家接口失效**（字段改名/接口下线/加签） | 诊断页 `PARSE_BROKEN` 或 `HTTP_404` 在单一平台连续出现；用户反馈"某一家的卡片全空" | 该平台不可用（25% 功能） | 分平台隔离（`LiveSite` 一平台一文件）；首页聚合容忍单平台缺席；`Endpoints.kt` 带验证日期便于逐条复核；发布节奏留"接口热修"通道（不改 UI 的补丁版） |
| **2** | **抖音免签接口（`reflow`）关停或加签** | `reflow/info` 返回 200 但 `room` 字段消失或 `status_code` 非 0；`stream_url` 为空（**注意下播房间的 `stream_url` 是满配的，空才是异常**）；网页能播、客户端不能播；出现风控提示 | 抖音整块不可用 | **v1 只有一层：① 免签 reflow（实测 detail+playUrls 全通，8 条线路）。这是一笔明账：我们撤了降落伞。** 真出事时按顺序：应急 WebView 补丁版（1 天，非交付项）→ 根治走 v2 QuickJS（3–5d，参考 APK 已证明可行）。所以 §6.2 的哨兵脚本对抖音必须**硬失败**（两项，含页面配对照 19 位），并且要能分清"接口变了"与"房间下播"——否则我们会是最后一个知道的。预期管理：`capabilities.recommend/search=false`，抖音只有"粘直播页链接 / 输房间号"一条路；**风险接受度：抖音是四家里唯一"允许整体失效"的平台**——它没有 TV 端、接口最严，用户对"抖音在电视上不完整"有预期 |
| **3** | **CDN 防盗链**（Referer/UA/时效 `wsSecret`） | 列表正常、播放 403；只有部分房间失败；刷新后又能播几秒 | 播放失败率高，用户第一印象是"这 App 不能看" | `PlayUrl.headers` 强制带上 Referer/UA（player 不许自己猜）；首帧 3s 提示 / 4s 换线路（§3.2）；`SIGN_INVALID` 单独计数并可上报；**虎牙目前不用自己算 `wsSecret`，若接口收回这个便利，才需要补一个 `KotlinSigner`** |
| **4** | **低端盒子解码失败**（HEVC 无硬解、黑屏有声、花屏） | `DECODE_UNSUPPORTED`；用户反馈"有声音没画面"；首帧后立即 `Failed` | A 档（1GB 盒子）用户流失 | 启动时 `MediaCodecList` 探测并缓存结果（`DeviceProfile`）；**能力探测失败即请求 H.264 线路**；设置里给"强制 H.264 / 软解"两个开关；不集成 FFmpeg 的代价就写在这里（§0.1 判断 3） |
| **5** | **合规与牌照** | 被平台发函、被应用商店/分发渠道下架、被举报 | 产品不能分发，最坏项目终止 | 不做电视频道（设计强约束）；不预置任何源（只走平台自己的公开接口）；"关于"页写明内容来源与"仅供学习交流"；不出账号体系、不碰支付；只走侧载渠道，不做商店投放 |

**第 6 条（附加，不进前五但要有预案）**：**弹幕协议失效**。四家的协议难度差别很大（细节与依据见 [`05-逆向分析报告.md`](05-逆向分析报告.md) §四）：哔哩 WSS + 16 字节头 + JSON（`protover=2` 时 zlib）、斗鱼 WSS:8506 + STT 二进制（`[4B len][4B len][4B 689][key@=value/…]`）、虎牙 WSS + **Tars**（命令 16 注册组 / 20 心跳 / push type 7/22）、抖音 WSS + **protobuf** + 签名。这类协议改版比取流频繁，但影响可降级——弹幕挂了画面还在。所以 `core/danmaku` 与 `player` 之间只有 `Flow<DanmakuEvent>`，**弹幕失败永远不阻塞播放**；虎牙 Tars 字段表未完全确认时，降级为"连接成功但不显示内容 + 一条系统提示"，不许崩。

**抖音这一条要单独立规矩，因为它没有后端可以兜（写在风险表之外，免得被当成普通一行）**：

> **如果抖音哪天关掉 `reflow` 的免签通道，补救路径是发一个新版本，不是远端开关。** 本产品没有后端，任何"服务端下发开关/规则包"的设计在这里都是纸上谈兵——**别在架构里给自己留一个不存在的控制面**。

同理，这条风险的处理不能只是"写在文档里"。它的**唯一替代品是早期预警**，所以：

- **哨兵红了 = 抖音取流通道可能已被关闭。这是本产品的单点依赖**，不是"某家接口抖了一下"。
- 哨兵是我们跑，用户跑不了。**App 里有一个等价测量**：诊断页的「抖音取流通道自检」按钮，用与哨兵同一个 `web_rid` 常量打一次 `reflow`，红条就照 §6.1 的措辞说"通道可能已被关闭"（§3.3.1 三）。用户报"抖音一直打不开"时，这一步比让他描述症状有用——而且它能区分"这个号没了"和"通道关了"，这两件事的处置完全不同。
- 也因此，`android/tools/probe-sites.sh` 里抖音那两项**都是硬断言、无条件计入退出码**（`SOFT_OK` 开关已废弃：代码留着，但**没有任何一项在用**，注释写明只为临时排查某个已知会抖的接口）。软失败的哨兵对单点依赖等于没有哨兵。
- 但硬失败不等于"下播也报警"——那是狼来了，报三次就没人看。判定要**三态**：`ok`（断言全中）/ `skip`（HTTP 成功、结构正确，只是房间当前不在直播，打印原因、不计通过也不计失败）/ `fail`（`status_code` 非 0、结构缺失、或非 2xx）。**中间不许有第四态"含糊通过"**："断言没命中且不知道为什么"必须归 `fail`。当前实跑 `skip = 0`，不是这一态没实现，而是抖音那两项被设计成"下播也不进 skip"（判据选择见 §6.2）；留着第三态是给将来"下播就分不清好坏"的接口用的。**措辞也要紧**：脚本汇总行现在把这一栏印成「软失败」，应改成「跳过」——「软失败」读起来正是"失败了但放过"，也就是上面被禁的那一态，同一份输出里印着这个词，规矩就白写了。

### 6.2 验证计划

| 验证项 | 方法 | 通过门槛 | 频率 |
| --- | --- | --- | --- |
| **接口哨兵** | **本地脚本** `android/tools/probe-sites.sh`：curl 打四家接口，输出 `平台 接口 状态码 判定 耗时 响应片段`，断言"HTTP 200 **且**响应里出现只有成功才有的字段"（只看 200 不够：哔哩风控回 200+`{"code":-352}`，抖音参数错回 200+`Request params error`）。**不进 CI、不做定时**，手工在 `tv-verify.sh` 之前跑一次。抖音走两步：页面严格配对出 19 位 `room_id` → 再打 `reflow/info`，两步都是硬断言 | 四家全部落在 `ok` 或 `skip`；**`skip` 必须单列并打印原因**（房间下播 ≠ 接口坏）；任何 `fail` 立即非零退出，并指到具体平台与接口。**2026-10-29 实跑：通过 12 / 失败 0 / 跳过 0，`EXIT=0`** | 每次改 `core/site` 后 + 发版前 |
| **首帧时延** | A 档真机（1GB 盒子）+ 4 平台各 10 次冷启播放 | P50 ≤ 3s，P90 ≤ 6s | 每个发版前 |
| **长播稳定性** | A 档真机连续播放 3 小时（弹幕开） | 无崩溃、无 OOM、内存无明显爬升（末半小时与首半小时差值 ≤ 20MB） | 每个发版前 |
| **解码矩阵** | 4 档设备 × 四家最高清晰度，记录"是否黑屏/花屏/有声无画" | 全部可播（不支持时降级到 H.264 后仍可播） | 每个发版前 |
| **真机版本矩阵** | A（API 22）/ B（28）/ C（31）/ D（34）+ TV AVD（API 36 兼容性） | 无崩溃、焦点 500 次不困住（沿用设计期的自测口径） | 每个发版前 |
| **达标线** | 冷启动 ≤ 3.5s（A 档出首屏）；APK ≤ 30MB（universal）；内存峰值 ≤ 280MB（1GB 机） | 三项全过 | 每个发版前 |
| **故障演练** | 拔网/限速 200kbps/DNS 污染各一次，走完"首页 → 播放 → 换台" | 每种故障都在 2s 内给出人话提示，且不显示假卡片 | 每季度 |

> **抖音那两项为什么断言 `"status_code":0` + `"room":`，而不是 `stream_url`（2026-10-29 定下，别顺手改回去）**：固定房间 `517422039354` 能稳定配对出 `7701710981495720704`，但该房间**已下播**——`reflow` 回 `status:4`，`room` 对象仍在。候选判据只有三条，逐条看过：
>
> | 断言 | 在播房间 | 下播房间 | 能当判据吗 |
> | --- | --- | --- | --- |
> | `stream_url` 非空 | 4 FLV + 4 HLS | **同样 4 + 4，一条不少** | **不能**。下播房间的地址是满配的，只是 FLV 拉起来 404 空 body、HLS 超时。有 `stream_url` 只证明"接口活着"，不证明"有画面" |
> | `stream_url` 非空 **+ 长期在播的大房间** | ok | 主播换班、比赛结束、被下播就红 | **不能**。三个月后没人分得清"红了是抖音改了接口"还是"那个主播今天没开"，**这就是假闹钟，而假闹钟最后一定被无视** |
> | `"status_code":0` + `"room":` 存在 | ok | ok（结构完整、语义正确） | **能**。接口真挂（`Request params error` / `status_code` 非 0 / 缺 `room`）照样红 |
>
> 一句话记住：**`stream_url` 存在与否跟房间在不在播完全无关，唯一判据是 `status`（2 在播 / 4 下播）。** 判空永远判不出下播——下播房间给的是满配地址。
>
> 选了最后一行。它**没有漏掉任何一种红法**——"页面配对不上"与"`reflow` 变了"是两条互相独立的红（脚本里前一步失败会直接把第二步也记 `FAIL`）。
>
> 代价写在明处：**若 `reflow` 改成"下播房间不再返回 `room`"，这一项会以 `fail` 形式红。** 那不是误报，是真变化——按 §4 的触发表处理。

**抖音那一项现在自带分辨器**：主房间配不上时**再用备用 `web_rid` 配一次**，一次额外请求就能把两种原因分开——页面结构变了则两个都配不上，只是这个房间没了则备用号会成功。而且主号失败、备用号成功时，**`reflow` 那一行继续用备用号跑**，不直接跳过：这样一眼能看出"通道活着、只是这个房间没了"，不用再看第二遍。

| 路径（`DOUYIN_WEBRID` / `DOUYIN_WEBRID_ALT` 都从 env 读，可复现） | 输出 | 退出码 | 读法 |
| --- | --- | --- | --- |
| 默认 | `页面配对 ok` + `reflow ok`，通过 2 / 失败 0 | 0 | 通道正常 |
| 主号设成不存在的号 | `999999999999 已不在页面上（换一个 DOUYIN_WEBRID）；本次用备用 715855351148 验下一步`；`reflow` 仍 `ok` | 1 | **通道活着，只是这个房间没了** → 换房间号 |
| 主号、备用号都设成不存在的号 | `两个房间都配不上：抖音页面结构变了，免签换算通道可能已关`；`reflow` 行 `未执行` | 1 | **我们坏了** → 按 §4 触发表处理 |

两条待记住的实现细节：① 判定只有 `ok` / `跳过` / `FAIL` 三态；**「跳过」现在有了第一个、也是唯一一个使用者**——斗鱼取流那一项（`ALLOW_OFFLINE_SKIP=1`，条件是响应里**明确写着** `{"error":104,"msg":"房间未开播"}`）。之前这里写的是「`SKIP_OK` 开关存在但没有任何一项在用、汇总行印本版无跳过项」，这句话在斗鱼固定房间 `288016` 下播之后就不成立了：哨兵每天红一次，红久了没人看，**退出码从此失去意义**；② 抖音的配对判据在**生产（`resolveRoomId`）与脚本里是同一套**，只认相邻的两种顺序，否则脚本会假绿（§4.1）。

**「跳过」这个态一旦引入，闸门比它本身重要**：跳过 = 哨兵主动放弃这次判定，而**放弃判定和通过判定在退出码上是同一个数**。准入必须满足两条，缺一不可——① 跳过的条件能**在响应里指认**（斗鱼是 `error:104` 或「房间未开播」字样，不是"断言没命中但看着像临时问题"）；② 跳过必须**打在汇总行上**（现在是 `11 通过 / 0 失败 / 1 跳过`），不能静默吞掉。

> 反向风险也写在这里：`ALLOW_OFFLINE_SKIP` 的判据是**平台自己说的那句话**。哪天下播房间的响应变体多起来（只给 `error:104` 不给 msg、或换了码），这一项会从「跳过」掉回 `FAIL`——**那时别急着放宽条件**，先问一句：放宽之后，「接口坏了」还能不能红？**能跳过的判据越多，能报警的判据就越少**，而这两者在同一行代码里，改的人只会看见前者。

### 6.3 上线后第一个月要拿到的数据

我们自己没有服务端，所以埋点只落在**本地诊断页 + 用户主动截图**这一条链上，够用：

1. **设备档位分布**（A/B/C/D 各占比）→ 校准 §1.1 的"量级"判断；
2. **每平台 `playUrls` 成功率**（成功/请求数）→ 判断哪家的人最不稳；
3. **每平台错误码 Top3** → 直接对应 §3.3 的应对；
4. **HLS vs FLV 实际使用比例** → 检验 §3.1 的「硬顺序」是否真的落地了；
5. **每千次请求的失败数**（`Net.requestCount` 做分母）→ 它比「失败次数」有用：失败次数随用户量涨，比率不涨。它还顺带暴露「重试被静默关掉」——重试失效时请求数会掉下去，而失败率不会。

### 6.4 能力落地进度（v1 承诺 vs 现在）

这张表回答的问题和 §6.1 **不是同一个**：§6.1 那张是「**接口坏了怎么办**」（按风险触发信号组织），这张是「**v1 到底承诺了什么、还差多少**」。两件事混进一张表，会让人把"还没做"读成"做过又坏了"。

**只写三档，不写天数。** 工期数字既不可验，又会诱使人拿它当承诺——写了"0.5 天"之后，过期的是**一句没人负责的话**；写了判据之后，过期的是**一条可以被证伪的断言**。只有后者会有人回来改。

| 档 | 判据（缺一不可） |
| --- | --- |
| **已落地** | ① 有代码；② 有一条**现在跑就是绿的**复查命令（或真机三态），命令写在下表第三列；③ 不存在"看起来做完了但最后一公里还是字符串"的缺口 |
| **差一步** | ① 落点明确到 `文件:函数`；② 缺的那一步不在这批改动里；③ 有一句闸门：「X 完成前，不许把 Y 宣布为已交付」 |
| **未开始** | 落点还没定，或只有结论没有代码 |

| 能力 | 档 | 复查（现在就能跑） | 缺口 |
| --- | --- | --- | --- |
| 13 个错误码 + `SiteException` | 已落地 | `grep -cE '^[[:space:]]+[A-Z0-9_]+\(retryable' core/model/SiteError.kt` → **13**（**别图省事写 `[A-Z_]`**：字符类不匹配数字，`HTTP_4XX`/`HTTP_5XX` 会被静默跳过，数出 11——**漏掉的判据看起来永远正常**，它不会报错，只会少两只码） | — |
| `Net.requestCount` 请求计数（`AtomicLong`，公开只读） | 已落地 | `grep -c 'incrementAndGet' core/net/Net.kt` → **恰好 2**（`get:223`、`postForm:256`）；同文件 `suspend fun` 里的请求方法有 **4 个**（`get`/`getJson`/`postForm`/`postJsonForm`）。**两个计数点覆盖四个公开方法**，`getJson` 是 `JSONObject(get(...))` 的薄包装，`postJsonForm` 同理——**它们里面不许再加一次 `incrementAndGet`** | 为什么判据是**等号 2**而不是「至少 3 处」：不等号的判据在它过期那天仍然通过。谁哪天在 `getJson` 里补一次计数，一个 JSON 请求就数成两次，而「重试掉了没」这条唯一外部证据**当场失真且不报错**。用途见 §3.3 可观测闭环与 §6.3 |
| `Net` 的 HTTP → `kind` 映射 | 已落地 | `Net.httpError()`（`:179`）里 `:181` 起那个 `when` 是映射主体；`:146` 是 `NET_OFFLINE`；`Net.ioError()`（`:206`）是 IO 四合一。**别写成"13 行只映射 7 个码"以外的花样**——四家 site 不许再判 `resp.code`（`grep -rn 'resp.code' core/site/` → 0） | — |
| 四家 site 抛点改写 | 已落地 | `grep -rnE 'SiteException\(' core/ \| grep -v 'class SiteException(' \| wc -l` → **51**；51 处**全部带 `detail`**（逐个括号配平核过，见下面的闸门）。**别用 `grep -o 'SiteException(' \| wc -l` 直接数**：它出 **52**——多出来的那条是 `SiteError.kt:120` 的 `class SiteException(` 声明本身。口径只差 1，而**差 1 的判据看起来永远正常**，它不会报错，只会让人以为多了（或少了）一个抛点 | — |
| 聚合层带 `kind`（`SiteFailure.kind`） | 已落地 | `core/site/Sites.kt:19-23` 是数据类，`:155` 是 `failureOf`，`:25` 是 `reason` 的后门 | 过渡态 `describe()` 仍在，见下行 |
| 斗鱼「房间不存在」的判据（`betard` 形状匹配） | **已落地**（2026-10-29 晚） | **跑单测，不数文本**：`app/src/test/.../site/DouyuBetardShapeTest.kt` 有 **12 条** `@Test`（`grep -c '@Test'` → 12），覆盖真实 119588B JSON / 1355B 错误页 / 200KB HTML / 空体与空白 / BOM+JSON / 3999 与 4000 边界两侧，以及**两档的 `report` 必须相反**；全仓 `./tools/run-unit-tests.sh` 全绿。三档定义：空白 → `PARSE_BROKEN`、`{` 开头（去 BOM 后）→ 去解析、非 JSON 且 `<4000B` → `ROOM_NOT_FOUND`、非 JSON 且 `>=4000B` → `PARSE_BROKEN` | 判据与理由见 §3.3.1 三。**它比原计划多了一条**：`trimStart().removePrefix("\uFEFF")`——BOM 会让 `startsWith("{")` 假失败，于是 119KB 的正常响应被 4000B 那条判成 `PARSE_BROKEN`，**全站每个房间都显示"接口改版"**。**这是形状匹配自己带来的新失败模式**，不补这一行就等于把静默失效换了个入口 |
| 播放器错误带 `kind`（步 5） | **差一步** | `grep -n 'private fun readableError' player/LivePlayer.kt` → `:159`，返回类型是 **`String`** | 缺 `kind`：`DECODE_UNSUPPORTED` 与 `SIGN_INVALID` 在诊断页不可分 |
| UI 读 `kind` + 文案表（步 6） | **差一步**（播放页那一支已接） | `grep -rn 'SiteError' ui/ \| wc -l` → **5**，全在 `screens/PlayerScreen.kt`（1 import + 4 个码）；`grep -rn 'SiteError' ui/strings/ \| wc -l` → **0**（表还没建）；`Async.Failed` 仍装 `String`（`ui/components/Bits.kt:49`），`Async.Failed(e.message)` 那 5 处一个没动 | ① 文案必须收进 `ui/strings` **一张表**：现在那 4 句写在 `PlayerScreen` 的 `when (e.kind)` 里，**第二屏再写一遍就是第二份真源**，两边会各自改文案，谁也不知道哪句是现役；② 首页/浏览/搜索三处改成读 `kind`；③ `SiteFailure.detail` 要回到「诊断细节」语义（现在被 `describe()` 塞的是成品文案），验收见 §3.3.1 五 第 12 条。**闸门：`ui/strings` 的表建好之前，不许出现第二个 `when (kind)`**——第一个是必要过渡，第二个就是分叉 |
| `describe()` / `SiteFailure.reason` 消失 | **未开始** | `grep -rnE '^ *private fun describe\(' core/` → **1**（`Sites.kt:128`），步 6 之后必须是 **0**。**别用宽松的 `grep -rn 'fun describe'`**：它现在是 **2**，多出来那条是 `Sites.kt:117` 里记录这条判据的注释本身——**记录判据的那行字会被判据自己数进去**，于是"还没删"和"已经删了"在输出上长得一样 | 与步 6 同一个 PR，见 §3.3.1 六 步 6 |
| 诊断页「抖音取流通道自检」（步 7） | **未开始** | — | 按钮 + 与哨兵同一个 `web_rid` 常量；三态都要可达（验收第 8 条） |
| 抖音弹幕 | **未开始**（等 spike） | `core/site/DouyinSite.kt:50-56` 里 `danmaku = false` | 签名器选型未定，ADR-004 按住不动笔 |
| 抖音列表 / 分区 / 搜索 | **未开始**（v1 不做） | 同上，`recommend`/`categories`/`search` 都是 `false` | 要 `a_bogus`，属 v2（§4.2） |

**交付闸门（写死，别绕）：步 5–6 完成之前，不许把错误码体系宣布为已交付。** 这条不是"不够完整"那种含糊评价，它有一个具体的失败形态：

> `SiteException.message = detail ?: kind.name`。今天 51 个抛点**全都传了 `detail`**，所以暂时不会漏；但这条防线靠的是「每处都记得传」，**不是构造保证**。**第 52 处忘了写 `detail`，用户屏幕上就是 `ROOM_NOT_FOUND`**——一个英文枚举名出现在中文界面里，而且它先出现在用户的截图里，不是我们的日志里。

同理，`Async.Failed` 现在还装着 `String`：**"类型"在最后一公里又变回了字符串**。诊断页想按 `kind` 计数，只能回头解析那句话——这正是 §3.3.1 要消灭的东西，只不过它换了个位置躲着。

**2026-10-29 的独立验证证据**（都可复跑，不是"我记得"）：`:app:assembleDebug` → `BUILD SUCCESSFUL`、`gradle_status=0`、`e:` 错误 0 条、APK 16:11 产出；弹幕重连夹具独立复跑 → `harness_status=0` 全绿；两份文档结构校验 → 表格列数 0 问题、三个占位词（未定事项那三个）grep 全空。

**这张表会过期，这是设计的一部分。** 第三列每一行都是一条现在能跑的命令，复查一次约 30 秒。**过期却不可复查的进度表比没有更糟**：它把"我记得做完了"升级成"文档上写着做完了"，而后者没人敢质疑。

---

## 七、不做清单（已定案，别再讨论）

版本层面的"不做"在 §1.6；这一节是**功能与技术层面的不做**，每条都带理由和"改变了就说明什么"。

| 不做 | 定案 | 理由 | 什么情况下重开 |
| --- | --- | --- | --- |
| **四家平台都不登录（不做扫码）** | v1 完全不做 | 账号是最大的风险面（风控、封号、Cookie 保管），而它换来的只是清晰度。且**不登录的降级可以被理解，假装有原画不行**（见下一行） | 用户明确要求"我要原画"并且接受扫码 |
| **不做后台音频 / 前台服务 / 通知 / 画中画 / MediaSession** | v1 | 纯前台观看场景；少一类 Android 14/16 的行为变更（FGS 类型、edge-to-edge、返回键） | 用户要求"关屏听直播" |
| **不做电视频道 / IPTV / m3u 源** | 永不做 | 越过这条线，产品性质从"第三方客户端"变成"未经许可的电视直播服务"，风险等级完全不同（`01-市场调研.md` §3.1） | 不重开 |
| **不做服务端代签** | v1 | 见 §4 第 4 条与 ADR-004 | 已有服务端做关注同步时顺带评估 |
| **不集成 FFmpeg / ijkplayer 软解** | v1 | ADR-003 | A 档 `DECODE_UNSUPPORTED` 占比 > 5% |
| **不做抖音弹幕（v1）** | v1 | 握手必须带签名：`DouyinDanmaku.kt` 实测「带空 `signature`」与「完全不带」两种都返回 **200 而非 101**，即握手直接失败；当前客户端只 emit 一条 `Failed("抖音弹幕暂不可用")`。所以 `capabilities.danmaku=false` 与实现一致，播放页对抖音**隐藏弹幕按钮**（不给用户一个点不动的开关） | 签名器落地（v2）后重测握手；一条 101 就翻 true |
| **不做 QuickJS，也不做 WebView 签名器**（前者 v2、后者应急） | v1 | §4：v1 抖音全免签，取流用不上签名器。两样都做等于为还没出现的需求先付维护费（QuickJS 还要 3–6MB 与 ABI 分包） | ① 被关（立刻上应急 WebView 补丁版），或开始做 tvOS 的列表/搜索 |
| **不做远程规则包（`RemoteSigner`）** | v1 | 没有服务端，规则包无处安放；先定接口形状 | 第一次因签名失效被迫发版修 |
| **不做多用户档案** | v1 | 会改设置页信息架构，是产品决策不是架构问题（`03-Apple规范对照.md` 已列为未覆盖） | 产品决定做"谁在看" |
| **不做手机 / 平板触摸 UI** | v1 | `LAUNCHER` 入口只为调试保留；两套交互会吃掉整个项目 | 不重开 |
| **不进任何应用商店 / OTT 商店** | v1 | 无播控牌照，只能侧载分发（`01-市场调研.md` §3.3） | 不重开 |

### 7.1 不登录带来的清晰度表现（必须如实，不许美化）

哔哩未登录时服务端把 `current_qn` 压到 **250**（实测，`accept_qn=[10000,400,250]`）。规则只有一条：**服务端返回什么就显示什么，不写"原画"这两个字。**

| 位置 | 做法 |
| --- | --- |
| 播放页清晰度胶囊 | 显示**「超清 1080P」**（250 对应的档位名），**不显示「原画」**；用户切换后若服务端仍回 250，胶囊就停在 250 并保持不闪 |
| 切换时 | 选高档但服务端降档 → 胶囊回落到真实档位 + 角落一行"未登录，最高 1080P"，**不是**静默失败 |
| 设置页「画质偏好」 | **只列服务端真实返回过的档位名（250 / 400 这一档）**，不放 10000。放了就是给用户一个选了没反应的选项 |
| 关于页 | 一句"未登录状态下清晰度受平台限制" |

**为什么这么较真**：用户能接受"没登录所以只有 1080P"，不能接受"我选了原画，结果还是 1080P"。前者是平台规则，后者是我们在骗人——这和 `01-市场调研.md` 里"状态诚实"是同一条原则。

---

## 八、ADR 索引

| ADR | 决策 | 主要理由 | 失效/重审条件 |
| --- | --- | --- | --- |
| ADR-001 | minSdk 21 / targetSdk 34 | Compose for TV 与 Media3 的库下限是 21；Play 对 TV 应用的 target 下限是 34 | Compose for TV 抬 minSdk；Play 把 TV target 抬到 35/36 |
| ADR-002 | 单 Gradle module + 包边界 + `ArchGuardTest` | 两人团队，多 module 的配置成本大于隔离收益 | 包边界被反复突破，或出现第二个消费方（KMP 抽 shared 时必然重审） |
| ADR-003 | 不集成 FFmpeg/ijkplayer，只用 Media3 + 硬件解码 | APK 少 8–15MB×ABI；四家主流流是 H.264/HEVC，硬解覆盖足够 | A 档 `DECODE_UNSUPPORTED` 占比 > 5% |
| ADR-004 | `Signer` 只留接口，**v1 实现数 = 0**：`RemoteSigner` 不存在，`core/site/douyin/DouyinSigner.kt` 是 31 行纯注释、零类型声明，**签名器代码量 0 行** | 没有服务端，规则包无处安放；先定形状。**收窄决策换来的就是这个 0**：不存在需要维护的协议族，没有会随平台 JS 一起腐化的代码，也没有"靠签名器兜底"的运行路径 | 第一次因签名失效被迫发版修（说明热更值得做），或 v2 接列表/搜索首次真的需要签名 |
| ADR-005 | 抖音 v1 **只走免签 `reflow`，不写任何签名器**；WebView 降为应急（非交付项）、QuickJS 排 v2（目标是列表/搜索） | 真拉流实测 detail+playUrls 全通（8 条线路）；原先"取流要签"是误记，实际是"列表/搜索才要签"。省下一天工期，还顺手让抖音协议可被 tvOS 直接复用 | ① 被关（`stream_url` 空 / 风控提示）→ 立刻上应急 WebView + 开 v2 |
| ADR-006 | 不做前台服务/通知/画中画/MediaSession | 纯前台播放，不做后台音频；少一类 34/36 的行为变更 | 用户明确要求"后台听直播" |
| ADR-007 | HLS 优先于 FLV，**例外仅限抖音**（FLV 优先，但 HLS 必须留在候选列表里）；虎牙只保留 HS 系 HLS | tvOS 只能播 HLS，Android 端要替它看护线路；但抖音 douyincdn 的 `.m3u8` 边缘节点间歇 404、虎牙 AL/TX 的 HLS 恒 403，硬排第一等于每次播放先白撞一次错误 | 抖音 HLS 不再间歇 404（或 CDN 换调度）→ 恢复统一 HLS 优先 |
| ADR-008 | `core/*` 零 Android 依赖；接口文档与代码同源（C1/C3） | 协议逆向是本项目最贵资产，必须在 tvOS 前保住 | 无（这条是底线） |
| ADR-009 | 错误分类用枚举 + 资源文案，不进 core | 一处分类，两处消费（Android/tvOS） | 无 |
| ADR-010 | 首帧**两段式**：3s 出"正在换线路"，4s 才切 | 3s 是交互预期（用户会不安），4s 是工程阈值（实测虎牙 FLV 弱网首帧 3.2–3.8s）；一个数字管不了两件事 | 真实数据证明 P90 首帧 < 2s |
| ADR-011 | 接口哨兵是**本地脚本**，不进 CI、不定时 | 项目没有 CI 预算；接口挂了要的是"本地一跑就知道哪家变了"，不是等邮件 | 有了 CI 预算，再谈定时 |
| ADR-012 | v1 四家都不登录，清晰度如实显示（哔哩未登录 = 超清 1080P） | 账号是最大风险面；且"没登录只有 1080P"可被理解，"选了原画还是 1080P"是骗人 | 用户要求扫码换原画 |
| ADR-013 | 抖音 v1 输入形态 = 19 位 room_id / `web_rid` / 直播页或分享链接；不做抖音号反查 | `reflow` 吃 19 位内部 ID；`web_rid` 靠直播间页 HTML 里 `roomId` 与 `web_rid` 配对换算，免签；抖音号反查要签名 | 短链落地页实测不含 `roomId`，或页面结构改版导致配对失败 |
| ADR-014 | `capabilities` 是 **`val` 且带默认值（全 true）**，缺能力的一方显式覆盖（**已落地**：`SiteCapabilities` + `IdInput`；实测抖音 `recommend/categories/search/danmaku=false`、`idInput=RoomIdOrWebRidOrLink`，其余三家零改动） | UI 要在 composition 里同步读，`suspend fun` 会逼每个调用点开协程；四家里三家全支持，默认值让它们零改动，**v1 需要写代码的平台数 = 1**。代价是"新平台忘声明"会静默变成"什么都支持" | `ArchGuardTest` 挡不住"返回空列表但 `capabilities=true`"，或出现第二家需要声明 false 的平台 |
| ADR-015 | 档位与解码探测放在**新的 `core/device`**，`DeviceProfile` 单例，不落盘；`isLowEnd = sdkInt < 26 \|\| totalMemMb ≤ 1200 \|\| heapLimitMb ≤ 192`；判据是**能力与资源**，不是机型名单 | ui 与 player 都要读同一份降级参数；`isLowRamDevice()` 在国产盒子上不可靠，需要内存与解码器探测兜底，这个逻辑只该有一份。不落盘是因为换机器/刷机/恢复备份的用户会读到上一台机器的档位。阈值取 1200 不取 1024：1GB 机型 `totalMem` 实测 950–1000MB，不是精确的 1024 | `DeviceProfile` 开始被 `core/site` 依赖（说明分层被打破） |

### 8.1 三条按已落地代码对齐（ADR-004 / 014 / 015）

上表是索引，这三条需要把"代码里到底是什么"写死，否则下一个人会按设计意图去用一套不存在的 API。

**ADR-004 · 签名器：v1 实现数 0**

- `core/site/Signer.kt`：只有接口，没有实现类。`RemoteSigner` 不存在（没有服务端，它无处安放）。
- `core/site/douyin/DouyinSigner.kt`：31 行，**纯注释、零类型声明**。
- 结论一句话：**v1 签名器代码量 0 行，不存在需要维护的协议族。** 这是收窄决策（§4）唯一实打实换来的东西——没有任何一行代码会因为 `webmssdk.js` 改版而腐化，也没有一条"签名坏了走兜底"的路径需要测。
- 留接口的成本是 4 行空接口 + §3.4 那条删除判据。**若 v2 发现把 JS 内联进 `DouyinSite` 更省事，直接删掉这个接口，不算返工。**

**ADR-014 · `capabilities` 是同步读的 `val`**

- 形状已定型，不要改成 `suspend fun`：`val capabilities: SiteCapabilities get() = SiteCapabilities()`；`data class` 全 `true` 默认 + `IdInput` 枚举（`RoomId` / `RoomIdOrWebRidOrLink`）。
- **v1 需要写代码的平台数 = 1**（只有抖音覆盖默认值：`recommend/categories/search/danmaku = false`）。其余三家一个字不用改——这正是"给默认值"的全部理由。
- 反向约束比正向更重要：**返回空列表却声明 `capabilities = true` 是欺骗**，UI 会照着一个不存在的入口去做空态。谁改某个 site，谁负责把这个位改成 `false`。

**ADR-015 · `DeviceProfile`：判据是能力与资源，不是机型名单**

- 已落地 API，照用不要自己加字段：`isLowEnd` / `isTv` / `sdkInt` / `totalMemMb` / `heapLimitMb` / `hevcDecodable` / `av1Decodable` / `defaultDanmakuMax` / `init(context)` / `summary()`。
- **`isLowEnd` 的实际判据（三条，每条各管一件事，不是冗余）**：

  ```kotlin
  val isLowEnd: Boolean by lazy {
      Build.VERSION.SDK_INT < 26 || totalMemMb <= 1200 || heapLimitMb <= 192
  }
  val heapLimitMb: Int by lazy { (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt() }
  ```

  | 判据 | 意图 |
  | --- | --- |
  | `SDK_INT < 26` | Android 7 及以下，Compose 掉帧明显 |
  | `totalMemMb <= 1200` | 覆盖 512MB 与 1GB 两种盒子。**不用 `<= 1024`**：1GB 机型 `totalMem` 实测约 950–1000MB，不是精确的 1024 |
  | `heapLimitMb <= 192` | 兜底：少数盒子的 `totalMem` 上报值被厂商改过，而"Android 按物理内存给 heap"是系统行为，更可信 |

- **没有任何 `Build.MODEL` 白/黑名单，也不该有**：机型列表永远不全，盒子的 `Build.MODEL` 经常是厂商乱填的字符串，同型号不同批次的内存还能差一档；**按名字匹配等于给未来所有还没上市的盒子提前判错**——我们不会知道它们叫什么。解码那两条（`hevcDecodable` / `av1Decodable`）则由 `MediaCodecList(REGULAR_CODECS)` 直接回答"这块芯片到底解得开吗"，同样不查表。
- **不落盘**：档位是每次冷启重新探测的事实。落盘只会让换机器、刷机、恢复备份的用户读到上一台机器的档位。
- 真机实测（`init` 打印格式）：`sdk=… tv=… lowEnd=… mem=…MB heap=…MB hevc=… av1=…`。已有一次：`sdk=36 tv=true lowEnd=false mem=1471MB hevc=true av1=true`（`heap` 一列是后加的）。

> **教训：一个永远为 `false` 的降级开关，比没有这个开关更糟——因为它看起来在工作。**
>
> 原来的 `< 192` 把 MB 当成了"GB 的百分之一"，在任何机型上都不成立。而 `DeviceProfile.summary()` 照常打印「低配模式」与否，`init` 日志照常输出 `lowEnd=false`，一切看起来正常。**没有报错、没有崩溃、没有异常日志——只是那张降级表从来没有生效过**，而它针对的正是 1GB 盒子，也就是本产品最需要它的用户。
>
> 所以判据写完要做两件事，缺一不可：
> 1. **拿一台已知不该命中的真实设备验一次**（如 1.4–1.5GB 的模拟器，必须 `lowEnd=false`）。不做这一步，永远不知道这个判据是"没命中"还是"已经死了"。
> 2. **再拿一台该命中的验一次**（1GB 盒子或 `totalMem ≤ 1200` 的机型，必须 `lowEnd=true`，且 `defaultDanmakuMax` 变成 8）。只验反面会把开关写成永远 `true`，同样是坏的。
>
> 这条不只适用于内存：任何"只在某些设备上生效"的代码路径，都必须有一台设备证明它真的走过。**没被任何设备命中过的分支，等于没写。**

---

## 附：这份文档没回答的问题

诚实列出来，避免被当成"已解决"。（"扫码登录不做""多用户档案不做"已定案，见 §7，不再列在这里。）

1. **鉴权失败后的刷新机制**：`ttwid`、哔哩 Cookie 都会过期，目前只有"过期就报错"这一条路。要不要做静默续期，取决于 v2 是否接签名——两者的失效面重叠。
2. **斗鱼/虎牙弹幕协议的具体帧结构**尚未逐条抓包复核（逆向报告 §九 也这么写）。`core/danmaku` 的四个客户端里必须有"已验证/待验证"标记，**不要把推断写成事实**。
3. **虎牙新版 Tars 的完整字段表**（`HYMessage` 的 content/userInfo/bulletFormat 各在哪个 tag）未取到：抓包不通时降级为"连接成功但不显示内容 + 一条系统提示"。
4. **有效 `v.douyin.com` 短链到底能不能用**：失效短链的行为已经测清（302 → `www.douyin.com`，200 但 `roomId` 出现 0 次，§4.1），并且已经做成"严格配不上就报错"；**但一条真正有效的短链还没验过**——它由分享动作生成，无法自造。谁手上有就补一次：粘进诊断页看是否命中房间。验不过就把短链从输入框提示里彻底删掉（代码里的分支可以留着，它至少能把失效短链说清楚）。
5. **不存在的 `web_rid` 页面长什么样**：`10011` 在 `reflow` 上已经被判清（`douyinIdShape` 三分类落地之后，进 `reflow` 的一定是 19 位，所以只剩「号不存在」，§3.3.1 三），但**页面这一侧没测**——`live.douyin.com/{不存在的web_rid}` 到底回 404，还是 200 加一个"直播间不存在"的空壳页。这决定 `DouyinSite.resolveRoomId()` 末尾那支（`pairing-miss`，`:328`）该不该背 `PARSE_BROKEN` 的误报：如果不存在也回 200 空壳页，用户粘错号就会让 `PARSE_BROKEN` 红一次。**目前按"宁可误报"取了 `PARSE_BROKEN`**（漏报代价是抖音整条通道静默失效，远大于误报）。谁抓一次就能把这一支收窄成"页面含『直播间不存在』字样 → `ID_UNRESOLVED`"。
6. **tvOS 侧的分发与合规路径**：与 Android 同构（侧载），但 Apple 的企业签名 / TestFlight 限制需另行评估。
