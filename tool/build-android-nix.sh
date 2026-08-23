#!/usr/bin/env bash
# Nix-native Android build (replaces build-android.sh's Docker/cross flow).
# Run inside `nix develop`: tool/build-android-nix.sh [--release]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:---debug}"

echo "🔧 Generating Rust bridge code..."
cd "$ROOT" && flutter_rust_bridge_codegen generate

echo "🦀 Building Rust library (arm64 + x86_64, API 24)..."
cd "$ROOT/rust"
# 16KB page alignment for Android 15+
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
cargo ndk -t arm64-v8a -t x86_64 --platform 24 \
  -o "$ROOT/android/app/src/main/jniLibs" build --release

echo "📦 Building APK ($MODE)..."
cd "$ROOT"
flutter build apk "$MODE"
echo "✅ $(ls build/app/outputs/flutter-apk/*.apk)"
