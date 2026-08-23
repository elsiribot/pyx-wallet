#!/usr/bin/env bash
# tool/compare.sh <name> — side-by-side montage + RMSE tripwire + color samples.
# Expects docs/design/shots/ref/<name>.png and docs/design/shots/app/<name>.png
set -euo pipefail
NAME="${1:?usage: compare.sh <name>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/docs/design/shots"; mkdir -p cmp
montage "ref/$NAME.png" "app/$NAME.png" -tile 2x1 -geometry +8+8 \
  -background '#0B0D11' "cmp/$NAME.png"
printf 'RMSE: '
compare -metric RMSE "ref/$NAME.png" "app/$NAME.png" null: 2>&1 || true
echo
for p in "40,60" "390,120" "40,400" "700,60"; do
  printf '%s  ref=%s  app=%s\n' "$p" \
    "$(identify -format "%[pixel:p{$p}]" "ref/$NAME.png")" \
    "$(identify -format "%[pixel:p{$p}]" "app/$NAME.png")"
done
echo "montage: docs/design/shots/cmp/$NAME.png"
