# Sourced by the other scripts: finds adb and sets ADB.
#
# On WSL this picks the Windows adb.exe, so a USB-connected phone works without usbipd.
# Override with ADB=/path/to/adb.

find_adb() {
  if [[ -n "${ADB:-}" ]]; then echo "$ADB"; return; fi
  if command -v adb.exe >/dev/null; then command -v adb.exe; return; fi
  local win_sdk="${WINDOWS_ANDROID_SDK:-}"
  if [[ -n "$win_sdk" && -x "$win_sdk/platform-tools/adb.exe" ]]; then echo "$win_sdk/platform-tools/adb.exe"; return; fi
  command -v adb
}

ADB="$(find_adb)"

# A local path as this adb wants it: adb.exe needs a Windows path.
adb_path() {
  if [[ "$ADB" == *.exe ]]; then wslpath -w "$1"; else echo "$1"; fi
}

require_device() {
  if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "No device found. Connect the phone with USB debugging on, then run: $ADB devices" >&2
    exit 1
  fi
}
