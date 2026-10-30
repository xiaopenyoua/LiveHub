#!/usr/bin/env bash
#
# LiveHub · 目录契约校验器
#
#   用法：bash android/tools/verify-layout.sh          （在仓库任意位置跑都行）
#   退出码：0 = 八项全绿；1 = 有 FAIL；2 = 跑不起来（不在仓库里 / 缺工具）
#
# 它校验的是 AGENTS.md 里那八条能被机器判定的规则，不是"代码风格"。
# 每条打印一句人话结论 + 违规清单；清单为空即 ✓。
#
# ── 先读这里：已知差异（不是新 bug，别急着"修"）────────────────────────────
#
#   [A] export/*.png ↔ src/*.svg 不逐字节相等 —— 正常。
#       中间隔着一次光栅化，Chrome 版本一变，边缘抗锯齿就差几个字节。
#       所以本脚本**不比** src↔export；要比请跑 `node design/brand/build-icons.mjs --check`
#       （它打印 6 行 △，那是情报不是错误）。export 是"人确认过的记录位"，不是"每次重新生成的缓存"。
#
#   [B] export↔res 必须逐字节相等 —— 这一档是拷贝，差一个字节就是错，本脚本判 FAIL。
#
#   [C] banner 判的不是 md5，也不是"谁是源"，而是**各归其位**（这两张图本来就不该相等）：
#       · res/drawable-xhdpi/tv_banner.png 必须是**满版 bleed**（PNG colortype 2 = RGB 无 alpha）。
#         圆角交给 TV launcher 自己裁；把圆角画进图里 = 双重圆角。真源是
#         wordmark/src/banner-16x9-bleed.svg，**手工档，不在自动管线内**（所以本脚本不查它的 md5）。
#       · export/banner-320.png 是**展示档**：给 icon.html 看的圆角版，RGBA（colortype 6）是正常的。
#         反过来它要是变成了 RGB，说明圆角丢了，icon.html 里会显示成直角 —— 那是 WARN，不是 FAIL。
#
#   [D] AGENTS.md 不参与第 7 项路径检查：它通篇是刻意的**反例**
#       （`docs/02-设计规范.md`、`splash-v1.html` 这些"违规长什么样"的样本），
#       拿它检查等于跟自己的教科书较劲。
#
#   [E] 第 7 项不检查"裸文件名"。只有两种才算指针：
#       ① 跨层全路径（以 design/ android/ docs/ README.md 开头，R10 要求的写法）；
#       ② 真超链接（`[x](路径)`、HTML 的 src=/href=）。
#       正文里写"见 03-开屏视觉规格.md"这种同目录裸名是散文，不是地址，不判。
#       目录已表达的、跨层必须写全路径 —— 判据只有这一条，否则会造出上百条假阳性。
#
#   [F] 只有**冻结记录**（design/*/spec/）里的断链只报数、不判 FAIL：那里的路径是"当时的坐标"，
#       正文不许改（R7）。六份都已挂"路径时效"声明；哪天某份没挂却出现断链，本脚本会对它判 FAIL
#       —— 那是"记录位缺 errata"，不是断链。
#       **android/docs/ 是活文档，不在这个豁免里**：它的路径必须今天就能用，断了就是 FAIL。
#       旧路径在活文档里不是"历史坐标"，是一条必然失败的命令 —— 读者复制粘贴就报错。
#
#   [G] 第 5 项的名字规则有**唯一豁免**：android/tools/<某个 spike>/assets/。
#       那是参考 APK 内部 assets/ 的原样镜像（目录名硬编码在 spike 的 Kotlin 源码与
#       -Dspike.dir 里），名字是忠实的，不是"不回答是谁的产物"的混装袋。
#
#   [H] 第 8 项（孤儿检查）的匹配口径：两档，且引用源里**排除 design/brand/README.md**。
#       ① 全名匹配（`home.png`）—— shots/ 与 screenshots/ 只认这一档。
#       ② 去扩展名的 stem 匹配（`banner-480`）—— **只对 export/ 放开**。
#          冻稿是按裸 stem 点名的（spec/08-品牌裁决.md:1004 的残差表写「`banner-480` 1.9986」，
#          永不带 .png），拿带扩展名的全名去 grep 恒 0 命中；但 shots/screenshots 的文件名是
#          通用词（home / exit / ready），对它们放开 stem 会把散文里的同名英文词算成引用。
#          边界取 [^0-9A-Za-z-]，免得 `square-96` 命中 `square-960`。
#       · 为什么排除 design/brand/README.md：它是**资产登记表**，表里出现某个文件名
#         不构成"有人在用它"。不排除这一项，第 8 项就是循环论证 —— 一张写着
#         「零引用（可删）」的表，替它自己完成了「有人引用」的证明，脚本自己发合格证。
#
set -u

cd "$(dirname "${BASH_SOURCE[0]}")/../.." 2>/dev/null || { echo "找不到仓库根"; exit 2; }
ROOT=$(pwd)
[ -f AGENTS.md ] && [ -d design ] && [ -d android ] || { echo "这里不是 LiveHub 仓库根：$ROOT"; exit 2; }

FAIL=0
WARN=0
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; FAIL=$((FAIL+1)); }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; WARN=$((WARN+1)); }
item() { printf '      %s\n' "$*"; }
head_() { printf '\n\033[1m[%s]\033[0m %s\n' "$1" "$2"; }

md5of() {
  if command -v md5 >/dev/null 2>&1; then md5 -q "$1"
  elif command -v md5sum >/dev/null 2>&1; then md5sum "$1" | cut -d' ' -f1
  else echo "no-md5"; fi
}

# PNG 的 colortype：0=灰度 2=RGB 3=调色板 4=灰度+alpha 6=RGBA
png_colortype() { od -An -tu1 -j25 -N1 "$1" 2>/dev/null | tr -d ' \n'; }

printf '\033[1mLiveHub 目录契约校验\033[0m  %s\n' "$ROOT"

# ─────────────────────────────────────────────────────────────────────────────
head_ 1 "顶层只有三个内容目录（R1）"
tops=$(ls -d */ 2>/dev/null | sed 's:/$::' | sort | tr '\n' ' ')
if [ "$tops" = "android design docs " ]; then
  ok "顶层目录 = android / design / docs"
else
  bad "顶层目录不是恰好这三个：$tops"
  for d in $tops; do
    case "$d" in android|design|docs) ;; *) item "多出来的：$d/（按 R1/R8 处理，不要新建第四个）";; esac
  done
fi
for bad_dir in assets build tools tmp out dist misc; do
  [ -d "$bad_dir" ] && bad "顶层出现禁用目录 $bad_dir/（R8）"
done

# ─────────────────────────────────────────────────────────────────────────────
head_ 2 "单一归属：一个字节只属于一侧（R2）"
n=$(find design -type d \( -name 'mipmap-*' -o -name 'drawable-*' -o -name 'values' \) 2>/dev/null | wc -l | tr -d ' ')
if [ "$n" = 0 ]; then ok "design/ 下没有 res 形状的目录（mipmap-*/drawable-*/values）"
else bad "design/ 下出现了 $n 个 res 形状目录 —— 那是给 res/ 造了第二个真相源"; find design -type d \( -name 'mipmap-*' -o -name 'drawable-*' -o -name 'values' \) | sed 's/^/      /'; fi
# gradle 的 build/ 是 gitignore 的产物位，不算"归属错误"
PRUNE_ANDROID="( -name build -o -name .gradle -o -name .kotlin -o -name captures ) -prune"
n=$(eval "find android $PRUNE_ANDROID -o -name '*.svg' -print" 2>/dev/null | wc -l | tr -d ' ')
if [ "$n" = 0 ]; then ok "android/ 下没有 .svg（矢量源只在 design/brand/wordmark/src/）"
else bad "android/ 下有 $n 个 .svg"; eval "find android $PRUNE_ANDROID -o -name '*.svg' -print" | sed 's/^/      /'; fi
n=$(eval "find android $PRUNE_ANDROID -o -name '*.html' -print" 2>/dev/null | wc -l | tr -d ' ')
if [ "$n" = 0 ]; then ok "android/ 下没有 .html 设计稿"
else bad "android/ 下有 $n 个 .html —— 设计稿归 design/"; eval "find android $PRUNE_ANDROID -o -name '*.html' -print" | sed 's/^/      /'; fi

# ─────────────────────────────────────────────────────────────────────────────
head_ 3 "源与产物分层（R3）"
n=$(find design -type d -name src 2>/dev/null | wc -l | tr -d ' ')
if [ "$n" -gt 0 ]; then
  junk=$(find design -type d -name src -exec find {} -type f ! -name '*.svg' \; 2>/dev/null)
  [ -z "$junk" ] && ok "src/ 里只有 .svg（$n 个 src/ 目录）" || { bad "src/ 里混进了非源文件"; echo "$junk" | sed 's/^/      /'; }
fi
junk=$(find design -type d -name export -exec find {} -type f ! -name '*.png' \; 2>/dev/null)
[ -z "$junk" ] && ok "export/ 里只有 .png" || { bad "export/ 里混进了非位图"; echo "$junk" | sed 's/^/      /'; }

# ─────────────────────────────────────────────────────────────────────────────
head_ 4 "编号与版本号只在被授权的位置（R4 / R5）"
outside=$(find . -path ./.git -prune -o -path ./.workbuddy -prune -o -name '[0-9][0-9]-*' -print 2>/dev/null \
          | sed 's|^\./||' | grep -v '^design/[^/]*/spec/' | grep -v '^\./design/[^/]*/spec/')
if [ -z "$outside" ]; then ok "NN- 前缀只出现在 design/<版本>/spec/ 里"
else bad "NN- 前缀越界（编号是永久地址，只在 spec/ 里能用）"; echo "$outside" | sed 's/^/      /'; fi

# macOS 自带 bash 3.2 有个解析坑：`case` 直接写在 $( ) 里会报 syntax error。
# 所以判定一律抽成函数，在 $( ) 里只留函数调用。
is_version_dir() {
  [ -d "$1" ] || return 1
  case "$1" in
    design/v[0-9]*.[0-9]*.[0-9]*) return 0 ;;
  esac
  return 1
}
vbad=$(find design docs -name '*v[0-9]*' -print 2>/dev/null | while read -r p; do
  if ! is_version_dir "$p"; then echo "$p"; fi
done)
if [ -z "$vbad" ]; then ok "文件名里没有 v<数字>；版本轴只有 design/vX.Y.Z/ 一处"
else bad "v<数字> 出现在文件名里（R5：版本由目录承担，不写进文件名）"; echo "$vbad" | sed 's/^/      /'; fi
[ -z "$(ls -d design/v*/ 2>/dev/null | grep -v -E 'design/v[0-9]+\.[0-9]+\.[0-9]+/$')" ] \
  && ok "版本目录名都是 vX.Y.Z" || bad "有版本目录不是 vX.Y.Z 三段式"

# ─────────────────────────────────────────────────────────────────────────────
head_ 5 "没有垃圾桶目录（R6 / R8）"
found=$(find . \( -name .git -o -name .workbuddy -o -name node_modules -o -name .gradle -o -name .kotlin \) -prune \
        -o -name build -path './android/*' -prune \
        -o -type d \( -name archive -o -name old -o -name backup -o -name tmp -o -name out \
                      -o -name dist -o -name misc -o -name assets \) -print 2>/dev/null \
        | grep -v -E '^\./android/tools/[^/]+/assets$')
if [ -z "$found" ]; then ok "没有 archive/old/backup/tmp/out/dist/misc/assets（唯一豁免：android/tools/<spike>/assets/）"
else bad "出现垃圾桶目录（不确定归属的东西，删掉比存档便宜 —— 历史在 git 里）"; echo "$found" | sed 's/^/      /'; fi
# 只允许出现在注释里；写路径里出现即"设计侧持有开发侧写权限"
writes=$(grep -n -E '(writeFileSync|mkdirSync|copyFileSync)\(.*android/' design/brand/*.mjs 2>/dev/null || true)
if [ -z "$writes" ]; then ok "design/brand/*.mjs 没有写 android/ 的语句（R9）"
else bad "设计侧脚本在写 android/（R9 违规）"; echo "$writes" | sed 's/^/      /'; fi

# ─────────────────────────────────────────────────────────────────────────────
head_ 6 "图标字节：export ↔ res 两档严格性（R9 的唯一合法跨层形式）"
EXPORT=design/brand/wordmark/export
RES=android/app/src/main/res
if [ ! -d "$EXPORT" ] || [ ! -d "$RES" ]; then
  bad "缺少 $EXPORT 或 $RES，无法比对"
else
  f0=$FAIL
  for pair in \
    "square-48-B.png:mipmap-mdpi/ic_launcher.png" \
    "square-72.png:mipmap-hdpi/ic_launcher.png" \
    "square-96.png:mipmap-xhdpi/ic_launcher.png" \
    "square-144.png:mipmap-xxhdpi/ic_launcher.png" \
    "square-192.png:mipmap-xxxhdpi/ic_launcher.png"
  do
    s="$EXPORT/${pair%%:*}"; d="$RES/${pair##*:}"
    if [ ! -f "$s" ]; then bad "export 缺 ${pair%%:*}"; continue; fi
    if [ ! -f "$d" ]; then bad "res 缺 ${pair##*:}（跑 node android/tools/sync-icons.mjs --write）"; continue; fi
    a=$(md5of "$s"); b=$(md5of "$d")
    [ "$a" = "$b" ] || bad "字节不等：${pair%%:*} → ${pair##*:}（期望 $a 实际 $b）→ 跑 sync-icons.mjs --write"
  done
  [ "$FAIL" -eq "$f0" ] && ok "五档 ic_launcher：export 与 res 逐字节相同"

  # banner：两张图本来就不该相等，判"各归其位"，不判 md5（见文件头 [C]）
  sb="$EXPORT/banner-320.png"; db="$RES/drawable-xhdpi/tv_banner.png"
  if [ ! -f "$sb" ] || [ ! -f "$db" ]; then
    bad "缺 banner（$sb / $db）"
  else
    cs=$(png_colortype "$sb"); cd_=$(png_colortype "$db")
    # 唯一硬判：res 那张必须是满版 bleed（RGB 无 alpha）。圆角画进图里就是双重圆角。
    if [ "$cd_" != 2 ]; then
      bad "res/drawable-xhdpi/tv_banner.png colortype=$cd_，必须是 2（RGB 无 alpha）"
      item "它是手工档，真源 wordmark/src/banner-16x9-bleed.svg；圆角交给 TV launcher 自己裁"
    else
      ok "banner 两种图各归其位：export 是 RGBA 展示档，res 是 RGB 满版 bleed（手工档）"
    fi
    # 双向那一半：export 展示档理应是 RGBA；变成 RGB 说明圆角丢了，icon.html 会显示直角
    if [ "$cs" = 2 ]; then
      warn "export/banner-320.png 是 RGB（colortype 2）—— 展示档的圆角丢了，icon.html 里会显示成直角"
    elif [ "$cs" != 6 ]; then
      warn "export/banner-320.png 的 colortype=$cs，展示档预期 6（RGBA 圆角版）"
    fi
  fi
fi

# ─────────────────────────────────────────────────────────────────────────────
head_ 7 "文档里的路径是真的（R7「指针改，记录不改」/ R10 全路径）"
FILES=$( { find design docs android/docs -type f \( -name '*.md' -o -name '*.html' \) 2>/dev/null; [ -f README.md ] && echo README.md; } | sort )

# 把一个候选串解析成"它应该指向的文件"；不是指针就输出空。
# 只认两种（见文件头 [E]）：跨层全路径、真超链接的相对路径。裸文件名是散文，不判。
ptr_target() {
  _f=$1; _p=$2
  case "$_p" in
    http*|mailto:*|data:*|'#'*|'//'*|/*) return 0 ;;
  esac
  case "$_p" in
    *'*'*|*'{'*|*'}'*|*'<'*|*'>'*|*'…'*|*'|'*|*'$'*|*' '*) return 0 ;;
  esac
  _c=${_p%%#*}; _c=${_c%%\?*}
  [ -z "$_c" ] && return 0
  case "$_c" in
    ./*|../*) echo "${_f%/*}/$_c" ;;
    design/*|android/*|docs/*|README.md) echo "$ROOT/$_c" ;;
    *) return 0 ;;
  esac
}

n_fail=0; n_rec=0; rec_files=""
while IFS= read -r f; do
  [ -z "$f" ] && continue
  miss=""
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    # 占位符（design/vX.Y.Z/、NN-标题.md 这类模板）不算指针
    case "${p#*/}" in
      *[A-Z]*) continue ;;
    esac
    target=$(ptr_target "$f" "$p")
    [ -z "$target" ] && continue
    [ -e "$target" ] || miss="$miss$p
"
  done < <(grep -oE '\]\([^)[:space:]]+\)|`[^`]+`|(src|href)="[^"]+"' "$f" 2>/dev/null \
           | sed -E 's/^\]\(//; s/\)$//; s/^`//; s/`$//; s/^(src|href)="//; s/"$//' \
           | grep -E '^(design/|android/|docs/|README\.md|\.\.?/)' )
  [ -z "$miss" ] && continue
  n=$(printf '%s' "$miss" | grep -c .)
  # 只有**冻结记录**（design/<版本>/spec/）里的路径是"当时的坐标"，只报数（R7）。
  # android/docs/ 是活文档，断了就是 FAIL —— 旧路径在活文档里是一条必然失败的命令。
  case "$f" in
    design/*/spec/*)
      n_rec=$((n_rec+n)); rec_files="$rec_files$f($n) "
      if ! head -30 "$f" | grep -q '路径时效'; then
        bad "$f 有 $n 条断链，但文首没有「路径时效」声明（记录位缺 errata）"
        printf '%s' "$miss" | sed 's/^/      /'
      fi
      ;;
    *)
      n_fail=$((n_fail+n))
      bad "$f 有 $n 条指针指向不存在的路径"
      printf '%s' "$miss" | sed 's/^/      /'
      ;;
  esac
done <<< "$FILES"
[ "$n_fail" -eq 0 ] && ok "活文档 0 条断链（${n_rec} 条在冻结记录 design/*/spec/ 里，按 R7 只报数）"
[ "$n_rec" -gt 0 ] && item "记录位断链分布：$rec_files"

# ─────────────────────────────────────────────────────────────────────────────
head_ 8 "孤儿：没有任何引用者的位图（信息级，不判 FAIL）"
# 一次把所有"可能引用位图"的文本读进内存，再逐个名字比对 —— 逐文件 grep 会慢到不可用。
# [H] 两档匹配：全名（全仓通用）+ 去扩展名的 stem（**只对 export/ 放开**）。
#     引用源**排除 design/brand/README.md**：资产登记表里的名字不算"有人在用"，
#     否则「写着可删」的表会替自己完成「有人引用」的证明（循环论证）。
REF=$( { find design docs android/docs -type f \( -name '*.md' -o -name '*.html' \) \
           ! -path '*design/brand/README.md' -exec cat {} + 2>/dev/null
         find android/tools design/brand -name '*.mjs' -exec cat {} + 2>/dev/null
         cat README.md AGENTS.md 2>/dev/null; } )
orphan=0
for p in "$EXPORT"/*.png design/v1.0.0/shots/*.png design/v1.0.0/screenshots/*.png; do
  [ -f "$p" ] || continue
  base=$(basename "$p")
  n=$(printf '%s' "$REF" | grep -c -F -- "$base")
  if [ "$n" = 0 ]; then
    case "$p" in
      "$EXPORT"/*)
        # 冻稿按裸 stem 点名（`banner-480`），永不带 .png。stem 只含 [a-z0-9-]，无需转义。
        stem=${base%.png}
        n=$(printf '%s' "$REF" | grep -c -E -- "$stem([^0-9A-Za-z-]|$)") ;;
    esac
  fi
  if [ "$n" = 0 ]; then orphan=$((orphan+1)); item "零引用：$p"; fi
done
[ "$orphan" = 0 ] && ok "没有零引用位图（全名 + export/ 的裸 stem，均已在引用源里数过）" \
  || warn "$orphan 张位图零引用 —— 有生成命令**且**确实没人用的才可删（R6，两个条件缺一不可）；证据对象请登记进 README 或 AGENTS.md"

# ─────────────────────────────────────────────────────────────────────────────
printf '\n────────────────────────────────────────\n'
if [ "$FAIL" -eq 0 ]; then
  printf '\033[32m★ 布局自检通过\033[0m（%d 项警告）\n' "$WARN"
  exit 0
else
  printf '\033[31m★ %d 项 FAIL\033[0m（%d 项警告）—— 修完再跑一遍\n' "$FAIL" "$WARN"
  exit 1
fi
