#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; SCRIPT="$ROOT/tool/certify-native-android-performance.sh"
bash -n "$SCRIPT"
grep -F 'PREVIEW_PACKAGE="cash.pyx.app.nativepreview"' "$SCRIPT" >/dev/null
grep -F 'PRODUCTION_PACKAGE="cash.pyx.app"' "$SCRIPT" >/dev/null
grep -F 'for sample in 1 2 3 4 5' "$SCRIPT" >/dev/null
grep -F 'sleep 10' "$SCRIPT" >/dev/null
grep -F 'SUCCESS=1' "$SCRIPT" >/dev/null
if grep -E 'uninstall|pm clear "\$PRODUCTION_PACKAGE"|install .*"\$RELEASE_APK"' "$SCRIPT" >/dev/null; then
  echo "forbidden production mutation found" >&2; exit 1
fi
echo "Performance certifier static safety checks passed."
