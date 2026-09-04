#!/usr/bin/env bash
# Compatibility entry point; the certifier owns all device and package safeguards.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/tool/certify-native-android-device.sh" "$@"
