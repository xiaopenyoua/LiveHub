#!/usr/bin/env bash
#
# LiveHub · 目录契约校验器
#
#   用法：bash app/tools/verify-layout.sh          （在仓库任意位置跑都行）
#   退出码：0 = 全部检查通过；1 = 有 FAIL；2 = 跑不起来（不在仓库里）
#
# 它校验的是 AGENTS.md 里那些能被机器判定的规则，不是"代码风格"。
# 每条打印一句人话结论 + 违规清单；清单为空即 ✓。
#
# ── 先读这里：判据边界（不是新 bug，别急着"修"）─────────────────────────────
#
#   [A] export/*.png ↔ src/*.svg 不逐字节相等 —— 正常。
#       中间隔着一次光栅化，Chrome 版本一变，边缘抗锯齿就差几个字节。
#       所以本脚本**不比** src↔export；要比请跑
#       `node design/brand/build-icons.mjs --check`（△ 是情报，不是错误）。
#
#   [B] export ↔ 各包图标目录是**拷贝**，必须逐字节相等 —— 但这一档由
#       `node app/tools/sync-icons.mjs` 判定，本脚本只报同步状态（WARN），不判 FAIL。
#       原因：平台图标二进制由各平台负责人落盘；结构自检不该因为"还没跑 --write"而红。
#
#   [C] banner 是**手工档**，不在自动管线内。TV banner 必须是满版 RGB（colortype 2），
#       圆角交给 launcher 自己裁；`export/banner-320.png` 是带 alpha 的圆角展示档（colortype 6），
#       两者本来就不该相等。本脚本对 TV banner 只做形状 WARN，不查 md5。
#
#   [D] AGENTS.md 不参与第 7 项路径检查：它通篇有刻意的**反例**
#       （`v2`、`final` 这些"违规长什么样"的样本），拿它检查等于跟教科书较劲。
#
#   [E] 第 7 项只认两种指针：跨层全路径（design/ app/ docs/ README.md 开头）与真超链接。
#       裸文件名是散文，不是地址。占位符（含大写、花括号、空格）一律跳过。
#
#   [F] 只有**冻结记录**（design/*/spec/）里的断链只报数、不判 FAIL —— 那里的路径是
#       "当时的坐标"，正文不许改（R7）。**活文档的断链判 FAIL**：旧路径在活文档里
#       不是历史坐标，是一条必然失败的命令。
#
#   [G] 第 5 项的名字规则有两个窄豁免：
#       ① `app/<包>/assets/` —— Flutter 的 pubspec 资源目录（`flutter: assets:` 直接吃它），
#          层级固定为 app/<包>/assets，深一层或换位置都不是它。
#       ② `app/<包>/<平台>/packaging/<打包器>/assets/` —— 打包配置（deb/rpm 的
#          `icon: assets/logo.png`）按这个相对路径取图标；名字由打包器约定，不是混装袋。
#       两条都要求层级固定，且都不覆盖 archive/old/tmp/out/dist/misc。
#
#   [H] 第 8 项（孤儿检查）是信息级，不判 FAIL。匹配两档：全名；以及**只对 export/**
#       放开去扩展名的 stem（冻结裁决按裸 stem 点名）。引用源排除 design/brand/README.md，
#       否则资产登记表里的名字会替自己完成"有人引用"的证明。
#
set -u

cd "$(dirname "${BASH_SOURCE[0]}")/../.." 2>/dev/null || { echo "找不到仓库根"; exit 2; }
ROOT=$(pwd)
[ -f AGENTS.md ] && [ -d design ] && [ -d app ] && [ -d docs ] || {
  echo "这里不是 LiveHub 仓库根：$ROOT"; exit 2;
}

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

# Flutter / 构建产物的目录，所有权检查与垃圾桶检查都跳过它们。
PRUNE='-name .dart_tool -o -name build -o -name .gradle -o -name .kotlin -o -name Pods -o -name .symlinks -o -name ephemeral -o -name node_modules -o -name .cxx'
prune_find() { find . \( $PRUNE -o -name .git -o -name .workbuddy -o -name .playwright-mcp \) -prune -o "$@"; }

printf '\033[1mLiveHub 目录契约校验\033[0m  %s\n' "$ROOT"

# ─────────────────────────────────────────────────────────────────────────────
head_ 1 "顶层只有三个内容目录（R1）"
tops=$(ls -d */ 2>/dev/null | sed 's:/$::' | sort | tr '\n' ' ')
if [ "$tops" = "app design docs " ]; then
  ok "顶层目录 = app / design / docs"
else
  bad "顶层目录不是恰好这三个：$tops"
  for d in $tops; do
    case "$d" in app|design|docs) ;; *) item "多出来的：$d/（按 R1/R8 处理，不要新建第四个）";; esac
  done
fi
for bad_dir in assets build tools tmp out dist misc; do
  [ -d "$bad_dir" ] && bad "顶层出现禁用目录 $bad_dir/（R8）"
done

# ─────────────────────────────────────────────────────────────────────────────
head_ 2 "单一归属：一个字节只属于一侧（R2）"
n=$(find design -type d \( -name 'mipmap-*' -o -name 'drawable-*' -o -name values \) 2>/dev/null | wc -l | tr -d ' ')
if [ "$n" = 0 ]; then
  ok "design/ 下没有 res 形状的目录（mipmap-*/drawable-*/values）"
else
  bad "design/ 下出现了 $n 个 res 形状目录 —— 那是给平台资源造了第二个真相源"
  find design -type d \( -name 'mipmap-*' -o -name 'drawable-*' -o -name values \) | sed 's/^/      /'
fi

n=$(prune_find -type f -name '*.svg' -print 2>/dev/null | grep '^\./app/' | wc -l | tr -d ' ')
if [ "$n" = 0 ]; then
  ok "app/ 下没有 .svg（品牌矢量只在 design/brand/wordmark/src/）"
else
  bad "app/ 下有 $n 个 .svg"
  prune_find -type f -name '*.svg' -print | grep '^\./app/' | sed 's/^/      /'
fi

n=$(prune_find -type f -name '*.html' -print 2>/dev/null | grep '^\./app/' | wc -l | tr -d ' ')
if [ "$n" = 0 ]; then
  ok "app/ 下没有 .html 设计稿"
else
  bad "app/ 下有 $n 个 .html —— 设计稿归 design/"
  prune_find -type f -name '*.html' -print | grep '^\./app/' | sed 's/^/      /'
fi

# ─────────────────────────────────────────────────────────────────────────────
head_ 3 "源与产物分层（R3）"
junk=$(find design/brand/wordmark/src -type f ! -name '*.svg' 2>/dev/null)
[ -z "$junk" ] && ok "wordmark/src/ 里只有 .svg" || {
  bad "wordmark/src/ 里混进了非源文件"; echo "$junk" | sed 's/^/      /';
}
junk=$(find design/brand/wordmark/export -type f ! -name '*.png' 2>/dev/null)
[ -z "$junk" ] && ok "wordmark/export/ 里只有 .png" || {
  bad "wordmark/export/ 里混进了非位图"; echo "$junk" | sed 's/^/      /';
}

# ─────────────────────────────────────────────────────────────────────────────
head_ 4 "编号与版本号只在被授权的位置（R4 / R5）"
outside=$(find . \( -path ./.git -o -path ./.workbuddy -o -path ./.playwright-mcp -o -name build -o -name .dart_tool \) -prune \
          -o -name '[0-9][0-9]-*' -print 2>/dev/null \
          | sed 's|^\./||' | grep -v '^design/[^/]*/spec/')
if [ -z "$outside" ]; then
  ok "NN- 前缀只出现在 design/<版本>/spec/ 里"
else
  bad "NN- 前缀越界（编号是永久地址，只在 spec/ 里能用）"; echo "$outside" | sed 's/^/      /';
fi

# macOS 自带 bash 3.2 在 $( ) 里直接写 case 会报 syntax error，所以抽成函数。
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
if [ -z "$vbad" ]; then
  ok "文件名里没有 v<数字>；版本轴只有 design/vX.Y.Z/ 一处（app/ 里的 -vNN 是平台限定符，不扫）"
else
  bad "v<数字> 出现在文件名里（R5：版本由目录承担，不写进文件名）"; echo "$vbad" | sed 's/^/      /';
fi
[ -z "$(ls -d design/v*/ 2>/dev/null | grep -v -E 'design/v[0-9]+\.[0-9]+\.[0-9]+/$')" ] \
  && ok "版本目录名都是 vX.Y.Z" || bad "有版本目录不是 vX.Y.Z 三段式"

# ─────────────────────────────────────────────────────────────────────────────
head_ 5 "没有垃圾桶目录（R6 / R8），设计侧脚本不写 app/（R9）"
found=$(prune_find -type d \( -name archive -o -name old -o -name backup -o -name tmp -o -name out \
                          -o -name dist -o -name misc -o -name assets \) -print 2>/dev/null \
        | grep -v -E '^\./app/[^/]+/assets$' \
        | grep -v -E '^\./app/[^/]+/[^/]+/packaging/[^/]+/assets$')
if [ -z "$found" ]; then
  ok "没有 archive/old/backup/tmp/out/dist/misc/assets（豁免：app/<包>/assets/、packaging/<打包器>/assets/）"
else
  bad "出现垃圾桶目录（不确定归属的东西，删掉比存档便宜 —— 历史在 git 里）"
  echo "$found" | sed 's/^/      /'
fi

writes=$(grep -rnE '(writeFileSync|appendFileSync|mkdirSync|copyFileSync|rmSync|renameSync|createWriteStream)[[:space:]]*\(' design --include='*.mjs' 2>/dev/null \
         | grep -E "['\"]app/|['\"]app['\"]|/app/" || true)
if [ -z "$writes" ]; then
  ok "design/ 下的 .mjs 没有写 app/ 的语句（R9）"
else
  bad "设计侧脚本在写 app/（R9 违规：设计侧不持有开发侧写权限）"
  echo "$writes" | sed 's/^/      /'
fi

# ─────────────────────────────────────────────────────────────────────────────
head_ 6 "平台图标同步状态（WARN，不判 FAIL）"
EXPORT=design/brand/wordmark/export
synced=0
pending=0
for target in livehub_tv livehub; do
  res="app/$target/android/app/src/main/res"
  if [ ! -d "$res" ]; then
    warn "$res 不存在"
    continue
  fi
  for pair in \
    "mipmap-mdpi:square-48-B.png" \
    "mipmap-hdpi:square-72.png" \
    "mipmap-xhdpi:square-96.png" \
    "mipmap-xxhdpi:square-144.png" \
    "mipmap-xxxhdpi:square-192.png"
  do
    density=${pair%%:*}; src=${pair##*:}
    s="$EXPORT/$src"; d="$res/$density/ic_launcher.png"
    if [ -f "$s" ] && [ -f "$d" ] && [ "$(md5of "$s")" = "$(md5of "$d")" ]; then
      synced=$((synced+1))
    else
      pending=$((pending+1))
      item "待同步：$d"
    fi
  done
done
if [ "$pending" -eq 0 ]; then
  ok "livehub / livehub_tv 各 5 档 ic_launcher.png 与 export 逐字节相同（$synced 档）"
else
  warn "$pending 档图标与 export 不一致（先确认 export 已由 build-icons 更新，再跑 node app/tools/sync-icons.mjs --write）"
fi

BANNER=app/livehub_tv/android/app/src/main/res/mipmap-xhdpi/ic_banner.png
if [ ! -f "$BANNER" ]; then
  warn "缺 TV banner：$BANNER"
else
  ct=$(png_colortype "$BANNER")
  if [ "$ct" = 2 ]; then
    ok "TV banner 是 RGB 满版（colortype 2，圆角交给 launcher 裁）"
  else
    warn "TV banner colortype=${ct}，手工档预期 2（RGB 满版）；真源 wordmark/src/banner-16x9-bleed.svg"
  fi
fi

# ─────────────────────────────────────────────────────────────────────────────
head_ 7 "文档里的路径是真的（R7「指针改，记录不改」/ R10 全路径）"

# 本契约直接负责的活文档。其他活文档里的旧 android/ 引用只报 WARN，不判 FAIL。
LIVE_DOCS="README.md app/README.md app/livehub/README.md app/livehub_tv/README.md app/livehub_core/README.md app/livehub_console/README.md design/brand/README.md"

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
    design/*|app/*|docs/*|README.md) echo "$ROOT/$_c" ;;
    *) return 0 ;;
  esac
}

extract_ptrs() {
  grep -oE '\]\([^)[:space:]]+\)|`[^`]+`|(src|href)="[^"]+"' "$1" 2>/dev/null \
    | sed -E 's/^\]\(//; s/\)$//; s/^`//; s/`$//; s/^(src|href)="//; s/"$//' \
    | grep -E '^(design/|app/|docs/|README\.md|\.\.?/)'
}

n_fail=0
for f in $LIVE_DOCS; do
  [ -f "$f" ] || continue
  miss=""
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    case "${p#*/}" in
      *[A-Z]*) continue ;;
    esac
    target=$(ptr_target "$f" "$p")
    [ -z "$target" ] && continue
    [ -e "$target" ] || miss="$miss$p
"
  done <<< "$(extract_ptrs "$f")"
  if [ -n "$miss" ]; then
    n=$(printf '%s' "$miss" | grep -c .)
    n_fail=$((n_fail+n))
    bad "$f 有 $n 条指针指向不存在的路径"
    printf '%s' "$miss" | sed 's/^/      /'
  fi
done
[ "$n_fail" -eq 0 ] && ok "契约负责的活文档 0 条断链"

# 冻结记录：只报数，不判 FAIL（R7）。
n_rec=0; rec_files=""
for f in design/*/spec/*.md; do
  [ -f "$f" ] || continue
  miss=""
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    case "${p#*/}" in
      *[A-Z]*) continue ;;
    esac
    target=$(ptr_target "$f" "$p")
    [ -z "$target" ] && continue
    [ -e "$target" ] || miss="$miss$p
"
  done <<< "$(extract_ptrs "$f")"
  if [ -n "$miss" ]; then
    n=$(printf '%s' "$miss" | grep -c .)
    n_rec=$((n_rec+n)); rec_files="$rec_files$f($n) "
  fi
done
[ "$n_rec" -gt 0 ] && item "冻结记录断链 ${n_rec} 条（R7：正文不改，按历史坐标读）：$rec_files"

stale=""
while IFS= read -r f; do
  grep -qE '(^|[^/A-Za-z0-9_-])android/' "$f" 2>/dev/null && stale="$stale$f "
done <<< "$(find design docs \( -path 'design/v1.0.0/*' -o -path '*/spec/*' \) -prune -o -type f \( -name '*.md' -o -name '*.html' \) -print 2>/dev/null)"
[ -n "$stale" ] && warn "以下活文档仍在引用旧 android/ 布局（由各自负责人更新）：$stale"

# ─────────────────────────────────────────────────────────────────────────────
head_ 8 "孤儿：没有任何引用者的位图（信息级，不判 FAIL）"
REF=$( {
  find design docs app \( -name .dart_tool -o -name build -o -name node_modules \) -prune \
    -o -type f \( -name '*.md' -o -name '*.html' \) -print 2>/dev/null \
    | grep -v '^design/brand/README.md$' \
    | while IFS= read -r f; do cat "$f" 2>/dev/null; done
  find app/tools design/brand -name '*.mjs' -exec cat {} + 2>/dev/null
  cat README.md AGENTS.md 2>/dev/null
} )
orphan=0
for p in "$EXPORT"/*.png design/v1.0.0/shots/*.png design/v1.0.0/screenshots/*.png; do
  [ -f "$p" ] || continue
  base=$(basename "$p")
  n=$(printf '%s' "$REF" | grep -c -F -- "$base")
  if [ "$n" = 0 ]; then
    case "$p" in
      "$EXPORT"/*)
        stem=${base%.png}
        n=$(printf '%s' "$REF" | grep -c -E -- "$stem([^0-9A-Za-z-]|$)") ;;
    esac
  fi
  if [ "$n" = 0 ]; then orphan=$((orphan+1)); item "零引用：$p"; fi
done
[ "$orphan" = 0 ] && ok "没有零引用位图（全名 + export/ 的裸 stem，均已在引用源里数过）" \
  || warn "$orphan 张位图零引用 —— 有生成命令**且**确实没人用的才可删（R6，两个条件缺一不可）"

# ─────────────────────────────────────────────────────────────────────────────
printf '\n────────────────────────────────────────\n'
if [ "$FAIL" -eq 0 ]; then
  printf '\033[32m★ 布局自检通过\033[0m（%d 项警告）\n' "$WARN"
  exit 0
else
  printf '\033[31m★ %d 项 FAIL\033[0m（%d 项警告）—— 修完再跑一遍\n' "$FAIL" "$WARN"
  exit 1
fi
