# Native Android cutover evidence

This document defines the evidence bundle required before Phase 5 moves
`android-native/` into the production Android lane and records completed target
runs. A recorded partial target does not declare cutover readiness; checkboxes
remain authoritative in `release-checklist.md`.

Current source/build evidence also includes typed Navigation Compose routes;
bounded input validation; recent-payment details with date and operation ID;
lifecycle-owned ordinary work that suppresses late secret results; configurable
fail-closed biometrics shared by payments, backup and federation leave; and
ownership-safe 60-second sensitive clipboard expiry. These deterministic tests
and build checks do not replace the open hardware and live-federation gates.

The home reconciliation loop is injectable through a narrow
`HomeStreamRepository` without changing the production constructor path.
Coroutine virtual-time tests reconnect its balance/payment streams and prove
that the latest nonzero balance and unique recent-payment rows remain cached;
three consecutive fallback snapshot failures retain that cache while changing
the visible refresh status from degraded to offline. This is deterministic
state-machine evidence, not observation of a live federation subscription.

The instrumentation suite also contains `NativeOwnershipCycleTest`, an offline
100-cycle packaged-JNI bootstrap/shutdown regression. Every cycle opens the
same isolated, uninitialized production-path `client.db`, requires a fresh
positive database-handle generation, observes exactly one terminal callback
for initial bootstrap, repeated bootstrap and shutdown, requires repeated
bootstrap to retain the active handle but the next post-shutdown cycle to rotate
it, and shuts the process-owned graph down before the next cycle.
It creates no wallet entropy and uses no federation or network.
Passing this test is handle/callback lifecycle evidence only: without a heap
profile or LeakCanary measurement it must not be described as proof that Java
or native heap usage is leak-free.

A 2026-09-03 tooling audit found no cached LeakCanary dependency or installed
headless heap analyzer. More importantly, the offline database has no selected
federation and therefore cannot create the real balance, connection, and
payment subscriptions required by the planned open/select/subscribe/close leak
gate. Adding a test-only synthetic client would test a different ownership
path. The measured leak gate remains open until an approved initialized fixture
or controlled live federation can exercise the production client lifecycle;
plain PSS comparison or an unanalyzed heap dump is not accepted as a substitute.

### Android 14 x86_64 100-cycle JNI ownership — PASS

- Run: 2026-09-02 14:11:28 UTC on the headless `pyx(AVD)` API 34 emulator
- Result: 1 test, 0 failures, 0 errors, 0 skipped; 100 complete cycles in
  4.197 seconds
- Runtime assertions: 100 unique post-shutdown database-handle generations;
  same-handle reuse only while each session remained active; 300 correlated,
  exactly-once terminal callbacks across initial bootstrap, repeated bootstrap,
  and shutdown; successful `client.db` open on every cycle
- Isolation: one uninitialized cache-owned database, deleted afterward; no
  entropy generation, federation, subscription, payment, or network operation
- Preserved local result: `build/ownership-certification/20260902T141128Z.xml`
  (SHA-256 `6b75b1b09f7a9f5eddf7b15c3efd17b5283008f71794c01707659a69a28430dd`)
- Scope limit: this is deterministic packaged-JNI ownership regression
  evidence. No Android Studio heap profile or LeakCanary measurement was taken,
  so the separate memory-leak evidence gate remains open.

## Recorded connected target evidence

### Android 14 x86_64 preview — PASS

- Report: `build/device-certification/20260903T091710Z.txt`; preserved JUnit XML
  and inventory: `build/device-certification/20260903T091710Z-reports/`
- Run: 2026-09-03 09:17:10–09:19:20 UTC
- Target: Google `sdk_gphone64_x86_64`, API 34, primary/tested ABI `x86_64`
- Package: `cash.pyx.app.nativepreview`; `cash.pyx.app` was not installed and
  was neither modified nor cleared
- Native runtime: packaged `lib/x86_64/libpyx.so` and matching NDK
  `libc++_shared.so` loaded through real JNI
- Result: 50 tests, 0 failures, 0 errors, 0 skipped. The certifier parses fresh
  JUnit XML, requires every test to appear in its inventory, rejects failures,
  errors, or skips, and records hashes for both artifacts. A focused camera
  permission/UI run also passed 5/5
- Covered: JNI version and local classifier symbols; isolated production-path
  `client.db` bootstrap, repeated-bootstrap identity, idempotent shutdown and
  rebootstrap; cold/warm/duplicate deep links, persisted safety-lock replay and
  `singleTop`; Activity recreation and fresh reconciliation-object restoration;
  native preview launch; secure-window lifecycle; permanent camera-denial
  recovery; persisted biometric-policy recreation and authenticated seed
  reveal/clearing; typed parent-route back behavior; collapsible balance
  semantics; all transfer tabs at 2.0x text on a narrow screen; 1.3x/2.0x text
  and 390 dp layout; 48 dp accessible actions; allowlisted legacy preference
  migration; sensitive clipboard/share policy; onboarding secret redaction;
  DataStore-backed preferences; and the
  packaged JNI ownership regression; and a packaged-JNI BIP39 check that
  accepts a checksum-valid repeated-word vector while rejecting bad checksum
- Not covered: production signing or install-over-Flutter, persisted production
  wallet compatibility, live federation/client streams, live-operation process death,
  physical arm64 loading, or the full hardware accessibility/security and
  API-level matrix

### Android 13 x86_64 redroid preview — PASS

- Report: `build/device-certification/20260904T103402Z.txt`; preserved JUnit XML
  and inventory: `build/device-certification/20260904T103402Z-reports/`
- Run: 2026-09-04 10:34:02–10:42:19 UTC
- Target: redroid `redroid13_x86_64` container (the intended local x86_64 lane
  environment), API 33, primary/tested ABI `x86_64`
- Package: `cash.pyx.app.nativepreview`; production `cash.pyx.app` was present
  on the container and recorded untouched — the certifier installs and tests
  only the suffixed preview identity
- Native runtime: packaged `lib/x86_64/libpyx.so` and matching NDK
  `libc++_shared.so` loaded through real JNI (`NativeJniSmokeTest`)
- Result: 50 tests, 0 failures, 0 errors, 0 skipped; inventory SHA-256
  `78b093dbad5946e08bbf7d0f6f3a19ed0f344f19d0a2c664afa6eaa34c00d363`, JUnit XML
  SHA-256 `6bf9fec6196c888828602a89f991e7007518041735066a01146082e6bec9a196`
- This satisfies the "supported x86_64 lane must run on its intended
  redroid/emulator environment" matrix requirement on redroid specifically, at
  API 33 in addition to the earlier API 34 emulator run
- Not covered: production signing or install-over-Flutter, persisted production
  wallet compatibility, live federation/client streams, live-operation process
  death, physical arm64 loading, or the full hardware accessibility/security
  and API-level matrix
- Re-run 2026-09-05 after the home-screen rework (inline paginated activity,
  payment detail bottom sheet, federation row removed): 52 tests, 0 failures;
  report `build/device-certification/20260905T134428Z.txt` with preserved
  reports in `build/device-certification/20260905T134428Z-reports/`

### Android 14 x86_64 isolated R8/JNI — PASS

- Runner: `tool/certify-native-android-r8-headless.sh`
- Package: `cash.pyx.app.r8cert`; isolated from and refusing to run alongside
  the production `cash.pyx.app` package
- Artifact: non-debuggable and R8-minified with production keep rules, but
  debug-signed solely so instrumentation can target it
- Result: 4 tests, 0 failures, 0 errors, 0 skipped
- Covered: packaged JNI version and classifier entry points plus isolated
  production-path database bootstrap, repeated bootstrap, idempotent shutdown,
  handle invalidation, and rebootstrap after R8 processing
- Not covered: production certificate continuity, install-over-Flutter,
  physical arm64 runtime loading, a live federation/client stream, payment
  submission, or other hardware/device-matrix gates

The 4/4 isolated R8/JNI suite was rerun after the current production changes and
passed again. This remains debug-signed instrumentation evidence, not production
certificate or physical-arm64 evidence.

### Android 14 x86_64 offline process termination — PASS

- Report: `build/process-death-certification/20260903T092601Z.txt`
- Run: 2026-09-03 09:26:01–09:26:24 UTC
- Package: `cash.pyx.app.nativepreview`; production remained untouched
- Process transition: the writer PID was terminated and the relaunched reader
  used a distinct PID; numeric IDs are redacted
- Result: the synthetic SHA-256 deep-link fingerprint/timestamp and bounded
  non-secret irreversible-operation journal metadata both survived
- Scope limit: this is an offline persisted-state probe only. It did not create
  a live federation client, run recovery, create/review an invoice or quote, or
  submit/reconcile a real payment, and must not be cited as proof of those paths

### Final signed arm64 release artifact — BUILD PASS

- The production-identity release build completed signed, minified and
  nondebuggable with required arm64 `libpyx.so` and `libc++_shared.so` plus
  native libraries contributed by Android dependencies; the ABI set is exactly
  arm64 even though the library-name set is larger
- Artifact verification passed signature, production package, 16 KiB alignment,
  compiled cleartext prohibition, and Flutter/legacy-artifact rejection
- Signer certificate SHA-256:
  `147ac9d00feb0cba985d7e75ad509bef6adceeedb2fd10737654c304b45a8537`
- APK SHA-256:
  `64ed1d4da6115cb7022c84f88c6e25e8f29b602262bd11147899645d977248ef`
- This does not prove signer equality with an archived Flutter APK, install-over-
  Flutter compatibility, or runtime loading on physical arm64 hardware

### Locked dependency inventory — PASS; Android scan PASS; Rust scan FAIL

- Report: `build/dependency-audit/20260903T084038Z.txt`
- Native Rust selection: `--no-default-features --features android-jni`; FRB is
  an optional `flutter-bridge` dependency and its imports/annotations are cfg-gated
- Inventory: locked Rust metadata and Android release runtime graph recorded;
  all 647 Rust third-party packages contain license metadata
- Flutter oracle: `flutter analyze` passes with the retained bridge lane
- RustSec gate: `tool/audit-rust-vulnerabilities.sh` scans the exact lockfile
  without ignores and fails closed. The 2026-09-02 scan still reports six
  vulnerabilities pinned through Fedimint 0.11.1 / Iroh 0.35.0 after three
  compatible remediations; report
  `build/vulnerability-audit/20260903T085921Z-rustsec.txt`, lockfile SHA-256
  `4ae1fc36ab39b013a08fae9a0d00ee9ea155cf9cee95efd23e2bbaf94cb908ea`,
  RustSec database commit `5a0ebedfe8bdd2e295b171f4162f8c977bcad9a5`.
  See `dependency-review.md`. This is a cutover blocker.
- Android OSV gate: `tool/audit-android-vulnerabilities.sh` verifies the resolved
  release graph exactly matches committed Gradle lock state and queries every
  Maven coordinate without ignores. All 165 components passed with zero
  findings and suppressions in
  `build/vulnerability-audit/20260903T084111Z-android-osv.txt`; lock SHA-256
  `adf692c10d89ac4573869bfc9301d3f5ea2325f6eb60802c5c35302b8934d448`.
  This current result must be rerun close to release. The Rust failure remains
  a cutover blocker.

### Bounded offline performance probe — PASS

- Report: `build/performance-certification/20260903T092631Z.txt`
- Target/safety: Android 14 x86_64 emulator, exact preview package, production
  absent and untouched, preview data cleared, uninitialized offline launch
- APK observations: preview 691,378,184 bytes (SHA-256
  `49f022657695b6a48b841470ed6e7c8f163a8750c25965e7786aa0d9ebadedb7`);
  release 53,081,089 bytes (SHA-256
  `64ed1d4da6115cb7022c84f88c6e25e8f29b602262bd11147899645d977248ef`)
- Five cold Activity starts: 5092, 4551, 4866, 4250 and 3849 ms; mean 4521 ms,
  median 4551 ms
- One idle sample after 10 seconds: total PSS 191,909 KiB; total RSS 246,172 KiB
- Interpretation: observational PASS means the guarded measurement completed;
  no performance thresholds or Flutter comparison were applied. This is not
  first-balance, wallet-ready, QR, payment-completion, live-network or physical-
  device performance evidence, all of which remain open.

## Immutable release inputs

Record these outside the source repository in access-controlled release
storage. Never add a keystore, signing password, seed, wallet database, invoice,
address, ecash token, or other wallet secret to this document or CI logs.

| Evidence | Required record |
| --- | --- |
| Flutter rollback artifact | Commit, version code/name, APK SHA-256, signer certificate SHA-256, storage reference |
| Native candidate | Commit, version code/name, APK SHA-256, signer certificate SHA-256, native-symbol archive reference |
| Behavioral oracle | Storage reference for the approved Flutter screenshots/recordings |
| Synthetic upgrade fixture | Fixture revision/hash and non-secret initialization procedure |

## Upgrade and target matrix

For Android 8 (API 26), Android 12 (API 31), and the current target API, record
the target model/image, CPU ABI, clean-install result, install-over-Flutter
result without data clearing, instrumentation report, and reviewer. At least one
run must be physical arm64 hardware; the supported x86_64 lane must run on its
intended redroid/emulator environment.

The upgrade comparison must explicitly cover seed-derived identity,
federations, selected federation, balances, currency, contacts, and payment
history. Logs and screenshots must redact wallet payloads.

`tool/certify-flutter-to-native-upgrade.sh` is the explicit local runner for
this matrix row. It is intentionally separate from the canonical preview
workflow. Its timestamped `build/upgrade-certification/` report proves
artifact, package, signature, and install-sequencing checks. There is no
production non-secret checkpoint hook, so the report preserves fixed-word
operator confirmations for the before/after behavioral assertions and must be
reviewed with separately stored redacted captures; it does not by itself prove
wallet-state equality.

### Android 13 x86_64 redroid install-over-Flutter — first FAIL found a P0 bug; re-run PASS (2026-09-04)

- Reports: `build/upgrade-certification/20260904T105259Z.txt` (FAIL) and
  `build/upgrade-certification/20260904T110857Z.txt` (PASS), each with a
  `-captures/` directory holding the redacted screenshots and the operator
  checkpoint record
- Target: clean redroid `pyx-upgrade` container, Android 13 x86_64,
  `cash.pyx.app` absent before the run; both APKs production-signed
  (signer SHA-256 `147AC9D0…B45A8537`), versionCode 38, x86_64 included for
  the redroid lane only
- Fixture: fresh synthetic wallet generated in the Flutter release app; default
  currency changed to Swiss Franc; joined the public "Liberty Tree Network"
  federation by `fedimint:` deep link; balance 0 sats; no contacts; empty
  history; seed words never captured
- FAIL run finding (P0): the pre-fix native app opened `filesDir/client.db`
  while Flutter's wallet actually lives at `<dataDir>/app_flutter/client.db`
  (path_provider documents directory) — the upgraded install presented an
  existing wallet as fresh onboarding. The frozen contract's "files directory"
  recording was wrong; every prior fixture test used the native path and could
  not catch this.
- Fix: `WalletDataDirectory.resolve` (Kotlin) opens the legacy
  `app_flutter/client.db` RocksDB directory whenever it exists and uses
  `filesDir` only when there is no legacy wallet; the database is never copied,
  moved, or rewritten. Unit tests cover legacy-present, legacy-absent,
  both-present (legacy wins over a stray uninitialized native DB), and
  plain-file `client.db` rejection. `behavior-contract.md` and the rust
  bootstrap documentation were corrected.
- PASS run: install-over preserved identity (no onboarding), the joined and
  selected federation (native app connected live: "4 of 4 guardians online"),
  0 sats balance, CHF currency, empty contacts and history; data was never
  cleared or uninstalled.
- Incidental: the known upstream x86_64 SIGSEGV (null deref,
  `tokio-runtime-w`) recurred in the retained Flutter/FRB lane during the
  deep-link join; the join state had already persisted and the wallet was
  intact on relaunch. Native-lane recurrence has not been observed.
- Scope limit: this is a synthetic-fixture x86_64 emulator-container row of the
  matrix. Physical arm64 hardware, Android 8/12 API rows, funded balances, and
  payment-history-bearing upgrades remain open.

## Manual platform evidence

### Deterministic adaptive Compose coverage — PASS (2026-09-02)

A focused `AdaptiveSecurityTest` run on the Android 14 x86_64 headless emulator
passed 9/9 against `cash.pyx.app.nativepreview`. The suite fails closed if the
target package is not the preview identity. Semantic/layout assertions cover
the 390×844 dp reference viewport at font scale 1.0, font scales 1.3 and 2.0,
a genuinely narrow 280×600 dp viewport at 2.0, and a 280×480 dp viewport with
1.35× injected Compose display density and 1.3 font scale. Primary onboarding
actions remain visible, scroll-reachable, and at least 48 dp high.

The same run sets Android's window, transition, and animator duration scales to
zero, exercises navigation, and restores each exact prior value in `finally`
and test teardown. Post-run values were `1.0`, `1.0`, and unset respectively.
This is deterministic emulator measurement, not physical-device display-size,
TalkBack, Accessibility Scanner, switch access, or screenshot/golden evidence;
those manual gates remain open.

### Synthetic Compose screenshot evidence — PASS (2026-09-03)

The guarded debug-only runner `tool/certify-native-android-screenshots.sh`
captured thirteen PNGs on the Android 14 x86_64 emulator under
`build/screenshot-certification/20260903T092318Z/`. Its manifest records a PASS
and `hashes.tsv` records SHA-256 and byte size for each image. Before capture,
UIAutomator structural assertions required the expected non-secret text.

Covered fixtures are onboarding; home with degraded guardian/status and recent
activity; synthetic non-payable send/receive review; settings/access with seed
content explicitly hidden before authentication; and offline/error. Each is
captured at the 390×844 dp reference viewport at 1.0 font scale and again at
1.3. Additional captures cover a 280×600 dp viewport at 2.0, increased display
density with 1.3 text, and home with all system animation scales zero. Fixtures
contain no mnemonic, invoice,
address, invite, ecash, live wallet handle, or payable QR. The runner refuses a
non-emulator, refuses any emulator containing `cash.pyx.app`, installs only
`cash.pyx.app.nativepreview`, and restores/verifies the exact original display
size, density, font-scale, and three animation settings. The final run restored
780×1688 physical size, density 320, an unset font-scale key, and animation
scales `1.0`, `1.0`, and unset. Visual inspection of the most constrained
onboarding and 1.3-font home captures found usable, unclipped actions after the
home action grid was made adaptive.

These are review artifacts with integrity hashes, not committed golden
baselines or Flutter pixel-parity assertions. Hashes prove artifact identity;
they do not by themselves decide whether a visual change is acceptable.
The checked-in historical Flutter and prototype reference inventory, including
per-file hashes, intermediate-capture caveats, and explicit missing-state
provenance, is recorded in
`docs/native-android/flutter-visual-oracle.md`.
Physical-device rendering, TalkBack/Accessibility Scanner and reviewer signoff
remain open.

### Deterministic onboarding and recovery coverage — PASS (2026-09-02)

The complete debug unit-test suite and lint passed after adding focused
`OnboardingRecoveryViewModelTest` coverage. It proves that incomplete phrases
never enter native restore, native invalid-word/checksum failures remain
explicit, blank/oversized invites are rejected before join, offline recovery
joins preserve the normalized invite for retry, and a cancelled non-cooperative
restore cannot publish a late factory handle or secret-bearing state. Existing
seed-backup tests prove pending confirmation survives ViewModel recreation and
continues to block home until acknowledgement. Existing lifecycle tests prove
recovery subscriptions close on stop and a recreated ViewModel resumes through
a fresh callback without accepting stale progress.

The focused `MainActivityTest` run passed 7/7 on the Android 14 x86_64 headless
emulator. Compose assertions cover invalid-word and invalid-order/checksum
presentation with disabled restore submission, plus an offline join failure
whose invite remains visible for retry. The packaged JNI suite additionally
proves checksum-valid repeated BIP39 words are accepted and checksum-invalid
repetition is rejected; positional UI tests prove repeated entries remain
independently editable. These deterministic seams do not prove live federation
join or live recovery completion; those integration gates remain open.

Attach dated results and a reviewer for:

- TalkBack and switch/keyboard navigation; font scales 1.3 and 2.0; narrow and
  scaled displays; reduced motion; accessibility scanner
- biometric unavailable, unenrolled, success, cancellation, and lockout
- camera grant, denial, rationale, permanent denial, rotation, and backgrounding
- secure recents/screenshots and sensitive clipboard clearing
- offline/poor network, low storage, process death, and foreground restoration
- R8 release JNI callbacks and runtime loading on 16 KiB arm64 hardware

## Rollout decision record

Before internal distribution, name an owner and a backup owner for release,
Android/JNI, wallet/recovery, payments, and incident response. For each metric,
record the measurement window, data source, baseline, stop threshold, decision
owner, and rollback action:

- crash-free users, ANR rate, and native-crash rate
- bootstrap and wallet-open failures
- Lightning, on-chain, and ecash submission/finalization failures
- recovery failures and support reports

Advance internal → closed → 5% → 25% → 50% → 100% only after the recorded
window at the current stage passes. Retain the signing-compatible Flutter APK
until a later native release proves native-to-native upgrade and the rollback
owner signs off. Any native database write must remain backward compatible for
that entire rollback window.

## Cutover authorization

The cutover commit may remove Flutter/FRB and relocate the Gradle project only
after links to all evidence above are recorded, every Critical item in
`release-checklist.md` is checked, and product/release, Android/JNI, wallet, and
security/accessibility reviewers approve. Until then, Flutter retention is an
intentional safety control rather than migration debris.
