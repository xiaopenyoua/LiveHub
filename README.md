# LiveHub

> 四个平台，一台电视。像换台一样换主播。

聚合哔哩哔哩、斗鱼、虎牙、抖音四家直播的 Android TV 客户端。

仓库分两层：`design/` 是可交互的 1920×1080 界面原型、设计规范与品牌资产（**视觉唯一真源**）；`android/` 是照这份原型落地的 Android TV 工程（Kotlin + Compose for TV + Media3）。顺序是**原型先行、工程对齐原型**，不是反过来——所以任何"界面上该长什么样"的争议，回到 `design/v1.0.0/ui.html` 看，不看代码。

---

## 东西在哪

三个内容目录，一份判据：

| 目录 | 是什么 | 从这进去 |
| --- | --- | --- |
| `design/` | 设计侧：原型、规格、品牌矢量源、稿子素材 | `design/README.md` |
| `android/` | 开发侧：可执行工程、被 APK 吃掉的资源、实现文档 | `android/docs/架构.md` |
| `docs/` | 判断依据：为什么做这个产品、参考的那个 App 是怎么做的 | `docs/README.md` |
| `AGENTS.md` | **归属判据**：东西该放哪、能不能放、怎么校验 | `AGENTS.md` |

按"我想找什么"查：

| 想找 | 去哪 |
| --- | --- |
| 界面长什么样 / 交互怎么走 | `design/v1.0.0/ui.html`（单文件原型，双击能开） |
| 开屏动画 | `design/v1.0.0/splash.html`（带 `?t=` 时间轴） |
| 应用图标（矢量源 / 自适应三层 / 五档 / monochrome） | `design/v1.0.0/icon.html` + `design/brand/wordmark/src/` |
| 品牌字标的矢量真源与交付位图 | `design/brand/wordmark/src/`、`design/brand/wordmark/export/`（规矩见 `design/brand/README.md`） |
| 稿子用的封面与字体 | `design/media/` |
| 跨版本都成立的设计规则（色彩/字阶/焦点/栅格/键位） | `design/system/设计规范.md` |
| 与 tvOS 官方规范逐条对照 | `design/system/apple-tvos-对照.md` |
| 某版规格与品牌裁决（六份，编号即地址） | `design/v1.0.0/spec/` |
| 定稿截图与开屏定稿帧 | `design/v1.0.0/screenshots/`、`design/v1.0.0/shots/` |
| Android 怎么实现、为什么这么取舍 | `android/docs/架构.md` |
| 四家接口实测到了什么、踩过哪些坑 | `android/docs/接口实测.md` |
| 为什么做这个产品 | `docs/市场调研.md` |
| 参考的 App 是怎么搭起来的 | `docs/逆向分析.md` |

```
design/                    设计侧，单向的源头
  v1.0.0/   ui / splash / icon.html + spec/ + shots/ + screenshots/   ← 定稿即冻结
  brand/    wordmark/{src/*.svg, export/*.png} + build-icons.mjs      ← 品牌资产
  system/   跨版本规则（设计规范、tvOS 对照）
  media/    稿子的运行时素材（封面、字体）
android/                   开发侧，照原型落地
  app/src/main/java/com/livehub/tv/{core,player,ui}/
  docs/     架构.md、接口实测.md
  tools/    sync-icons.mjs（位图进 res）、probe-sites.sh（接口哨兵）、verify-layout.sh（布局校验）
docs/                      判断依据（市场调研、逆向分析）
AGENTS.md                  归属判据
```

**管路只有一条，方向不能反**（详见 `AGENTS.md` §0）：

```
design/brand/wordmark/src/*.svg ──build-icons.mjs──▶ export/*.png ──sync-icons.mjs──▶ res/mipmap-*/ic_launcher.png
```

### 五条规矩（全文在 `AGENTS.md`，这里只够拦住最常见的四种错）

1. **一个字节只属于一侧。** 能被 `assembleDebug` 直接吃掉的 → `android/`；设计稿、规格、矢量、稿子素材 → `design/`；都不是但下一个人做决定需要 → `docs/`。
2. **设计侧不写 `android/`。** 位图进 `res/` 只有 `android/tools/sync-icons.mjs` 一条路（`tv_banner.png` 是手工档，例外且已写明理由）。
3. **源与产物分层。** 能由一条命令重建的，不与源同目录。
4. **可再生且无人引用的字节，删掉，不建 `archive/`。** 历史在 git 里，不在目录里。
5. **文件名不写版本、不写状态词。** `v2` `final` `new` `old` 描述的是"处在什么阶段"，不是"画的是什么"。

```bash
bash android/tools/verify-layout.sh    # 八项结构校验，退出码 0 才算干净
```

---

## 为什么做这个

虎牙 TV 版 2026 年 8 月停服，斗鱼 TV 版已下架，B 站 TV 端以点播为主。四家平台都在往手机和网页收，**没有一个产品是为"沙发上的人 + 一个方向键"设计的四平台直播客户端**。详见 [`docs/市场调研.md`](docs/市场调研.md)。

产品的三根柱子：

1. **换台优先** —— 播放页 ↑↓ 直接换下一个直播间，不用退回列表。
2. **房间号就是房间号** —— 不造跨平台统一编号。斗鱼 4–8 位、虎牙最多 8 位、哔哩短号+8–10 位真实号、抖音只有抖音号，界面原样显示各家的号，搜索按字符串匹配。
3. **状态诚实** —— 未开播就说未开播，降级就说降到 720P，不装。

---

## 看原型

直接用浏览器打开 `design/v1.0.0/ui.html`。

| 参数 | 作用 |
| --- | --- |
| 默认 | 带设计稿外壳，顶部可切六屏，底部显示键位提示 |
| `?canvas=1` | 剥掉外壳，纯净 1920×1080 TV 画布（出图用） |
| `?canvas=1&screen=player&osd=1&room=660000` | 直接落到某屏 / 某状态 / 某个房间号或抖音号 |

**键位**：↑↓←→ 移动焦点；OK 确认；返回上一级（最高级回首页，首页再按弹二次确认）；播放页 ↑↓ 换台、←→ 换平台。搜索页字母与数字同屏，没有模式切换；数字键在首页和播放页不做任何事——真实房间号 4–10 位、抖音号还是字母串，遥控器盲打无处校验。

---

## 六屏

十张定稿截图都在 `design/v1.0.0/screenshots/`，文件名就是屏名（不编号——"第几张"不是地址，屏名才是）。

| | |
| --- | --- |
| 首页 | `design/v1.0.0/screenshots/home.png` |
| 分类 | `design/v1.0.0/screenshots/browse.png` |
| 关注 | `design/v1.0.0/screenshots/follow.png` |
| 搜索·拼音 | `design/v1.0.0/screenshots/search.png` |
| 搜索·房间号直达 | `design/v1.0.0/screenshots/search-rid.png` |
| 播放 | `design/v1.0.0/screenshots/player.png` |
| 设置·右栏焦点 | `design/v1.0.0/screenshots/settings.png` |
| 设置·左栏焦点 | `design/v1.0.0/screenshots/settings-nav.png` |
| 换台条 | `design/v1.0.0/screenshots/channel-osd.png` |
| 退出确认 | `design/v1.0.0/screenshots/exit-dialog.png` |

`settings.png` 与 `settings-nav.png` 是刻意出的对照：前者焦点在右栏设置行，后者焦点在左栏导航。并排看，左右两栏落到的是**同一块玻璃**——同为 `rgba(255,255,255,.13)` 底、同为 1.02 放大、同为那道顶沿高光，只是同一时刻只有一处亮着。

`search.png` 与 `search-rid.png` 是搜索页的两个状态：前者打拼音首字母（`lp`，命中主播名与标题），后者打房间号（`660000`，`rank 0` 完全相等，结果区顶上一句「按「搜索」直达」）。两张里的键盘是**同一块**——数字行和三行字母都在，没有任何「123 / ABC」切换键。

播放页控制条的顺序是 `弹幕 → 清晰度 → 线路 → 换台 → ＋ 关注`。关注放最后，因为前四个都是"看这一次"的参数，关注是"以后都看"的动作——它不在这条参数链上，它是出口。主按钮的属性（白底黑字）也顺带把它和四个玻璃胶囊分开。

---

## Android TV 客户端

`android/` 是 Kotlin + Jetpack Compose for TV + Media3 的实现，六屏与 `design/v1.0.0/ui.html` 一一对应。

```bash
cd android
./gradlew :app:assembleDebug --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

| 包 | 管什么 |
| --- | --- |
| `core/site/` | 四家取流：列表 / 分区 / 搜索 / 详情 / `playUrls()` |
| `core/danmaku/` | 四家弹幕（哔哩 wss、斗鱼 STT、虎牙 Tars、抖音诚实失败），含 `tars/` 协议层 |
| `core/device/` | 能力与资源探测：低配降级按实测判据，不看机型名单 |
| `core/model/`、`core/net/`、`core/store/` | 数据模型、网络与错误映射、本地持久化 |
| `player/` | Media3 封装 + 弹幕层（原生 View 叠在 Surface 上） |
| `ui/` | `screens/` 六屏 Compose 实现、`components/`、`theme/`、`strings/` |

**版本范围**：`minSdk 21` / `targetSdk 34`。下限不是随手定的，是 Compose for TV 与 Media3 的依赖压出来的——再往下的盒子拿不到 Compose 的焦点系统，整套方向键交互得推倒重写；上限按 Google Play TV 应用的合规要求走。

**坐标系（真机踩过才发现的坑）**：设计稿是 1920×1080 的**像素**画布，而 1080p 电视报 `density = 320`，即 1dp = 2px。把原型里的 px 直接当 dp 抄进 Compose，整屏会放大一倍：顶栏吃掉 21% 屏高、hero 标题被顶出容器、右上角「有线网络」竖排成四行。解法是在 `LiveHubTheme` 里把 `LocalDensity` 换成 `base.density / 2f`，让 **1dp ≡ 1 设计稿 px**（1920px ≡ 960dp），原型数值才能原样搬进代码。原生 View（弹幕层）拿不到 Compose 的 density，单独用 `v * widthPixels / 1920f` 换算。

**真机验证状态**（Google TV / API 36 / 1920×1080）：

| 平台 | 列表 / 分类 | 播放 | 弹幕 |
| --- | --- | --- | --- |
| 哔哩哔哩 | ✔ 免签接口 30 条/页 | ✔ 普通主播间正常出帧 | ✔ 真实中文弹幕 |
| 斗鱼 | ✔ 120 条/页 | ✔ | 已实现，未单独逐房间验证 |
| 虎牙 | ✔ 120 条/页 | ✔ | ✔ 真实中文弹幕 + 断线自愈 |
| 抖音 | ✘ 列表/搜索需签名 | 取流协议已通，`web_rid → room_id` 转换待接 | ✘ 需 `X-Bogus` |

四家里三家视频已实测播放。抖音的「黑屏 / 200 但报错」这类**看起来是 bug、其实不是**的现象，连同踩过的坑一起记在 [`android/docs/接口实测.md`](android/docs/接口实测.md)。其中两条是反例，值得单独记住：

- **赛事号的赛前占位画面本身就是黑场**（标题「【预告】30日4点 …」，`live_status=1`、取流 4/4 通、弹幕照飘）。看到黑屏先看房间内容，别先怀疑 CDN。
- **`HTTP 200` 不等于成功**。抖音 `reflow/info` 参数错时返回 200 + `{"data":{"message":"Request params error"}}`，只看状态码会把失败判成成功。全仓库的接口断言都按「200 **且** 响应里出现只有成功才有的字段」写，脚本在 `android/tools/probe-sites.sh`。

---

## 焦点：不放边框

对齐 tvOS：**焦点不画环**，靠放大 1.08 + 封面提亮 0.86→1.00 + 标题转白 + 表面高光提亮来表达。真黑底上投影看不见，所以"浮起"交给 scale，"选中"交给明度与高光。加了白环会把界面切成一格一格的表格，也压过封面本身。

代价是网格必须留够放大的余量：卡片行间距 ≥ 36px，分组标题下沿距网格 ≥ 34px。

## 方向导航：按边缘间隙排序

四向键的落点不能用"中心点最近"来选。设置页从左侧导航按右，顶栏「首页」的水平中心只差 37px，右侧第一行设置差 904px——它就近了，焦点窜到顶栏去。窄而长的元素在中心点距离上永远占便宜。

现在分两步：

- **方向半平面用中心差**：中心必须在按键那一侧，否则这个元素根本不参与（这条保证不会走到背后去）。
- **排序用边缘间隙**：真分开了就按分开多远排，重叠记 0。副轴间隙乘 3，压住顶栏那种"水平很近、垂直隔了 300px"的候选。

方向判据不能也改成间隙——首页第一次按右，301 那张卡的左边缘比"进入直播间"按钮的左边缘还靠左，间隙是负的，可它明明是这一行最该去的地方。中心差能过，间隙不能。

改完跑了一遍全量：五个屏 125 个可聚焦元素 × 4 方向 = 500 次，**非边界卡住 0 次**。

## 按钮是玻璃，不是色块

tvOS 26 的控件材质是 **Liquid Glass**：半透明、会吸收并模糊背后的内容、表面有一道镜面高光。Apple 对 `CustomHoverEffect.highlight` 的说法是"投影效果配一道镜面高光，并随运动调整高光偏移"。

所以按钮不是一个纯色块，而是四层：

| 层 | 实现 |
| --- | --- |
| 底色 | 半透明白 + `backdrop-filter: blur(24px) saturate(180%)` |
| 顶沿 | `inset 0 2px 0 rgba(255,255,255,.98)` |
| 底部柔暗 | `inset 0 -16px 24px -10px rgba(0,0,0,.24)` |
| 底沿窄暗 | `inset 0 -3px 5px -3px rgba(0,0,0,.22)` |

底部那层柔暗就是所谓的"渐变"：白色主按钮实测顶部行亮度 **246**、底部 **229**，差 17 级。用 `inset` 光而不是 `linear-gradient`，是因为渐变不可过渡（焦点切换会硬切），而 `box-shadow` 能插值，高光才能跟着动。

外投影一律不做——真黑底上落进黑色等于没有。

进度的证据：播放器胶囊（`design/v1.0.0/screenshots/player.png`）压在视频上时能透出背后的观众席颜色，这是玻璃在工作的直接表现。

## 卡片的字阶：标题和副文本要差得开

卡片标题 26px，副文本（平台 · 主播 · 人数）单列一个 `--fs-card-meta: 18px`，不是和页面其它 meta 共用的 22px。

差 4px 的时候（26 / 22），三米外这两层会糊成一片，眼睛分不出哪行是标题、哪行是注脚。18px 拉出 1.44 倍，主次才读得出来。卡片是信息密度最高的地方，副文本本来就该比页面其它位置更弱一档。

顺带一个测量上的副作用：字号太接近时，连机器也分不清这两层是"一个标题折成了三行"还是"标题加副文本"，整张卡会被当成一个标题块量行数。拉开层级之后，这个误读自然消失了——不是绕过规则，是把规则想指出的问题真的解决了。

## 滚动：左固定，右独立滚

首页、关注页、分类页、设置页、搜索页——五个屏的滚动行为不一样，但只有两条规则：

**单栏页整页滚。** 首页和关注页是「hero + 若干分组」，往下滚的时候用户不需要记住"我刚才在哪一栏"，标题跟着走没有损失。热门推荐原本是单行横滑，改成和关注页同一套 `.card-grid` 竖排网格——横滑一次只能看四张，竖排一屏能看到八个，而且遥控器上下键的直觉就是"往下还有"。

**分栏页右栏单独滚，左栏钉死。** 分类页（分类菜单 | 网格）、设置页（分组 | 面板）、搜索页（键盘 | 结果）都是这个结构。左栏是"我在哪"的坐标系，跟着内容一起滚走，用户滚到第二屏就不知道自己站在哪个分类里了。

实现上只有一行是命门：

```css
.browse{flex:1 1 auto; min-height:0; display:flex}
```

`min-height:0` 不能省。flex 子项默认 `min-height:auto`，意思是"我不能比我的内容更矮"，于是内部那个 `overflow-y:auto` 的容器会被内容顶开，高度永远等于内容高度，滚动条永远不出现，整页跟着一起滚。**这是"我明明加了 overflow:auto 却不滚"的唯一原因。**

设置页的面板还多两行：

```css
.panel{align-self:flex-start; max-height:100%; overflow-y:auto}
```

`align-self:flex-start` 让内容少的组（比如「关于」只有 5 行）呈现为一张内容高度的卡片，而不是一块撑满整列、下半截空着的白板；`max-height:100%` 兜住内容超长的情况。两个缺一个都不对：只留 `flex-start` 内容超长时会溢出到屏幕外，只留 `max-height` 每张面板都会被撑满。

还有一个连带问题：面板变成自己滚之后，**换分组必须手动 `scrollTop = 0`**。上一层滚到一半切过来，新分组会从一个莫名其妙的位置开始。

滚动的量级（1920×1080）：

| 屏 | 滚动容器 | scrollH / clientH | 可滚 |
| --- | --- | --- | --- |
| 首页 | `.scroll` | 1773 / 964 | 809px |
| 分类 | `#browseScroll` 右栏 | 1920 / 920 | 1000px |
| 分类 | `.rail-col` 左栏 | 920 / 920 | 不动 |
| 设置 | `#sPanel` 右栏 | 876 / 773 | 103px |
| 设置 | `.sgroups` 左栏 | 773 / 773 | 不动 |
| 搜索 | `#searchScroll` 右栏 | 1027 / 777 | 250px |
| 搜索 | `.kb` 左栏 | 777 / 777 | 不动 |

面板只有 103px 可滚是刻意调的：原设置项加起来正好 773px，等于可视高度，**"支持滚动"是假的**——一条都不需要滚。扩到 23 项之后才真的滚得起来。

焦点移到视口外会自动带动最近的滚动祖先，不用写代码：`setFocus` 到最后一张分类卡，`#browseScroll` 自己滚到 940px，卡片落在 y=809–1080 完整可见。

## 动效

所有时间参数的来源是 tvOS 内置的 focus hover：**~200ms 的透视抬升 + 一道镜面光泽**。

| 场景 | 参数 |
| --- | --- |
| 焦点移动 | 200ms，`cubic-bezier(.32,.72,0,1)` |
| 按压回弹 | 120ms，`scale(.96)` |
| 顶栏胶囊滑动 | 260ms |
| 开关位移 | 180ms，`cubic-bezier(.4,0,.2,1)` |
| 开关变色 | 120ms，延后 55ms |

### 开关为什么不借用焦点曲线

开关和焦点是两件事：焦点是"一块面被举起来"，面可以极快起步；开关是"一个点滑过去"，点不行。

第一版直接用了焦点那条 `.32,.72,0,1`，主观反馈是"顿"。采样之后才知道顿在哪——每 20ms 读一次 `::after` 的 `translateX`：

```
t= 22ms   9.2px     ← 起步
t= 86ms  31.7px     ← 83% 已经走完
t=235ms  38.0px     ← 其后 150ms 只挪 6px
```

前 86ms 冲掉五分之四，剩下 150ms 在挪 6px。这不是"快"，是"冲一下然后卡住"。换成过冲曲线 `.34,1.4,.64,1` 更糟：64ms 就走完 80%，滑块早早到站，轨道怎么调都追不上。

最终换回 Material 的 standard 曲线 `cubic-bezier(.4,0,.2,1)`，总时长从 240ms 压到 180ms，每 20ms 的推进变成 **0.8 / 10.9 / 9.2 / 6.6 / 7.1 / 1.7**——一段连续滑行，末端 3.4px 摊在 72ms 里，肉眼读作"靠住"而不是"拖住"。

变色单独拆出来：延后 55ms、走 120ms、用焦点那条 `--ease`（这条曲线不能过冲，alpha 顶到 1 会提前饱和）。**先看见它滑过去，再看见颜色反过来**，两件事分开读，就不是一次闪烁。

轨道跟滑块共用同一条 `--sw-ease`——它们必须是同一个东西在动，轨道自己用一条更快的曲线就会抢在滑块前面闪一下。

一条 `transition` 必须同时覆盖 `transform` / `background-color` / `color` / `box-shadow`。漏掉 `box-shadow` 就会出现"块变大了、高光没跟上"的两半不同步。

顶栏五个页签**不是各画一块底**，而是一层玻璃胶囊跟着焦点滑（用 `offsetLeft/offsetWidth` 定位，不用 `getBoundingClientRect`——后者读到的是过渡中的中间值，胶囊会追着跑偏）。换屏时传 `instant`，胶囊直接出现在正确位置。

`prefers-reduced-motion` 下把时长压到 1ms 但**不删 transform**：本项目没有边框，缩放是唯一的焦点线索，状态必须到达、过程才去掉。

## 播放页：画面不调曝光

照 tvOS 26 的「始終以內容為觀看體驗的核心」改的。早先给视频挂 `filter:brightness(.78)`（收起控制条时 `.92`），等于用户看两小时直播，两小时都在看一张被调低曝光的画面。

量过才知道亏：对四张真实封面做条带亮度采样、再乘遮罩 alpha 算对比度，**这层压暗对顶栏文字的对比度只贡献 +0.4～+1.0（19.4 → 19.0 这种量级），代价是整幅画面亮度直接乘 0.78。**

现在 `filter` 去掉，只在顶栏、底栏压着的两条带子上加深遮罩（0–36% 与 55%–100%），**36%–55% 的画面中段一点不碰**；收起控制层时遮罩一起淡出，把整块屏幕还给内容。

顺带修掉一处：换台提示原用 `--faint`(#8E8E93)，落在最亮那张封面（LED 墙）上实测只有 **2.55:1**，改成 `--muted` 并加深底栏遮罩后是 **4.74:1**。可读性是文字的责任，不能反过来要求整部片子压暗。

细节见 [`design/system/设计规范.md` 第九节](design/system/设计规范.md)，逐条对照在 [`design/system/apple-tvos-对照.md`](design/system/apple-tvos-对照.md)。

---

## 图标与字标

五档 mipmap 的源是 `design/brand/wordmark/src/square-*.svg`，交付位图在 `design/brand/wordmark/export/`，
两者逐字节相等由 `android/tools/sync-icons.mjs` 断言。48 与 72 两档交付 `LH` 单字标、96 以上交付完整
`LiveHub` 字标 —— 这条分界是按**竖干 ≥ 2px** 算出来的，不是按档位拍的；`square-48-A` 是那个阈值的
对照组（不采用、留痕）。规矩、取证与 banner 两种图的差别都在 [`design/brand/README.md`](design/brand/README.md)。

---

## 质检（历史结论，工具产物不落仓库）

静态规则与渲染测量由本机 design skill 的脚本跑（`lint.mjs` / `inspect.mjs`，不在本仓库内），
输出写 `$TMPDIR`。下面是定版时的结论，留档备查：

```bash
# 静态规则（禁用模式、渐变、阴影、默认字体…）
node ~/.workbuddy/skills/design-taste-frontend/scripts/lint.mjs design/v1.0.0/ui.html --app
# → 0 FAIL（34 WARN：23 处玻璃内高光 + 10 处 backdrop blur + 1 处 em-dash，
#    全部是玻璃材质的组成部分，已在下方逐条说明）

# 渲染后测量（对比度、裁切、密度、字号、色彩占比）
node ~/.workbuddy/skills/design-taste-frontend/scripts/inspect.mjs \
  design/v1.0.0/ui.html --app --out "$TMPDIR/lh-inspect" --widths 1920 \
  --allow edge-text,button-fx
# → 0 FAIL, 1 WARN（app-title-size，见下）

node ~/.workbuddy/skills/design-taste-frontend/scripts/inspect.mjs \
  design/v1.0.0/ui.html --app --widths 1440,768,390 --allow button-fx
# → 0 FAIL, 2 WARN，多出的一条是卡片上的 LIVE 红标对比度 3.4:1。红底白字是直播这个状态
#   在各家平台上通行的表达，压到 4.5:1 会变成砖红，反而丢掉"正在直播"该有的跳。
#   19px/600 属于大字，按大字 3:1 的标准是达标的。

# 遥控器真实可操作性 + 焦点困住检测（自测脚本与出图脚本已随定版归档移除，结论留档）
# → 14 步链路全通（含播放页 控制条/空闲 三态），焦点原地不动 6/40，全部是边界：
#   首页 hero 按钮在左上角、顶栏 tab 在最上、网格首列按左、设置页左栏最左与右栏最右
# → 另附搜索断言：字母数字同屏、无 123/ABC 模式键、
#   660000 / 9999 / lpe / xiaojiuguan / 88 分别命中房间号·短号·拼音·抖音号·前缀，空查询 0 条
```

**为什么 lint 要带 `--app`**：app 模式的 `direction.json` 与页面模式的判据不同，不带参数时 `shadow` 会按页面规则判 FAIL。带 `--app` 后按 app 规则降为 WARN。

允许项及其理由：

- `button-fx` —— 这条规则的判据是**任何** `box-shadow`，不区分内外。全文件 86 个 `<button>` 里 46 个带 `box-shadow`，**46 个全部是 `inset`**（`--spec*` 三层镜面高光），**外投影 0 个**——量过，不是估计。inset 光只画在元素内部，物理上不可能形成外发光。这是 Liquid Glass 的材质本身，去掉它按钮就退回纯色块。lint 那条 `shadow` WARN（23×）也是同一批：渲染后 `shadowStatic` 实测 **0**，因为全部被 inset 过滤掉了。
- ~~`covered-text`~~ —— 上一版的允许项，**已随台号一起消失**。它当时报的是首页第三行卡片的台号（`205` / `403`）被压住；那两块文字正是虚构的台号，改成平台名之后这条 FAIL 不再出现，允许项也就撤掉了。`edge-text` 同理：横向滚动行改竖排网格后不再报。
- `app-title-size`（WARN）—— 主图标题 76px 是按 3 米观看距离定的；桌面 app 的 18–32px 不适用于 10-foot UI。

**不带 `--app` 时会多报一条 `reduced-hidden`**（1440 档报 177 个文本块）。原因很具体：这条检查遍历整个 `body`，把每段文本沿祖先链乘 `opacity`、查 `visibility`；而六屏是 `position:absolute; inset:0` 叠在一起的，非当前屏是 `opacity:0; visibility:hidden`。它数到的是**五个没打开的屏**，不是"等动画的隐藏态"——`prefers-reduced-motion` 下切屏时长已经压到 1ms，当前屏当帧就到位。这条检查建立在"一页一屏"的假设上，`--app` 模式下它不跑。

---

## 尚未做

- **抖音原生列表 / 分区 / 搜索 / 弹幕签名**：需要 WebView 或 QuickJS 跑平台下发的签名脚本（`X-Bogus`）。首版不引入 JS 引擎——为四条里的一条塞一个浏览器内核，代价和收益不成比例。抖音目前只能靠房间号直达播放。
- **账号扫码登录与关注列表云同步**：关注列表已按各家自己的 `rid` 在本地持久化，跨设备同步没有做。
- **多用户个人档案**（tvOS 最佳实践里有这一条：减少登录频次、唤醒时自动显示档案）。本设计全站 0 处档案概念，一家人共用一台电视时关注列表会混在一起。加"谁在看"会改设置页的信息架构，需要产品决策，没有顺手塞进去。
- **字号缩放 / Dynamic Type**：原型文件 0 处 `rem`、41 处硬编码 `font-size`，系统级字号放大对此设计完全无效。要支持的话正确做法是把 `--fs-*` 全部改成 `calc(<基准> * var(--fs-scale))`，一个变量控全站；Compose 侧对应 `LocalType` 的 `TypeScale`。
- **Apple TV 版**：暂不做，但约束已经预留——取流层与弹幕层不依赖 Android API（协议实现是纯 Kotlin + OkHttp），播放内核换 AVPlayer、HLS 优先（虎牙只保留 HS 系 HLS 也是为此）。

## 参考

- 斗鱼直播流获取参考了 [@wbt5/real-url](https://github.com/wbt5/real-url)
- 抖音弹幕参考了 [@saermart/DouyinLiveWebFetcher](https://github.com/saermart/DouyinLiveWebFetcher)
- 虎牙参考了 https://github.com/liuchuancong/pure_live https://github.com/ihmily/DouyinLiveRecorder
- b站弹幕参考了 https://github.com/xfgryujk/blivedm
