# AGENTS.md · 目录契约与执行规则

> **先读这份，再动手。** 本文件是仓库里**唯一的归属判据**：任何其他 README 描述的目录结构
> 与本文冲突时，以本文为准。每条规则都可判定 —— 每条都给出一条能当场跑的命令，以及
> 「违规长什么样 / 正确长什么样」。改完自检：`bash app/tools/verify-layout.sh`（退出码 0 才算干净）。
> 它**不是提交闸门** —— 它的作用是「改完自己看一眼」，不是「不跑不许提交」。

---

## 0. 三十秒版

**三个内容目录，一个方向。**

```
design/     设计侧 —— 设计稿、规格、品牌矢量源、稿子素材
app/        开发侧 —— Flutter 五包工程、被平台直接吃掉的资源、开发脚本
docs/       判断依据 —— 为什么做这个产品、参考的那个 App 是怎么做的
```

**单向管线（只有这一个方向，反向即违规）：**

```
design/brand/wordmark/src/*.svg                  ← 唯一矢量源（9 个 SVG）
        │  node design/brand/build-icons.mjs      光栅化（默认写 export/；--check 零写入）
        ▼
design/brand/wordmark/export/*.png               ← 交付位图 · 记录位（12 张）
        │  node app/tools/sync-icons.mjs --write  拷贝 + 逐档 md5 断言（10 档）
        ▼
app/livehub/android/app/src/main/res/mipmap-*/ic_launcher.png
app/livehub_tv/android/app/src/main/res/mipmap-*/ic_launcher.png
        │  flutter build apk
        ▼
APK                                              ← 可执行产物（不进仓库）
```

**这张图有一条支线，别把它读成主干**：TV banner
`app/livehub_tv/android/app/src/main/res/mipmap-xhdpi/ic_banner.png` **不在自动管线里**。
它是**手工档** —— 真源是 `banner-16x9-bleed.svg`，渲染一次、人工确认、放进去就不再自动同步
（理由见 §6）。图上把它画进来会撒谎，所以它只在图外说。

**四条最常被违反的禁令（先记住这四条就够用）：**

1. **设计侧不写 `app/`。** `design/**` 下的任何脚本都不得写 `app/**`。把位图送进各包图标目录
   只有 `app/tools/sync-icons.mjs` 一条路（TV banner 是手工档，见 §6）。
2. **平台资源的字节不在 `design/` 下再存一份。** 需要在本机留一份的，是 `export/`
   （那是源的光栅产物，不是平台资源目录的副本）。
3. **可再生且无人引用的字节，删掉，不要建 `archive/`。**
4. **文件名里不出现 `v2` `final` `new` `old` 这类词。** 它描述的是"处在什么阶段"，
   不是"画的是什么"。

---

## 1. 目录契约

格式：**`目录` — 谁的产物；只进什么；永远不进什么；谁有写权限**

| 目录 | 谁的产物 | 只进 | 永远不进 | 写权限 |
| --- | --- | --- | --- | --- |
| `app/` | 开发侧全部产物 | Flutter 五包工程、平台资源、开发脚本、上游归属文件 | 设计稿、品牌矢量、`build/`、`.dart_tool/`、`node_modules/` | 开发侧 |
| `app/livehub/` | 手机 / 桌面客户端（Android · iOS · macOS · Windows · Linux） | `lib/`、`assets/`、`pubspec.yaml`、各平台目录 | 另一份设计稿、品牌 SVG | 开发侧 |
| `app/livehub_tv/` | 大屏客户端（Android TV · Windows） | `lib/`、`assets/`、`pubspec.yaml`、`android/`、`windows/` | 手机专属平台目录、设计稿 | 开发侧 |
| `app/livehub_core/` | 四平台取流与弹幕核心库 | `lib/`、`packages/`、`test/`、`pubspec.yaml` | UI 主题、平台资源 | 开发侧 |
| `app/livehub_ui/` | 共享设计系统（tokens / theme / glass / components） | `lib/`、`assets/fonts/`、`pubspec.yaml` | 业务页面、平台资源 | 开发侧 |
| `app/livehub_console/` | 命令行探测工具 | `bin/`、`lib/`、`test/`、`pubspec.yaml` | 平台资源、UI | 开发侧 |
| `app/tools/` | 开发侧脚本 | 跑在本机、读写 `app/` 或读 `design/` 的脚本 | `*/build/` 下的任何东西 | 开发侧 |
| `app/*/lib/` | Dart 源码 | `.dart` | 图片、字体、平台配置 | 开发侧 |
| `app/*/assets/` | Flutter 资源（`flutter: assets:` 直接吃） | 图片、图标、Lottie、字体 | 另一份品牌矢量源、平台图标目录的副本 | 开发侧 |
| `design/` | 设计侧全部产物 | 定稿 `.html`、规格与裁决 `.md`、品牌 `.svg`、稿子引用的字体与图片 | 平台资源（`mipmap-*` / `drawable-*` / `values`）、APK、任何 `build/`、取证截图批 | 设计侧任何脚本，**但不得写 `app/`** |
| `design/brand/` | 品牌资产的源与交付位 | `wordmark/src/*.svg`、`wordmark/export/*.png`、光栅脚本、本目录 README | 任何平台资源形状的目录、代次编号目录（`v1/` `v2/`） | `build-icons.mjs`（只写 `src`↔`export`） |
| `design/brand/wordmark/src/` | **唯一矢量源** | `.svg` only | `.png`、任何位图、任何由脚本产出的中间物 | 人（改矢量必须是人决定） |
| `design/brand/wordmark/export/` | **被引用的交付位图（记录位）** | 出货档 + 有引用者的留痕档 + 展示档，`.png` only | 无引用者的导出、缩略图、预览缩略、平台资源的字节副本 | `build-icons.mjs`（默认写这里；`--check` 零写入） |
| `design/system/` | 跨版本设计规则 | 当前有效的规则文本 | 版本目录、历史版本（历史在 git）、并存两套值 | 人 |
| `design/media/` | 稿子的运行时素材 | 稿子以相对路径引用的字体与图片 | 矢量（归 `brand/`）、截图、平台资源 | 人 |
| `design/v1.0.0/` | 设计定稿（冻结） | 本版定稿稿、`spec/`、`shots/`、`screenshots/` | 跨版本素材、下一版草稿（下一版另开 `v1.1.0/`） | 冻结后只许加 errata |
| `design/v1.0.0/spec/` | 本版规格与裁决记录（**唯一编号空间**） | `NN-中文标题.md` | 代码、截图、跨版本内容 | 只许在文首加 errata |
| `design/v1.0.0/shots/` | 开屏定稿帧 | 被 `spec/` 或 `.html` 引用过的帧 | 未引用的批次帧、`v2-` 之类批次前缀 | 可重拍、可删 |
| `design/v1.0.0/screenshots/` | 原型定稿截图 | `<屏名>.png` | 编号、中间版本 | 可重拍、可删 |
| `docs/` | 既不属设计也不属开发的判断依据 | 「为什么做」「参考的是什么」 | 实现细节、设计规格、截图与取证 | 人 |
| `README.md` | 仓库总索引 | 「东西在哪」+ 规矩摘要 | 规格正文（正文在 `spec/`） | 人 |
| `AGENTS.md` | **本文件：归属判据** | 规则与禁令 | 任何具体规格内容 | 人 |

**"删掉它损失什么"四问（新增顶层目录前必须答）：** `design/` → 失去不可再生的设计定稿与品牌源；
`app/` → 失去可执行工程；`docs/` → 失去"为什么做这个产品"的唯一叙述；
`AGENTS.md` → 失去归属判据，下一个人会重新把东西堆成垃圾场。

**当前没有 `app/docs/`。** 实现文档先按包写在 `app/<包>/README.md`；将来若要集中，
再在 `app/docs/` 落，不要提前建空目录。

---

## 2. 规则清单

### R1 · 顶层只有三个内容目录

```
ls -d */            # 只允许 app/ design/ docs/
ls -d assets build tools tmp out dist misc 2>/dev/null ; echo "^ 必须为空"
```
- ❌ 违规：`assets/`（混装袋）、`build/`（产物位）、顶层 `tools/`（跨侧脚本没有归属）
- ✅ 正确：素材进 `design/media/`，产物进 `$TMPDIR`（或各包 gitignore 的 `build/`），
  跨侧脚本进 `design/brand/` 或 `app/tools/`

### R2 · 单一归属：一个字节只能属于一侧

判定法：**它能被 `flutter build` 直接吃掉吗？**
- 能（`lib/`、`assets/`、`pubspec.yaml`、平台目录里的资源与配置）→ `app/`
- 它是设计稿 / 规格 / 矢量 / 稿子素材 → `design/`
- 都不是 → `docs/`，或者按 R6 删除

```
find design -type d \( -name 'mipmap-*' -o -name 'drawable-*' -o -name values \)   # 必须为空
find app \( -name .dart_tool -o -name build -o -name Pods -o -name ephemeral \) -prune \
     -o -type f \( -name '*.svg' -o -name '*.html' \) -print                        # 必须为空
```
- ❌ 违规：`design/**/android-res/mipmap-mdpi/ic_launcher.png`（给同一份字节造了第二个真相源）
- ✅ 正确：`app/livehub_tv/android/app/src/main/res/mipmap-mdpi/ic_launcher.png` 唯一存在；
  设计侧要看它，看 `design/brand/wordmark/export/square-48-B.png`

### R3 · 源与产物分层：能重新生成的，不和源放一起

判定法：**若 B 能由 A + 一条命令重新生成，B 不得与 A 同目录。**
```
find design/brand/wordmark/src    -type f ! -name '*.svg'   # 必须为空
find design/brand/wordmark/export -type f ! -name '*.png'   # 必须为空
```
- ❌ 违规：`src/square.svg` 与 `src/square-96.png` 并排
- ✅ 正确：`src/square.svg`（源） → `export/square-96.png`（产物，可重建）

**唯一的例外，且必须写清**：`export/` 虽然是产物，但它是**记录位**（被冻结的定稿稿按相对路径引用，
且平台图标目录的字节以它为断言基准），所以它进版本库。这个例外的边界是：
**只有 `build-icons.mjs` 能写它，且写入前要人确认。**

### R4 · 只有 `design/<版本>/spec/` 能用 `NN-` 前缀

```
find . \( -path ./.git -o -path ./.workbuddy -o -path ./.playwright-mcp \
       -o -name build -o -name .dart_tool \) -prune \
     -o -name '[0-9][0-9]-*' -print | grep -v '/design/[^/]*/spec/'   # 必须为空
```
- ❌ 违规：`docs/02-设计规范.md`（和 `spec/02-图标几何规格.md` 撞车，读者按编号走错门）
- ❌ 违规：`screenshots/01-home.png`（编号只被当排序用，起不到地址作用）
- ✅ 正确：`spec/02-图标几何规格.md`；`screenshots/home.png`；`docs/市场调研.md`

**编号是永久地址：作废不复用，允许空号。** `spec/` 现在是 `01,02,03,04,07,08` ——
05/06 是历史空号，**不要拿它们给新文档补位**。

### R5 · 文件名出现 `v<数字>` 即违规

```
find design docs -name '*v[0-9]*' -print | grep -v -E '/v[0-9]+\.[0-9]+\.[0-9]+$'
```
- ❌ 违规：`08-品牌裁决-v2.md`、`shots/v2-splash-t500.png`、`splash-v1.html`
- ✅ 正确：`08-品牌裁决.md`、`shots/splash-t500.png`、`splash.html`

**范围说明（不然会误报）**：`app/` 下 `mipmap-anydpi-v26`、`values-v21` 里的 `-vNN`
是 **Android 平台规定的 API 限定符**，不是版本轴，不受此规则约束。所以这条只扫 `design/` 与 `docs/`。

### R6 · 不归属即删除，并且不建 `archive/`

判定法：**`grep` 零命中 且 有生成命令 → 删。** 两个条件缺一不可（零命中但没有生成命令的，
是"证据"，不是垃圾）。
```
find . \( -name .git -o -name .dart_tool -o -name build -o -name node_modules \) -prune \
     -o -type d \( -name archive -o -name old -o -name backup -o -name tmp -o -name out \
                  -o -name dist -o -name misc \) -print   # 必须为空
```
- ❌ 违规：`archive/2026-10/`、`old/`、`final/`、`.workbuddy/test/`（取证 png 批）
- ✅ 正确：删掉，结论写进 README 或 `spec/`；**历史在 git，不在目录里**

**为什么不建 `archive/`**：它一定会变成第二个垃圾桶 —— 进去了就没人再判断"能不能删"，
而仓库的可读性只取决于"里面的东西是否都还有用"。删掉是可逆的
（`git log --diff-filter=D`），存档是不可逆的（它把一次判断变成了永久负债）。

### R7 · 冻结记录只许加 errata，不许改写正文

**R7 只管冻结记录。** 冻结的只有 `design/v1.0.0/` 这一层：`spec/` 下六份裁决证据，以及
`ui.html` / `splash.html` / `icon.html` 三个定稿（定版即冻结，改动另开 `v1.1.0/`）。
它们的正文里有 `git show HEAD:…` 这类历史命令，目录一变这些路径就"不存在"，
但**"文件没了"和"证据没了"是两件事**。

- ❌ 违规：把 `spec/08` 里的历史路径全局替换成新路径（等于伪造当时的命令）
- ✅ 正确：正文一字不动，文首加一条

```
> 路径时效（YYYY-MM-DD）：本文件中的 `assets/brand/v2/icon/` 现位于
> `design/brand/wordmark/src/`。正文里的 `git show HEAD:...` 保留原样，作历史坐标读。
```

**其余全是活文档，不受 R7 保护：** `app/<包>/README.md`、`design/system/**`、`docs/**`、
各个 `README.md`。它们还会继续长，里面的路径必须是**今天能用的**。

| 文档性质 | 例子 | 路径怎么办 |
| --- | --- | --- |
| **冻结记录**（`design/v1.0.0/spec/**`） | `git show HEAD:assets/brand/v2/icon/…` | 正文一字不动，文首加 errata |
| **活文档**（`app/**/README.md`、`design/system/**`、各 `README.md`） | `grep -n … docs/技术方案调研.md`、「我跑这条得到 0」 | **路径必须改成当前可用的**；旧名留一句 errata 说明它原先叫什么即可 |

**为什么必须这么分**：活文档里的旧路径不是"历史坐标"，是**一条必然失败的命令** ——
读者复制粘贴就报错，那不是诚实，那是坑。冻结记录的旧路径才是历史坐标，因为它的价值在于
"当时我确实敲了这条"。`verify-layout.sh` 第 7 项按这条分界判：`design/*/spec/` 里的断链只报数，
契约负责的活文档断链是 FAIL。

### R8 · 禁止的目录名

`assets` `build` `tmp` `out` `dist` `misc` `archive` `old` `backup` `assets2` `vN`
（除 `design/v<X.Y.Z>`）。理由：这些名字不回答"是谁的产物"，于是每个进来的人都会往里扔不同的东西。

```
find . \( -name .git -o -name .dart_tool -o -name build -o -name node_modules \) -prune \
     -o -type d \( -name archive -o -name old -o -name backup -o -name tmp -o -name out \
                  -o -name dist -o -name misc -o -name assets \) -print \
     | grep -v -E '/app/[^/]+/assets$|/app/[^/]+/[^/]+/packaging/[^/]+/assets$'   # 必须为空
```

**豁免一：`app/<包>/assets/`。** 判据两条，缺一不可 ——
① 路径形如 `app/<某个包>/assets`（**层级固定**，`app/livehub/assets` 是，`app/x/y/assets` 不是）；
② 它是 Flutter 的 `pubspec.yaml` 里 `flutter: assets:` 直接引用的资源目录。

为什么给它开豁免：这个 `assets` 的名字是**忠实的** —— Flutter 生态把它固定叫 `assets/`，
`pubspec.yaml` 按这个路径打包；改成别的名字，代价是连 `pubspec.yaml` 与所有 `AssetImage`
一起改，零收益纯风险。它**不是** R8 想禁的那类混装袋：它的归属由父包回答，内容单一。
豁免只此一种形状 —— 别拿"反正 Flutter 也这么叫"当理由，往 `design/`、`app/<包>/<平台>/`、
或任何非 `app/<包>/` 的目录里再建一个 `assets/`。

**豁免二：`app/<包>/<平台>/packaging/<打包器>/assets/`。** 判据同样两条 ——
① 路径形如 `app/livehub/linux/packaging/deb/assets`（层级固定，打包器如 `deb` / `rpm`）；
② 该目录被同一层打包配置引用（例如 deb 的 `icon: assets/logo.png`）。
这是 `flutter_distributor` 一类打包器按相对路径取图标的约定，名字由工具决定；
它只放打包用图标，不是内容混装袋，也不放 Flutter `pubspec.yaml` 的资源。

`build/` 是构建产物位，不是"内容目录"：各包 `.gitignore` 已忽略，`verify-layout.sh` 会 prune 掉它，
不判违规；但**不要手工往里放东西**，也不要把它提交。

### R9 · 脚本归属：谁拥有目标目录，谁执行写入

判定法：**看脚本的写路径。写进谁的目录，脚本就属于谁。**
```
grep -rnE '(writeFileSync|mkdirSync|copyFileSync|rmSync|renameSync)\(.*app/' \
     design --include='*.mjs' && echo "^ 必须为空（只允许出现在注释里）"
```
- ❌ 违规：`design/brand/build-icons.mjs --apply` 直接写 `app/livehub/android/app/src/main/res/`
  —— 设计侧持有开发侧的写权限，这是唯一能让平台图标被静默覆盖的通道
- ✅ 正确：两个脚本各管一段
  - `design/brand/build-icons.mjs`：读 `src/*.svg`，默认写 `export/*.png`
    （`--check` 只在内存里比、零写入；`--out DIR` 换目标），**不碰 `app/`**
  - `app/tools/sync-icons.mjs`：读 `export/*.png`，写两个包的
    `mipmap-*/ic_launcher.png`（10 档），同一次运行内逐档 md5 断言；默认 `--check` 零写入

### R10 · 跨目录引用写全路径 + `§节`

- ❌ 违规：`见 02 第四节`、`见 docs/02`（路径不明、节不明）
- ✅ 正确：`见 design/system/设计规范.md 第四节`、`见 docs/技术方案调研.md §4.1`

```
bash app/tools/verify-layout.sh     # 第 7 项：契约负责的活文档里，跨层全路径必须指向真实文件
```

**跨层引用的唯一合法形式是「构建步骤（拷贝 + 字节断言）」** —— 不是相对路径
（平台只吃自己资源目录里的字节）、不是人工拷贝、不是 symlink（构建缓存行为不确定，
Windows 侧会断）、不是 submodule。

---

## 3. 允许与禁止的箭头（穷举）

| 方向 | 形式 | 允许？ |
| --- | --- | --- |
| `wordmark/src/` → `wordmark/export/` | 光栅化脚本 | ✅ 只经 `build-icons.mjs` |
| `wordmark/export/` → `app/livehub{,_tv}/…/res/mipmap-*/ic_launcher.png` | 拷贝 + 逐档 md5 断言 | ✅ 只经 `sync-icons.mjs`（10 档） |
| `wordmark/src/banner-16x9-bleed.svg` → `app/livehub_tv/…/mipmap-xhdpi/ic_banner.png` | 渲染一次、人工确认、放置 | ⚠️ **手工档**，不在自动管线内（理由见 §6） |
| `design/v<版本>/*.html` → `design/media/`、`wordmark/export/`、`shots/` | 相对路径 | ✅ 同侧引用，唯一允许的"引用"形态 |
| `app/tools/sync-icons.mjs` → `wordmark/export/` | 读 | ✅ 派发器输入侧 |
| `design/**` 写 `app/**` | 任何 | ❌ |
| `app/**` 写 `design/**` | 任何 | ❌ |
| `design/**` 读 `app/**` | 任何 | ❌（设计稿一读开发资源，就变成"必须编译才能看"） |
| 平台资源的字节在 `design/` 下再存一份 | 任何 | ❌ |
| 仓库内的产物位（`build/`、`.dart_tool/`、`node_modules/`） | 任何 | ❌（构建产物默认由工具写到 gitignore 的位置或 `$TMPDIR`） |

**手写平台图标 XML 是例外，但不是豁免。** `mipmap-anydpi-v26/ic_launcher.xml`、
`values/ic_launcher_background.xml`、TV 的 `ic_banner.xml` 是**开发侧代码**（不是导出物，
生成器不管它们），但它们与 `square.svg` 有**同源义务**：字形 `path` 的 `d` 必须与
`design/brand/wordmark/src/square.svg` 保持一致。这条义务用**守卫断言**满足，不用生成 ——
生成会把手写意图（自适应分层、anydpi 分流）抹掉。

---

## 4. 版本四轴（互不推断）

| 轴 | 数的是 | 唯一写法 | 唯一权威位置 |
| --- | --- | --- | --- |
| 设计定稿 | 设计稿第几次定版 | `design/vX.Y.Z/` | 该级目录名 |
| 应用版本 | 可发布的 Flutter 包 | `version: x.y.z+build` | 各包 `pubspec.yaml`（`app/livehub/pubspec.yaml`、`app/livehub_tv/pubspec.yaml`） |
| 品牌代次 | 品牌图形第几代 | **语义名**：`wordmark`（现行）/ `triangle`（已废） | 目录名 |
| 文档修订 | 某份文档自己改到第几稿 | 文内首段 `> 版本 v3.33` | 文档首段 |

**四条轴不得互相推断。** `design/v1.0.0`（设计定稿第 1 版）与某个包 `version: 1.x`（应用版本）
不是同一条轴：设计定稿可以先于多个应用版本，下一次应用版本升级不需要改设计定稿的目录名。
引用时必须说清是哪一条轴 ——
- ❌ 违规："v1.0.0 版 App" / "v2 图标"
- ✅ 正确："设计定稿 v1.0.0"、"应用版本 1.11.4"、"wordmark 字标板"

---

## 5. 改完自检

```
bash app/tools/verify-layout.sh
```

**八项**，输出 `★ 布局自检通过` 即退出码 0，任一 FAIL 即退出码 1
（不在仓库根跑是退出码 2）。八项分别是：

1. 顶层只有三个内容目录（R1）
2. 单一归属：`design/` 下没有平台资源形状目录、`app/` 下没有 `.svg` / `.html`（R2）
3. 源与产物分层：`src/` 只有 `.svg`、`export/` 只有 `.png`（R3）
4. `NN-` 只在 `design/*/spec/`、文件名里没有 `v<数字>`（R4 / R5）
5. 没有 `archive` `old` `backup` `tmp` `out` `dist` `misc` `assets` 目录
   （R6 / R8，含 `app/<包>/assets` 唯一豁免）+ 设计侧脚本不写 `app/`（R9）
6. 平台图标同步状态（**WARN，不判 FAIL**）+ TV banner 必须是 RGB 满版（R9）
7. 文档里的路径是真的（R7 / R10）：契约负责的活文档断链判 FAIL，`design/*/spec/` 里的只报数
8. 零引用位图清单（**信息级，不判 FAIL**）

**先读脚本顶部注释的 [A]–[H]** —— 那八条是判据的边界（哪些差异是正常的、哪些检查刻意不做、
唯一豁免是什么、匹配口径为什么这样），不是"已知 bug"，别照着它去改文件。

**顺手要跑（需要 Chrome，慢，默认不在自检里）：**
```
node design/brand/build-icons.mjs --check     # src ↔ export 允许差异（抗锯齿），只当情报看
node app/tools/sync-icons.mjs                 # export ↔ 10 档平台图标必须逐档相同，任何差异都是错
```

**两档严格性不同，这是设计意图，不是妥协：**
- `export ↔ 平台图标目录` 是**拷贝** → 必须字节相等，差一个字节就是错
- `src ↔ export` 是**光栅化** → 允许抗锯齿差异（Chrome 版本会变）。正因为它会漂，
  `export` 才必须是人确认过的记录位，而不是每次重新生成的

---

## 6. banner：两种图，一条手工支线

`banner` 是这套管线里**唯一不在自动管线上**的东西，两个文件各归其位、本来就不该相等：

| 文件 | 形状 | 服务谁 | 谁生成 |
| --- | --- | --- | --- |
| `design/brand/wordmark/export/banner-320.png` | **RGBA，圆角 r=27.9** | 浏览器里的 `icon.html`（设计稿展示档） | `build-icons.mjs`（由 `banner-16x9.svg`）自动出 |
| `app/livehub_tv/android/app/src/main/res/mipmap-xhdpi/ic_banner.png` | **RGB，满版 bleed 无 alpha** | TV launcher（圆角它自己裁，画进图里就是双重圆角） | **手工档**：由 `banner-16x9-bleed.svg` 渲染一次、人工确认后放置 |

**为什么 banner 不自动化**：那条路必然漂 —— Chrome 版本一变，生成器渲出的 bleed 版与已有交付档
就会差几个通道值。一自动化就会每跑一次静默覆盖平台目录，而那正是 R3 / R9 想守住的字节断言要防的事：
**断言的意义在于"不一致时停手"，自动重写会让它永远不一致、永远不停手。**

`sync-icons.mjs` 因此**只覆盖 10 档 `ic_launcher.png`**（`--check` 应当 10/10 绿），banner 那条映射已经删掉。

**展示档的边界**：`verify-layout.sh` 对 `export/banner-320.png` 只做一件事 ——
它要是变成 RGB（圆角丢了、`icon.html` 里会显示直角），给一条 **WARN**，不判 FAIL。
硬判的只有 TV banner 那张必须保持 RGB 满版（当前未更换时也只报 WARN，换图后应转绿）。

细节取证（含每个文件的 colortype 与角像素）见 `design/brand/README.md` 的「banner 有两种图」一节。

---

## 7. 上游归属与许可证

- 本工程基于 [dart_simple_live](https://github.com/xiaoyaocz/dart_simple_live)（GPL-3.0）二次开发，
  上游作者：xiaoyaocz。
- `app/LICENSE` 是 GPL-3.0 全文；`app/NOTICE` 记上游归属与本仓库改动摘要。**两者不得删除或改写。**
- 代码里保留的上游 URL（版本检查、虎牙配置、项目主页链接）是**必须保留的上游引用**，
  不属于"产品名残留"，不要清理。
- 重新分发本工程或其衍生作品时，必须继续以 GPL-3.0 提供对应源码。
