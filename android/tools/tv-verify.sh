#!/usr/bin/env bash
# TV 验证脚本：构建 → 安装 → 启动 → 按键走查 → 截图
#
# 用法：
#   ./tools/tv-verify.sh                 # 全流程
#   ./tools/tv-verify.sh shots           # 只截图（假设已安装且在前台）
#   AVD=googletv_api36 ./tools/tv-verify.sh
#
# 截图落在 design/screenshots-tv/，按 01-home.png … 的顺序命名。

set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
PKG="com.livehub.tv"
ACT="$PKG/.MainActivity"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/../design/screenshots-tv"

key() { "$ADB" shell input keyevent "$1"; }
shot() {
  mkdir -p "$OUT"
  "$ADB" exec-out screencap -p > "$OUT/$1.png"
  echo "  → $OUT/$1.png"
}
wait() { sleep "${1:-1}"; }

boot_emulator() {
  if "$ADB" devices | grep -q "emulator-.*device"; then
    echo "== 模拟器已就绪"
    return
  fi
  AVD="${AVD:-googletv_api36}"
  echo "== 启动模拟器 $AVD"
  "$SDK/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim \
      -gpu swiftshader_indirect -no-snapshot >/tmp/livehub-emulator.log 2>&1 &
  "$ADB" wait-for-device
  for _ in $(seq 1 90); do
    [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 2
  done
}

build_install() {
  echo "== 构建 debug APK"
  (cd "$ROOT" && ./gradlew :app:assembleDebug --console=plain -q)
  echo "== 安装"
  "$ADB" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
}

launch() {
  echo "== 启动 $PKG"
  "$ADB" shell am force-stop "$PKG" || true
  "$ADB" shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 || \
    "$ADB" shell am start -n "$ACT" >/dev/null
  wait 4
}

walkthrough() {
  echo "== 按键走查"
  shot 01-home
  key KEYCODE_DPAD_DOWN;  wait; shot 02-home-down
  key KEYCODE_DPAD_RIGHT; wait
  key KEYCODE_DPAD_CENTER; wait 3; shot 03-player
  key KEYCODE_DPAD_DOWN;  wait; shot 04-player-osd
  key KEYCODE_BACK;       wait
  key KEYCODE_BACK;       wait; shot 05-back-home
  key KEYCODE_DPAD_UP;    wait
  key KEYCODE_DPAD_RIGHT; wait
  key KEYCODE_DPAD_RIGHT; wait; shot 06-browse
  key KEYCODE_DPAD_RIGHT; wait
  key KEYCODE_DPAD_RIGHT; wait; shot 07-search
}

case "${1:-all}" in
  shots)  shot "${2:-00-screen}" ;;
  walk)   walkthrough ;;
  *)
    boot_emulator
    build_install
    launch
    walkthrough
    echo "== 完成。崩溃日志：$ADB logcat -d -s AndroidRuntime:E"
    ;;
esac
