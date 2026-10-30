#!/bin/bash
# 抖音弹幕 spike —— 编译 + 运行。**完全不碰 app/src**。
#
# 与 danmaku-reconnect-test 的关键区别：那边要拷 core/{danmaku,net,model} 的源码副本再打补丁，
# 这边**一行生产源码都不读**（backend 正在改 core/net、core/site、core/model，读了就会被卡住）。
# 依赖只有三样：Rhino（下载一次，缓存在 build/lib）、OkHttp、kotlin-stdlib —— 都从 Gradle 缓存取。
#
# 用法：
#   ./run.sh                      # 用 README 里记的那间房
#   ./run.sh <web_rid> <room_id>   # 换房间（房间下播了就换）
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build/out"
LIB="$HERE/build/lib"
CACHE="${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}"
RHINO="$LIB/rhino-1.7.14.jar"
RHINO_URL="https://repo1.maven.org/maven2/org/mozilla/rhino/1.7.14/rhino-1.7.14.jar"
# 记录下来的预期指纹：换了 jar 就等于换了签名引擎，必须显式重验
RHINO_SHA="c9290b0d801bf0dbbbc44338e0f769b7650a0c5d04e6bb1aeb85775c0211b003"

# 一次遍历把要用的 jar 全找出来，**不要每个依赖 find 一次**：
# 这台机器上 `find ~/.gradle/caches/modules-2/files-2.1` 单趟要 60s 上下（几十万个文件），
# 7 个依赖就是 7 分钟，看起来和「卡死」一模一样。实测过一次，别再改回去。
pick_all() {
  find "$CACHE" -type f \( \
    -name 'kotlin-compiler-embeddable-*.jar' -o \
    -name 'trove4j-*.jar' -o \
    -name 'annotations-2*.jar' -o \
    -name 'kotlin-stdlib-2*.jar' -o \
    -name 'okhttp-4*.jar' -o \
    -name 'okio-jvm-*.jar' -o \
    -name 'kotlinx-coroutines-core-jvm-*.jar' \
  \) 2>/dev/null | sort
}

echo "查找依赖 jar（一次遍历 Gradle 缓存，这一步要 1 分钟左右，安静是正常的）…"
JARS="$(pick_all)"
echo "找到 $(printf '%s\n' "$JARS" | grep -c . ) 个候选 jar"

pick() {
  local pat="$1" f best=""
  while IFS= read -r f; do
    [[ "$(basename "$f")" == $pat ]] && best="$f"
  done <<< "$JARS"
  [[ -n "$best" ]] || { echo "找不到 $pat，检查 GRADLE_CACHE=$CACHE" >&2; exit 1; }
  echo "$best"
}

# 注意：这里**不删** $OUT。以前是 `rm -rf "$OUT"; mkdir -p "$OUT"`，两个问题：
#   1. 某些环境下 rm -rf 会被安全钩子拦下（BULK_CONFIRM），结果 run.sh 直接以非零退出，
#      而日志里只有一行 JSON——比编译错误难查得多；
#   2. kotlinc 本来就会覆盖同名 .class。
# 代价是**删掉的源码文件会留下一个孤儿 .class**。真要干净重编就手动 `rm -rf build/out`。
mkdir -p "$LIB" "$OUT"

# ---- 1. Rhino（唯一一个不在 Gradle 缓存里的依赖：app 没引入它，**生产端也不用它**）
# 它在这个 spike 里只当对照组：同一份 sign.js、同一条 md5，Rhino 签出来抖音回 200、V8 才 101。
# RHINO_JAR=<path> 可以临时换引擎（比如试 1.8.x）——换了就不校验指纹，但会打印警告：
# 换引擎等于换签名结果，必须重新跑一次 101 验证，别拿它当「升级了应该更好」。
if [[ -n "${RHINO_JAR:-}" ]]; then
  RHINO="$RHINO_JAR"
  echo "⚠ 使用外部 Rhino：${RHINO}（跳过指纹校验）"
  [[ -f "$RHINO" ]] || { echo "RHINO_JAR 不存在：$RHINO" >&2; exit 1; }
else
  if [[ ! -f "$RHINO" ]]; then
    echo "下载 Rhino 1.7.14 → $RHINO"
    curl -sSL --max-time 120 -o "$RHINO" "$RHINO_URL"
  fi
  got="$(shasum -a 256 "$RHINO" | awk '{print $1}')"
  [[ "$got" == "$RHINO_SHA" ]] || { echo "Rhino jar 指纹不符：$got ≠ $RHINO_SHA" >&2; exit 1; }
fi

# ---- 2. 编译（不拷任何 app/src 源码）
KC="$(pick 'kotlin-compiler-embeddable-*.jar')"
TROVE="$(pick 'trove4j-*.jar')"
ANN="$(pick 'annotations-2*.jar')"
STDLIB="$(pick 'kotlin-stdlib-2*.jar')"
OKHTTP="$(pick 'okhttp-4*.jar')"
OKIO="$(pick 'okio-jvm-*.jar')"
# 编译器自己要解析依赖时会用到 coroutines（否则 K2JVMCompiler 起不来），
# 虽然这个 spike 的源码一行协程都没写，也必须给。
COROUT="$(pick 'kotlinx-coroutines-core-jvm-*.jar')"

RUNCP="$STDLIB:$OKHTTP:$OKIO:$RHINO"

mkdir -p "$OUT"   # 不清空：见上面那段「这里不删 $OUT」的理由
echo "编译中（$(date +%T)）…"
java -cp "$KC:$TROVE:$ANN:$STDLIB:$COROUT:$RHINO" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -nowarn -jvm-target 17 -cp "$RUNCP" -d "$OUT" \
  $(find "$HERE/src" -name '*.kt')
echo "编译完成 → $OUT"

# ---- 3. 运行（-Dspike.dir 让程序找到 assets/sign.js）
exec java -Dspike.dir="$HERE" -cp "$OUT:$RUNCP" spike.MainKt "$@"
