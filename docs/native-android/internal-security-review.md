# Internal native Android source security review

Date: 2026-09-02

Scope: Kotlin/Compose production and debug source sets, Android manifests and
backup rules, Rust Android bridge code, native build/R8 rules, deterministic
tests, and locally built APK invariants. This is an internal engineering review,
not an independent external assessment, penetration test, hardware review,
live-federation/payment exercise, or dependency advisory scan.

Run the reproducible source checks from the repository root:

```sh
tool/review-native-android-security.sh
tool/review-native-android-security.sh --apk android-native/app/build/outputs/apk/release/app-release.apk
```

## Findings matrix

| Area | Source evidence reviewed | Result | Remaining evidence |
| --- | --- | --- | --- |
| Seed handling | Recovery words stay in non-saveable operation state, backup display is biometric-gated/non-shareable, `ON_STOP` cleanup suppresses late results, and no seed/recovery call reaches `QrPayload.copy`. Seed parsing is bounded and Rust errors do not echo submitted words. | No source-level seed persistence/log/share path found. Static checks fail on saved navigation or an obvious seed-copy call. | Physical recents capture, process-memory/heap inspection, keyboard/IME behavior, rooted-device storage, and independent review remain open. |
| JNI handles and memory | Rust opaque handles encode slot plus generation, validate type, close idempotently, contain panics, and shut down child-before-parent. Host race/stale tests and the packaged 100-cycle JNI test cover exactly-once callbacks and generation rotation. | Ownership model is source- and runtime-tested. No raw pointer crosses Kotlin. | LeakCanary/Android Studio native+Java heap profiling, sanitizer builds, physical arm64, and independent native-code review remain open. |
| Intent spoofing and bounds | Production exports only `MainActivity`; payment schemes route to classification/review rather than execution. Payload-free typed routes, SHA-256 duplicate fingerprint, authoritative Rust parsing, and bounded inputs are tested. | Fixed during this review: oversized exported URI strings are now rejected at 16 KiB before hashing or persistence, with a JVM regression test. | Fuzzing of real Android intent delivery, hostile-app testing across supported APIs, and verified-app-link/domain policy are not applicable to these custom schemes but caller authenticity must never be assumed. |
| Clipboard | Sensitive writes carry Android's sensitive flag, an unguessable ownership token, value-digest comparison and a 60-second timer; older timers cannot erase newer/user clips. Recovery-word copying requires a dedicated warning confirmation and never enters Sharesheet. | Source, JVM ownership, and connected platform-policy tests pass. | Real OEM clipboard behavior, background clipboard-read denial, keyboard/history integrations, and API 24–36 device matrix remain open. |
| Screenshots and recents | Sensitive result/seed/ecash surfaces set `FLAG_SECURE`; lifecycle disposal clears it, and `ON_PAUSE` redacts ephemeral secret presentation state. | Compose instrumentation verifies flag set/cleanup and recovery-word redaction across background/resume. | Physical screenshots, recents thumbnails, screen sharing/casting and OEM behavior require manual testing. |
| Logs and errors | Production Kotlin has no logging/stack-print APIs. Root crashes persist only a marker. JNI catches panics and maps stable sanitized errors without formatting panic payloads; parsing failures use generic user messages. | Static review script rejects common log/stack/error-message sinks. | Upstream Fedimint/native dependency logging and OS/native crash dumps are not exhaustively controlled; inspect release logcat/tombstones under adversarial failures. |
| RocksDB compatibility | The exact `filesDir/client.db` production APIs close/reopen a deterministic public-vector wallet and hash its logical seed-derived identity/currency/contact/empty-federation snapshot. Backups/transfers exclude all storage domains. | Empty-wallet schema path is deterministically covered. | No approved secret-free initialized federation/module/history database exists; Flutter-to-native signed fixture upgrade and rollback compatibility remain blocking gates. |
| Payment idempotency | Kotlin commits bounded non-secret correlation/federation metadata before dispatch. Rust persists a correlation marker around native execution. Restart reconciliation never retries ambiguous/in-flight/submitted records and clears the lock only after native conclusive clearing. | Host tests cover races/status classification and offline process death covers journal persistence. | Live federation submissions, crash injection at each native commit boundary, recovery/invoice/quote process death, and operator incident drills remain open. |
| Manifest/platform policy | Backup disabled plus all-domain exclusions, cleartext forbidden, one production export, debug probes confined to preview source set, and narrow R8 JNI keeps. Build tooling rejects Flutter/legacy native artifacts and validates ABI/signing/alignment. | Reproducible static checks pass; optional APK mode checks compiled identity/manifest/native contents. | Physical arm64, API matrix, external APK analysis and signer comparison to archived Flutter production remain open. |

## Static-check limitations

The script deliberately uses narrow patterns with low false-positive risk. It
cannot prove absence of secrets in memory, infer arbitrary data flow, inspect
dependency implementation logs, detect side channels, validate cryptography,
measure heap retention, or replace runtime/manual review. A passing result is a
regression guard for named invariants only.

## Disposition

No release authorization is granted by this review. The concrete oversized
intent issue was fixed and tested. All remaining items above continue as
hardware, live-network, compatibility-fixture, dynamic-analysis, or independent
review gates in the release checklist.
