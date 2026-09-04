#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE="cash.pyx.app.r8cert"

mapfile -t DEVICES < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if [[ ${#DEVICES[@]} -ne 1 ]]; then
  echo "Exactly one ready Android device is required; found ${#DEVICES[@]}." >&2
  exit 1
fi
DEVICE="${DEVICES[0]}"
ABI="$(adb -s "$DEVICE" shell getprop ro.product.cpu.abi | tr -d '\r')"

if [[ "$(adb -s "$DEVICE" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "Refusing to run release-equivalent certification on a physical device." >&2
  echo "Use a disposable headless emulator; this tool never targets production data." >&2
  exit 1
fi

if adb -s "$DEVICE" shell pm path cash.pyx.app 2>/dev/null | grep -q '^package:'; then
  echo "Refusing to run while production cash.pyx.app is installed." >&2
  exit 1
fi

# This tooling-only package contains no wallet. Removing a stale copy guarantees
# every run starts from an empty Android sandbox.
adb -s "$DEVICE" uninstall "$PACKAGE" >/dev/null 2>&1 || true
cleanup() {
  adb -s "$DEVICE" uninstall "$PACKAGE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

case "$ABI" in
  x86_64)
    if [[ ! -f "$ROOT/android-native/app/src/main/jniLibs/x86_64/libpyx.so" ]]; then
      echo "Missing x86_64 libpyx.so; first run PYX_ABI_X86_64=1 tool/build-native-android.sh --debug." >&2
      exit 1
    fi
    PYX_ABI_X86_64=1 gradle -p "$ROOT/android-native" \
      -PpyxTestBuildType=r8cert \
      -Pandroid.testInstrumentationRunnerArguments.class=cash.pyx.app.NativeJniSmokeTest \
      :app:connectedR8certAndroidTest --console=plain
    ;;
  arm64-v8a)
    if [[ ! -f "$ROOT/android-native/app/src/main/jniLibs/arm64-v8a/libpyx.so" ]]; then
      echo "Missing arm64-v8a libpyx.so; first run tool/build-native-android.sh --debug." >&2
      exit 1
    fi
    gradle -p "$ROOT/android-native" \
      -PpyxTestBuildType=r8cert \
      -Pandroid.testInstrumentationRunnerArguments.class=cash.pyx.app.NativeJniSmokeTest \
      :app:connectedR8certAndroidTest --console=plain
    ;;
  *)
    echo "Unsupported emulator primary ABI: $ABI" >&2
    exit 1
    ;;
esac

# connectedAndroidTest normally uninstalls both APKs itself; cleanup is
# idempotent for runner or device failures.
cleanup
trap - EXIT

echo "R8-minified JNI runtime certification passed on emulator $DEVICE."
echo "Covered: libpyx load, nativeVersion, local JNI, async callback, RocksDB open/shutdown."
