#!/usr/bin/env bash
# Scan the exact locked release-runtime Gradle graph against the current OSV
# Maven advisory database. Network, resolution, response, and findings all fail
# closed. There is deliberately no ignore list or severity threshold.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPORT_ROOT="$ROOT/build/vulnerability-audit"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="$REPORT_ROOT/$STAMP-android-osv.txt"
COMPONENTS="$REPORT_ROOT/$STAMP-android-components.tsv"
QUERY="$REPORT_ROOT/$STAMP-android-osv-query.json"
RESPONSE="$REPORT_ROOT/$STAMP-android-osv-response.json"
GRADLE_OUTPUT="$REPORT_ROOT/$STAMP-android-gradle-output.txt"
LOCKED_COMPONENTS="$REPORT_ROOT/$STAMP-android-locked-components.txt"
LOCKFILE="$ROOT/android-native/app/gradle.lockfile"
OSV_ENDPOINT="https://api.osv.dev/v1/querybatch"

for command in curl gradle jq sha256sum; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "required command is unavailable: $command" >&2
    exit 1
  fi
done
if [[ ! -s "$LOCKFILE" ]]; then
  echo "Gradle lock state is missing or empty: $LOCKFILE" >&2
  echo "Generate and review it with: gradle -p android-native :app:dependencies --configuration releaseRuntimeClasspath --write-locks" >&2
  exit 1
fi

mkdir -p "$REPORT_ROOT"
: >"$REPORT"
trap 'status=$?; if [[ $status -ne 0 ]]; then printf "result=FAIL\nexit_status=%s\n" "$status" >>"$REPORT"; echo "Android OSV audit failed: $REPORT" >&2; fi' EXIT

gradle -p "$ROOT/android-native" \
  -I "$ROOT/tool/export-android-release-components.init.gradle" \
  :app:exportReleaseRuntimeComponents \
  --console=plain >"$GRADLE_OUTPUT"

awk -F '\t' '$1 == "PYX_COMPONENT" { print $2 "\t" $3 "\t" $4 }' \
  "$GRADLE_OUTPUT" >"$COMPONENTS"
if [[ ! -s "$COMPONENTS" ]]; then
  echo "Gradle component export produced no Maven components" >&2
  exit 1
fi

# Prove that the graph submitted to OSV exactly matches the reviewed lock
# entries for this configuration; lenient or stale partial lock state fails.
awk -F '=' '$2 ~ /(^|,)releaseRuntimeClasspath(,|$)/ { print $1 }' \
  "$LOCKFILE" | sort -u >"$LOCKED_COMPONENTS"
awk -F '\t' '{ print $1 ":" $2 ":" $3 }' "$COMPONENTS" | sort -u \
  | diff -u "$LOCKED_COMPONENTS" - >/dev/null || {
    echo "resolved release graph does not exactly match committed Gradle lock state" >&2
    exit 1
  }

jq -Rn '
  [inputs | split("\t")
    | select(length == 3)
    | {package: {ecosystem: "Maven", name: (.[0] + ":" + .[1])}, version: .[2]}]
  | {queries: .}' <"$COMPONENTS" >"$QUERY"

curl --fail-with-body --silent --show-error \
  --connect-timeout 20 --max-time 180 \
  -H 'Content-Type: application/json' \
  --data-binary "@$QUERY" "$OSV_ENDPOINT" >"$RESPONSE"

EXPECTED="$(wc -l <"$COMPONENTS" | tr -d ' ')"
ACTUAL="$(jq -er '.results | length' "$RESPONSE")"
if [[ "$ACTUAL" != "$EXPECTED" ]]; then
  echo "OSV response cardinality mismatch: expected $EXPECTED, received $ACTUAL" >&2
  exit 1
fi

FINDINGS="$(jq '[.results[] | (.vulns // []) | length] | add // 0' "$RESPONSE")"
{
  echo "Pyx native Android vulnerability audit"
  echo "generated_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "advisory_source=$OSV_ENDPOINT"
  echo "gradle_lock_sha256=$(sha256sum "$LOCKFILE" | awk '{print $1}')"
  echo "component_count=$EXPECTED"
  echo "finding_count=$FINDINGS"
  echo "components_sha256=$(sha256sum "$COMPONENTS" | awk '{print $1}')"
  echo "response_sha256=$(sha256sum "$RESPONSE" | awk '{print $1}')"
  echo "suppressed_findings=0"
  echo
  jq -r --slurpfile components <(jq -Rn '[inputs | split("\t")]' <"$COMPONENTS") '
    .results as $results
    | range(0; $results | length) as $index
    | ($results[$index].vulns // [])[]
    | ($components[0][$index]) as $component
    | [$component[0] + ":" + $component[1] + ":" + $component[2], .id,
       ((.aliases // []) | join(",")), (.summary // "")]
    | @tsv' "$RESPONSE"
} >>"$REPORT"

if [[ "$FINDINGS" != "0" ]]; then
  echo "OSV reported $FINDINGS unsuppressed finding(s)" >&2
  exit 1
fi

echo "result=PASS" >>"$REPORT"
trap - EXIT
echo "Android OSV audit passed: $REPORT"
