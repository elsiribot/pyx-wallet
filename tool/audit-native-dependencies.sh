#!/usr/bin/env bash
# Produce a secret-free, reproducible inventory of the native Android and Rust
# dependency graphs. This is license/inventory evidence, not a vulnerability
# scan: release approval still requires a current advisory database scanner.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPORT_ROOT="$ROOT/build/dependency-audit"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="$REPORT_ROOT/$STAMP.txt"
RUST_JSON="$REPORT_ROOT/$STAMP-rust-metadata.json"
ANDROID_GRAPH="$REPORT_ROOT/$STAMP-android-release-runtime.txt"

for command in cargo gradle jq; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "required command is unavailable: $command" >&2
    exit 1
  fi
done

mkdir -p "$REPORT_ROOT"

cargo metadata \
  --manifest-path "$ROOT/rust/Cargo.toml" \
  --format-version 1 \
  --locked \
  --no-default-features \
  --features android-jni >"$RUST_JSON"

gradle -p "$ROOT/android-native" \
  :app:dependencies \
  --configuration releaseRuntimeClasspath \
  --console=plain >"$ANDROID_GRAPH"

RUST_THIRD_PARTY_COUNT="$(jq '
  (.resolve.nodes | map(.id)) as $resolved
  | [.packages[] as $package
      | select($package.source != null and ($resolved | index($package.id))) ]
  | length' "$RUST_JSON")"
RUST_MISSING_LICENSES="$(jq -r '
  (.resolve.nodes | map(.id)) as $resolved
  | [.packages[] as $package
      | select($package.source != null
          and ($resolved | index($package.id))
          and ($package.license == null or $package.license == ""))
      | "\($package.name) \($package.version) \($package.source)"]
  | sort | .[]' "$RUST_JSON")"

{
  echo "Pyx native dependency inventory"
  echo "generated_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "rust_manifest_sha256=$(sha256sum "$ROOT/rust/Cargo.toml" | awk '{print $1}')"
  echo "rust_lock_sha256=$(sha256sum "$ROOT/rust/Cargo.lock" | awk '{print $1}')"
  echo "android_build_sha256=$(sha256sum "$ROOT/android-native/app/build.gradle.kts" | awk '{print $1}')"
  echo "rust_third_party_packages=$RUST_THIRD_PARTY_COUNT"
  echo "rust_missing_license_metadata=$([[ -n "$RUST_MISSING_LICENSES" ]] && printf yes || printf no)"
  echo "android_graph=$ANDROID_GRAPH"
  echo "rust_metadata=$RUST_JSON"
  echo "vulnerability_scan=SEPARATE_GATE (run tool/audit-rust-vulnerabilities.sh; Gradle scan also required)"
  echo
  echo "Rust packages (name, version, SPDX/license expression, source)"
  jq -r '(.resolve.nodes | map(.id)) as $resolved
    | .packages[] as $package
    | select($package.source != null and ($resolved | index($package.id)))
    | [$package.name, $package.version, ($package.license // "MISSING"), $package.source]
    | @tsv' "$RUST_JSON" | sort
} >"$REPORT"

if [[ -n "$RUST_MISSING_LICENSES" ]]; then
  echo "Rust third-party packages missing license metadata:" >&2
  echo "$RUST_MISSING_LICENSES" >&2
  echo "Dependency inventory failed: $REPORT" >&2
  exit 1
fi

echo "Dependency inventory passed: $REPORT"
echo "Resolved Android graph: $ANDROID_GRAPH"
echo "Vulnerability scanning remains a separate release gate."
