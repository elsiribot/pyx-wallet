#!/usr/bin/env bash
# Offline emulator-only proof that non-secret production persistence survives a true force-stop.
# This does not exercise or certify live wallet/payment reconciliation.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PREVIEW_PACKAGE="cash.pyx.app.nativepreview"
PRODUCTION_PACKAGE="cash.pyx.app"
RECEIVER="cash.pyx.app.debug.ProcessDeathProbeReceiver"
ACTION="$PREVIEW_PACKAGE.PROCESS_DEATH_PROBE"
REPORT_ROOT="$ROOT/build/process-death-certification"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="$REPORT_ROOT/$STAMP.txt"
RESULT="FAIL"
FAILURE="probe did not complete"
SERIAL=""
CLEAN_PREVIEW=0

if (($# != 0)); then
  echo "usage: $0" >&2
  exit 2
fi

mkdir -p "$REPORT_ROOT"
printf '%s\n' "Pyx native Android offline persisted-state process-death probe" >"$REPORT"

report_value() {
  local key="$1" value="$2"
  value="${value//$'\r'/}"
  value="${value//$'\n'/ }"
  printf '%s=%s\n' "$key" "$value" >>"$REPORT"
}

adb_device() { adb -s "$SERIAL" "$@"; }

finish() {
  local exit_code=$?
  if [[ -n "$SERIAL" && "$CLEAN_PREVIEW" == "1" ]]; then
    # The target is a compile-time preview ID. Never substitute a caller-provided package.
    adb_device shell pm clear "$PREVIEW_PACKAGE" >/dev/null 2>&1 || true
  fi
  report_value result "$RESULT"
  [[ "$RESULT" == "PASS" ]] || report_value failure "$FAILURE"
  report_value finished_utc "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Process-death certification report: $REPORT" >&2
  exit "$exit_code"
}
trap finish EXIT

report_value started_utc "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
report_value tested_package "$PREVIEW_PACKAGE"
report_value scope "offline persistence only; no live recovery or payment claim"
report_value checkpoint_data "synthetic fingerprint and non-secret operation metadata; values redacted"

for command in adb gradle cargo jar; do
  if ! command -v "$command" >/dev/null 2>&1; then
    FAILURE="required command unavailable: $command"
    exit 1
  fi
done

BUILD_TOOLS_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/build-tools"
BUILD_TOOLS_DIR=""
for candidate in "$BUILD_TOOLS_ROOT"/*; do
  if [[ -x "$candidate/aapt" ]]; then BUILD_TOOLS_DIR="$candidate"; fi
done
if [[ -z "$BUILD_TOOLS_DIR" ]]; then
  FAILURE="Android build-tools with aapt are unavailable"
  exit 1
fi
AAPT="$BUILD_TOOLS_DIR/aapt"

ADB_DEVICES_OUTPUT="$(adb devices)"
mapfile -t TRANSPORTS < <(awk 'NR > 1 && NF >= 2 { print $1 "\t" $2 }' <<<"$ADB_DEVICES_OUTPUT")
if ((${#TRANSPORTS[@]} != 1)); then
  FAILURE="expected exactly one adb transport; found ${#TRANSPORTS[@]}"
  exit 1
fi
IFS=$'\t' read -r SERIAL DEVICE_STATE <<<"${TRANSPORTS[0]}"
if [[ "$DEVICE_STATE" != "device" ]]; then
  FAILURE="the adb transport is not authorized and online"
  exit 1
fi
if [[ "$(adb_device shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  FAILURE="refusing physical device; this probe is emulator-only"
  exit 1
fi

API="$(adb_device shell getprop ro.build.version.sdk | tr -d '\r')"
ABI="$(adb_device shell getprop ro.product.cpu.abi | tr -d '\r')"
report_value device "Android emulator API $API"
report_value abi "$ABI"

PRODUCTION_BEFORE="$(adb_device shell pm path "$PRODUCTION_PACKAGE" 2>/dev/null | tr -d '\r' || true)"
report_value production_package "$PRODUCTION_PACKAGE (untouched)"

FAILURE="native debug preview build failed"
case "$ABI" in
  x86_64) PYX_ABI_X86_64=1 "$ROOT/tool/build-native-android.sh" --debug ;;
  arm64-v8a) "$ROOT/tool/build-native-android.sh" --debug ;;
  *) FAILURE="unsupported emulator ABI: $ABI"; exit 1 ;;
esac

APK="$ROOT/android-native/app/build/outputs/apk/debug/app-debug.apk"
FAILURE="refusing APK without the exact debug-preview application ID"
"$AAPT" dump badging "$APK" | grep -F "package: name='$PREVIEW_PACKAGE'" >/dev/null || exit 1
FAILURE="debug preview installation failed"
adb_device install -r "$APK" >/dev/null
FAILURE="preview package identity is unavailable after install"
adb_device shell pm path "$PREVIEW_PACKAGE" >/dev/null
CLEAN_PREVIEW=1

# Start from a deterministic preview-only sandbox. The trap clears it again on every exit path.
FAILURE="could not clear preview data before probe"
adb_device shell pm clear "$PREVIEW_PACKAGE" >/dev/null

FAILURE="production package path changed before checkpoint"
[[ "$(adb_device shell pm path "$PRODUCTION_PACKAGE" 2>/dev/null | tr -d '\r' || true)" == "$PRODUCTION_BEFORE" ]] || exit 1

FAILURE="production persistence classes did not commit the synthetic checkpoint"
WRITE_OUTPUT="$(adb_device shell am broadcast -a "$ACTION" -n "$PREVIEW_PACKAGE/$RECEIVER" --es phase write)"
grep -F 'data="WRITE_PASS"' <<<"$WRITE_OUTPUT" >/dev/null || exit 1
OLD_PID="$(adb_device shell pidof "$PREVIEW_PACKAGE" | tr -d '\r')"
if [[ ! "$OLD_PID" =~ ^[0-9]+$ ]]; then
  FAILURE="could not identify checkpoint-writer process"
  exit 1
fi

FAILURE="force-stop did not terminate preview process"
adb_device shell am force-stop "$PREVIEW_PACKAGE"
[[ -z "$(adb_device shell pidof "$PREVIEW_PACKAGE" | tr -d '\r')" ]] || exit 1

FAILURE="preview did not relaunch after force-stop"
adb_device shell am start -W -n "$PREVIEW_PACKAGE/cash.pyx.app.MainActivity" >/dev/null
NEW_PID="$(adb_device shell pidof "$PREVIEW_PACKAGE" | tr -d '\r')"
if [[ ! "$NEW_PID" =~ ^[0-9]+$ || "$NEW_PID" == "$OLD_PID" ]]; then
  exit 1
fi

FAILURE="fresh process did not reconstruct both persisted checkpoints"
VERIFY_OUTPUT="$(adb_device shell am broadcast -a "$ACTION" -n "$PREVIEW_PACKAGE/$RECEIVER" --es phase verify)"
grep -F 'data="VERIFY_PASS"' <<<"$VERIFY_OUTPUT" >/dev/null || exit 1

FAILURE="production package path changed during preview-only probe"
[[ "$(adb_device shell pm path "$PRODUCTION_PACKAGE" 2>/dev/null | tr -d '\r' || true)" == "$PRODUCTION_BEFORE" ]] || exit 1

report_value process_transition "writer PID terminated; distinct relaunched PID verified (IDs redacted)"
report_value deep_link_checkpoint "PASS: SHA-256 fingerprint and timestamp survived"
report_value operation_checkpoint "PASS: non-secret journal metadata survived"
RESULT="PASS"
FAILURE=""
echo "Offline preview process-death persistence probe passed."
