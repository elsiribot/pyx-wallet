#!/usr/bin/env bash
# tool/app_shot.sh <name> [serial] — screenshot the connected Android device.
# Output: docs/design/shots/app/<name>.png
set -euo pipefail
NAME="${1:?usage: app_shot.sh <name> [adb-serial]}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/docs/design/shots/app"; mkdir -p "$OUT"
ADB="${ADB:-$HOME/.local/share/android-local/adb/bin/adb}"
command -v "$ADB" >/dev/null 2>&1 || ADB=adb
SERIAL_ARGS=()
[ $# -ge 2 ] && SERIAL_ARGS=(-s "$2")
"$ADB" "${SERIAL_ARGS[@]}" exec-out screencap -p > "$OUT/$NAME.png"
echo "$OUT/$NAME.png"
