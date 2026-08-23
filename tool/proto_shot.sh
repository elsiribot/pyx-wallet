#!/usr/bin/env bash
# tool/proto_shot.sh <screen-id> [json-params] — renders one prototype screen
# at 390x844 logical px (2x scale => 780x1688 png) with the stable module OFF.
# Output: docs/design/shots/ref/<screen-id>.png
set -euo pipefail
SCREEN="${1:-home}"; PARAMS="${2:-}"
[ -z "$PARAMS" ] && PARAMS='{}'
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/docs/design/shots/ref"; mkdir -p "$OUT"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
cp "$ROOT/docs/design/prototype.html" "$TMP/p.html"

# Use local fonts if vendored (sandboxed chromium may not reach Google Fonts)
if [ -d "$ROOT/docs/design/fonts" ]; then
  FONTCSS="$TMP/fonts.css"
  : > "$FONTCSS"
  for w in 400 500 600 700; do
    for fam in "Space Grotesk:SpaceGrotesk" "Inter:Inter"; do
      name="${fam%%:*}"; file="${fam##*:}"
      f="$ROOT/docs/design/fonts/${file}-${w}.ttf"
      [ -f "$f" ] && printf '@font-face{font-family:"%s";font-weight:%s;src:url("file://%s");}\n' "$name" "$w" "$f" >> "$FONTCSS"
    done
  done
  # inject after <head> so it overrides the Google Fonts link
  sed -i "s|</title>|</title><style>$(tr -d '\n' < "$FONTCSS")</style>|" "$TMP/p.html"
fi

cat >> "$TMP/p.html" <<EOF
<style>.env-fab,.hint,.toast{display:none!important}</style>
<script>
  CONFIG.stable=false;            // scope: stable balance module OFF
  ${CONFIG_JS:-}
  stack=[{id:'home',p:{}}];
  if('$SCREEN'!=='home'){ stack.push({id:'$SCREEN', p:$PARAMS}); }
  render();
</script>
EOF

chromium --headless=new --disable-gpu --hide-scrollbars --no-sandbox \
  --window-size=390,844 --force-device-scale-factor=2 \
  --virtual-time-budget=8000 \
  --screenshot="$OUT/$SCREEN.png" "file://$TMP/p.html" 2>/dev/null
echo "$OUT/$SCREEN.png"
