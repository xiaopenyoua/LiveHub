# design/brand/ — 品牌资产的源与交付位

这是全仓库**唯一**存放品牌图形的地方。它有两个子层，界线是一条命令能不能重建：

```
wordmark/src/*.svg      矢量真源   —— 人画的，不可再生（9 个）
wordmark/export/*.png   交付位图   —— 由 src 光栅而来，可重建，但**进版本库**（记录位，见下）
build-icons.mjs         那个"一条命令"（光栅器，只写本目录）
```

**它不属于 `android/`，也不给 `android/` 写任何东西。** 把位图送进 `res/mipmap-*/` 是
`android/tools/sync-icons.mjs` 的事 —— 谁拥有目标目录，谁执行写入（`AGENTS.md` §2 R9）。
`res/drawable-xhdpi/tv_banner.png` 是唯一例外：**手工档**，不在自动管线上（见下面「banner 有两种图」）。

---

## 单向管线

```
design/brand/wordmark/src/*.svg
        │  node design/brand/build-icons.mjs                   光栅化（Chrome/CDP，默认写 export/）
        ▼
design/brand/wordmark/export/*.png                            ← 交付位图 · 记录位
        │  node android/tools/sync-icons.mjs --write          拷贝 + 逐档 md5 断言（只 5 档）
        ▼
android/app/src/main/res/mipmap-*/ic_launcher.png             ← 交付副本
        │  ./gradlew assembleDebug
        ▼
APK                                                           ← 不进仓库

banner 支线（不在管线上，人负责）：
wordmark/src/banner-16x9-bleed.svg ──渲一次+人工确认──▶ res/drawable-xhdpi/tv_banner.png
```

反向即违规：`res/` 的字节不许在 `design/` 下再存一份，`export/` 不许被 `res/` 反向覆盖。

---

## `src/` 的规矩

- **只放 `.svg`。** 位图、脚本、截图、中间产物一律不进（`verify-layout.sh` 第 3 项会拦）。
- **改矢量必须是人决定的。** 脚本只读它，不写它。
- 命名 = 画布 + 构成，不写版本、不写状态：`square-<画布>.svg`、`banner-16x9[-bleed].svg`。

九个文件其实是**同一套字形数据的九个取景框**：所有 `path` 的 `d` 逐字节相同（首个 path 的 md5 都是
`ea7dc6b4…`），不同的只有画布尺寸与 `transform` 的缩放平移。所以"改图标"不是改这九个文件，
是改字形本身 —— 现存两套构成：

| 构成 | `path` 数 | 出现在 | 一个 `L` 的竖干 |
| --- | --- | --- | --- |
| 完整字标 `LiveHub` | 7 | `square.svg`(512) · `square-96/144/192` · `square-48-A` · 两个 banner | 随画布缩放 |
| 单字标 `LH` | 2 | `square-48-B` · `square-72` | 见下表 |

---

## `square-48-A` 与 `square-48-B`：同一张画布，两种构成

**像素尺寸不是唯一键 —— 48 这一档同时存在两张，这是全目录唯一的例外，且有据。**

| | `square-48-A` | `square-48-B` |
| --- | --- | --- |
| 构成 | 完整字标 `LiveHub`（7 path） | `LH`（2 path） |
| 画布 / 圆角 | 48×48 / `rx=7.44` | 48×48 / `rx=7.44`（同） |
| 字号 | 10.39 | 23.91 |
| cap 高 | 7.56 px | 17.40 px |
| 竖干 | 1.28 px → 光栅后 **1.34 px、半透明** | 2.95 px → 光栅后 **3.1 px、实心** |
| 白墨覆盖率 | 51% | 100% |
| 去向 | **不进 `res/`，不采用；留痕** | `res/mipmap-mdpi/ic_launcher.png` |
| 证据 | `design/v1.0.0/spec/07-字标图标与开屏规格.md`、`08-品牌裁决.md`、`icon.html` 第 238 行图注 | 同上，`icon.html` 的 md5 表 |

**为什么必须两张都在**：48 画布上完整字标光栅化后笔画不到 1.5 px 且 51% 半透明，进电视桌面栅格就是一坨灰。
切换阈值是按**竖干 ≥ 2 px** 算的，不是按档位清单拍的：完整字标需画布 ≥ 75.1，`LH` 只需 ≥ 31.1，
所以阈值落在 72 与 96 之间。**相邻两档是两种构成，是规则的结果，不是 bug**；`48-A` 是那条阈值的对照组，
删掉它就没人能复核这个决定。同理 `square-72` 交付 `LH`，`square-96/144/192` 交付完整字标。

---

## `export/` 的规矩

- **只放 `.png`。** 命名 = `square-<像素>.png` / `banner-<宽>.png`，一张一个尺寸，一个尺寸一个名字。
- **它是记录位，不是缓存。** 一般来说"能被一条命令重建的东西不进版本库"，
  这里破例是为了两个理由：① 冻结的定稿稿按相对路径引用它（`splash.html` 引 `square-192.png`、
  `icon.html` 引五个尺寸）；② `res/` 的字节以它为断言基准。**破例的边界是：只有
  `build-icons.mjs` 不带参数跑（默认就写这里）能写它；`--check` 只在内存里比、零写入。写入前要人确认。**
- **它是平的。** 不按 `mipmap-mdpi/` 那种 res 形状分目录 —— 一按 res 形状分，它就成了 `res/` 的副本，
  而副本就要同步，同步就要断言，断言失败就没人知道哪边对。

当前 12 张：

| 文件 | 尺寸 | 身份 |
| --- | --- | --- |
| `square-48-A.png` | 48×48 | 阈值对照组（不交付）+ 冻结稿引用 —— 残差 1.8678 |
| `square-48-B.png` | 48×48 | mdpi 交付档 + 冻结稿引用 —— 残差 1.5291 |
| `square-72.png` | 72×72 | hdpi 交付档 + 冻结稿引用 —— 残差 1.5774 |
| `square-96.png` | 96×96 | xhdpi 交付档 + 冻结稿引用 —— 残差 **2.0605**（三枚之一） |
| `square-144.png` | 144×144 | xxhdpi 交付档 + 冻结稿引用 —— 残差 1.9986 |
| `square-192.png` | 192×192 | xxxhdpi 交付档 + `splash.html` 引用 —— 残差 **2.0605**（三枚之一） |
| `square-512.png` | 512×512 | **Google Play 商店图标唯一交付档（512×512，上架必需）** + 证据对象（残差 1.9910） |
| `banner-160.png` | 160×90 | `icon.html` 展示档（带圆角）+ 证据对象（残差 1.9986） |
| `banner-240.png` | 240×135 | `icon.html` 展示档（带圆角）+ 证据对象（残差 1.9910） |
| `banner-320.png` | 320×180 | `icon.html` 展示档（带圆角）+ 证据对象（残差 1.9910）—— **不同步进 `res/`** |
| `banner-480.png` | 480×270 | 证据对象（残差 1.9986）—— **不可删** |
| `banner-640.png` | 640×360 | 证据对象（残差 **2.0605**，三枚之一，`08:37` v3.7 段点名）—— **不可删** |

**这 12 张必须齐，少一张就有读数无从复核。** `design/v1.0.0/spec/08-品牌裁决.md` §6.2（`:1004`）那张残差表，是**逐枚点着这 12 个文件量的**：阈值下界 **2.0605** 由三枚同时取到（`square-96` / `square-192` / `banner-640`），其余九枚都 **≤ 1.9986**，每枚的数值都挂在一个具体文件名上。

所以这里「没人 import 它」**不等于**「可删」。`AGENTS.md` R6 的删除判据是「`grep` 零命中 **且** 有生成命令」——两个条件缺一不可，而在这 12 张上一个都不成立：它们有生成命令，同时被冻结裁决按名字引用着。**删掉 `banner-480` 的后果不是"少一个 480×270 的 png"，是 §6.2 里那个 `1.9986` 从此无人可核。** 证据不是垃圾，删证据比删垃圾更坏。

`square-512` 另有一层身份：它不是「`square.svg` 的一条可选导出」，是 **Play Store 上架唯一认的那一档尺寸**。所以它同时是交付档和证据对象，两头都不可删。

---

## banner 有两种图，别把它们当成一对

`src/` 里有两个 banner 源，差别只有圆角 —— 它们产出的是**两种用途不同的图**，不是两代同一张：

| 源 | `rect` | 产出 | 服务谁 | 谁生成 |
| --- | --- | --- | --- | --- |
| `banner-16x9.svg` | `rx=27.9` | `export/banner-*.png`（RGBA，角透明） | 浏览器里的 `icon.html`：**展示档** | `build-icons.mjs`（自动） |
| `banner-16x9-bleed.svg` | 无 `rx`（满版） | `res/drawable-xhdpi/tv_banner.png`（RGB，不透明） | TV launcher：**交付档** | **手工档**：渲一次、人工确认、放置 |

**交付档必须满版**：launcher 会对 banner 再裁一次圆角，图里再画一层就是双重圆角。
**展示档必须带圆角**：它是给人看"这图长什么样"的，直角会让预览失真。

实测（PNG 头 + 角像素）：

| 文件 | 尺寸 | colortype | 左上角像素 | 结论 |
| --- | --- | --- | --- | --- |
| `export/banner-320.png` | 320×180 | `6` = RGBA | `[0,0,0,0]` 全透明 | 圆角展示档，**正确** |
| `res/drawable-xhdpi/tv_banner.png` | 320×180 | `2` = RGB | `[35,28,22]` 不透明 | 满版交付档，**正确** |
| `export/square-192.png`（对照） | 192×192 | `6` = RGBA | 角透明 | 方形图标本来就有圆角 |

**所以这两张本来就不该相等，也不该同形** —— 上一版把 `banner-320.png → tv_banner.png` 写成
`sync-icons.mjs` 里的一条映射，是**把两种用途当成了一对**，那条映射已经删掉。
`sync-icons.mjs` 现在只覆盖 5 档 mipmap，`--check` 应当 5/5 绿。

### 为什么 banner 不自动化

那条路必然漂：现在生成器渲出的 bleed 版与 `res/` 里那张，边缘差约 130 px、最大通道差 38/255
（`res/` 那张是更早的 Chrome 渲的）。一旦自动化，每跑一次就会静默覆盖 `res/` ——
而字节断言的意义恰恰是**不一致时停手**，自动重写会让它永远不一致、永远不停手。

代价要认：`tv_banner.png` 没有"一条命令重建"的保证，改 `banner-16x9-bleed.svg` 之后
**必须人工重渲并放置**，再跑 `verify-layout.sh` 第 6 项确认它仍是 RGB 满版。
这是全目录唯一一处"人负责同步"的地方，所以它写在 `AGENTS.md` §6 里，不藏在脚本注释里。

---

## 两档 md5 严格性，不是妥协

| 比对 | 中间发生了什么 | 判据 |
| --- | --- | --- |
| `src/*.svg` ↔ `export/*.png` | **光栅化**（Chrome/CDP） | 允许差异。Chrome 版本一变，边缘抗锯齿就差几个字节 —— 所以 `export` 才是"人确认过的记录位"，不是每次重新生成的缓存 |
| `export/*.png` ↔ `res/mipmap-*/*.png` | **拷贝** | **必须逐字节相等**，差一个字节就是错（5 档，banner 不在其中） |

```
bash android/tools/verify-layout.sh           # 第 6 项：5 档字节相等 + banner 形状
node android/tools/sync-icons.mjs             # 只判 5 档：任何 ✗ 都是真的
node design/brand/build-icons.mjs --check     # 只当情报：src↔export 的 △ 是抗锯齿，不是错
```

---

## 永远不进这里

- 任何 `res/` 形状的目录（`mipmap-*` / `drawable-*` / `values`）—— 那是 `res/` 的第二个真相源
- Android 专用 XML（自适应三层、`anydpi-v26|v33`、`colors.xml`）—— 那些是**开发侧代码**，
  与 `square.svg` 有同源义务，但归 `android/`
- 代次目录（`v1/`、`v2/`）或文件名里的 `v2` —— 品牌代次用**语义名**（现行 `wordmark`，已废 `triangle`），不编号
- 预览缩略图、取证截图、`build/` 中间产物
- 别处来的 `res/` 字节拷贝
