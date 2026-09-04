# Pyx Wallet Native Android Rebuild — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Keep the checkboxes current and stop at each phase gate.

**Goal:** Replace the Flutter application with a native Android app that preserves Pyx Wallet's Fedimint behavior, data, recognizable color scheme, package identity, and existing user wallets while adopting native Android interaction and presentation conventions.

**Architecture:** Keep the Rust/Fedimint core as the source of truth. Add a deliberately small JNI boundary with stable DTOs and callback/subscription handles, then build the app in Kotlin with Jetpack Compose, coroutines/Flow, Navigation Compose, and Android platform security APIs. During migration, the current Flutter app remains a read-only behavioral and brand-color reference; native Android ships only after persisted-wallet compatibility and critical payment flows pass on-device tests.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3 (Pyx color scheme over standard Material defaults), Navigation Compose, lifecycle ViewModel, coroutines/Flow, CameraX + ML Kit barcode scanning, AndroidX Biometric, Android Sharesheet/Clipboard, Rust 2024, Fedimint 0.11, `jni` crate, cargo-ndk, Gradle Kotlin DSL, JUnit, Robolectric, Compose UI tests, Rust unit/integration tests, redroid/device screenshot tests.

## Decisions and non-negotiable constraints

- Android only. The old `ios/` tree is archived or removed only after an explicit product decision; this plan does not create a native iOS replacement.
- Preserve `applicationId = "cash.pyx.app"`, version lineage, deep-link schemes, launcher icon, app label, and signing configuration so Android treats the result as an update.
- Preserve the existing `files/client.db` RocksDB location and schema. Never copy, migrate, or rewrite a real wallet database before a tested compatibility fixture proves it safe.
- Preserve the Rust/Fedimint implementation in `rust/`; do not reimplement wallet or payment logic in Kotlin.
- Remove Flutter and `flutter_rust_bridge` only after the native parity and upgrade gates pass. Generated FRB sources are not a Kotlin API specification; the handwritten Rust methods are.
- Dark theme only and stable-balance module off. Retain the established Pyx brand colors from `docs/design/tokens.md`, mapped to Material 3 color roles with accessible contrast. Prefer platform and Material 3 defaults for typography (including the system font), spacing, shapes, elevation, navigation, dialogs, sheets, controls, motion, touch feedback, and accessibility. `docs/design/prototype.html` remains a content, flow, and brand-color reference rather than a pixel-matching specification; introduce custom components or tokens only when a documented wallet-safety or essential brand requirement cannot be met by a standard component.
- Never log seed words, invoices, ecash tokens, LNURLs, addresses, database paths, clipboard contents, or raw JNI arguments. Release builds must be non-debuggable and redact Rust logs.
- All money crossing JNI is integer sats/msats, never `Double`. Fiat display values cross as decimal strings or scaled integers with currency metadata.
- Every async JNI operation returns a typed result; no Rust panic or Java exception may cross the FFI boundary uncontrolled.
- One active `ConduitClient` per selected federation. Explicitly close subscriptions and native handles from `ViewModel.onCleared()`/repository shutdown.
- Minimum SDK remains 24. Release ABI remains `arm64-v8a`; `x86_64` is retained for local redroid tests.

## Target source layout

```text
android/app/src/main/kotlin/cash/pyx/app/
  PyxApplication.kt
  MainActivity.kt
  core/native/          # NativeWalletApi, handle ownership, JNI DTO mapping
  core/model/           # Kotlin-only immutable domain models
  core/security/        # biometric gate and secure screen policy
  data/                 # repositories; only layer allowed to call native API
  ui/theme/             # Material 3 color-role mapping and exceptional brand assets
  ui/navigation/        # routes and deep-link dispatch
  feature/onboarding/
  feature/home/
  feature/receive/
  feature/send/
  feature/activity/
  feature/federation/
  feature/settings/
rust/src/android/       # JNI exports, DTO conversion, runtime, subscriptions
android/app/src/androidTest/fixtures/
```

State flows in one direction: Compose event → `ViewModel` → repository → `NativeWalletApi` → JNI/Rust. Rust events return through callbacks into a repository-owned `callbackFlow`, then immutable `UiState` returns to Compose. Composables never hold native handles or call JNI.

## Phase 0 — Freeze the contract and establish safety nets

### Task 0.1: Record the current application contract

**Files:**
- Create: `docs/native-android/behavior-contract.md`
- Create: `docs/native-android/screen-inventory.md`
- Create: `docs/native-android/ffi-contract.md`

- [x] Inventory every route in `lib/screens/` and `lib/drawers/`; map it to its prototype screen, entry conditions, Rust calls, loading/error/empty states, and deep links.
- [x] Inventory every public Rust API in `rust/src/{lib,client,factory,events,currency,lnurl}.rs`. For each call record arguments, result, threading expectation, handle lifetime, sensitive fields, and current Flutter caller.
- [x] Define native DTOs for federation summaries, balances, connection status, payment history/details, receive requests, fee quotes, recovery progress, contacts, and fiat currencies. Use explicit nullable fields and enum discriminants.
- [x] Record package/version/signing, DB path, shared preferences used by Flutter, intent filters, permissions, ABI policy, and notification behavior.
- [ ] Capture reference screenshots and short screen recordings for onboarding, home, receive modes, send modes, transaction details, federation state, settings, backup/recovery, offline, and failure states. Use them to preserve information, behavior, and Pyx color identity—not legacy Flutter geometry or pixel layout.
- [ ] Commit: `docs: freeze flutter behavior and native ffi contract`

### Task 0.2: Build deterministic compatibility fixtures

**Files:**
- Create: `rust/tests/fixtures/README.md`
- Create: `rust/tests/android_contract.rs`
- Create: `tool/make-sanitized-wallet-fixture.sh`

- [x] Add Rust tests for parsing Bitcoin/BIP21, Bolt11, LNURL, Fedimint invites, and ecash; include malformed and wrong-network cases.
- [x] Create a synthetic, secret-free initialized database fixture using test-only deterministic keys. Do not derive it from a developer wallet.
- [ ] Prove current Rust can load the fixture, list federations, read currency, and read history. Hash immutable fixture contents in the test.
- [ ] Save expected JSON snapshots for all read DTOs; these become JNI contract fixtures.
- [x] Prove the deterministic production-API fixture closes/reopens, preserves
  seed-derived identity, currency and contact state, returns an empty federation
  list, and matches a hashed logical snapshot. Federation/history compatibility
  remains open until an approved initialized federation fixture exists.
- [x] Centralize and execute secret-free expected JSON snapshots for deterministic
  read DTO variants; retain the all-DTO checkbox above because live-client and
  federation-database DTO construction is not covered by serializer fixtures.
- [x] Run `cargo test --manifest-path rust/Cargo.toml`.
- [ ] Commit: `test: add native migration contract fixtures`

**Phase gate:** No implementation starts until the screen/API inventory is complete and the synthetic DB is loadable by the current app core.

## Phase 1 — Convert the Android build without losing the Flutter oracle

### Task 1.1: Create a native build lane

**Files:**
- Create: `android-native/` initially, mirroring the final `android/` Gradle project
- Modify: `flake.nix`
- Create: `tool/build-native-android.sh`

- [x] Start in `android-native/` so the existing Flutter APK remains buildable for comparison. Configure AGP/Kotlin/JDK versions as one version catalog and compile/target SDK from the single documented `pyx.androidSdk` property backed by the installed Nix SDK.
- [x] Add Compose, Navigation, lifecycle, coroutines, AndroidX Biometric, CameraX, ML Kit barcode scanning, and test dependencies with locked versions.
- [x] Configure `minSdk 24`, `cash.pyx.app`, debug application id suffix `.nativepreview`, arm64 and optional x86_64 ABIs, packaging of `libpyx.so`, and release signing via the existing `key.properties` convention.
- [x] Copy launcher/splash assets, including adaptive launcher variants. Use the Android system font unless a specific brand asset has a documented requirement for another typeface; do not bundle Space Grotesk or Inter merely to reproduce Flutter typography. Add only required permissions (`CAMERA`, `USE_BIOMETRIC`, and network state/internet if declared by the final merged manifest).
- [x] Add CI/local commands for `assembleDebug`, `testDebugUnitTest`, `connectedDebugAndroidTest`, lint, and Rust tests.
- [x] Update the Nix shell to support both builds during migration, but do not remove Flutter yet.
- [ ] Commit: `build: add native android preview lane`

### Task 1.2: Native shell and process lifecycle

**Files:**
- Create: `android-native/app/src/main/kotlin/cash/pyx/app/{PyxApplication,MainActivity}.kt`
- Create: `android-native/app/src/main/kotlin/cash/pyx/app/ui/theme/*`
- Create: `android-native/app/src/androidTest/kotlin/cash/pyx/app/AppLaunchTest.kt`

- [x] Implement edge-to-edge `ComponentActivity`, dark system bars, splash theme, process/application initialization, and a root error boundary.
- [x] Map the existing Pyx colors from `docs/design/tokens.md` onto the smallest practical Material 3 `ColorScheme`. Start with standard Material 3 typography, system fonts, dimensions, shapes, elevation, components, and motion; add an override only when its requirement and accessibility impact are documented.
- [x] Use standard Material 3 app bars, navigation, buttons, cards, text fields, lists, dialogs, bottom sheets, snackbars, progress indicators, switches, and selection controls. Let Android provide ripple/pressed states, predictive-back behavior, insets, and reduced-motion behavior. Build custom QR, guardian-status, or payment-confirmation surfaces only where no standard control expresses the required behavior safely.
- [x] Add a debug component-gallery route covering the Material color roles, standard controls, QR surface, transaction row, status presentation, and any approved custom safety-critical component.
- [x] Add adaptive-layout and accessibility checks at font scales 1.0, 1.3, and 2.0, at 390×844 and one narrow device, with display scaling and reduced-motion settings. Screenshot tests should catch accidental regressions, not enforce pixel parity with Flutter. The guarded matrix records exact settings and hashes in `build/screenshot-certification/20260903T092318Z/`; semantic device tests separately assert operability and minimum targets.
- [x] Verify zero Flutter engine/library appears in `apkanalyzer files list` output.
- [ ] Commit: `feat(android): add compose shell and pyx theme`

## Phase 2 — Replace flutter_rust_bridge with a safe JNI boundary

### Task 2.1: Separate portable core API from FRB annotations

**Files:**
- Modify: `rust/Cargo.toml`
- Modify: `rust/src/lib.rs`
- Create: `rust/src/android/{mod,error,dto,runtime,handles}.rs`
- Create: `rust/tests/jni_dto_contract.rs`

- [x] Add features `flutter-bridge` and `android-jni`; keep Flutter compiling under the former until cutover. Add the `jni` crate only to the latter.
- [x] Move bridge-neutral operations behind internal Rust service functions so FRB and JNI adapters call the same code.
- [x] Define `AndroidError { code, user_message, retryable }`; map all Rust errors and caught panics at the boundary. User messages must not contain secrets.
- [x] Use a single managed Tokio runtime. Never create a runtime per JNI call and never block the Android main thread.
- [x] Implement typed handle registries for database, factory, client, invoice, ecash, invite, and subscription handles. Handles include type/generation checks; close is idempotent.
- [x] Serialize immutable DTO results as bounded UTF-8 JSON initially. Keep bulk/stream events callback-based. Document the threshold that would justify generated bindings later.
- [x] Test enum stability, nullability, error mapping, invalid/stale handles, double-close, size limits, invalid UTF-8, and panic containment.
- [ ] Commit: `refactor(rust): add bridge-neutral wallet service`

### Task 2.2: JNI exports and Kotlin facade

**Files:**
- Create: `rust/src/android/api.rs`
- Create: `android-native/app/src/main/kotlin/cash/pyx/app/core/native/{NativeBindings,NativeWalletApi,NativeResult,NativeHandles}.kt`
- Create: `android-native/app/src/test/kotlin/cash/pyx/app/core/native/NativeDtoTest.kt`

- [x] Expose bootstrap/open DB, factory load/create, seed generate/parse, federation join/recover/list/load/leave, currency/contact operations, and all client read/payment operations from the frozen contract.
- [x] Wrap Rust futures with request IDs and one completion callback. Completion happens exactly once, including cancellation/races.
- [x] Wrap balance, connection, event log, recovery progress, and payment notification streams with subscription handles. Callback methods attach safely to the JVM and dispatch off the Rust worker thread immediately.
- [x] In Kotlin, hide all `external fun` declarations behind `NativeWalletApi`; expose suspend functions and `Flow`. Validate DTOs before mapping them to domain models.
- [x] Add `Closeable` ownership, cancellation propagation, bounded callback buffers, and explicit overflow behavior (refresh snapshot, never silently lose a balance state).
- [x] Build `libpyx.so` via cargo-ndk with 16 KiB page-size compatibility; load it once through `System.loadLibrary("pyx")`.
- [ ] Run Rust tests, JVM DTO tests, and an instrumented smoke test that opens the synthetic fixture and observes then cancels one stream.
- [ ] Commit: `feat(android): expose wallet core through jni`

### Task 2.3: Upgrade and process-death proof

**Files:**
- Create: `android-native/app/src/androidTest/kotlin/cash/pyx/app/WalletUpgradeTest.kt`
- Create: `android-native/app/src/androidTest/kotlin/cash/pyx/app/NativeLifecycleTest.kt`

- [ ] Install the Flutter release-signed test APK, initialize the synthetic wallet, then install the native APK over it without clearing data. Assert federation, balance, currency, contacts, and history are unchanged.
- [ ] Kill/recreate the process during idle, an active stream, invoice generation, and recovery observation. Assert handles are rebuilt and no operation is duplicated.
- [ ] Run a 100-cycle open/select/subscribe/unsubscribe/close test under Android Studio memory profiler or LeakCanary debug tooling.
- [x] Run a separate offline 100-cycle packaged-JNI bootstrap/reuse/shutdown
  regression with handle-generation rotation and exactly-once callback checks;
  this does not satisfy or replace the profiler/LeakCanary item above.
- [ ] Commit: `test(android): prove flutter-to-native wallet upgrade`

**Phase gate:** Upgrade-in-place, lifecycle, and JNI contract tests are green on arm64 hardware and x86_64 redroid.

## Phase 3 — Data layer, navigation, and platform integrations

### Task 3.1: Repositories and app state

**Files:**
- Create: `android-native/app/src/main/kotlin/cash/pyx/app/data/{Wallet,Payment,Settings,Contact}Repository.kt`
- Create: `android-native/app/src/main/kotlin/cash/pyx/app/core/model/*`
- Create: corresponding unit tests under `android-native/app/src/test/`

- [x] Implement application-scoped bootstrap and selected-federation state; model `Uninitialized`, `Loading`, `Ready`, `Recovering`, and `Fatal` explicitly.
- [x] Combine snapshot reads with native streams into immutable state. Retry only retryable errors with capped exponential backoff and visible offline status.
- [x] Preserve selected currency and any existing preference keys. Flutter stores currency in the unchanged Rust RocksDB and defines no SharedPreferences keys; native migration allowlists only the three documented pre-DataStore booleans, excludes unknown/sensitive keys, and is idempotent. New ordinary preferences use DataStore and contain no secrets.
- [ ] Add fake repositories with virtual-time controls for every UI state and race.
  - [x] Add a narrow fake bootstrap repository seam with virtual-time coverage for all five application lifecycle states and stale-load races.
  - [x] Add a narrow home stream repository seam with virtual-time coverage for
    reconnect retention, deduplicated recent activity, degraded status and the
    three-failure offline transition. Other feature seams remain open.
  - [x] Add production-wired receive and recovery seams with virtual-time
    coverage for retryable/fatal failure, cancellation-ignoring late secret
    suppression, stale restore completion, and recovery stream teardown plus
    recreation/resubscription.
  - [x] Add independent contacts and currency/settings owners with cached-list
    preservation, stale-result suppression, mutation/refetch safety, duplicate
    action guards, and feature-scoped Compose progress/error presentation.
  - [x] Add a production-wired activity owner with independent paging/detail
    jobs and generation guards for same-client reset, client switch, live merge,
    detail selection/dismissal, cached failure and duplicate loading.
  - [x] Add a production-wired federation owner with separately owned selection,
    details, connection and mutation jobs; lifecycle resubscription, live-over-
    poll precedence, stale-client guards, and explicit leave reconciliation are
    covered under virtual time. Remaining minor feature seams remain open.
  - [x] Add production-wired ecash QR/classification, on-chain address, and
    LNURL transient-handle owners with deterministic stale-result, duplicate,
    cross-client, lifecycle teardown, and exact-close coverage.
- [ ] Commit: `feat(android): add wallet repositories and state model`

### Task 3.2: Navigation and deep links

**Files:**
- Create: `android-native/app/src/main/kotlin/cash/pyx/app/ui/navigation/*`
- Modify: native `AndroidManifest.xml`
- Create: `android-native/app/src/androidTest/kotlin/cash/pyx/app/DeepLinkTest.kt`

- [x] Define typed routes for onboarding/recovery, home, receive, send, activity, federation/guardians/wallets, settings, currency, seed/access, contacts, and modal sheets. Quick-spend limit remains intentionally absent because no real product policy backs it.
- [x] Parse `bitcoin:`, `lightning:`, `lnurl:`, and `fedimint:` intents once into sealed `IncomingRequest` values. Reject malformed/oversized payloads and never auto-send.
- [x] Queue incoming requests until wallet bootstrap is Ready; consume each intent once across activity recreation. Biometric policy gates protected execution, not review-only routing; there is no app-wide authentication lock in the behavior contract.
- [x] Test cold start, warm start, duplicate intent, queued delivery while bootstrap is not Ready, malformed/oversized input, and back-stack/root-reset behavior.
- [ ] Commit: `feat(android): add typed navigation and payment deep links`

### Task 3.3: Android security and utility services

**Files:**
- Create: `android-native/app/src/main/kotlin/cash/pyx/app/core/security/*`
- Create: `android-native/app/src/main/kotlin/cash/pyx/app/core/platform/*`

- [x] Implement biometric capability/enrollment checks with `BiometricPrompt`; gate seed display and configured spend actions. Authentication grants a short-lived in-memory capability, not a persisted boolean.
- [x] Apply `FLAG_SECURE` while seed/recovery secrets or ecash tokens are visible. Clear transient secret state on background and exclude secret screens from recents previews.
- [x] Implement clipboard writes with Android sensitivity flags and ownership-safe timed clearing; require confirmation before copying seed words.
- [x] Implement Sharesheet, camera permission rationale/denial, locale-aware formatting, and accessibility announcements. Preserve the current in-app payment notification behavior via Snackbar/live-region announcements; the Flutter contract has no Android system notifications.
- [ ] Commit: `feat(android): add secure platform integrations`

## Phase 4 — Vertical feature slices

Each task follows the same loop: port behavior from the named Flutter files, implement against fake repositories, add Compose tests for every state, connect the real repository, verify content and Pyx color identity against the prototype/Flutter reference while using native Material 3 layout and interaction patterns, test TalkBack, large fonts, display scaling, and reduced motion, then test on redroid and arm64 hardware. Commit only when the slice is independently usable.

### Task 4.1: Onboarding, create, join, and recovery

**Reference:** `landing_screen.dart`, `input_recovery_phrase_screen.dart`, `confirm_recovery_phrase_screen.dart`, invite/recovery drawers.

- [x] Implement create-wallet seed confirmation, existing-seed input, invite scan/paste, federation join, recovery progress, cancellation rules, and resumable error states.
- [x] Keep seed words in memory only as long as the flow requires; no saved state, logs, analytics, or screenshots. Restore entry and seed confirmation are redacted at the `ON_PAUSE` boundary; pending confirmation is refetched only after resume.
- [x] Add tests for invalid words/order, duplicate words, invalid invite, offline join, process recreation, and recovery resume.
  - Local deterministic coverage now includes incomplete/invalid/checksum seed failures, checksum-valid repeated-word acceptance, invalid and offline invite handling with retry preservation, seed-confirmation process recreation, cancellation with non-cooperative late restore suppression, and recovery stop/recreation/resubscription. Real federation recovery completion remains open.
- [ ] Commit: `feat(android): port onboarding and recovery`

### Task 4.2: Home and wallet selection

**Reference:** `base_screen.dart`, `home_screen_body.dart`, `recent_payments_widget.dart`, prototype `home` and `wallets`.

- [x] Port live balance, fiat conversion, masking, selected federation, connection/quorum state, recent activity, empty/loading/error states, collapsible balance header, scan action, and wallet switcher.
- [x] Implement the collapsible balance header with a Material surface, retained
  masking semantics, and narrow-screen/2.0x-font connected coverage.
- [x] Ensure stream reconnects do not duplicate rows or flash zero balances.
  A controllable `HomeStreamRepository` drives the production reconciliation
  loop under coroutine virtual time; cached nonzero balance and unique recent
  payment IDs survive stop/start resubscription and fallback polling failures.
  Real-client lifecycle evidence remains part of the Phase 4 device gate.
- [ ] Commit: `feat(android): port home and wallet selection`

### Task 4.3: Receive

**Reference:** receive/invoice/on-chain/ecash/LNURL screens and drawers; prototype `receive`.

- [x] Implement Lightning amount → invoice, no-amount LNURL when supported, on-chain address/BIP21, ecash generation/claim scanning, QR animation, copy/share, expiry, and recheck behavior.
- [x] Camera scanning uses CameraX + ML Kit; pause analysis while a result is handled and deduplicate frames.
- [x] Test invoice failure/expiry, unsupported LNURL, address generation failure, animated ecash fragments out of order, duplicate token, and rotation/background.
- [ ] Commit: `feat(android): port receive flows`

### Task 4.4: Send and confirmation

**Reference:** `send_screen.dart`, lightning address, LNURL, on-chain amount, and confirmation drawers; prototype `send`.

- [x] Accept scan/paste/manual Lightning invoice, Lightning address/LNURL, Bitcoin/BIP21, and ecash. Resolve type in Rust, then render a typed amount/fee/recipient confirmation.
- [x] Preserve fee calculation, insufficient-balance checks, fixed/ranged LNURL constraints, memo/contact naming, biometric policy, deliberate confirmation, pending/result states, and idempotency. Prefer a standard Material 3 confirmation action; retain a custom slide-to-send control only if usability testing shows it is necessary to prevent accidental payments and it meets TalkBack, keyboard/switch access, large-target, and reduced-motion requirements.
- [x] Disable confirmation after the first submit; retain the request ID through recreation and reconcile outcome from payment history before allowing retry.
- [x] Test wrong network, expired invoice, amountless invoice, fee race,
  insufficient funds, offline gateway, cancellation, double tap, and ambiguous
  timeout. `SendFlowViewModelTest` drives these cases through a narrow fake
  repository seam; ambiguous results retain the durable reconciliation lock.
- [ ] Commit: `feat(android): port send flows`

### Task 4.5: Activity and payment details

**Reference:** `payment_history_screen.dart`, `payment_details_drawer.dart`; prototype `activity`.

- [x] Port grouping, status, type/direction, fiat display, pagination/refresh behavior, empty state, detail sheet, and copyable technical fields.
- [x] Keep stable list keys based on operation ID and test live updates arriving while details are open.
- [ ] Commit: `feat(android): port activity and payment details`

### Task 4.6: Federation, guardians, contacts, and connection status

**Reference:** federation/connection/contact screens and wallet detail/leave drawers.

- [x] Port federation metadata, module stats, guardian quorum/status, expiry/successor, contacts CRUD, federation join/switch/leave, and recovery restrictions.
- [x] Require explicit destructive confirmation before leave; Rust rejects leave while unsafe/pending according to current semantics.
- [ ] Commit: `feat(android): port federation and contacts`

### Task 4.7: Settings, currency, access, backup, and about

**Reference:** settings, currency picker, recovery phrase; prototype `settings`, `seed`, `access`, `limit`.

- [x] Port fiat/BTC unit selection, biometric toggle, quick-spend limit if backed by real policy, seed backup reveal/copy warning, version/licenses/privacy links, and app diagnostics without secrets. No quick-spend action or approved privacy URL exists in the frozen Flutter behavior contract, so neither is invented.
- [x] Add Compose tests proving seed content is absent before successful authentication and cleared on background.
- [ ] Commit: `feat(android): port settings and secure backup`

**Phase gate:** All P0 flows—create/recover, upgrade/load, balance, receive Lightning/on-chain/ecash, send Lightning/on-chain/ecash, history, seed backup—pass real-core tests on arm64 hardware.

## Phase 5 — Native cutover

### Task 5.1: Replace the Flutter Android project

**Files:**
- Move native project from `android-native/` to `android/`
- Modify: `flake.nix`, `README.md`, build scripts, CI configuration
- Remove only after parity gate: `lib/`, `pubspec.yaml`, `pubspec.lock`, `flutter_rust_bridge.yaml`, generated bridge sources, Flutter Gradle plugins/configuration
- Modify: `rust/Cargo.toml`, `rust/src/lib.rs`

- [ ] Tag the last Flutter-buildable commit and archive its signed internal APK plus screenshots as release artifacts (never commit signing material).
- [ ] Move the native Gradle project into `android/`; update scripts/CI/Nix to build Rust then Gradle without Flutter.
- [ ] Remove the `flutter-bridge` feature, `flutter_rust_bridge` dependency/annotations, and generated bindings only after JNI parity is complete. Run the full Rust suite after removal.
- [ ] Preserve application ID, signing config, version code/name, manifest deep links, DB path, backup rules, launcher assets, and release ABI.
- [ ] Decide separately whether to retain or archive `ios/`; document that native Android cutover ends Flutter-based iOS releases.
- [ ] Verify `rg -i 'flutter|dart|flutter_rust_bridge'` contains only migration history/docs and intentional archived references.
- [ ] Commit: `build: cut over pyx wallet to native android`

### Task 5.2: Release hardening

**Files:**
- Modify: native ProGuard/R8 rules and release manifest
- Create: `docs/native-android/release-checklist.md`

- [x] Enable R8/resource shrinking after adding narrow JNI keep rules. Verify every native callback in a minified release build.
- [x] Verify 16 KiB page alignment, arm64 packaging, native symbols artifact, reproducible versioning, non-debuggable release, network security config, exported components, backup/data extraction rules, and dependency licenses.
- [ ] Run Android lint, unit tests, connected tests, Rust format/clippy/tests, dependency vulnerability/license checks, and APK analysis.
- [ ] Test upgrade from the latest production Flutter APK on at least Android 8, 12, and current target Android; test fresh install and process restore.
- [ ] Measure cold start, time to first balance, APK size, idle memory, QR scan latency, and send/receive completion against recorded Flutter baselines. Record justified regressions.
- [ ] Run accessibility scan/TalkBack, large font, display scaling, locale/decimal, offline/poor network, camera denial, biometrics absent/locked out, low storage, and background/foreground cases.
- [ ] Perform an independent security review focused on seed handling, JNI memory/handle safety, intent spoofing, clipboard leakage, screenshots, logs, RocksDB compatibility, and payment idempotency.
- [x] Complete an internal source review and reproducible static regression
  checks for those surfaces; this explicitly does not satisfy the independent
  review, hardware, live-payment, dynamic-memory, or compatibility gates above.
- [ ] Commit: `chore(android): harden native release`

### Task 5.3: Staged rollout and rollback

- [ ] Publish internal, then closed test tracks with staged cohorts. Monitor crash-free users, ANRs, native crashes, bootstrap failures, payment failure rates, recovery failures, and support reports without collecting secret payloads.
- [ ] Define stop thresholds and owners before rollout. Keep the last Flutter APK/signing-compatible rollback artifact available; note that any DB schema write introduced by native must be backward compatible until rollback closes.
- [ ] Advance 5% → 25% → 50% → 100% only after a full observation window at each stage.
- [ ] Close rollback only after the native release is stable and the next release has validated native-to-native upgrade.

## Definition of done

- [x] Native APK contains no Flutter engine, Dart assets, or FRB runtime.
- [ ] Existing production wallets upgrade in place without seed re-entry, data loss, or federation rejoin.
- [ ] All critical payment and recovery flows pass on physical arm64 devices and the supported Android range.
- [ ] The native UI retains the approved Pyx color identity and all required content and states. Material 3 adaptations are expected; any custom styling beyond the color scheme or safety-critical components is documented and approved.
- [ ] Security, accessibility, performance, release, and rollback checklists are signed off.
- [ ] README and contributor instructions describe only the native Android build, while migration history remains in `docs/native-android/`.

## Recommended execution order

Implement one vertical slice at a time after the bridge foundation: contract/fixtures → dual build → JNI read paths → upgrade proof → onboarding → home → receive → send → activity → federation/settings → cutover → staged rollout. Do not delete Flutter early; it is the strongest available oracle for behavior, persisted-data compatibility, required content, and Pyx color identity.
