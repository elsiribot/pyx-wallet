#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/tool/certify-flutter-to-native-upgrade.sh"
bash -n "$SCRIPT"
"$SCRIPT" --help 2>&1 | grep -F -- '--flutter-apk' >/dev/null
"$SCRIPT" --help 2>&1 | grep -F -- '--native-apk' >/dev/null
grep -F 'adb_device install "$FLUTTER_APK"' "$SCRIPT" >/dev/null
grep -F 'adb_device install -r "$NATIVE_APK"' "$SCRIPT" >/dev/null
if grep -E 'adb(_device)? (uninstall|shell pm clear)|pm (clear|uninstall)' "$SCRIPT" >/dev/null; then
  echo "upgrade certifier contains a forbidden clear/uninstall command" >&2; exit 1
fi
echo "Upgrade certifier static safety checks passed."
