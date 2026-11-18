#!/usr/bin/env bash
# Put a skip model on the connected phone.
#
#   scripts/push-model.sh [ml/models/skip_model.json]
#
# The file comes from `python -m autodoom.deploy`. The app reads it when a session starts,
# so switch the mode to Off and back afterwards.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/adb.sh"

MODEL="${1:-$ROOT/ml/models/skip_model.json}"
TARGET="/sdcard/Android/data/com.autodoomscroller/files/model"

if [[ ! -f "$MODEL" ]]; then
  echo "No model at $MODEL. Make one with: python -m autodoom.deploy data/<export> --out ml/models/skip_model.json" >&2
  exit 1
fi
require_device

"$ADB" shell mkdir -p "$TARGET"
"$ADB" push "$(adb_path "$MODEL")" "$TARGET/skip_model.json"
