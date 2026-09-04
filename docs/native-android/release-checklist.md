# Native Android release checklist

This checklist is a release gate, not a progress estimate. A box is checked only
when the current artifact or a recorded device run proves it.

Use `cutover-evidence.md` for the artifact, target-matrix, manual-test, owner,
threshold, and approval records. That template does not satisfy a gate by
itself.

## Reproducible build and identity

- [x] Production application ID is `cash.pyx.app`; preview builds use
  `cash.pyx.app.nativepreview`.
- [x] Version code/name and minimum SDK preserve the Flutter release lineage.
- [x] Release signing reads the existing `android/key.properties` and keystore;
  credentials are not copied into the native tree.
- [x] Release uses R8/resource shrinking and narrow keep rules for JNI callback
  class and method names.
- [x] The build verifier checks the final APK signature and rejects a debuggable
  or incorrectly named release artifact.
- [x] The production manifest disables cleartext traffic and the artifact
  verifier rejects a compiled manifest that does not preserve that setting.
- [ ] Record certificate equality against the latest production Flutter APK.
- [ ] Install the native release over the production-signed Flutter fixture
  without clearing app data.

## Native packaging

- [x] Default release requires arm64 `libpyx.so` and matching
  `libc++_shared.so`; Android dependencies also contribute native libraries.
  The verifier enforces the exact ABI set across all packaged native libraries.
- [x] The optional redroid build packages exactly arm64 and x86_64, with no
  32-bit ABIs.
- [x] Every `PT_LOAD` segment in the required Rust/NDK libraries and the final
  APK pass 16 KiB alignment checks; dependency-provided libraries remain part
  of Android dependency review and target-device certification.
- [x] The verifier rejects Flutter engine/assets, `libconduit.so`, and other
  legacy bridge artifacts.
- [x] Release native symbols are retained under `build/native-symbols/` before
  stripping the packaged library.
- [ ] Load and execute the release JNI library on arm64 hardware.
- [x] Load and execute the x86_64 artifact and its NDK C++ runtime on the
  Android 14 headless emulator; recorded in
  `build/device-certification/20260903T091710Z.txt`.
- [x] Exercise packaged JNI after production-rule R8 minification in the
  isolated, non-debuggable, debug-signed `cash.pyx.app.r8cert` artifact: 4/4
  tests pass again via `tool/certify-native-android-r8-headless.sh`.

## Wallet safety and compatibility

- [x] Rust contract tests reopen a deterministic wallet through production APIs
  at the unchanged `files/client.db` location.
- [x] Kotlin wallet I/O is callback-based/cancellable; bounded local parsing and
  codec operations are the only synchronous native calls.
- [x] Typed Navigation Compose routes keep wallet payloads out of saved route
  arguments, and lifecycle-owned ordinary operations suppress late results,
  including recovery words, after background cleanup.
- [x] Lightning receive derives a checked authoritative invoice expiry and the
  strict Kotlin/Compose path presents live countdown and expired states.
- [x] Seed, contact, amount, LNURL, BIP21 and scanned/deep-link inputs are
  bounded and validated before dispatch; recent payment rows open details that
  expose the date and stable operation ID needed for support/reconciliation.
- [x] Typed generation handles, dependency-ordered shutdown, double-close and
  100-cycle packaged-JNI reuse tests pass; preserved result
  `build/ownership-certification/20260902T141128Z.xml` has SHA-256
  `6b75b1b09f7a9f5eddf7b15c3efd17b5283008f71794c01707659a69a28430dd`.
- [x] Irreversible submissions are durably marked before native execution and
  are never retried automatically after an ambiguous cancellation/restart.
- [x] Stable non-secret correlation IDs reconcile Kotlin and native operation
  records after restart; only conclusive outcomes clear the retry gate and no
  manual acknowledgement can authorize an ambiguous duplicate retry.
- [ ] Prove seed identity, federations, balance, currency, contacts and payment
  history are unchanged after an upgrade from the Flutter fixture.
- [ ] Exercise process death during recovery, invoice creation, quote review and
  submitted payments.

## Platform security and accessibility

- [x] Android backup is disabled and wallet/secret paths are excluded by backup
  and data-extraction rules.
- [x] Seed and ecash secret surfaces use secure-window handling, non-saveable
  state, sensitive clipboard behavior and no implicit share path.
- [x] Strong biometric prompts gate configured spend and seed actions without a
  persisted authentication grant; configured protection fails closed when
  capability disappears, and federation leave uses the same guarded path.
- [x] Sensitive clipboard writes carry an ownership token and schedule a
  60-second clear that preserves any replacement clipboard content.
- [x] Internal source/APK regression checks cover logging/error sinks, secret
  navigation/copy paths, exported components, backup, cleartext, secure-window,
  intent bounds, payment journals, JNI/R8 and native artifact invariants; see
  `internal-security-review.md`. This is not independent security approval.
- [x] Locale-aware display formatting, non-secret Android Sharesheet actions,
  in-app-only payment notices with accessibility live regions, and truthful
  settings/about/license surfaces match the retained product contract; see
  `utility-platform-parity.md` for intentionally absent system notifications,
  quick-spend controls, and privacy URL.
- [ ] Verify recents capture, clipboard clearing, biometric unavailable/lockout,
  and camera denial/rationale on supported API levels.
- [ ] Obtain independent security review and close its findings; include
  dynamic memory/native analysis and adversarial live-payment crash injection.
- [ ] Run TalkBack, font-scale 1.3/2.0, narrow layout, display scaling,
  reduced-motion and accessibility-scanner passes.

## Final verification commands

The required secret-free preview workflow runs Rust formatting, native-only
Clippy with warnings denied, both native-only and retained Flutter-bridge Rust
test graphs, strict locked dependency inventory, source security review, JVM
tests, lint, debug assembly, and debug APK inspection. Its commands are listed
in `android-native/README.md` for local reproduction. Signing, connected-device
tests, physical-device checks, and live advisory databases intentionally remain
separate release gates.

- [x] `cargo test --manifest-path rust/Cargo.toml --no-default-features --features android-jni`
- [x] `nix develop -c flutter analyze` (retained migration oracle)
- [x] `nix develop -c tool/build-native-android.sh --debug`
- [x] `nix develop -c tool/build-native-android.sh --release`
- [x] `nix develop -c env PYX_ABI_X86_64=1 tool/build-native-android.sh --debug`
- [x] `nix develop -c tool/audit-native-dependencies.sh` records the locked
  Rust/Android dependency and license inventory; PASS report
  `build/dependency-audit/20260903T084038Z.txt`.
- [x] `tool/review-native-android-security.sh` and its `--apk` mode pass for the
  current source and production artifact invariants.
- [ ] Make `nix shell nixpkgs#cargo-audit -c
  tool/audit-rust-vulnerabilities.sh` pass with no suppressed advisories. The
  2026-09-02 run fails on six vulnerabilities pinned through Fedimint 0.11.1 /
  Iroh 0.35.0; three compatible findings have already been remediated.
- [x] `nix develop -c tool/audit-android-vulnerabilities.sh` proves the 165
  resolved release-runtime Maven modules exactly match committed lock state and
  reports zero current OSV findings with no suppressions; PASS report
  `build/vulnerability-audit/20260903T084111Z-android-osv.txt`.
- [x] Bounded offline emulator performance probe passes with APK byte sizes,
  five Activity cold starts and one post-10-second PSS/RSS sample; report
  `build/performance-certification/20260903T092631Z.txt`. No thresholds or
  Flutter comparison were applied.
- [ ] Measure and approve product-level startup/wallet-ready/first-balance, QR,
  payment, live-network and physical-device performance against recorded
  thresholds and applicable baselines.
- [x] Clean Android 14 primary-x86_64 connected suite: 50/50 packaged-JNI/database,
  deep-link safety/replay, lifecycle/recreation, secure-window, camera-denial,
  biometric-policy persistence, adaptive-layout, launch, and accessible-target
  tests pass; report `build/device-certification/20260903T091710Z.txt`, with
  preserved hashed JUnit XML and a 50-entry test inventory.
- [x] Focused Android 14 camera permission/UI suite passes 5/5.
- [x] Android 14 x86_64 isolated non-debuggable R8/JNI suite: 4/4 tests pass via
  `tool/certify-native-android-r8-headless.sh` without installing or modifying
  `cash.pyx.app`.
- [x] Offline force-stop/relaunch probe verifies a terminated writer PID and a
  distinct relaunched PID retain only the redacted deep-link fingerprint and
  non-secret irreversible-operation journal metadata; report
  `build/process-death-certification/20260903T092601Z.txt`.
- [x] Final signed, minified arm64 production-identity release build passes APK
  signature, package, nondebuggable, ABI, 16 KiB, cleartext and legacy-artifact
  verification. Its signer SHA-256 is
  `147ac9d00feb0cba985d7e75ad509bef6adceeedb2fd10737654c304b45a8537`.
  APK SHA-256 is
  `64ed1d4da6115cb7022c84f88c6e25e8f29b602262bd11147899645d977248ef`
  (53,081,089 bytes).
  This is build evidence, not archived-Flutter signer equality or physical
  arm64 runtime evidence.
- [ ] Run the applicable connected Compose/instrumentation suite on physical
  arm64 hardware and complete the broader API/feature matrix.

Run the device gate from the repository's Nix development shell:

```sh
nix develop -c tool/certify-native-android-device.sh
```

The runner deliberately requires exactly one authorized ADB transport, records
the device model/API/ABI, builds the matching debug JNI artifact, runs
`connectedDebugAndroidTest`, and verifies the installed preview package path and
native ABI. Reports are written under `build/device-certification/` without the
ADB serial or wallet data. It only targets `cash.pyx.app.nativepreview`: it does
not clear app data, uninstall packages, or install over `cash.pyx.app`. A
production upgrade fixture must remain a separate, deliberate certification
procedure using an archived production-signed APK.
Run `nix develop -c tool/test-native-android-connected.sh` once per target and
retain the generated Android test report as evidence.

When both archived production-signed artifacts are available, run the separate
upgrade gate on a dedicated clean device:

```sh
nix develop -c tool/certify-flutter-to-native-upgrade.sh \
  --flutter-apk /absolute/path/to/flutter-production.apk \
  --native-apk /absolute/path/to/native-production.apk \
  --confirm-production-upgrade cash.pyx.app
```

The gate requires exactly one authorized device, exact non-debuggable
`cash.pyx.app` identities, matching signer SHA-256 sets, and recognizable
Flutter/native contents. It refuses to start if production is already installed,
never clears or uninstalls it, installs Flutter first, then uses replacement
installation to preserve data. Because no production non-secret checkpoint hook
exists, identity/federation/balance/currency/contact/history comparisons remain
fixed-prompt manual assertions. A `PASS` report means the operator explicitly
confirmed them; it does not mean the tool read wallet state automatically.
Reports contain artifact hashes and device metadata but no APK paths, ADB serial,
or wallet values.

## Rollout

- [ ] Archive the last production-signed Flutter APK and its behavioral
  reference captures outside the source repository.
- [ ] Define crash/ANR/bootstrap/payment/recovery stop thresholds and owners.
- [ ] Complete internal and closed-track observation before staged production
  rollout.
- [ ] Confirm the rollback APK remains installable and database-compatible until
  native-to-native upgrade is proven.
