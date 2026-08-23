#!/usr/bin/env bash
# tool/check_tokens.sh — fail if raw colors/fonts appear outside the token layer.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BAD=$(grep -rnE 'Color\(0x|Colors\.[a-z]|fontFamily: *['"'"'"]' "$ROOT/lib" \
  --include='*.dart' \
  | grep -v 'lib/theme/tokens.dart' \
  | grep -v 'lib/bridge_generated.dart/' \
  | grep -vE 'Colors\.(transparent|white|black)\b' || true)
if [ -n "$BAD" ]; then
  echo "Raw colors/fonts outside lib/theme/tokens.dart:"
  echo "$BAD"
  exit 1
fi
echo "check_tokens: OK"
