# Pyx Wallet native Android

This is the standalone Kotlin/Jetpack Compose replacement for the Flutter
Android app. It uses the Rust wallet core through an asynchronous JNI boundary.
Debug builds use `cash.pyx.app.nativepreview`, so they can be installed beside
the current `cash.pyx.app` release without accessing its app-private data.

The UI maps the established Pyx dark palette onto Material 3 color roles and
otherwise prefers Android system fonts, standard components, navigation,
motion, insets, and accessibility behavior.

## Build from the repository root

Use the integrated script rather than invoking Gradle first: the script builds
Rust with `--no-default-features --features android-jni`, packages required
`libpyx.so` and matching NDK `libc++_shared.so` alongside native libraries from
Android dependencies, runs the relevant Gradle checks, and certifies the exact
ABI set and resulting APK.

```sh
nix develop
tool/build-native-android.sh --debug
```

Useful variants:

```sh
# Signed, minified arm64 production-identity artifact. Requires the existing
# android/key.properties and referenced keystore.
tool/build-native-android.sh --release

# Debug artifact for an x86_64 test target as well as arm64.
PYX_ABI_X86_64=1 tool/build-native-android.sh --debug
```

Open `android-native/` as the project root in Android Studio. Gradle-only tasks
are appropriate after the integrated script has populated
`app/src/main/jniLibs`; a clean checkout must run the integrated script first.

Plugin, Kotlin/JVM, Android minimum-SDK, AndroidX and Compose versions are
centralized in `gradle/libs.versions.toml`. `pyx.androidSdk` in
`gradle.properties` is the single compile/target SDK value and must name a
platform installed by `flake.nix`; both SDK settings intentionally move
together.

## Tests

### Debug component gallery

The component gallery exists only in the `debug` source set and contains fixed,
synthetic fixtures. After installing the native debug APK, open it explicitly:

```sh
adb shell am start -n cash.pyx.app.nativepreview/cash.pyx.app.debug.ComponentGalleryActivity
```

It has no launcher or deep-link intent filter and is absent from release builds.

Capture review-only, secret-free Compose screenshots on the isolated headless
emulator with:

```sh
nix develop -c tool/certify-native-android-screenshots.sh
```

The runner writes PNGs, structural markers and SHA-256 hashes under
`build/screenshot-certification/`; binary baselines are intentionally not
committed.

Host-verifiable checks:

```sh
cargo test --manifest-path rust/Cargo.toml --no-default-features --features android-jni
tool/build-native-android.sh --debug
tool/audit-native-dependencies.sh
```

With exactly one supported Android target visible to `adb`:

```sh
tool/test-native-android-connected.sh
```

On a disposable headless emulator with no production Pyx package installed,
the release-equivalent R8/JNI lane is:

```sh
PYX_ABI_X86_64=1 tool/build-native-android.sh --debug
tool/certify-native-android-r8-headless.sh
```

It installs only `cash.pyx.app.r8cert`, which is minified with the production
rules but debug-signed and isolated from `cash.pyx.app`. The tool refuses a
physical device or an emulator containing the production package.

Recorded on Android 14 x86_64, the clean preview suite passes 50/50 tests
(report `../build/device-certification/20260903T091710Z.txt`), a focused camera
run passes 5/5, the packaged JNI ownership test passes 100 cycles, and the
isolated non-debuggable R8 lane
passes 4/4 packaged-JNI tests. The latter proves that R8
retains the exercised JNI entry points and callbacks in this isolated artifact;
it is not a production-signed release or physical-arm64 result.

The locked Rust/Android dependency and license inventory passes at
`../build/dependency-audit/20260903T084038Z.txt`. The exact locked Android
release graph passes the current OSV Maven scan for all 165 components with no
findings or suppressions
(`../build/vulnerability-audit/20260903T084111Z-android-osv.txt`). The separate current RustSec
gate fails on six transitive vulnerabilities pinned by Fedimint 0.11.1 / Iroh
0.35.0 and suppresses none; see `../docs/native-android/dependency-review.md`.
That failure remains a release blocker. The JNI
feature is opt-in and the Flutter Rust Bridge dependency/import annotations are
compiled only for the optional retained `flutter-bridge` feature.

The required `android-native-preview.yml` CI lane is reproducible locally with
the commands below (inside `nix develop`). It uses strict Gradle dependency
locking and contains no signing-secret, emulator, physical-device, OSV, or
RustSec-network requirement:

```sh
cargo fmt --manifest-path rust/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path rust/Cargo.toml \
  --no-default-features --features android-jni --all-targets -- -D warnings
cargo test --locked --manifest-path rust/Cargo.toml \
  --no-default-features --features android-jni
cargo test --locked --manifest-path rust/Cargo.toml
tool/review-native-android-security.sh
tool/audit-native-dependencies.sh
tool/build-native-android.sh --debug
```

The final helper runs the locked Gradle JVM-test, lint, and debug-assembly
tasks and verifies the produced APK/native ABI invariants. Connected suites,
production signing, current vulnerability-database scans, and hardware checks
remain separate release evidence rather than flaky preview prerequisites.

The performance certifier is deliberately narrower than a product benchmark:
on exactly one emulator with production absent, it installs and clears only
`cash.pyx.app.nativepreview`, records preview/release APK byte sizes, five
Activity cold-start `am start -W` samples, and one `TOTAL PSS`/`TOTAL RSS`
sample after 10 idle seconds. It force-stops preview on exit and never claims a
Flutter comparison, first balance, QR latency, payment completion, or live
wallet/network performance. Run it only after rebuilding both current APKs.

The current bounded run passes at
`../build/performance-certification/20260903T092631Z.txt`: preview/release APK
sizes are 691,378,184/53,081,089 bytes; cold-start samples are 5092, 4551, 4866,
4250 and 3849 ms (mean 4521, median 4551); the single post-10-second idle sample
is 191,909 KiB PSS and 246,172 KiB RSS. These are observations without pass/fail
thresholds or a Flutter baseline and do not expand the narrow scope above.

```sh
tool/certify-native-android-performance.sh
```

The connected wrapper selects the correct JNI ABI, builds the debug artifact,
and runs `:app:connectedDebugAndroidTest`. It covers the packaged JNI smoke,
database lifecycle, deep-link delivery, configuration recreation, and Compose
instrumentation sources currently in the project. It does not prove an
install-over-Flutter upgrade, production signing continuity, real federation
network behavior, TalkBack quality, biometrics, camera policy, or the complete
device/API matrix. Activity recreation and fresh-object-graph tests do not
substitute for an OS killing and restarting the instrumentation process. Record
those external and hardware results separately in
`docs/native-android/release-checklist.md`.

## Preference durability

Ordinary preferences use AndroidX Preferences DataStore. The biometric policy,
camera-permission history, and pending seed-confirmation state migrate from
their legacy SharedPreferences files; seed confirmation awaits the atomic
DataStore update before wallet creation or entry continues.

Two deliberately narrow stores remain synchronous SharedPreferences commits:
the irreversible-operation journal must report durable success before native
submission, and the consume-once deep-link fingerprint must be durable before
routing exposes a payload. Both records are bounded and non-secret, write all
fields in one commit, and fail closed if that commit fails. Replacing either
with fire-and-forget DataStore work would reopen a process-death replay window.

## Migration boundary

The `main` release workflow publishes this lane as the signed Kotlin/Compose APK.
Do not remove the retained Flutter rollback/oracle sources or move this project
into `android/` until every critical upgrade and device gate is supported by
recorded evidence. Publishing a candidate is not an assertion that the remaining
cutover certification is complete.
