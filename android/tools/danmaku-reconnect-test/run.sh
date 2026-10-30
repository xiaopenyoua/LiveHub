#!/bin/bash
# 弹幕「有限重连」夹具 —— 生成源码副本 → 打端点补丁 → 编译 → 运行。
#
# 为什么不接进 Gradle：它要跑在桌面 JVM 上（模拟器/真机都造不出可控的掐断），
# 接进 app 模块会给 assembleDebug 拉进一堆只测试用的源码副本。
# 所以这里用 Gradle 缓存里已有的 kotlinc 与依赖 jar 直接编译，**不联网**。
#
# 用法：
#   ./run.sh            # 跑全部用例
#   ./run.sh douyu      # 只跑有 douyu 字样的用例
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/../.." && pwd)/app/src/main/java/com/livehub/tv/core"
GEN="$HERE/build/gen"
OUT="$HERE/build/out"
CACHE="${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}"

pick() {  # pick <jar 名通配>：在 Gradle 缓存里找最新的那个
  local f
  f="$(find "$CACHE" -type f -name "$1" 2>/dev/null | sort | tail -1)"
  [[ -n "$f" ]] || { echo "找不到 $1，检查 GRADLE_CACHE=$CACHE" >&2; exit 1; }
  echo "$f"
}

# ---- 1. 生成源码副本（每次从 app/src 重新拷，保证测的是当前源码）
rm -rf "$GEN" "$OUT"
mkdir -p "$GEN/com/livehub/tv/core"
cp -R "$APP/danmaku" "$GEN/com/livehub/tv/core/danmaku"
cp -R "$APP/net"    "$GEN/com/livehub/tv/core/net"
cp -R "$APP/model"  "$GEN/com/livehub/tv/core/model"

# ---- 2. 打端点补丁（只作用于副本；仓库里的 app 源码零改动）
#         先 --dry-run：若生产源码动过 WS_URL/resolve 那两行，这里会直接失败并提示更新补丁，
#         而不是悄悄测一个过时的副本。
patch -p1 --dry-run -s -d "$GEN" < "$HERE/patches/endpoints.patch" \
  || { echo "补丁打不上：生产源码里 WS_URL / resolve() 那几行改过了，请同步更新 patches/endpoints.patch" >&2; exit 1; }
patch -p1 -s -d "$GEN" < "$HERE/patches/endpoints.patch"
echo "补丁已应用（douyu.ws / huya.ws / huya.room 三个覆盖点）"

# ---- 3. 编译
KC="$(pick 'kotlin-compiler-embeddable-*.jar')"
TROVE="$(pick 'trove4j-*.jar')"
ANN="$(pick 'annotations-2*.jar')"
STDLIB="$(pick 'kotlin-stdlib-2*.jar')"
COROUT="$(pick 'kotlinx-coroutines-core-jvm-*.jar')"
OKHTTP="$(pick 'okhttp-4*.jar')"
OKIO="$(pick 'okio-jvm-*.jar')"
JSON="$(pick 'json-2*.jar')"

RUNCP="$STDLIB:$COROUT:$OKHTTP:$OKIO:$JSON"

mkdir -p "$OUT"
# 注意 -no-stdlib：stdlib 从 -cp 里给，避免和 iOS/JS 变体打架
java -cp "$KC:$TROVE:$ANN:$STDLIB:$COROUT:$OKHTTP:$OKIO" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -nowarn -jvm-target 17 -cp "$RUNCP" -d "$OUT" \
  $(find "$GEN" "$HERE/src" -name '*.kt')
echo "编译完成 → $OUT"

# ---- 4. 运行
java -cp "$OUT:$RUNCP" harness.MainKt "$@"
