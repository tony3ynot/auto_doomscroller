#!/usr/bin/env bash
# Build the debug APK, install it on the connected phone and launch it.
#
#   scripts/run-debug.sh            build + install + launch
#   scripts/run-debug.sh --logcat   ...then stream the app's debug logs
#
# On WSL this uses the Windows adb.exe, so a USB-connected phone works without usbipd.
# Override with ADB=/path/to/adb.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
APP_ID="com.autodoomscroller"
LOG_TAGS=(ReelTracker ScreenCapturer ReelsService NodeTree)

find_adb() {
  if [[ -n "${ADB:-}" ]]; then echo "$ADB"; return; fi
  if command -v adb.exe >/dev/null; then command -v adb.exe; return; fi
  local win_sdk="${WINDOWS_ANDROID_SDK:-}"
  if [[ -n "$win_sdk" && -x "$win_sdk/platform-tools/adb.exe" ]]; then echo "$win_sdk/platform-tools/adb.exe"; return; fi
  command -v adb
}

ADB="$(find_adb)"
apk_arg="$APK"
[[ "$ADB" == *.exe ]] && apk_arg="$(wslpath -w "$APK")"

(cd "$ROOT/android" && ./gradlew assembleDebug)

if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "No device found. Connect the phone with USB debugging on, then run: $ADB devices" >&2
  exit 1
fi

"$ADB" install -r "$apk_arg"
"$ADB" shell am start -n "$APP_ID/.MainActivity" >/dev/null
echo "Installed and launched $APP_ID."

if [[ "${1:-}" == "--logcat" ]]; then
  "$ADB" logcat -c
  "$ADB" logcat -s "${LOG_TAGS[@]}"
fi
