#!/usr/bin/env bash
# Deliberate local production-identity upgrade gate. Never clears or uninstalls.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPORT_ROOT="$ROOT/build/upgrade-certification"
PACKAGE="cash.pyx.app"
FLUTTER_APK=""; NATIVE_APK=""; CONFIRM=""; STAGE="argument validation"; REPORT=""

usage() {
  cat >&2 <<'EOF'
usage: certify-flutter-to-native-upgrade.sh \
  --flutter-apk /absolute/path/to/production-flutter.apk \
  --native-apk /absolute/path/to/production-native.apk \
  --confirm-production-upgrade cash.pyx.app

Requires exactly one authorized ADB device and requires cash.pyx.app to be
absent before starting. Fixed-word prompts never collect wallet values.
EOF
}

while (($#)); do
  case "$1" in
    --flutter-apk) [[ $# -ge 2 ]] || { usage; exit 2; }; FLUTTER_APK="$2"; shift 2 ;;
    --native-apk) [[ $# -ge 2 ]] || { usage; exit 2; }; NATIVE_APK="$2"; shift 2 ;;
    --confirm-production-upgrade) [[ $# -ge 2 ]] || { usage; exit 2; }; CONFIRM="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
done

[[ -n "$FLUTTER_APK" && -n "$NATIVE_APK" && "$CONFIRM" == "$PACKAGE" ]] || { usage; exit 2; }
[[ "$FLUTTER_APK" == /* && "$NATIVE_APK" == /* ]] || { echo "Both APK paths must be absolute." >&2; exit 2; }
[[ -f "$FLUTTER_APK" && -f "$NATIVE_APK" ]] || { echo "Both APK paths must name existing regular files." >&2; exit 2; }
[[ "$FLUTTER_APK" != "$NATIVE_APK" ]] || { echo "Flutter and native APK paths must differ." >&2; exit 2; }

BUILD_TOOLS_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/build-tools"
BUILD_TOOLS_DIR=""
for candidate in "$BUILD_TOOLS_ROOT"/*; do
  if [[ -x "$candidate/aapt" && -x "$candidate/apksigner" ]]; then BUILD_TOOLS_DIR="$candidate"; fi
done
[[ -n "$BUILD_TOOLS_DIR" ]] || { echo "Android build-tools with aapt and apksigner are required." >&2; exit 1; }
AAPT="$BUILD_TOOLS_DIR/aapt"; APKSIGNER="$BUILD_TOOLS_DIR/apksigner"

mapfile -t TRANSPORTS < <(adb devices | awk 'NR > 1 && NF { print $1 " " $2 }')
if [[ ${#TRANSPORTS[@]} -ne 1 || "${TRANSPORTS[0]#* }" != "device" ]]; then
  echo "Exactly one authorized ADB device must be connected; found ${#TRANSPORTS[@]} transport(s)." >&2
  exit 1
fi
DEVICE="${TRANSPORTS[0]%% *}"
adb_device() { adb -s "$DEVICE" "$@"; }

apk_package() { "$AAPT" dump badging "$1" | sed -n "s/^package: name='\([^']*\)'.*/\1/p"; }
apk_version_code() { "$AAPT" dump badging "$1" | sed -n "s/^package: .* versionCode='\([^']*\)'.*/\1/p"; }
apk_version_name() { "$AAPT" dump badging "$1" | sed -n "s/^package: .* versionName='\([^']*\)'.*/\1/p"; }
apk_signers() {
  "$APKSIGNER" verify --print-certs "$1" |
    sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' |
    tr '[:lower:]' '[:upper:]' | sort -u | paste -sd, -
}
is_debuggable() { "$AAPT" dump xmltree "$1" AndroidManifest.xml | grep -E 'android:debuggable.*0xffffffff' >/dev/null; }

for apk in "$FLUTTER_APK" "$NATIVE_APK"; do
  "$APKSIGNER" verify --verbose "$apk" >/dev/null || { echo "APK signature verification failed." >&2; exit 1; }
  [[ "$(apk_package "$apk")" == "$PACKAGE" ]] || { echo "APK does not use exact production package $PACKAGE." >&2; exit 1; }
  ! is_debuggable "$apk" || { echo "Refusing a debuggable APK." >&2; exit 1; }
done

FLUTTER_ENTRIES="$(jar tf "$FLUTTER_APK")"; NATIVE_ENTRIES="$(jar tf "$NATIVE_APK")"
grep -Eq '(^|/)flutter_assets/' <<<"$FLUTTER_ENTRIES" || { echo "Flutter APK has no Flutter asset bundle." >&2; exit 1; }
grep -Eq '(^|/)libflutter\.so$' <<<"$FLUTTER_ENTRIES" || { echo "Flutter APK has no Flutter engine library." >&2; exit 1; }
if grep -Eq '^lib/[^/]+/libpyx\.so$' <<<"$FLUTTER_ENTRIES"; then
  echo "Flutter APK unexpectedly contains the native-only libpyx.so." >&2; exit 1
fi
grep -Eq '^lib/[^/]+/libpyx\.so$' <<<"$NATIVE_ENTRIES" || { echo "Native APK has no libpyx.so." >&2; exit 1; }
if grep -Eq 'libflutter\.so|flutter_assets|libconduit\.so' <<<"$NATIVE_ENTRIES"; then
  echo "Native APK contains Flutter or legacy bridge artifacts." >&2; exit 1
fi

FLUTTER_SIGNERS="$(apk_signers "$FLUTTER_APK")"; NATIVE_SIGNERS="$(apk_signers "$NATIVE_APK")"
[[ -n "$FLUTTER_SIGNERS" && "$FLUTTER_SIGNERS" == "$NATIVE_SIGNERS" ]] || {
  echo "Flutter and native signer SHA-256 sets do not match." >&2; exit 1
}
if adb_device shell pm path "$PACKAGE" 2>/dev/null | tr -d '\r' | grep -q '^package:'; then
  echo "$PACKAGE is already installed. Refusing to replace, clear, or uninstall it; use a dedicated clean device." >&2
  exit 1
fi

mkdir -p "$REPORT_ROOT"; REPORT="$REPORT_ROOT/$(date -u +%Y%m%dT%H%M%SZ).txt"
report() { printf '%s=%s\n' "$1" "$2" >>"$REPORT"; }
finish() {
  status=$?
  if [[ -n "$REPORT" ]]; then
    if [[ $status -eq 0 ]]; then report result PASS; else report result FAIL; report failed_stage "$STAGE"; fi
    echo "Upgrade certification report: $REPORT" >&2
  fi
  exit "$status"
}
trap finish EXIT

printf '%s\n' "Pyx production Flutter-to-native upgrade certification" >"$REPORT"
report timestamp_utc "$(date -u +%Y-%m-%dT%H:%M:%SZ)"; report package "$PACKAGE"
report device_manufacturer "$(adb_device shell getprop ro.product.manufacturer | tr -d '\r\n')"
report device_model "$(adb_device shell getprop ro.product.model | tr -d '\r\n')"
report device_api "$(adb_device shell getprop ro.build.version.sdk | tr -d '\r\n')"
report device_abi "$(adb_device shell getprop ro.product.cpu.abi | tr -d '\r\n')"
report flutter_apk_sha256 "$(sha256sum "$FLUTTER_APK" | awk '{print $1}')"
report flutter_version_code "$(apk_version_code "$FLUTTER_APK")"; report flutter_version_name "$(apk_version_name "$FLUTTER_APK")"
report native_apk_sha256 "$(sha256sum "$NATIVE_APK" | awk '{print $1}')"
report native_version_code "$(apk_version_code "$NATIVE_APK")"; report native_version_name "$(apk_version_name "$NATIVE_APK")"
report signer_sha256 "$FLUTTER_SIGNERS"; report initial_production_package absent
report flutter_artifact_class verified_flutter; report native_artifact_class verified_compose_native
report automated_wallet_checkpoint_hook unavailable

STAGE="Flutter installation"; adb_device install "$FLUTTER_APK" >/dev/null; report flutter_install PASS
echo "Flutter installed. Initialize the approved synthetic fixture and externally record redacted" >&2
echo "identity, federations, selection, balances, currency, contacts, and payment history." >&2
read -r -p "Type FLUTTER-CHECKPOINT-RECORDED after completing those checks: " PRE_CONFIRM
[[ "$PRE_CONFIRM" == "FLUTTER-CHECKPOINT-RECORDED" ]] || { echo "Checkpoint not confirmed; app/data left untouched." >&2; exit 1; }
report flutter_manual_checkpoint confirmed

STAGE="native replacement installation"
# Replacement is used only here, preserving the Flutter-created app data.
adb_device install -r "$NATIVE_APK" >/dev/null; report native_replacement_install PASS
STAGE="post-upgrade manual verification"
adb_device shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null
echo "Compare the same redacted assertions and verify wallet/payment/recovery behavior." >&2
read -r -p "Type NATIVE-CHECKPOINT-MATCHED only if every required assertion matches: " POST_CONFIRM
[[ "$POST_CONFIRM" == "NATIVE-CHECKPOINT-MATCHED" ]] || { echo "Checkpoint not confirmed; app/data left untouched." >&2; exit 1; }
report native_manual_checkpoint confirmed; report data_clear_or_uninstall never
STAGE="complete"; echo "Production-signed Flutter-to-native upgrade certification passed." >&2
