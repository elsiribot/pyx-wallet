# Pyx Wallet

Pyx Wallet is a Fedimint wallet with a Rust wallet core. The Android client is
being migrated from Flutter to Kotlin and Jetpack Compose while preserving the
production package identity, signing lineage, and `files/client.db` wallet.

The native client lives in `android-native/` until the upgrade and device gates
in [the native release checklist](docs/native-android/release-checklist.md) are
proven. The Flutter tree is intentionally retained as the current release lane,
upgrade fixture, and behavioral oracle; do not delete it or move the native
project into `android/` before those gates pass.

CI mirrors that boundary: `android-release.yml` remains the signed Flutter
release workflow, while `android-native-preview.yml` performs non-publishing
host and APK checks for the migration lane. The preview gate checks Rust
formatting, warning-free native-feature Clippy, both the JNI-only and retained
Flutter-bridge Rust test graphs, strict Gradle lock resolution, JVM tests,
Android lint, debug assembly, dependency/license inventory, source security
invariants, and APK/ABI properties. It deliberately excludes signing,
emulators, physical devices, and advisory scanners that require a live
RustSec/OSV database; those remain explicit release gates below.

## Features

- Lightning, on-chain Bitcoin, and ecash send/receive
- Multi-federation wallets and recovery
- Contacts, fiat display, and paged payment activity
- Typed Navigation Compose routing with lifecycle-owned wallet operations
- Configurable, fail-closed biometric protection for spending, federation
  removal, and seed access
- Ownership-safe 60-second expiry for sensitive clipboard writes

## Development

Enter the reproducible development shell:

```sh
nix develop
```

Build and certify the native debug APK, including the Rust JNI library, JVM
tests, Android lint, ABI checks, 16 KiB alignment, and APK inspection:

```sh
tool/build-native-android.sh --debug
```

The output is
`android-native/app/build/outputs/apk/debug/app-debug.apk` and uses the
side-by-side application ID `cash.pyx.app.nativepreview`.

Other migration checks are:

```sh
cargo test --manifest-path rust/Cargo.toml --no-default-features --features android-jni
flutter analyze
tool/build-native-android.sh --release
PYX_ABI_X86_64=1 tool/build-native-android.sh --debug
tool/test-native-android-connected.sh
tool/certify-native-android-r8-headless.sh
tool/certify-native-android-process-death.sh
tool/audit-native-dependencies.sh
```

The release command requires the existing, uncommitted
`android/key.properties` and its referenced keystore. It creates a signed,
minified production-identity artifact for certification; it is not permission
to publish or install over a real wallet. The connected command requires one
attached arm64 or x86_64 Android target and does not perform the separate
Flutter-to-native upgrade fixture procedure.

The clean Android 14 x86_64 headless run passes all 50 connected tests; its
self-contained report and hashed JUnit inventory are under
`build/device-certification/20260903T091710Z*`. A focused camera
permission/UI run passes 5/5. The packaged JNI ownership regression also passes
100 bootstrap/shutdown cycles. A separate offline
force-stop/relaunch probe proves that redacted deep-link and irreversible-
operation safety metadata survives a distinct app process
(`build/process-death-certification/20260903T092601Z.txt`). It does not exercise
a live recovery or payment submission. The isolated, non-debuggable
`cash.pyx.app.r8cert` lane also passes all 4 packaged-JNI tests after an R8
rerun, and the final production-identity arm64 release build is signed,
non-debuggable, minified, cleartext-disabled, and verifier-clean (signer
SHA-256 `147ac9d00feb0cba985d7e75ad509bef6adceeedb2fd10737654c304b45a8537`).
Its APK SHA-256 is
`64ed1d4da6115cb7022c84f88c6e25e8f29b602262bd11147899645d977248ef`.
Native Rust builds without default features and enables only `android-jni`;
the Flutter bridge remains optional for the retained Flutter lane. The locked
dependency/license inventory passes
(`build/dependency-audit/20260903T084038Z.txt`), while a current vulnerability
scan now reproducibly fails on six transitive Rust vulnerabilities pinned by
Fedimint 0.11.1 / Iroh 0.35.0; no advisories are suppressed. The exact locked
Android release graph passes the current OSV Maven scan for all 165 components
with zero findings or suppressions
(`build/vulnerability-audit/20260903T084111Z-android-osv.txt`). `flutter analyze`
passes. These are
migration evidence, not cutover approval: physical arm64 runtime execution, a
production-signed Flutter-to-native upgrade, live federation/payment streams,
and the hardware accessibility/security matrix remain required.

The bounded offline emulator performance probe passes at
`build/performance-certification/20260903T092631Z.txt`: preview/release sizes
were 691,378,184/53,081,089 bytes.
Five Activity cold starts were 5092, 4551,
4866, 4250 and 3849 ms (mean 4521, median 4551); post-10-second idle memory is
191,909 KiB PSS and 246,172 KiB RSS. No thresholds or Flutter comparison were
applied, and this is not wallet-ready, balance, QR, payment, network, or
physical-device performance evidence.

See [android-native/README.md](android-native/README.md) for Android Studio and
test details, and
[the implementation plan](docs/superpowers/plans/2026-08-31-native-android-rebuild.md)
for cutover rules. Brand colors are documented in
[docs/design/tokens.md](docs/design/tokens.md); the Compose app otherwise uses
standard Material 3 and system styles.
