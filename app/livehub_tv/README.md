# livehub_tv

LiveHub Android TV 客户端（Flutter，设计画布 1920×1080）。

## 启动流程

1. **系统开屏**：Android 12+ 的启动图标帧，底色 `#231C16`（取图标底，见
   `android/app/src/main/res/values/styles.xml` 与 `values/colors.xml`）。
2. **应用内开屏**：`lib/modules/splash/splash_overlay.dart`，按
   `design/v1.0.0/splash.html` 实现。
   - 字标 + 四行平台状态；行序固定 哔哩 / 斗鱼 / 虎牙 / 抖音，不按状态重排。
   - 四家并发探测 `getRecommendRooms(page: 1)`，单家 1500ms 超时，只用来给四行状态；
     开屏退场等的是**首页第一页有结论**（它盖住的正是首页的 loading）。
   - `T = max(960ms, 首页结论时刻 + 200ms)`，退场 200ms；首页第一页失败时不退出，
     整块换成「标题 + 说明 + 重试」，重试会同时重拉首页。
   - 首页异常迟迟不结论时，5s 兜底退场，交给首页自己的 loading / 错误态。
   - 开屏底 `#1A140F`；首页在背后加载，退场时直接露出。
3. **首页**（首次启动先到用户协议页）。

## 分类页

- 左栏平台顺序：斗鱼 / 哔哩哔哩 / 虎牙 / 抖音；斗鱼置顶，不再有「全部平台」。
- 右栏按页加载，滚动接近底部追加下一页；到底显示「已经到底了」。

## 搜索

- **一次搜索，两类结果**：提交后同时查四平台的「正在直播」与「主播」，
  右栏按「正在直播 N 个 / 主播 N 个」分节展示；不再有「直播间 / 主播」模式切换。
  未开播主播只在主播索引里，先让用户猜模式等于把可搜到的结果藏起来。
- **搜索只刷新结果，不自动进房**。提交后焦点落到第一张结果卡；进哪个直播间
  由用户按 OK 选择。历史词点击同样只搜索。
- **平台限制显式可见**：抖音关键词搜索需要登录，接口返回 `status_code != 0` 时
  显示「抖音：请先登录，再继续搜索吧」，不再静默当作 0 条结果。其余平台失败
  也会在该行给出原因，部分失败不影响其他平台结果。
- 匹配排序：完全相等 > 前缀 > 包含，同档直播间按人气、主播按「直播中优先」。
  主播结果只保留本地能解释的匹配，过滤平台返回的无关模糊项。
  直播间结果上限 12 条，主播 20 条；与直播间重复的主播不再重复展示。

## 播放页交互

> 2026-11-04 在 Android TV 模拟器（`googletv_api36`）逐项实测，并与
> `lib/modules/live_room/live_room_page.dart`、`player/player_controls.dart` 对照。

### 遥控器键位

| 按键 | 播放页（面板关闭时） | 面板打开时 |
| --- | --- | --- |
| OK / Enter / Space | 显示 / 隐藏顶部与底部控制条 | 交给面板内焦点项：开关切换、枚举步进 |
| ← | 打开「我的关注」面板 | 同左（面板内控件改值） |
| → / Menu / M | 打开「设置」面板 | 同左 |
| ↑ / ↓ | 上一台 / 下一台（只在「正在直播」的关注列表里循环） | 焦点在面板内移动 |
| 返回 | 第一次提示「再按一次退出播放器」，2 秒内再按退出 | 关闭面板并还原播放页焦点 |

- 播放页没有独立的播放/暂停、快进、音量或全屏控件：直播流进入即播放，
  底部控制条只显示清晰度、线路、弹幕开关、分辨率与上述键位提示，没有可聚焦按钮。
- 触屏点击画面任意位置 = 打开「设置」面板（与 → 相同），不是显示控制条。
- 面板打开时全局快捷键不响应（`live_room_page.dart` 的 `Get.isDialogOpen` 守卫），
  避免长按 → 叠开多个设置面板。

### 设置面板（右侧 800px）

- 顶栏「← 返回」可聚焦；默认焦点在第一项「关注用户」。
- 控件只有两类，与设置页共用 `SettingsSwitchRow` / `SettingsChoiceRow`：
  - **开关**（关注用户、弹幕开关）：← 置为关、→ 置为开、OK 切换；即时生效 + toast，
    无二次确认，值没变时不重复写回。
  - **枚举**（清晰度、线路、画面比例、弹幕大小/速度/显示区域/不透明度）：
    ≤5 项显示行内分段，>5 项显示「‹ 当前值 ›」；←/→/OK 都只改当前值，
    到两端停住，**不再弹选值对话框**。
- 清晰度 / 线路只记录选择，**关闭面板时统一应用一次**（`getPlayUrl` /
  `changePlayLine`），避免连续左右步进触发多次重连。
- 关闭面板后焦点回到播放页键盘监听节点。

### 关注面板（右侧 800px，「我的关注」）

- 列出 `FollowUserService.livingList`（正在直播的关注主播），上下移动焦点，
  按 OK 切到该直播间并关闭面板；列表为空时显示空态。
- 打开前同样先 `unfocus()` 播放页，关闭后还原焦点。

### 已知边界

- 设计规范里的「换台条（↑↓ 呼出大字主播名）」未实现；当前 ↑↓ 直接换台。
- `design/system/设计规范.md` §6.3 的播放页键位表（←/→ 换平台）与 §8.3 的
  「搜索键打开第一条」仍是旧规则，需设计侧另行更新（本文与代码以实际行为为准）。

## 取流与播放稳定性（哔哩「英雄联盟赛事」房）

> 2026-11-04 静态排查 + 线上实测。真机现象：TCL 75T7L Ultra 进
> 哔哩哔哩英雄联盟赛事（`7734200`，短号 `6`）后画面停住，随后应用自己退出。

### 先说结论：这个退出不是应用自己干的

TV 端全仓只有 `lib/modules/agreement/agreement_page.dart` 里一个 `exit(0)`（拒绝协议），
播放链路里没有任何主动退出路径。所以"自动退出"只可能来自系统：UI 线程被占满触发
ANR 被收，或者 libmpv 的原生层 abort。两种都指向同一件事 —— **播放页让主线程长时间
干不了活**，而不是"流坏了"。

### 这个房间本身并不难播（实测数据）

| 项 | 实测值 | 怎么测的 |
| --- | --- | --- |
| 房间 | `7734200`，赛事分区（`parent_area_id=13` / `area_id=561`），常驻千万级人气 | `getInfoByRoom` |
| 清晰度 | `accept_qn=[15000,10000,400,250,150]`（2K/原画/蓝光/超清/高清），**匿名只签发 `current_qn=250`** | `getRoomPlayInfo` |
| 实际流 | 1280×720 @ 30fps，H.264 High@3.1，20s 抓包 4.5MB ≈ **1.8Mbps**（含 AAC 48k 立体声） | `ffprobe` |
| 关键帧 | 20s 内 9 个 IDR，GOP ≈ 2~3s | FLV tag 解析 |
| 线路 | FLV ×2 host + fMP4 HLS ×2 host（mcdn 变体不进线路表） | `curl` + 解析 |
| 弹幕（赛前占位） | 一条压缩包切出 26 个 JSON，其中 **8 条** 是 `DANMU_MSG`，18 条与弹幕无关 | 本仓库 `livehub_core` 实测 |

720p30 / 1.8Mbps 对任何一颗电视 SoC 都不构成压力 ⇒ **"卡住"不是解码能力不够**。
真正的差异在于：赛事房把下面三条链路同时推到了最坏值。

### 现场截图给出的两条硬证据

真机录屏里的两帧（同一晚 TES vs IG，房间页 10:53 / 10:56）：

| 观察到 | 说明 |
| --- | --- |
| **弹幕在画**，而且是 5 条轨道、40 号字、贴着顶部 —— 与 `area=0.25` + 默认字号完全吻合 | Flutter 渲染管线活着，房间信息、WebSocket、弹幕解析这条链是通的 |
| **视频区是纯黑**，但中央没有 buffering 转圈 | 播放器**自认为在播**（`state.buffering=false`），只是没有任何一帧被合成到画面上 |
| 按 OK 之后控制条没有出现 | UI 线程当时已经无法响应输入 |

第一、二条把问题钉在「视频输出」而不是「取流」：`media_kit_video 1.3.1` 在 Android 上
**只走 `TextureRegistry.SurfaceProducer`（Flutter 纹理）**，没有 SurfaceView 分支
（见 `android/src/main/java/com/alexmercerind/media_kit_video/VideoOutput.java`）。
而兼容模式选的 `vo=mediacodec_embed` 是让 MediaCodec 直接往这块纹理的 Surface 写，
它要求 surface 的尺寸在创建时就是视频尺寸；插件却是「先建 surface（1×1 占位），
拿到 videoParams 之后再 `SetSurfaceSize`」。这条竞态在电视上表现为**纹理永远拿不到帧**，
也就是"黑屏 + 弹幕正常"。

> 注意：`adb screencap` 在部分电视上对视频层本身就会取成黑色，所以"黑"不能单独当证据；
> 能区分的是「有没有 buffering 转圈」和「`state.width` 是否已经有值」。看门狗日志里
> 那两个字段就是为此打的。

### 机制一：没有任何东西负责"画面没出来"

**真机 ANR 堆栈（2026-11-04 09:36 / 10:12，修复前版本，`adb shell dumpsys dropbox --print`）**：

```
"main" prio=5 tid=1 Native
  native: mpv_set_property_string+24
  native: mpv_set_property+80
  native: pthread_cond_wait
  at android.os.MessageQueue.nativePollOnce
Subject: Input dispatching timed out (... Waited 5001ms for KeyEvent)
```

也就是：进房时先调 `setProperty('force-seekable','yes')`（同步 FFI，要抢 mpv 的 core 锁），
而 `AndroidVideoController` 在 surface 挂上时会把 `vo=null → wid → vo → vid` 重设一遍并
接着执行 `player.seek(Duration.zero)`；直播流本来不可 seek，被 `force-seekable=yes`
声明成可 seek 后这次 seek 会真的去重新解复用、卡在 demuxer 的网络读上 ——
**core 锁被长期占住，属性设置跟着死等**，主线程 5s 收不到按键 → ANR → 系统杀进程。
同一把锁也是"黑屏但弹幕正常"的来源：`vo` 已经被置成 `null`，重设那一步再也没机会跑完。

改前的播放链路只有两个输入：`player.stream.error` 和 `stream.completed`。
首帧从来没来过、或者解码线程卡住不报错时，**一个事件都不会有** ——
屏幕上就是一张静止画面，代码里也没有任何超时。外加三处放大器：

- `initializePlayer()` 设了 `force-seekable=yes`（上游遗留补丁）。直播流本来不可
  seek，声明成可 seek 后，`AndroidVideoController` 在 surface 每次重挂时执行的
  `player.seek(Duration.zero)` 会变成"从头重新解复用"。赛事房正好是最容易重挂
  surface 的一类房间。
- `setPlayer()` 不 `stop()` 直接 `player.open()`；`mediaError` 与 `mediaEnd` 各有
  一套重试计数，一次故障能连开两三次 —— 并发 open 在 libmpv 里会留下两条 demuxer。
- 恢复路径只在"有事件"时触发，且首帧从未到达时没有任何兜底。

对应的修法（`lib/modules/live_room/live_room_controller.dart`、`player/player_controller.dart`）：

1. **看门狗**：每 2s 巡检，起播 12s 仍在 buffering、或 18s 仍没有视频尺寸、
   或已出画但连续 buffering 超 12s ⇒ 判定卡死。
2. **单飞**：`_playerGeneration` 代次校验 + 先 `stop()` 再 `open()`，超时 5s/12s。
3. **统一恢复**：卡死/错误/EOF 全走 `_recover()` —— 原地重开 2 次 → 换下一条线路
   → 全部失败才认输并上屏错误。
4. **去掉 `force-seekable`**，改为下发表里的直播参数。

### 机制二：弹幕从解包到上屏，全在 UI isolate 上且没有上限

改前一条压缩包的处理是：brotli 解压 → `utf8.decode` → `RegExp(r"[\x00-\x1f]+", unicode: true)`
切分 → **对每个片段 `json.decode`**（进场、礼物、点赞、在线榜全解一遍）。
上屏那一步，`canvas_danmaku` 对每条弹幕还要：`TextPainter` 量宽 + 建 1~2 个
`ui.Paragraph`（中文要过字体整形，TV 上 0.3~1ms/条）。

而 1080p + 40 号字 + `area=0.25` 时轨道只有 `floor(1080×0.25/48)=5` 条，
每条轨道约 0.5 条/秒 ⇒ **看得见的上限约 3 条/秒**。赛事房开赛时每秒几十到几百条，
也就是说 97% 以上的排版工作做完就被丢掉了 —— 纯烧帧时间，帧时间一炸就是 ANR。

对应修法：

- `lib/src/danmaku/bilibili_danmaku.dart`：手写花括号扫描切 JSON（不假设分隔符），
  并在 `json.decode` **之前**用子串预筛 `"cmd":"DANMU_MSG"` / `"cmd":"SUPER_CHAT_MESSAGE"`。
  占位期实测 26 条里 18 条在这一步就被丢掉。
- `player/player_controller.dart`：令牌桶限流（默认 **12 条/秒**，设置页「弹幕密度」可调）
  + 100ms 合批 + 队列背压 120 条，全部在**建 Paragraph 之前**丢弃。
- 不再开 `massiveMode`（它把放不下的弹幕随机叠进已有轨道，赛事密度下会叠成一片糊）。

### 机制三：进房链路没有上限，失败还不说话

- `getRoomDetail` 是三次串行请求（buvid → `getInfoByRoom` → `getDanmuInfo`），
  单次 20s 超时、整体没有超时。配合 `SmartDialog` 的全屏 loading 遮罩，
  一次 DNS 卡顿就是最长 60s 的"遥控器按什么都不动"。
- `errorMsg` 只写不读：取流失败 / 线路换完 / 看门狗放弃，三种结局在屏幕上都长得一样。

对应修法：`loadData` 加 15s 总超时；播放页新增错误横幅（带"按 → 打开设置换线路"指引）。

### 直播 mpv 属性：**只在 onInit 下发一次**，不进 open 路径

> 第一版实现把这些属性放在每次 `open()` 之前下发，位置正好落进上面那条抢锁路径 ——
> 同步 FFI 卡住时 `Future.timeout` 不会触发，等于把 ANR 概率放大 8 倍。
> 现在挪到 `LiveRoomController.onInit`（mpv 已就绪、video 输出还没挂）一次性写下，
> 之后再也不写。

| 属性 | 值 | 为什么 |
| --- | --- | --- |
| `cache` | `yes` | 直播必须有读缓冲，否则一次抖动就进 EOF |
| `cache-secs` | `6` | 默认值会把延迟堆到十几秒，卡一下追不回来 |
| `demuxer-readahead-secs` | `6` | 同上 |
| `demuxer-max-bytes` / `max-back-bytes` | `16MiB` / `4MiB` | 直播不需要回看，限制内存 |
| `network-timeout` | `15` | CDN 掐连接时尽快落到错误分支，交给看门狗 |
| `hls-bitrate` | `max` | HLS 排在第二顺位，命中时别再被降档 |
| `stream-lavf-o` | `reconnect=1,reconnect_streamed=1,reconnect_delay_max=2,...` | 哔哩整点/换机位会掐 CDN 连接，让 ffmpeg 自己先重连 |
| ~~`force-seekable`~~ | 已移除 | 见机制一：它就是让 `seek(0)` 变成重解复用的开关 |

### 取流层（`livehub_core`）

- 清晰度不再写死 `stream[0].format[0].codec[0]`，改为"找 avc 且 `accept_qn` 非空的那条"，
  否则赛事/版权房在只签发 HLS 或只签发 hevc 的时段会直接抛空指针再被吞成一句 toast。
- 线路按 `FLV → HLS·fMP4 → HLS·TS` 稳定排序，mcdn 变体不进列表；
  `LivePlayUrl.lines` 带线路类型，控制条显示成「线路1·FLV」。
- `LivePlayUrl.actualQuality` 带回平台**实际签发**的 qn：请求蓝光只给超清时，
  界面徽章跟着改成超清，不再出现「标 720P、实测 1.3Mbps」这种自相矛盾的读数。
- 拉流头统一成接口层同一套 UA + `referer=https://live.bilibili.com/<roomId>` +
  `origin`（短效 `sig` 按申请时的客户端画像签，换 UA 会落进防盗链分支）。

### 设置面

- 「兼容模式」默认**关**（改前默认开）。它等于 `vo=mediacodec_embed` + `hwdec=mediacodec`：
  MediaCodec 直写 Flutter 纹理的 Surface，绕开 `vo=gpu` 的 GL 合成。
  只在 gpu VO 出花屏的老设备上才有意义；这条路径要求 surface 尺寸在创建时就正确，
  而且每次 surface 重挂都要重设 `vo`/`vid` 并 `seek(0)` —— 上面那两帧黑屏就是它。
- 新增「弹幕密度」（8/12/20/30/45/60 条每秒，默认 12）。

### 模拟器实测（2026-11-04 14:00，`googletv_api36` / Android 16 / arm64）

重启一台干净的 TV AVD（软件 GPU、无硬件解码器、模拟器里登录了哔哩账号 ⇒ 拿到 `qn=15000` 2K 流）：

| 步骤 | 结果 |
| --- | --- |
| 冷启动 → 首页 | ✅ 4s 内出首帧，1920×1080 完整 UI |
| 进房（`7734200`） | ✅ 无 ANR、无崩溃；`播放链接` → `线路1·FLV` |
| `vo=gpu` | ❌ `Could not create EGL context` / `Failed initializing any suitable GPU context` |
| 自动兜底 | ✅ `检测到视频输出失败` → 切 `mediacodec_embed` → 重开 → **出帧**（TES vs IG 重播画面正确） |
| 连续刷新 | ❌ 同一位置 35s 零像素变化（软解 2K + 模拟 GPU），但 UI 仍然响应按键 |
| 全程 ANR / crash | 0 |

由此落了两条代码：

1. **VO 失败检测 + 自动兜底**：mpv 在 `vo` 起不来时**不走 error 流**，只在日志里说
   （`cplayer: Error opening/initializing the selected video_out` / `vo/gpu: Failed initializing
   any suitable GPU context`）。现在从 `player.stream.log` 里认这三句，切到
   `mediacodec_embed` 并重开；最多切 2 次（surface 重挂会把 `vo` 重置回 gpu，需要允许复盘）。
   这条兜底就是真机上"黑屏 + 弹幕正常"的自动出口。
2. **修掉一个自己引入的回归**：`createdController` 回调早于 `_animationController` 创建，
   在回调里调 `updateOption` 会抛 `LateInitializationError`，debug 表现为红屏
   `_elements.contains(element)`，release 表现为整页画不出来 —— 初始选项现在只通过
   构造参数传入。

**模拟器能证明的**：取流 → 解码 → 出帧 → UI 存活这条链是通的，且 VO 自愈有效。
**模拟器证明不了的**：连续刷新/帧率。软解 2K + 模拟 GPU 的刷新率不能代表真机，
流畅度必须在 TCL 上验。

### 二分定位（不用改代码，遥控器就能做）

设置页里现在有两个开关可以单独切：

| 操作 | 结论 |
| --- | --- |
| 播放设置 →「兼容模式」开 → 关，各进一次同一间房 | 关掉后出画面 ⇒ 问题在视频输出路径（`mediacodec_embed` → `vo=gpu` 这条） |
| 弹幕设置 →「弹幕开关」关，同一间房再看 | 关掉后不卡 ⇒ 问题在弹幕排版链路 |
| 「弹幕密度」从 12 调到 60 | 只有高密度才卡 ⇒ 就是排版量本身，不是解析错误 |
| 以上都不变，仍卡 | 看门狗日志里的 `buffering` / `width`：`width=0` ⇒ 流根本没解出帧（网络/线路）；`width` 有值但画面黑 ⇒ 输出路径 |

### 真机复验（需要 `adb`）

```bash
adb logcat -c
adb logcat -s flutter:V libmpv:V MPV:V AndroidRuntime:E ActivityManager:W   | grep -E "看门狗|播放异常|起播失败|ANR in com.livehub.tv|FATAL|libmpv"
```

期望：进房 2s 内出现 `播放链接`；若卡住，10~20s 内必定出现 `看门狗：起播 …s 无画面`
并跟一条 `播放异常（画面卡住 …）`，随后自己恢复；
**不应**再出现 `ANR in com.livehub.tv` 或 `FATAL EXCEPTION`。

### 已知边界（别当 bug 查）

- **赛前占位黑场**：`live_status=1` 但画面是预告板/倒计时（本次实测标题
  「【预告】5日6点 GEN vs LYON」）。画面不动是内容，不是播放器。
- **清晰度被压档**：赛事房对匿名只签发 `qn=250`，请求原画也只是 720p；
  现在徽章跟随实际签发值，不再撒谎。
- `7734200` 与短号 `6` 是同一间房。

## 目录

- `lib/modules/` 各页面控制器与视图
- `lib/widgets/` 共享控件（焦点面、卡片、设置行等）
- `lib/routes/` 路由与一级页签
- `android/` 平台资源与启动主题
