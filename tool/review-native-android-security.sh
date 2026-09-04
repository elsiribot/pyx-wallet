#!/usr/bin/env bash
# Reproducible internal source review. This is not independent security approval,
# dynamic analysis, hardware validation, or a current dependency vulnerability scan.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/android-native/app/src/main"
DEBUG="$ROOT/android-native/app/src/debug"
RUST_ANDROID="$ROOT/rust/src/android"
APK=""

if (($# > 2)); then
  echo "usage: $0 [--apk path]" >&2
  exit 2
fi
if (($# == 2)); then
  [[ "$1" == "--apk" ]] || { echo "usage: $0 [--apk path]" >&2; exit 2; }
  APK="$2"
  [[ -f "$APK" ]] || { echo "APK not found: $APK" >&2; exit 2; }
elif (($# == 1)); then
  echo "usage: $0 [--apk path]" >&2
  exit 2
fi

fail() { echo "security review failed: $*" >&2; exit 1; }
require_text() {
  local pattern="$1" path="$2" reason="$3"
  rg -q -- "$pattern" "$path" || fail "$reason"
}
forbid_text() {
  local pattern="$1" path="$2" reason="$3"
  if rg -n -- "$pattern" "$path"; then fail "$reason"; fi
}

MANIFEST="$MAIN/AndroidManifest.xml"
require_text 'android:allowBackup="false"' "$MANIFEST" "backup is not explicitly disabled"
require_text 'android:usesCleartextTraffic="false"' "$MANIFEST" "cleartext traffic is not explicitly disabled"
require_text 'android:dataExtractionRules="@xml/data_extraction_rules"' "$MANIFEST" "data-extraction rules are not attached"
require_text 'android:fullBackupContent="@xml/backup_rules"' "$MANIFEST" "legacy backup rules are not attached"

for domain in root file database sharedpref external; do
  require_text "<exclude domain=\"$domain\" path=\"\.\"" "$MAIN/res/xml/backup_rules.xml" "backup_rules does not exclude $domain"
  require_text "<exclude domain=\"$domain\" path=\"\.\"" "$MAIN/res/xml/data_extraction_rules.xml" "data extraction does not exclude $domain"
done

# MainActivity is the sole production export and accepts only reviewed payment schemes.
EXPORTED_TRUE_COUNT="$(rg -o 'android:exported="true"' "$MANIFEST" | wc -l | tr -d ' ')"
[[ "$EXPORTED_TRUE_COUNT" == "1" ]] || fail "production manifest must have exactly one exported component"
require_text 'android:name="\.MainActivity"' "$MANIFEST" "exported MainActivity is missing"
for scheme in bitcoin lightning lnurl fedimint; do
  require_text "android:scheme=\"$scheme\"" "$MANIFEST" "deep-link scheme $scheme is missing"
done
forbid_text '<(receiver|service|provider)[^>]*android:exported="true"' "$MANIFEST" "production receiver/service/provider is exported"

# Debug-only exported probes must remain in the suffixed preview source set and never merge into release.
require_text 'cash\.pyx\.app\.nativepreview\.PROCESS_DEATH_PROBE' "$DEBUG/AndroidManifest.xml" "debug probe is not preview-package scoped"
forbid_text 'ProcessDeathProbeReceiver|ComponentGalleryActivity' "$MANIFEST" "debug component leaked into production manifest"

# No application source logging/stack serialization. Crash handling may persist only a marker.
forbid_text 'Log\.|Timber\.|println\(|printStackTrace\(|System\.(out|err)|throwable\.message|stackTraceToString' "$MAIN/java" "application source can emit logs, exceptions, or stack traces"
require_text 'crashMarker\.createNewFile\(\)' "$MAIN/java/cash/pyx/app/PyxApplication.kt" "redacted crash marker is missing"

# Saved navigation must remain payload-free; sensitive values stay in ephemeral request objects.
forbid_text 'SavedStateHandle|navArgument\(|NavType\.|route\s*=.*(payload|invoice|ecash|seed|preimage)' "$MAIN/java" "saved navigation can carry wallet payloads"
require_text 'enum class WalletRoute' "$MAIN/java/cash/pyx/app/ui/WalletRoute.kt" "typed payload-free routes are missing"
require_text 'MAX_DEEP_LINK_CHARS' "$MAIN/java/cash/pyx/app/ui/WalletBootstrapViewModel.kt" "exported deep links are not bounded before hashing"

# Recovery-word copying is opt-in behind a dedicated warning. Sensitive clipboard
# clearing is ownership- and value-digest-checked, so it cannot erase a newer clip.
require_text 'WalletModalRoute\.SEED_COPY_WARNING' "$MAIN/java/cash/pyx/app/ui/PyxApp.kt" "seed copy warning gate is missing"
require_text 'QrPayload\.copy\(context, "Pyx recovery words", result\.detail, sensitive = true\)' "$MAIN/java/cash/pyx/app/ui/PyxApp.kt" "confirmed seed copy is not classified sensitive"
require_text 'Sensitive payloads must not enter the Android Sharesheet' "$MAIN/java/cash/pyx/app/ui/QrPayload.kt" "sensitive sharesheet rejection is missing"
require_text 'CLIP_OWNERSHIP_TOKEN' "$MAIN/java/cash/pyx/app/ui/QrPayload.kt" "clipboard ownership token is missing"
require_text 'token != expected\.ownershipToken \|\| sha256\(value\) != expected\.valueSha256' "$MAIN/java/cash/pyx/app/ui/QrPayload.kt" "clipboard clear is not ownership/value-digest guarded"
require_text 'EXTRA_IS_SENSITIVE' "$MAIN/java/cash/pyx/app/ui/QrPayload.kt" "Android sensitive clipboard classification is missing"

# Screenshot protection and lifecycle clearing must remain explicit for secret surfaces.
require_text 'FLAG_SECURE' "$MAIN/java/cash/pyx/app/ui/PyxApp.kt" "secure-window protection is missing"
require_text 'Lifecycle\.Event\.ON_PAUSE' "$MAIN/java/cash/pyx/app/ui/PyxApp.kt" "background secret cleanup is missing"

# Native errors must remain stable/redacted and panics must not format their payload.
require_text 'from_panic\(_:' "$RUST_ANDROID/error.rs" "panic conversion may inspect a panic payload"
forbid_text 'format!\([^\n]*(panic|payload|secret)|panic_payload.*to_string' "$RUST_ANDROID/error.rs" "panic/error conversion can echo sensitive input"
require_text 'catch_unwind' "$RUST_ANDROID/exports.rs" "JNI panic containment is missing"
require_text 'upper 32 bits are a generation' "$RUST_ANDROID/handles.rs" "typed generation-handle invariant is undocumented"

# Irreversible operations must be journaled before dispatch and clear only after native confirmation.
require_text 'operationReconciliation\.begin\(kind, correlationId, selected\.federationId\)' "$MAIN/java/cash/pyx/app/ui/WalletBootstrapViewModel.kt" "pre-dispatch irreversible journal is missing"
require_text 'cleared is NativeResult\.Success && cleared\.value\.cleared && operationReconciliation\.resolved' "$MAIN/java/cash/pyx/app/ui/WalletBootstrapViewModel.kt" "payment safety lock can clear without native confirmation"
require_text 'start_with_correlation' "$RUST_ANDROID/reconciliation.rs" "native durable correlation record is missing"

# JNI/R8 and standalone-native artifact invariants are enforced in source tooling too.
require_text 'libflutter\\\.so\|flutter_assets\|libconduit\\\.so' "$ROOT/tool/build-native-android.sh" "build does not reject Flutter/legacy artifacts"
require_text 'android:usesCleartextTraffic' "$ROOT/tool/build-native-android.sh" "compiled manifest cleartext check is missing"
require_text '^-keep class cash\.pyx\.app\.nativeapi\.NativeBindings \{ \*; \}$' "$ROOT/android-native/app/proguard-rules.pro" "narrow JNI class retention rule is missing"

if [[ -n "$APK" ]]; then
  command -v jar >/dev/null || fail "jar is required for APK review"
  AAPT="$(command -v aapt || true)"
  if [[ -z "$AAPT" ]]; then
    BUILD_TOOLS_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/build-tools"
    for candidate in "$BUILD_TOOLS_ROOT"/*/aapt; do
      [[ -x "$candidate" ]] && AAPT="$candidate"
    done
  fi
  [[ -n "$AAPT" ]] || fail "Android build-tools aapt is required for APK review"
  BADGING="$($AAPT dump badging "$APK")"
  MANIFEST_TREE="$($AAPT dump xmltree "$APK" AndroidManifest.xml)"
  APK_ENTRIES="$(jar tf "$APK")"
  rg -q "^package: name='cash\.pyx\.app'" <<<"$BADGING" || fail "APK is not production cash.pyx.app"
  rg -q 'android:usesCleartextTraffic.*0x0$' <<<"$MANIFEST_TREE" || fail "APK permits or omits cleartext prohibition"
  if rg -q 'android:debuggable.*0xffffffff' <<<"$MANIFEST_TREE"; then fail "production APK is debuggable"; fi
  rg -q '^lib/arm64-v8a/libpyx\.so$' <<<"$APK_ENTRIES" || fail "APK lacks arm64 libpyx.so"
  rg -q '^lib/arm64-v8a/libc\+\+_shared\.so$' <<<"$APK_ENTRIES" || fail "APK lacks arm64 libc++_shared.so"
  if rg -q '^lib/(armeabi-v7a|x86|x86_64)/' <<<"$APK_ENTRIES"; then fail "production APK contains a non-arm64 ABI"; fi
  if rg -q 'libflutter\.so|flutter_assets|libconduit\.so' <<<"$APK_ENTRIES"; then fail "APK contains Flutter/legacy artifacts"; fi
fi

echo "Internal native Android source security review checks passed."
if [[ -z "$APK" ]]; then echo "APK checks not run (pass --apk with a built production artifact)."; fi
echo "This result is not independent review, dynamic analysis, hardware validation, or live-payment evidence."
