#!/usr/bin/env bash
# Measure what a stretch of feed costs in battery with the app set one way.
#
#   scripts/measure-battery.sh <name> [minutes]        (default: 10 minutes)
#   python ml/scripts/battery.py data/battery --out results/<date>-battery
#
# The phone must be off the charger, so adb has to reach it over Wi-Fi: with the cable in,
# `adb tcpip 5555` and `adb connect <phone's address>:5555`, then unplug. Set the app the way
# this run should measure it (mode, hands-free), open the feed, and start the script. It
# resets the phone's battery statistics, swipes to the next reel every four seconds so that
# every run sees the same pace, and saves the statistics at the end.
#
# One run per setting to compare, plus a second run of one of them to see how much two runs
# of the same setting differ. Leave the phone to cool between runs. The reels swiped past are
# recorded by the app like any others when it is on: note the times and leave them out of
# later analyses. The saved statistics name every app that ran, so data/battery stays out of
# git; battery.py takes the totals and this app's and the feed apps' shares from them.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/adb.sh"

NAME="${1:-}"
MINUTES="${2:-10}"
if [[ ! "$NAME" =~ ^[a-z0-9-]+$ || ! "$MINUTES" =~ ^[0-9]+$ ]]; then
  echo "usage: scripts/measure-battery.sh <name> [minutes]   (name: lower-case letters, digits, dashes)" >&2
  exit 1
fi
OUT="$ROOT/data/battery"
APP_ID="com.autodoomscroller"
SWIPE_EVERY_S=4
require_device

sh() { "$ADB" shell "$@" | tr -d '\r'; }

if sh dumpsys battery | grep -qE '(AC|USB|Wireless) powered: true'; then
  echo "The phone is charging. Unplug it and connect adb over Wi-Fi first." >&2
  exit 1
fi

# The uid as the statistics print it, e.g. u0a355; empty if the app isn't installed.
uid() {
  local id
  id="$(sh dumpsys package "$1" | sed -n 's/.*appId=\([0-9]*\).*/\1/p' | head -1)"
  [[ -n "$id" ]] && echo "u0a$((id - 10000))"
}

setting() { sed -n "s/.*name=\"$1\"[^>]*value=\"\([a-z]*\)\".*/\1/p" <<<"$PREFS"; }

conditions() {
  PREFS="$(sh run-as "$APP_ID" cat shared_prefs/session.xml)"
  echo "$1_time=$(date +%Y-%m-%dT%H:%M:%S%z)"
  echo "$1_mode=$(sed -n 's/.*name="mode">\([A-Z]*\)<.*/\1/p' <<<"$PREFS")"
  echo "$1_research_mode=$(setting research_mode)"
  echo "$1_ad_skip=$(setting ad_skip)"
  echo "$1_hands_free=$(sh dumpsys activity services "$APP_ID/.handsfree.HandsFreeService" | grep -q ServiceRecord && echo true || echo false)"
  echo "$1_brightness=$(sh settings get system screen_brightness)"
  echo "$1_brightness_auto=$(sh settings get system screen_brightness_mode)"
}

mkdir -p "$OUT"
{
  echo "name=$NAME"
  echo "device=$(sh getprop ro.product.model)"
  echo "android=$(sh getprop ro.build.version.release)"
  echo "uid_app=$(uid "$APP_ID")"
  echo "uid_instagram=$(uid com.instagram.android)"
  echo "uid_youtube=$(uid com.google.android.youtube)"
  echo "swipe_every_s=$SWIPE_EVERY_S"
  conditions start
} > "$OUT/$NAME.conditions"

# Swipe from three quarters down the screen to one quarter. The loop runs on the phone, so a
# dropped connection doesn't change the pace.
read -r WIDTH HEIGHT < <(sh wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
SWIPE="input swipe $((WIDTH / 2)) $((HEIGHT * 3 / 4)) $((WIDTH / 2)) $((HEIGHT / 4)) 150"
SECONDS_TO_RUN=$((MINUTES * 60))

sh dumpsys batterystats --reset >/dev/null
sh "nohup sh -c 'end=\$(( \$(date +%s) + $SECONDS_TO_RUN )); while [ \$(date +%s) -lt \$end ]; do $SWIPE; sleep $((SWIPE_EVERY_S - 1)).5; done' >/dev/null 2>&1 &"
echo "Swiping every $SWIPE_EVERY_S s for $MINUTES min. Leave the phone alone."
sleep "$((SECONDS_TO_RUN + 5))"

"$ADB" exec-out dumpsys batterystats > "$OUT/$NAME.batterystats.txt"
conditions stop >> "$OUT/$NAME.conditions"
echo "Saved $OUT/$NAME.batterystats.txt"
