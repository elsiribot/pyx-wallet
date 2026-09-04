#!/usr/bin/env bash
# Bounded offline launch/footprint probe. Never targets cash.pyx.app.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PREVIEW_APK="${1:-$ROOT/android-native/app/build/outputs/apk/debug/app-debug.apk}"
RELEASE_APK="${2:-$ROOT/android-native/app/build/outputs/apk/release/app-release.apk}"
PREVIEW_PACKAGE="cash.pyx.app.nativepreview"
PRODUCTION_PACKAGE="cash.pyx.app"
REPORT_ROOT="$ROOT/build/performance-certification"
REPORT=""; STAGE="guards"; DEVICE=""; SUCCESS=0

[[ -f "$PREVIEW_APK" && -f "$RELEASE_APK" ]] || { echo "usage: $0 [preview.apk release.apk]" >&2; exit 2; }
adb_raw() { timeout 20s adb "$@"; }
adb_device() { adb_raw -s "$DEVICE" "$@"; }

BUILD_TOOLS_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/build-tools"; BUILD_TOOLS_DIR=""
for candidate in "$BUILD_TOOLS_ROOT"/*; do [[ -x "$candidate/aapt" ]] && BUILD_TOOLS_DIR="$candidate"; done
[[ -n "$BUILD_TOOLS_DIR" ]] || { echo "aapt is required" >&2; exit 1; }
AAPT="$BUILD_TOOLS_DIR/aapt"
apk_package() { "$AAPT" dump badging "$1" | sed -n "s/^package: name='\([^']*\)'.*/\1/p"; }
[[ "$(apk_package "$PREVIEW_APK")" == "$PREVIEW_PACKAGE" ]] || { echo "preview APK package mismatch" >&2; exit 1; }
[[ "$(apk_package "$RELEASE_APK")" == "$PRODUCTION_PACKAGE" ]] || { echo "release APK package mismatch" >&2; exit 1; }
jar tf "$PREVIEW_APK" | grep -Eq '^lib/[^/]+/libpyx\.so$' || { echo "preview APK is not native Pyx" >&2; exit 1; }
jar tf "$RELEASE_APK" | grep -Eq '^lib/[^/]+/libpyx\.so$' || { echo "release APK is not native Pyx" >&2; exit 1; }

mapfile -t TRANSPORTS < <(adb_raw devices | awk 'NR > 1 && NF { print $1 " " $2 }')
[[ ${#TRANSPORTS[@]} -eq 1 && "${TRANSPORTS[0]#* }" == device ]] || { echo "exactly one authorized emulator is required" >&2; exit 1; }
DEVICE="${TRANSPORTS[0]%% *}"
[[ "$DEVICE" == emulator-* ]] || { echo "refusing non-emulator transport" >&2; exit 1; }
[[ "$(adb_device shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo "refusing non-emulator target" >&2; exit 1; }
if adb_device shell pm path "$PRODUCTION_PACKAGE" 2>/dev/null | tr -d '\r' | grep -q '^package:'; then
  echo "refusing emulator containing $PRODUCTION_PACKAGE" >&2; exit 1
fi

cleanup() {
  status=$?
  [[ -n "$DEVICE" ]] && adb_device shell am force-stop "$PREVIEW_PACKAGE" >/dev/null 2>&1 || true
  if [[ -n "$REPORT" ]]; then
    if [[ $SUCCESS -eq 1 && $status -eq 0 ]]; then
      printf 'result=PASS\n' >>"$REPORT"
    else
      printf 'result=FAIL\nfailed_stage=%s\n' "$STAGE" >>"$REPORT"
    fi
    echo "Performance certification report: $REPORT" >&2
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP

mkdir -p "$REPORT_ROOT"; REPORT="$REPORT_ROOT/$(date -u +%Y%m%dT%H%M%SZ).txt"
report() { printf '%s=%s\n' "$1" "$2" >>"$REPORT"; }
printf '%s\n' "Pyx native Android bounded offline performance probe" >"$REPORT"
report timestamp_utc "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
report package "$PREVIEW_PACKAGE"; report production_package "$PRODUCTION_PACKAGE (absent and untouched)"
report device "$(adb_device shell getprop ro.product.model | tr -d '\r\n') API $(adb_device shell getprop ro.build.version.sdk | tr -d '\r\n')"
report preview_apk_bytes "$(stat -c %s "$PREVIEW_APK")"; report release_apk_bytes "$(stat -c %s "$RELEASE_APK")"
report preview_apk_sha256 "$(sha256sum "$PREVIEW_APK" | awk '{print $1}')"; report release_apk_sha256 "$(sha256sum "$RELEASE_APK" | awk '{print $1}')"
report scope "Activity cold start and one idle process-memory sample only; no wallet, network, Flutter comparison, balance, QR, or payment claim"

STAGE="preview install"
adb_device install -r "$PREVIEW_APK" >/dev/null
adb_device shell pm clear "$PREVIEW_PACKAGE" >/dev/null

STAGE="five cold starts"; TIMES=()
for sample in 1 2 3 4 5; do
  adb_device shell am force-stop "$PREVIEW_PACKAGE" >/dev/null
  OUTPUT="$(adb_device shell am start -W -n "$PREVIEW_PACKAGE/cash.pyx.app.MainActivity" | tr -d '\r')"
  grep -q '^Status: ok$' <<<"$OUTPUT" || { echo "cold start $sample failed" >&2; exit 1; }
  VALUE="$(awk '/^TotalTime:/{print $2; exit}' <<<"$OUTPUT")"
  [[ "$VALUE" =~ ^[0-9]+$ ]] || { echo "cold start timing missing" >&2; exit 1; }
  TIMES+=("$VALUE"); report "cold_start_${sample}_ms" "$VALUE"
done
mapfile -t SORTED < <(printf '%s\n' "${TIMES[@]}" | sort -n)
SUM=0; for value in "${TIMES[@]}"; do SUM=$((SUM + value)); done
report cold_start_mean_ms "$((SUM / 5))"; report cold_start_median_ms "${SORTED[2]}"

STAGE="post-10s idle memory"
sleep 10
MEMINFO="$(adb_device shell dumpsys meminfo "$PREVIEW_PACKAGE" | tr -d '\r')"
PSS="$(awk '/TOTAL PSS:/{for(i=1;i<=NF;i++) if($i=="PSS:"){print $(i+1); exit}}' <<<"$MEMINFO")"
RSS="$(awk '/TOTAL RSS:/{for(i=1;i<=NF;i++) if($i=="RSS:"){print $(i+1); exit}}' <<<"$MEMINFO")"
[[ "$PSS" =~ ^[0-9]+$ && "$RSS" =~ ^[0-9]+$ ]] || { echo "TOTAL PSS/RSS unavailable" >&2; exit 1; }
report idle_after_seconds 10; report idle_total_pss_kib "$PSS"; report idle_total_rss_kib "$RSS"
report preview_state "data cleared before measurement; uninitialized offline launch"
STAGE="complete"; SUCCESS=1
