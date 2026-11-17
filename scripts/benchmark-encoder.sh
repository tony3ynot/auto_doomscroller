#!/usr/bin/env bash
# Time the frame encoder builds on the connected phone and write a result folder.
#
#   scripts/benchmark-encoder.sh results/<date>-encoder-latency
#
# Needs the builds in ml/models (python -m autodoom.quantize) and the ml virtualenv. Takes
# about twelve minutes. Leave the phone unlocked, on a table, with nothing else running. The
# benchmark is a package of its own, removed again at the end; the app is not touched.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/adb.sh"

OUT="${1:?usage: scripts/benchmark-encoder.sh results/<date>-encoder-latency}"
APK="$ROOT/android/benchmark/build/outputs/apk/androidTest/debug/benchmark-debug-androidTest.apk"
PACKAGE="com.autodoomscroller.benchmark.test"
PYTHON="${PYTHON:-$ROOT/ml/.venv/bin/python}"

if ! compgen -G "$ROOT/ml/models/encoder_*.tflite" >/dev/null; then
  echo "No encoder builds in ml/models. Make them with: python -m autodoom.quantize" >&2
  exit 1
fi

(cd "$ROOT/android" && ./gradlew :benchmark:assembleDebugAndroidTest)
require_device

printed="$(mktemp)"
trap 'rm -f "$printed"; "$ADB" uninstall "$PACKAGE" >/dev/null 2>&1 || true' EXIT

"$ADB" install -r -t "$(adb_path "$APK")"
"$ADB" shell input keyevent KEYCODE_WAKEUP
"$ADB" shell am instrument -w -r "$PACKAGE/androidx.test.runner.AndroidJUnitRunner" | tee "$printed" | grep -v '^INSTRUMENTATION_STATUS' || true

"$PYTHON" "$ROOT/ml/scripts/encoder_latency.py" "$printed" --models "$ROOT/ml/models" --out "$OUT"
