# AGENTS.md · 目录契约与执行规则

> **先读这份，再动手。** 本文件是仓库里**唯一的归属判据**：任何其他 README 描述的目录结构与本文冲突时，以本文为准。
> 每条规则都可判定 —— 每条都给出一条能当场跑的命令，以及「违规长什么样 / 正确长什么样」。
> 改完自检：`bash android/tools/verify-layout.sh`（退出码 0 才算干净）。**它不是提交闸门** —— 在这块盘上跑 14–17 秒，别把它挂成 pre-commit 强阻塞；它的作用是「改完自己看一眼」，不是「不跑不许提交」。

---

## 0. 三十秒版

**三个内容目录，一个方向。**

```
design/     设计侧 —— 设计稿、规格、品牌矢量源、稿子素材
android/    开发侧 —— 可执行工程、被 APK 直接吃掉的资源、实现文档
docs/       判断依据 —— 为什么做这个产品、参考的那个 App 是怎么做的
```

**单向管线（只有这一个方向，反向即违规）：**

```
design/brand/wordmark/src/*.svg                  ← 唯一源（9 个 SVG）
        │  design/brand/build-icons.mjs          光栅化（默认写 export/；--check 零写入）
        ▼
design/brand/wordmark/export/*.png               ← 交付位图 · 记录位（12 张）
        │  android/tools/sync-icons.mjs          拷贝 + 逐档 md5 断言（只覆盖 5 档 mipmap）
        ▼
android/app/src/main/res/mipmap-*/ic_launcher.png   ← 交付副本
        │  ./gradlew assembleDebug
        ▼
APK                                              ← 可执行产物（不进仓库）
```

**这张图有一条支线，别把它读成主干**：`res/drawable-xhdpi/tv_banner.png` **不在管线里**。
它是**手工档** —— 真源是 `wordmark/src/banner-16x9-bleed.svg`，渲染一次、人工确认、放进去就不再自动同步
（理由见 §6）。图上把它画进来会撒谎，所以它只在图外说。

**四条最常被违反的禁令（先记住这四条就够用）：**

1. **设计侧不写 `android/`。** `design/**` 下的任何脚本都不得写 `android/**`。把位图送进 `res/mipmap-*/` 只有 `android/tools/sync-icons.mjs` 一条路（`tv_banner.png` 是手工档，见 §6）。
2. **`res/` 的字节不在 `design/` 下再存一份。** 需要在本机留一份的，是 `export/`（那是源的光栅产物，不是 `res/` 的副本）。
3. **可再生且无人引用的字节，删掉，不要建 `archive/`。**
4. **文件名里不出现 `v2` `final` `new` `old` 这类词。** 它描述的是"处在什么阶段"，不是"画的是什么"。

---

## 1. 目录契约

格式：**`目录` — 谁的产物；只进什么；永远不进什么；谁有写权限**

| 目录 | 谁的产物 | 只进 | 永远不进 | 写权限 |
| --- | --- | --- | --- | --- |
| `design/` | 设计侧全部产物 | 定稿 `.html`、规格与裁决 `.md`、品牌 `.svg`、稿子引用的字体与图片 | Android 资源（`mipmap-*` / `drawable-*` / `values`）、APK、任何 `build/`、取证截图批 | 设计侧任何脚本，**但不得写 `android/`** |
| `design/brand/` | 品牌资产的源与交付位 | `wordmark/src/*.svg`、`wordmark/export/*.png`、光栅脚本、本目录 README | 任何 `res/` 形状的目录、Android 专用 XML、代次编号目录（`v1/` `v2/`） | `build-icons.mjs`（只写 `src`↔`export`） |
| `design/brand/wordmark/` | 字标板这一代品牌资产 | 一套字形（Live 白 / Hub 红）的源与位图 | 两代资产并存（旧代在 git 历史里）、代次编号 | 同上 |
| `…/src/` | **唯一矢量源** | `.svg` only | `.png`、任何位图、任何由脚本产出的中间物 | 人（改矢量必须是人决定） |
| `…/export/` | **被引用的交付位图（记录位）** | 出货档 + 有引用者的留痕档 + 展示档，`.png` only | 无引用者的导出、缩略图、预览缩略、`res/` 的字节副本 | `build-icons.mjs`（默认写这里；`--check` 零写入） |
| `design/system/` | 跨版本设计规则 | 当前有效的规则文本 | 版本目录、历史版本（历史在 git）、并存两套值 | 人 |
| `design/media/` | 稿子的运行时素材 | 稿子以相对路径引用的字体与图片 | 矢量（归 `brand/`）、截图、`res/` | 人 |
| `design/v1.0.0/` | 设计定稿（冻结） | 本版定稿稿、`spec/`、`shots/`、`screenshots/` | 跨版本素材、下一版草稿（下一版另开 `v1.1.0/`） | 冻结后只许加 errata |
| `…/spec/` | 本版规格与裁决记录（**唯一编号空间**） | `NN-中文标题.md` | 代码、截图、跨版本内容 | 只许在文首加 errata |
| `…/shots/` | 开屏定稿帧 | 被 `spec/` 或 `.html` 引用过的帧 | 未引用的批次帧、`v2-` 之类批次前缀 | 可重拍、可删 |
| `…/screenshots/` | 原型定稿截图 | `<屏名>.png` | 编号、中间版本 | 可重拍、可删 |
| `android/` | 开发侧全部产物 | gradle 工程、`res/`、`java/`、开发侧脚本与实现文档 | 设计稿、品牌矢量、`build/`、`.gradle/`、`.kotlin/` | 开发侧 |
| `android/docs/` | Android 实现文档 | 架构与实测记录（语义名） | 设计规格、编号前缀 | 人 |
| `android/tools/` | 开发侧脚本 | 跑在本机或设备上、读写 `android/` 或读 `design/` 的脚本 | `*/build/` 下的任何东西 | 开发侧 |
| `docs/` | 既不属设计也不属开发的判断依据 | 「为什么做」「参考的是什么」 | 实现细节、设计规格、截图与取证 | 人 |
| `README.md` | 仓库总索引 | 「东西在哪」+ 规矩摘要 | 规格正文（正文在 `spec/`） | 人 |
| `AGENTS.md` | **本文件：归属判据** | 规则与禁令 | 任何具体规格内容 | 人 |

**"删掉它损失什么"四问（新增顶层目录前必须答）：** `design/` → 失去不可再生的设计定稿与品牌源；`android/` → 失去可执行工程；`docs/` → 失去"为什么做这个产品"的唯一叙述；`AGENTS.md` → 失去归属判据，下一个人会重新把东西堆成垃圾场。

---

## 2. 规则清单

### R1 · 顶层只有三个内容目录

```
ls -d */            # 只允许 android/ design/ docs/
ls -d assets build tools 2>/dev/null ; echo "^ 必须为空"
```
- ❌ 违规：`assets/`（混装袋）、`build/`（产物位）、顶层 `tools/`（跨侧脚本没有归属）
- ✅ 正确：素材进 `design/media/`，产物进 `$TMPDIR`，脚本跟随它写入的目录

### R2 · 单一归属：一个字节只能属于一侧

判定法：**它能被 `assembleDebug` 直接吃掉吗？**
- 能（在 `res/`、`src/main/assets/`、`java/` 下），或它本身是构建配置 → `android/`
- 它是设计稿 / 规格 / 矢量 / 稿子素材 → `design/`
- 都不是 → `docs/`，或者按 R6 删除

```
find design -type d \( -name 'mipmap-*' -o -name 'drawable-*' -o -name values \)   # 必须为空
find android -name '*.svg'                                                          # 必须为空
```
- ❌ 违规：`design/**/android-res/mipmap-mdpi/ic_launcher.png`（给同一份字节造了第二个真相源）
- ✅ 正确：`res/mipmap-mdpi/ic_launcher.png` 唯一存在；设计侧要看它，看 `export/square-48-B.png`

### R3 · 源与产物分层：能重新生成的，不和源放一起

判定法：**若 B 能由 A + 一条命令重新生成，B 不得与 A 同目录。**
```
find design/brand/wordmark/src    -type f ! -name '*.svg'   # 必须为空
find design/brand/wordmark/export -type f ! -name '*.png'   # 必须为空
```
- ❌ 违规：`src/square.svg` 与 `src/square-96.png` 并排
- ✅ 正确：`src/square.svg`（源） → `export/square-96.png`（产物，可重建）

**唯一的例外，且必须写清**：`export/` 虽然是产物，但它是**记录位**（被冻结的定稿稿按相对路径引用，且 `res/` 的字节以它为断言基准），所以它进版本库。这个例外的边界是：**只有 `build-icons.mjs` 能写它，且写入前要人确认。**

### R4 · 只有 `design/<版本>/spec/` 能用 `NN-` 前缀

```
find . -path ./.git -prune -o -path ./.workbuddy -prune -o \
       -name '[0-9][0-9]-*' -print | grep -v '/design/v1.0.0/spec/'   # 必须为空
```
- ❌ 违规：`docs/02-设计规范.md`（和 `spec/02-图标几何规格.md` 撞车，读者按编号走错门）
- ❌ 违规：`screenshots/01-home.png`（编号只被当排序用，起不到地址作用）
- ✅ 正确：`spec/02-图标几何规格.md`（版本内规格，裸号互引上百处，编号是承重地址）；`screenshots/home.png`；`docs/市场调研.md`

**编号是永久地址：作废不复用，允许空号。** `spec/` 现在是 `01,02,03,04,07,08` —— 05/06 是历史空号，**不要拿它们给新文档补位**。

### R5 · 文件名出现 `v<数字>` 即违规

```
find design docs -path '*/.git' -prune -o -name '*v[0-9]*' -print | grep -v '^design/v1\.0\.0$'
```
- ❌ 违规：`08-品牌裁决-v2.md`、`shots/v2-splash-t500.png`、`splash-v1.html`
- ✅ 正确：`08-品牌裁决.md`、`shots/splash-t500.png`、`splash.html`

**范围说明（不然会误报）**：`android/` 下 `mipmap-anydpi-v26`、`values-v21` 里的 `-vNN` 是 **Android 平台规定的 API 限定符**，不是版本轴，不受此规则约束。所以这条只扫 `design/` 与 `docs/`。

### R6 · 不归属即删除，并且不建 `archive/`

判定法：**`grep` 零命中 且 有生成命令 → 删。** 两个条件缺一不可（零命中但没有生成命令的，是"证据"，不是垃圾）。
```
find . -path ./.git -prune -o -type d \( -name archive -o -name old -o -name backup \
     -o -name tmp -o -name out -o -name dist -o -name misc \) -print   # 必须为空
```
- ❌ 违规：`archive/2026-10/`、`old/`、`final/`、`.taste-skill/`、`.workbuddy/test/`（256 张取证 png）
- ✅ 正确：删掉，结论写进 README 或 `spec/`；**历史在 git，不在目录里**

**为什么不建 `archive/`**：它一定会变成第二个垃圾桶 —— 进去了就没人再判断"能不能删"，而仓库的可读性只取决于"里面的东西是否都还有用"。删掉是可逆的（`git log --diff-filter=D`），存档是不可逆的（它把一次判断变成了永久负债）。

### R7 · 冻结记录只许加 errata，不许改写正文

**R7 只管冻结记录。** 冻结的只有 `design/v1.0.0/` 这一层：`spec/` 下六份裁决证据，以及 `ui.html` / `splash.html` / `icon.html` 三个定稿（定版即冻结，改动另开 `v1.1.0/`）。它们的正文里有 `git show HEAD:…` 这类历史命令，目录一变这些路径就"不存在"，但**"文件没了"和"证据没了"是两件事**（这条教训在 `spec/07`、`spec/08` 里各栽过一次）。

- ❌ 违规：把 `spec/08` 里的 `git show HEAD:assets/brand/v2/icon/…` 全局替换成新路径（等于伪造当时的命令）
- ✅ 正确：正文一字不动，文首加一条

```
> 路径时效（YYYY-MM-DD）：本文件中的 `assets/brand/v2/icon/` 现位于
> `design/brand/wordmark/src/`。正文里的 `git show HEAD:...` 保留原样，作历史坐标读。
```

**其余全是活文档，不受 R7 保护：**`android/docs/**`、`design/system/**`、`docs/**`、各个 `README.md`。它们还会继续长，里面的路径必须是**今天能用的**。

| 文档性质 | 例子 | 路径怎么办 |
| --- | --- | --- |
| **冻结记录**（`design/v1.0.0/spec/**`） | `git show HEAD:assets/brand/v2/icon/…` | 正文一字不动，文首加 errata |
| **活文档**（`android/docs/**`、`design/system/**`、各 `README.md`） | `grep -n … docs/04-Android架构.md`、「我跑这条得到 0」 | **路径必须改成当前可用的**；旧名留一句 errata 说明它原先叫什么即可 |

**为什么必须这么分**：活文档里的旧路径不是"历史坐标"，是**一条必然失败的命令** —— 读者复制粘贴就报错，那不是诚实，那是坑。冻结记录的旧路径才是历史坐标，因为它的价值在于"当时我确实敲了这条"。
`verify-layout.sh` 第 7 项按这条分界判：`design/*/spec/` 里的断链只报数，活文档里的断链是 FAIL。


### R8 · 禁止的目录名

`assets` `build` `tmp` `out` `dist` `misc` `archive` `old` `backup` `assets2` `vN`（除 `design/v<X.Y.Z>`）。
理由：这些名字不回答"是谁的产物"，于是每个进来的人都会往里扔不同的东西 —— `assets/` 就是活教材（同时装着设计素材、品牌源、和 `res/` 的字节副本三样东西）。

**唯一豁免：`android/tools/<某个 spike>/assets/`。** 判据两条，缺一不可 ——
① 路径形如 `android/tools/<目录>/assets`（**层级固定，深度多一层就不是它**）；
② 里面是参考 APK **内部** `assets/` 的原样镜像（`sign.js`、`webmssdk.js` 这类第三方混淆产物），
且目录名被该 spike 的源码硬编码（`-Dspike.dir` + jar 内相对 require 依赖这个形状）。

为什么给它开豁免：这个 `assets` 的名字是**忠实的** —— 它镜像的是斗鱼参考 APK 里的那个 `assets/`，
改名的代价是要连 Kotlin 源码一起改、重编译、再验证 SDK 的相对路径不断，零收益纯风险。
它**不是** R8 想禁的那类混装袋：它的归属由父目录（那个 spike）回答，内容单一。
豁免只此一种形状 —— 别拿"反正 spike 里也这么叫"当理由，往 `design/`、`android/app/`、
或任何非 spike 目录里再建一个 `assets/`。


### R9 · 脚本归属：谁拥有目标目录，谁执行写入

判定法：**看脚本的写路径。写进谁的目录，脚本就属于谁。**
```
grep -n "android/" design/brand/*.mjs && echo "^ 只允许出现在注释里，不允许出现在写路径里"
```
- ❌ 违规：`design/tools/build-icons.mjs --apply` 直接写 `android/app/src/main/res/` —— 设计侧持有开发侧的写权限，这是唯一能让 `res/` 被静默覆盖的通道
- ✅ 正确：两个脚本各管一段
  - `design/brand/build-icons.mjs`：读 `src/*.svg`，默认写 `export/*.png`（`--check` 只在内存里比、零写入；`--out DIR` 换目标），**不碰 `android/`**
  - `android/tools/sync-icons.mjs`：读 `export/*.png`，写 `res/**`，同一次运行内逐档 md5 断言

### R10 · 跨目录引用写全路径 + `§节`

- ❌ 违规：`见 02 第四节`、`见 docs/02`（路径不明、节不明）
- ✅ 正确：`见 design/system/设计规范.md 第四节`、`见 android/docs/接口实测.md §4.1`

**跨层引用的唯一合法形式是「构建步骤（拷贝 + 字节断言）」** —— 不是相对路径（Android 只吃 `res/` 里的字节）、不是人工拷贝（就是现在 3 副本 0 断言的局面）、不是 symlink（gradle 增量缓存行为不确定，Windows 侧会断）、不是 submodule。

---

## 3. 允许与禁止的箭头（穷举）

| 方向 | 形式 | 允许？ |
| --- | --- | --- |
| `wordmark/src/` → `wordmark/export/` | 光栅化脚本 | ✅ 只经 `build-icons.mjs` |
| `wordmark/export/` → `android/…/res/mipmap-*/ic_launcher.png` | 拷贝 + 逐档 md5 断言 | ✅ 只经 `sync-icons.mjs`（5 档） |
| `wordmark/src/banner-16x9-bleed.svg` → `res/drawable-xhdpi/tv_banner.png` | 渲染一次、人工确认、放置 | ⚠️ **手工档**，不在自动管线内（理由见 §6） |
| `design/v<版本>/*.html` → `design/media/`、`wordmark/export/`、`shots/` | 相对路径 | ✅ 同侧引用，唯一允许的"引用"形态 |
| `android/docs/` → `design/system/` | 路径引用（文本） | ✅ 文档指路 |
| `android/tools/sync-icons.mjs` → `wordmark/export/` | 读 | ✅ 派发器输入侧 |
| `design/**` 写 `android/**` | 任何 | ❌ |
| `android/**` 写 `design/**` | 任何 | ❌ |
| `design/**` 读 `android/**` | 任何 | ❌（设计稿一读开发资源，就变成"必须编译才能看"） |
| `res/` 的字节在 `design/` 下再存一份 | 任何 | ❌ |
| 仓库内的产物位（`build/`、`_build/`、`.out/`） | 任何 | ❌ 默认输出到 `$TMPDIR` |

**手写 res 矢量资源是例外，但不是豁免。** `mipmap-anydpi-v26|v33/ic_launcher.xml`、`drawable/ic_launcher_{foreground,monochrome}.xml`、`values/ic_launcher_colors.xml`、`drawable/splash_wordmark.xml` 是**开发侧代码**（不是导出物，生成器不管它们），但它们与 `square.svg` 有**同源义务**：其中 7 条 `path` 的 `d` 必须与 `src/square.svg` 逐字节相同。这条义务用**守卫断言**满足，不用生成 —— 生成会把手写意图（三层结构、anydpi 分流）抹掉。

---

## 4. 版本四轴（互不推断）

| 轴 | 数的是 | 唯一写法 | 唯一权威位置 |
| --- | --- | --- | --- |
| 设计定稿 | 设计稿第几次定版 | `design/vX.Y.Z/` | 该级目录名 |
| 应用版本 | 可发布的 APK | `versionName = "x.y.z"` | `android/app/build.gradle.kts`（当前 `0.1.0`） |
| 品牌代次 | 品牌图形第几代 | **语义名**：`wordmark`（现行）/ `triangle`（已废） | 目录名 |
| 文档修订 | 某份文档自己改到第几稿 | 文内首段 `> 版本 v3.33` | 文档首段 |

**四条轴不得互相推断。** `design/v1.0.0`（设计定稿第 1 版）与 `versionName 0.1.0`（应用版本）**本来就不必相等**：设计定稿可以先于多个应用版本。引用时必须说清是哪一条轴 ——
- ❌ 违规："v1.0.0 版 App" / "v2 图标"
- ✅ 正确："设计定稿 v1.0.0"、"应用版本 0.1.0"、"wordmark 字标板"

---

## 5. 改完自检

```
bash android/tools/verify-layout.sh
```

**八项**，输出 `★ 布局自检通过` 即退出码 0，任一 FAIL 即退出码 1（不在仓库根跑是退出码 2）。八项分别是：
1. 顶层只有三个内容目录（R1）
2. 单一归属：`design/` 下没有 res 形状目录、`android/` 下没有 `.svg` / `.html`（R2）
3. 源与产物分层：`src/` 只有 `.svg`、`export/` 只有 `.png`（R3）
4. `NN-` 只在 `design/*/spec/`、文件名里没有 `v<数字>`（R4 / R5）
5. 没有 `archive` `old` `backup` `tmp` `out` `dist` `misc` `assets` 目录（R6 / R8，含唯一豁免）+ 设计侧脚本不写 `android/`（R9）
6. 五档 `ic_launcher` 的 `export ↔ res` 逐字节相等；`tv_banner.png` 必须是 RGB 满版（R9）
7. 文档里的路径是真的（R7 / R10）：活文档断链判 FAIL，`design/*/spec/` 里的只报数
8. 零引用位图清单（**信息级，不判 FAIL**）：引用源 = `design/` `docs/` `android/docs/` 的 md、`.mjs`、根 `README.md`、`AGENTS.md`，**不含 `design/brand/README.md`**（资产登记表里的名字不算"有人在用"，否则「写着可删」的表会替自己完成「有人引用」的证明）。匹配两档：全名（所有位图）+ 去扩展名的裸 stem（**只对 `export/`**，因为冻结裁决按裸 stem 点名）

**先读脚本顶部注释的 [A]–[H]** —— 那八条是判据的边界（哪些差异是正常的、哪些检查刻意不做、唯一豁免是什么、匹配口径为什么这样），不是"已知 bug"，别照着它去改文件。

**顺手要跑（需要 Chrome，慢，默认不在自检里）：**
```
node design/brand/build-icons.mjs --check     # src ↔ export 允许差异（抗锯齿），只当情报看
node android/tools/sync-icons.mjs             # export ↔ res 必须逐档相同（5 档），任何差异都是错
```

**两档严格性不同，这是设计意图，不是妥协：**
- `export ↔ res` 是**拷贝** → 必须字节相等，差一个字节就是错
- `src ↔ export` 是**光栅化** → 允许抗锯齿差异（Chrome 版本会变）。正因为它会漂，`export` 才必须是人确认过的记录位，而不是每次重新生成

---

## 6. banner：两种图，一条手工支线

`banner` 是这套管线里**唯一不在自动管线上**的东西，两个文件各归其位、本来就不该相等：

| 文件 | 形状 | 服务谁 | 谁生成 |
| --- | --- | --- | --- |
| `export/banner-320.png` | **RGBA，圆角 r=27.9** | 浏览器里的 `icon.html`（设计稿展示档） | `build-icons.mjs`（由 `banner-16x9.svg`）自动出 |
| `res/drawable-xhdpi/tv_banner.png` | **RGB，满版 bleed 无 alpha** | TV launcher（圆角它自己裁，画进图里就是双重圆角） | **手工档**：由 `banner-16x9-bleed.svg` 渲染一次、人工确认后放置 |

**为什么 banner 不自动化**：那条路必然漂 —— 现在生成器渲出的 bleed 版与 `res/` 里那张，边缘差约 130 px、最大通道差 38/255（`res/` 那张是更早的 Chrome 渲的）。一自动化就会每跑一次静默覆盖 `res/`，而那正是 R3 / R9 想守住的字节断言要防的事：**断言的意义在于"不一致时停手"，自动重写会让它永远不一致、永远不停手。**

`sync-icons.mjs` 因此**只覆盖 5 档 mipmap**（`--check` 应当 5/5 绿），banner 那条映射已经删掉。

**展示档的边界**：`verify-layout.sh` 对 `export/banner-320.png` 只做一件事 —— 它要是变成 RGB（圆角丢了、`icon.html` 里会显示直角），给一条 **WARN**，不判 FAIL。硬判的只有 `res/` 那张必须是 RGB 满版。

细节取证（含每个文件的 colortype 与角像素）见 `design/brand/README.md` 的「banner 有两种图」一节。
