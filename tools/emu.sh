#!/usr/bin/env bash
# Boot the cursor-kb emulator (headless), install the debug keyboard, set it
# as the IME, and tunnel the emulator's 127.0.0.1:9043 to a host-side bridge.
#
# Bridge on the host (optional, needs `agent` CLI on the host):
#   python3 bridge/cursor_acp_bridge.py --host 127.0.0.1 --port 9043 --workspace ~
# View/interact: Cursor command palette -> "Android: Open Device Screen".
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
AVD="${AVD:-cursor-kb}"
APK="build/outputs/apk/debug/LatinIME-debug.apk"
IME="com.android.inputmethod.latin/.LatinIME"

[ -f "$APK" ] || ./gradlew assembleDebug

if ! adb devices | rg -q 'emulator-[0-9]+\s+device'; then
  "$SDK/emulator/emulator" -avd "$AVD" -no-window -no-audio \
    -gpu swiftshader_indirect -no-boot-anim -no-snapshot &
fi
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 3
done

adb install -r "$APK" || {
  adb shell pm uninstall --user 0 com.android.inputmethod.latin
  adb install -r "$APK"
}
adb shell ime enable "$IME"
adb shell ime set "$IME"
adb reverse tcp:9043 tcp:9043

adb shell ime list -s
echo "Ready. View with: Android: Open Device Screen"
