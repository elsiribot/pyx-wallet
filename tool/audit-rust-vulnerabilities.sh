#!/usr/bin/env bash
# Fail-closed RustSec cutover gate for the exact checked-in native Rust lockfile.
# This script intentionally does not ignore, allow-list, or suppress advisories.
set -u -o pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lockfile="$repo_root/rust/Cargo.lock"
report_dir="$repo_root/build/vulnerability-audit"
database_dir="$report_dir/advisory-db"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
report="$report_dir/${timestamp}-rustsec.txt"

mkdir -p "$report_dir"

if ! command -v cargo-audit >/dev/null 2>&1; then
  echo "ERROR: cargo-audit is required." >&2
  echo "Run: nix shell nixpkgs#cargo-audit -c tool/audit-rust-vulnerabilities.sh" >&2
  exit 2
fi

lock_sha256="$(sha256sum "$lockfile" | awk '{print $1}')"
audit_version="$(cargo audit --version)"

{
  echo "Pyx native Android Rust vulnerability audit"
  echo "timestamp_utc=$timestamp"
  echo "lockfile=rust/Cargo.lock"
  echo "lockfile_sha256=$lock_sha256"
  echo "scanner=$audit_version"
  echo "database_policy=current RustSec advisory database fetched into build/vulnerability-audit/advisory-db"
  echo "suppressed_advisories=none"
  echo
} >"$report"

set +e
cargo audit --db "$database_dir" --file "$lockfile" 2>&1 | tee -a "$report"
audit_status=${PIPESTATUS[0]}
set -e

if git -C "$database_dir" rev-parse HEAD >/dev/null 2>&1; then
  {
    echo
    echo "advisory_database_commit=$(git -C "$database_dir" rev-parse HEAD)"
    echo "advisory_database_commit_time=$(git -C "$database_dir" show -s --format=%cI HEAD)"
  } | tee -a "$report"
else
  echo "advisory_database_commit=UNAVAILABLE" | tee -a "$report"
fi

if [[ $audit_status -eq 0 ]]; then
  echo "cutover_gate=PASS" | tee -a "$report"
  echo "Rust vulnerability cutover gate passed: $report"
  exit 0
fi

{
  echo
  echo "cutover_gate=FAIL"
  echo "scanner_exit_code=$audit_status"
} | tee -a "$report"
echo "Rust vulnerability cutover gate failed: $report" >&2
exit "$audit_status"
