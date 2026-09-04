#!/usr/bin/env bash
# Capture integrity-hashed synthetic Compose screenshots from the debug preview only.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/android-native/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="cash.pyx.app.nativepreview"
PRODUCTION="cash.pyx.app"
COMPONENT="$PACKAGE/cash.pyx.app.debug.ScreenshotFixtureActivity"
OUT_ROOT="$ROOT/build/screenshot-certification"
DEVICE=""; OUT=""; SUCCESS=0; STAGE="guards"

adb_raw() { timeout 30s adb "$@"; }
adb_device() { adb_raw -s "$DEVICE" "$@"; }
mapfile -t TRANSPORTS < <(adb_raw devices | awk 'NR > 1 && NF { print $1 " " $2 }')
[[ ${#TRANSPORTS[@]} -eq 1 && "${TRANSPORTS[0]#* }" == device ]] || { echo "exactly one authorized emulator is required" >&2; exit 1; }
DEVICE="${TRANSPORTS[0]%% *}"
[[ "$DEVICE" == emulator-* && "$(adb_device shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo "refusing non-emulator target" >&2; exit 1; }
if adb_device shell pm path "$PRODUCTION" 2>/dev/null | tr -d '\r' | grep -q '^package:'; then
  echo "refusing emulator containing $PRODUCTION" >&2; exit 1
fi

ORIGINAL_SIZE="$(adb_device shell wm size | tr -d '\r')"
ORIGINAL_DENSITY="$(adb_device shell wm density | tr -d '\r')"
ORIGINAL_FONT="$(adb_device shell settings get system font_scale | tr -d '\r')"
ORIGINAL_WINDOW_ANIMATION="$(adb_device shell settings get global window_animation_scale | tr -d '\r')"
ORIGINAL_TRANSITION_ANIMATION="$(adb_device shell settings get global transition_animation_scale | tr -d '\r')"
ORIGINAL_ANIMATOR_DURATION="$(adb_device shell settings get global animator_duration_scale | tr -d '\r')"
restore_setting() {
  local namespace="$1" key="$2" value="$3"
  if [[ "$value" == null || -z "$value" ]]; then adb_device shell settings delete "$namespace" "$key" >/dev/null
  else adb_device shell settings put "$namespace" "$key" "$value" >/dev/null; fi
}
restore_device() {
  if grep -q 'Override size:' <<<"$ORIGINAL_SIZE"; then adb_device shell wm size "$(awk -F': ' '/Override size:/{print $2}' <<<"$ORIGINAL_SIZE")" >/dev/null; else adb_device shell wm size reset >/dev/null; fi
  if grep -q 'Override density:' <<<"$ORIGINAL_DENSITY"; then adb_device shell wm density "$(awk -F': ' '/Override density:/{print $2}' <<<"$ORIGINAL_DENSITY")" >/dev/null; else adb_device shell wm density reset >/dev/null; fi
  if [[ "$ORIGINAL_FONT" == null || -z "$ORIGINAL_FONT" ]]; then
    adb_device shell settings delete system font_scale >/dev/null
    # Configuration propagation may materialize the default after the first
    # delete. Wait for Android to settle, then restore the original absence.
    adb_device shell cmd activity wait-for-broadcast-idle >/dev/null 2>&1 || true
    adb_device shell settings delete system font_scale >/dev/null
  else adb_device shell settings put system font_scale "$ORIGINAL_FONT" >/dev/null; fi
  restore_setting global window_animation_scale "$ORIGINAL_WINDOW_ANIMATION"
  restore_setting global transition_animation_scale "$ORIGINAL_TRANSITION_ANIMATION"
  restore_setting global animator_duration_scale "$ORIGINAL_ANIMATOR_DURATION"
}
verify_restore() {
  [[ "$(adb_device shell wm size | tr -d '\r')" == "$ORIGINAL_SIZE" ]] &&
    [[ "$(adb_device shell wm density | tr -d '\r')" == "$ORIGINAL_DENSITY" ]] &&
    [[ "$(adb_device shell settings get system font_scale | tr -d '\r')" == "$ORIGINAL_FONT" ]] &&
    [[ "$(adb_device shell settings get global window_animation_scale | tr -d '\r')" == "$ORIGINAL_WINDOW_ANIMATION" ]] &&
    [[ "$(adb_device shell settings get global transition_animation_scale | tr -d '\r')" == "$ORIGINAL_TRANSITION_ANIMATION" ]] &&
    [[ "$(adb_device shell settings get global animator_duration_scale | tr -d '\r')" == "$ORIGINAL_ANIMATOR_DURATION" ]]
}
cleanup() {
  status=$?
  adb_device shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
  restore_device || true
  if ! verify_restore; then status=1; SUCCESS=0; STAGE="restore emulator settings"; fi
  if [[ -n "$OUT" ]]; then
    if [[ $SUCCESS -eq 1 && $status -eq 0 ]]; then printf 'result=PASS\n' >>"$OUT/manifest.txt"
    else printf 'result=FAIL\nfailed_stage=%s\n' "$STAGE" >>"$OUT/manifest.txt"; fi
  fi
  [[ -n "$OUT" ]] && echo "Screenshot certification: $OUT" >&2
  exit "$status"
}
trap cleanup EXIT; trap 'exit 130' INT TERM HUP

STAGE="build preview"
PYX_ABI_X86_64=1 "$ROOT/tool/build-native-android.sh" --debug
[[ -f "$APK" ]] || { echo "debug preview APK missing" >&2; exit 1; }
STAGE="install preview"
adb_device install -r "$APK" >/dev/null

OUT="$OUT_ROOT/$(date -u +%Y%m%dT%H%M%SZ)"; mkdir -p "$OUT"
printf 'Pyx synthetic Compose screenshot certification\npackage=%s\nproduction=%s (absent and untouched)\ndevice=%s\noriginal_size=%s\noriginal_density=%s\noriginal_font_scale=%s\noriginal_animation_scales=%s,%s,%s\n' \
  "$PACKAGE" "$PRODUCTION" "$(adb_device shell getprop ro.product.model | tr -d '\r') API $(adb_device shell getprop ro.build.version.sdk | tr -d '\r')" \
  "$ORIGINAL_SIZE" "$ORIGINAL_DENSITY" "$ORIGINAL_FONT" "$ORIGINAL_WINDOW_ANIMATION" "$ORIGINAL_TRANSITION_ANIMATION" "$ORIGINAL_ANIMATOR_DURATION" >"$OUT/manifest.txt"
printf 'artifact\tfixture\tphysical_size\tdensity\tfont_scale\tanimation_scales\tsha256\tbytes\trequired_text\n' >"$OUT/hashes.tsv"

capture() {
  local artifact="$1" fixture="$2" marker="$3" hierarchy size density font animations
  local file="$OUT/$artifact.png"
  STAGE="capture $artifact"
  adb_device shell am force-stop "$PACKAGE" >/dev/null
  adb_device shell am start -W -n "$COMPONENT" --es fixture "$fixture" >/dev/null
  hierarchy="$(adb_device exec-out uiautomator dump /dev/tty | tr -d '\r')"
  grep -Fq "$marker" <<<"$hierarchy" || { echo "missing structural marker for $artifact: $marker" >&2; exit 1; }
  adb_device exec-out screencap -p >"$file"
  [[ -s "$file" ]] || { echo "empty screenshot for $artifact" >&2; exit 1; }
  size="$(adb_device shell wm size | awk -F': ' '/Override size:/{print $2}' | tr -d '\r')"
  density="$(adb_device shell wm density | awk -F': ' '/Override density:/{print $2}' | tr -d '\r')"
  font="$(adb_device shell settings get system font_scale | tr -d '\r')"
  animations="$(adb_device shell settings get global window_animation_scale | tr -d '\r'),$(adb_device shell settings get global transition_animation_scale | tr -d '\r'),$(adb_device shell settings get global animator_duration_scale | tr -d '\r')"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$artifact" "$fixture" "$size" "$density" "$font" "$animations" \
    "$(sha256sum "$file" | awk '{print $1}')" "$(stat -c %s "$file")" "$marker" >>"$OUT/hashes.tsv"
}

STAGE="390 by 844 dp reference viewport"
adb_device shell wm size 1080x2337 >/dev/null; adb_device shell wm density 443 >/dev/null
adb_device shell settings put system font_scale 1.0 >/dev/null
capture onboarding_f100 onboarding "Welcome to Pyx"
capture home_status_activity_f100 home_status_activity "Example federation"
capture send_receive_confirmation_f100 send_receive_confirmation "No payment will be submitted from this fixture"
capture settings_access_seed_safe_f100 settings_access_seed_safe "No seed words are present in this fixture"
capture error_offline_f100 error_offline "Could not reach federation guardians"

STAGE="390 by 844 dp at 130 percent font"
adb_device shell settings put system font_scale 1.3 >/dev/null
capture onboarding_f130 onboarding "Welcome to Pyx"
capture home_status_activity_f130 home_status_activity "Example federation"
capture send_receive_confirmation_f130 send_receive_confirmation "No payment will be submitted from this fixture"
capture settings_access_seed_safe_f130 settings_access_seed_safe "No seed words are present in this fixture"
capture error_offline_f130 error_offline "Could not reach federation guardians"

STAGE="280 by 600 dp narrow viewport at 200 percent font"
adb_device shell wm size 700x1500 >/dev/null; adb_device shell wm density 400 >/dev/null
adb_device shell settings put system font_scale 2.0 >/dev/null
capture onboarding_narrow_f200 large_font_narrow "Restore wallet"

STAGE="display scaling"
adb_device shell wm size 945x2025 >/dev/null; adb_device shell wm density 540 >/dev/null
adb_device shell settings put system font_scale 1.3 >/dev/null
capture onboarding_scaled_f130 onboarding "Welcome to Pyx"

STAGE="reduced motion"
adb_device shell wm size 1080x2337 >/dev/null; adb_device shell wm density 443 >/dev/null
adb_device shell settings put system font_scale 1.0 >/dev/null
adb_device shell settings put global window_animation_scale 0 >/dev/null
adb_device shell settings put global transition_animation_scale 0 >/dev/null
adb_device shell settings put global animator_duration_scale 0 >/dev/null
capture home_reduced_motion home_status_activity "Example federation"

STAGE="complete"; SUCCESS=1
