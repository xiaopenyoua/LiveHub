#!/usr/bin/env bash
# 取流层错误码的离线验收。不跑 Gradle、不连公网、不需要 Android 设备。
#
# 为什么不用 Gradle 单测：这个仓库的 build.gradle.kts 不在本轮的编辑范围里，
# 而 MockWebServer 需要新依赖。这个脚本用 JDK 自带 HttpServer + 从 Gradle 缓存里
# 拿到的 jar，做到同一件事（覆盖 13 行映射 + 数请求次数验重试），且零新增依赖。
set -u
cd "$(dirname "$0")/../.." || exit 1   # -> android/
ANDROID_DIR="$PWD"
SRC="$ANDROID_DIR/app/src/main/java/com/livehub/tv"

C="${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}"
jar() { find "$C/$1/$2" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | head -1; }

KC=$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable 2.0.21)
STD=$(jar org.jetbrains.kotlin/kotlin-stdlib 2.0.21)
TR=$(jar org.jetbrains.intellij.deps/trove4j 1.0.20200330)
AN=$(jar org.jetbrains/annotations 23.0.0)
CO=$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 1.6.4)
OK=$(jar com.squareup.okhttp3/okhttp 4.12.0)
OKIO=$(jar com.squareup.okio/okio-jvm 3.9.0)
JSON=$(jar org.json/json 20180813)
AJ="${ANDROID_JAR:-$HOME/Library/Android/sdk/platforms/android-34/android.jar}"

for v in KC STD TR AN CO OK OKIO JSON; do
  eval "p=\$$v"
  if [ -z "$p" ]; then echo "缺 jar: $v（Gradle 缓存里没有，先跑一次 ./gradlew 让它下载）"; exit 2; fi
done
if [ ! -f "$AJ" ]; then echo "缺 android.jar: $AJ（用 ANDROID_JAR=... 指定）"; exit 2; fi

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

# android.jar 只在编译期需要（虎牙的 antiQuery 用了 android.util.Base64），
# 运行期不会加载那个类；org.json 用真实实现，不是 android.jar 里的 stub。
java -cp "$KC:$STD:$TR:$AN:$CO" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -nowarn -jvm-target 17 \
  -classpath "$STD:$CO:$OK:$OKIO:$JSON:$AJ" \
  -d "$OUT/classes" \
  "$SRC/core/model" "$SRC/core/net/Net.kt" \
  "$SRC/core/site/LiveSite.kt" "$SRC/core/site/SiteCapabilities.kt" \
  "$SRC/core/site/HuyaSite.kt" "$SRC/core/site/DouyinSite.kt" \
  "$ANDROID_DIR/tools/error-mapping-check/ErrorMappingCheck.kt" || {
  echo "编译失败"; exit 3; }

java -cp "$OUT/classes:$STD:$CO:$OK:$OKIO:$JSON" check.ErrorMappingCheck
