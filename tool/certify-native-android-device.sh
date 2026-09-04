#!/usr/bin/env bash
# Certify the native debug preview on one explicitly connected Android device.
# This intentionally never installs, uninstalls, upgrades, or clears cash.pyx.app.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PREVIEW_PACKAGE="cash.pyx.app.nativepreview"
PRODUCTION_PACKAGE="cash.pyx.app"
REPORT_ROOT="$ROOT/build/device-certification"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="$REPORT_ROOT/$STAMP.txt"
EVIDENCE_DIR="$REPORT_ROOT/$STAMP-reports"
TEST_RUN_MARKER="$REPORT_ROOT/$STAMP-test-run-started"
RESULT="FAILED"
FAILURE="certification did not complete"
SERIAL=""

usage() {
  echo "usage: $0" >&2
  echo "Runs tests only against the debug preview package; production upgrades are never performed." >&2
}

if (($# != 0)); then
  usage
  exit 2
fi

mkdir -p "$REPORT_ROOT"

report_value() {
  local key="$1"
  local value="$2"
  value="${value//$'\r'/}"
  value="${value//$'\n'/ }"
  printf '%s=%s\n' "$key" "$value" >>"$REPORT"
}

finish_report() {
  local exit_code=$?
  report_value "result" "$RESULT"
  if [[ "$RESULT" != "PASS" ]]; then
    report_value "failure" "$FAILURE"
  fi
  report_value "finished_utc" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Device certification report: $REPORT" >&2
  exit "$exit_code"
}
trap finish_report EXIT

printf '%s\n' "Pyx native Android physical-device certification" >"$REPORT"
report_value "started_utc" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
report_value "tested_package" "$PREVIEW_PACKAGE"
report_value "production_package" "$PRODUCTION_PACKAGE (untouched)"

for command in adb gradle cargo jar; do
  if ! command -v "$command" >/dev/null 2>&1; then
    FAILURE="required command is unavailable: $command"
    echo "$FAILURE" >&2
    exit 1
  fi
done

# Requiring exactly one transport, rather than silently selecting among several,
# prevents a test run from reaching an unintended phone. Offline and unauthorized
# transports count and cause a safe failure.
if ! ADB_DEVICES_OUTPUT="$(adb devices)"; then
  FAILURE="adb server is unavailable"
  echo "$FAILURE" >&2
  exit 1
fi
mapfile -t TRANSPORTS < <(awk 'NR > 1 && NF >= 2 { print $1 "\t" $2 }' <<<"$ADB_DEVICES_OUTPUT")
if ((${#TRANSPORTS[@]} != 1)); then
  FAILURE="expected exactly one adb transport; found ${#TRANSPORTS[@]}"
  echo "$FAILURE" >&2
  exit 1
fi
IFS=$'\t' read -r SERIAL DEVICE_STATE <<<"${TRANSPORTS[0]}"
if [[ "$DEVICE_STATE" != "device" ]]; then
  FAILURE="the only adb transport is not authorized and online (state: $DEVICE_STATE)"
  echo "$FAILURE" >&2
  exit 1
fi

adb_device() {
  adb -s "$SERIAL" "$@"
}

MANUFACTURER="$(adb_device shell getprop ro.product.manufacturer | tr -d '\r')"
MODEL="$(adb_device shell getprop ro.product.model | tr -d '\r')"
API="$(adb_device shell getprop ro.build.version.sdk | tr -d '\r')"
ABI_LIST="$(adb_device shell getprop ro.product.cpu.abilist | tr -d '\r')"
PRIMARY_DEVICE_ABI="$(adb_device shell getprop ro.product.cpu.abi | tr -d '\r')"
if [[ ! "$API" =~ ^[0-9]+$ ]] || ((API < 24)); then
  FAILURE="device API is invalid or below the supported minimum: $API"
  echo "$FAILURE" >&2
  exit 1
fi

case "$PRIMARY_DEVICE_ABI" in
  arm64-v8a) TEST_ABI="arm64-v8a"; INCLUDE_X86_64=0 ;;
  x86_64) TEST_ABI="x86_64"; INCLUDE_X86_64=1 ;;
  *)
    FAILURE="device primary ABI is unsupported: ${PRIMARY_DEVICE_ABI:-empty} (ABI list: $ABI_LIST)"
    echo "$FAILURE" >&2
    exit 1
    ;;
esac
if [[ ",$ABI_LIST," != *",$TEST_ABI,"* ]]; then
  FAILURE="device primary ABI $TEST_ABI is absent from ABI list: $ABI_LIST"
  echo "$FAILURE" >&2
  exit 1
fi

report_value "device" "$MANUFACTURER $MODEL"
report_value "api" "$API"
report_value "device_abis" "$ABI_LIST"
report_value "device_primary_abi" "$PRIMARY_DEVICE_ABI"
report_value "tested_abi" "$TEST_ABI"
report_value "adb_transport_count" "1 authorized"

# Merely record whether production is present. All build, install, and test tasks
# below use the applicationId-suffixed preview package.
PRODUCTION_PATH_BEFORE="$(adb_device shell pm path "$PRODUCTION_PACKAGE" 2>/dev/null | tr -d '\r' || true)"
if [[ -n "$PRODUCTION_PATH_BEFORE" ]]; then
  report_value "production_installation" "present and untouched"
else
  report_value "production_installation" "not installed"
fi

FAILURE="native debug build failed"
if ((INCLUDE_X86_64)); then
  PYX_ABI_X86_64=1 "$ROOT/tool/build-native-android.sh" --debug
else
  "$ROOT/tool/build-native-android.sh" --debug
fi

FAILURE="connected instrumentation suite failed"
touch "$TEST_RUN_MARKER"
if ((INCLUDE_X86_64)); then
  PYX_ABI_X86_64=1 ANDROID_SERIAL="$SERIAL" gradle -p "$ROOT/android-native" \
    :app:connectedDebugAndroidTest --console=plain
else
  ANDROID_SERIAL="$SERIAL" gradle -p "$ROOT/android-native" \
    :app:connectedDebugAndroidTest --console=plain
fi

# Gradle's console success is not sufficient evidence: preserve and parse the
# XML produced by this exact invocation. This prevents a stale or filtered test
# run from being reported as the complete connected suite.
RESULTS_ROOT="$ROOT/android-native/app/build/outputs/androidTest-results/connected/debug"
mapfile -d '' -t TEST_XMLS < <(find "$RESULTS_ROOT" -type f -name 'TEST-*.xml' -newer "$TEST_RUN_MARKER" -print0 2>/dev/null | sort -z)
FAILURE="connected test task produced no fresh JUnit XML"
if ((${#TEST_XMLS[@]} == 0)); then
  echo "$FAILURE" >&2
  exit 1
fi
mkdir -p "$EVIDENCE_DIR"
TOTAL_TESTS=0
TOTAL_FAILURES=0
TOTAL_ERRORS=0
TOTAL_SKIPPED=0
INVENTORY="$EVIDENCE_DIR/test-inventory.tsv"
: >"$INVENTORY"
for index in "${!TEST_XMLS[@]}"; do
  xml="${TEST_XMLS[$index]}"
  suite_line="$(grep -m1 '<testsuite ' "$xml" || true)"
  tests="$(sed -n 's/.* tests="\([0-9][0-9]*\)".*/\1/p' <<<"$suite_line")"
  failures="$(sed -n 's/.* failures="\([0-9][0-9]*\)".*/\1/p' <<<"$suite_line")"
  errors="$(sed -n 's/.* errors="\([0-9][0-9]*\)".*/\1/p' <<<"$suite_line")"
  skipped="$(sed -n 's/.* skipped="\([0-9][0-9]*\)".*/\1/p' <<<"$suite_line")"
  FAILURE="connected JUnit XML has missing or malformed counters: $xml"
  if [[ -z "$tests" || -z "$failures" || -z "$errors" || -z "$skipped" ]]; then
    echo "$FAILURE" >&2
    exit 1
  fi
  TOTAL_TESTS=$((TOTAL_TESTS + tests))
  TOTAL_FAILURES=$((TOTAL_FAILURES + failures))
  TOTAL_ERRORS=$((TOTAL_ERRORS + errors))
  TOTAL_SKIPPED=$((TOTAL_SKIPPED + skipped))
  copied="$EVIDENCE_DIR/suite-$index.xml"
  cp "$xml" "$copied"
  sed -n 's/.*<testcase name="\([^"]*\)" classname="\([^"]*\)".*/\2\t\1/p' "$xml" >>"$INVENTORY"
done
FAILURE="connected suite was incomplete: tests=$TOTAL_TESTS failures=$TOTAL_FAILURES errors=$TOTAL_ERRORS skipped=$TOTAL_SKIPPED"
if ((TOTAL_TESTS == 0 || TOTAL_FAILURES != 0 || TOTAL_ERRORS != 0 || TOTAL_SKIPPED != 0)); then
  echo "$FAILURE" >&2
  exit 1
fi
INVENTORY_COUNT="$(wc -l <"$INVENTORY" | tr -d ' ')"
if [[ "$INVENTORY_COUNT" != "$TOTAL_TESTS" ]]; then
  FAILURE="connected test inventory count $INVENTORY_COUNT does not match JUnit count $TOTAL_TESTS"
  echo "$FAILURE" >&2
  exit 1
fi
report_value "instrumentation_tests" "$TOTAL_TESTS"
report_value "instrumentation_failures" "$TOTAL_FAILURES"
report_value "instrumentation_errors" "$TOTAL_ERRORS"
report_value "instrumentation_skipped" "$TOTAL_SKIPPED"
report_value "instrumentation_report_dir" "$EVIDENCE_DIR"
report_value "instrumentation_inventory_sha256" "$(sha256sum "$INVENTORY" | awk '{print $1}')"
report_value "instrumentation_xml_sha256" "$(sha256sum "$EVIDENCE_DIR"/suite-*.xml | sort | sha256sum | awk '{print $1}')"
rm -f "$TEST_RUN_MARKER"

# Gradle removes both APKs after connectedDebugAndroidTest. Reinstall only the
# already-tested preview artifact so the certifier can inspect Android's actual
# installed ABI selection. This never targets or clears the production package.
DEBUG_APK="$ROOT/android-native/app/build/outputs/apk/debug/app-debug.apk"
FAILURE="tested preview APK is missing after the connected test task"
if [[ ! -f "$DEBUG_APK" ]]; then
  echo "$FAILURE" >&2
  exit 1
fi
FAILURE="could not reinstall the tested preview APK for post-test inspection"
adb_device install -r "$DEBUG_APK" >/dev/null

FAILURE="preview package was not installed for post-test inspection"
PACKAGE_PATH_OUTPUT="$(adb_device shell pm path "$PREVIEW_PACKAGE" | tr -d '\r')"
BASE_APK="${PACKAGE_PATH_OUTPUT#package:}"
if [[ "$PACKAGE_PATH_OUTPUT" != package:/* || "$BASE_APK" == *$'\n'* ]]; then
  echo "$FAILURE" >&2
  exit 1
fi
report_value "installed_package_path" "$BASE_APK"

PACKAGE_DUMP="$(adb_device shell dumpsys package "$PREVIEW_PACKAGE" | tr -d '\r')"
PRIMARY_ABI="$(awk -F= '/^[[:space:]]*primaryCpuAbi=/{gsub(/[[:space:]]/, "", $2); print $2; exit}' <<<"$PACKAGE_DUMP")"
if [[ -n "$PRIMARY_ABI" && "$PRIMARY_ABI" != "null" && "$PRIMARY_ABI" != "$TEST_ABI" ]]; then
  FAILURE="installed package primary ABI is $PRIMARY_ABI, expected $TEST_ABI"
  echo "$FAILURE" >&2
  exit 1
fi

INSTALLED_APK="$REPORT_ROOT/$STAMP-installed-base.apk"
FAILURE="could not copy the installed preview APK for ABI verification"
adb_device pull "$BASE_APK" "$INSTALLED_APK" >/dev/null
for native_library in libpyx.so libc++_shared.so; do
  if ! jar tf "$INSTALLED_APK" | grep -Fx "lib/$TEST_ABI/$native_library" >/dev/null; then
    FAILURE="installed preview APK does not contain lib/$TEST_ABI/$native_library"
    echo "$FAILURE" >&2
    exit 1
  fi
done
rm -f "$INSTALLED_APK"
report_value "installed_primary_abi" "${PRIMARY_ABI:-verified from installed APK: $TEST_ABI}"
report_value "packaged_jni" "lib/$TEST_ABI/libpyx.so with NDK libc++_shared.so loaded by connected NativeJniSmokeTest"

PRODUCTION_PATH_AFTER="$(adb_device shell pm path "$PRODUCTION_PACKAGE" 2>/dev/null | tr -d '\r' || true)"
if [[ "$PRODUCTION_PATH_AFTER" != "$PRODUCTION_PATH_BEFORE" ]]; then
  FAILURE="production package path changed during preview certification"
  echo "$FAILURE" >&2
  exit 1
fi

RESULT="PASS"
FAILURE=""
echo "Native Android device certification passed on $MANUFACTURER $MODEL (API $API, $TEST_ABI)."
