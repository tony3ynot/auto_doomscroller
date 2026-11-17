#!/usr/bin/env bash
# Build the debug APK, install it on the connected phone and launch it.
#
#   scripts/run-debug.sh            build + install + launch
#   scripts/run-debug.sh --logcat   ...then stream the app's debug logs
#   scripts/run-debug.sh --logcat HandsFree ReelsService   ...only these tags
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
APP_ID="com.autodoomscroller"
LOG_TAGS=(ReelTracker ScreenCapturer ReelsService NodeTree HandsFree)

source "$ROOT/scripts/adb.sh"

(cd "$ROOT/android" && ./gradlew assembleDebug)


require_device
"$ADB" install -r "$(adb_path "$APK")"
"$ADB" shell am start -n "$APP_ID/.MainActivity" >/dev/null
echo "Installed and launched $APP_ID."

if [[ "${1:-}" == "--logcat" ]]; then
  shift
  (( $# )) && LOG_TAGS=("$@")
  "$ADB" logcat -c
  "$ADB" logcat -s "${LOG_TAGS[@]}"
fi
