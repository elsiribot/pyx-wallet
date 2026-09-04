# Native dependency review

Run the locked inventory from the repository Nix shell:

```sh
nix develop -c tool/audit-native-dependencies.sh
```

The command records the resolved release-runtime Android graph and the complete
Cargo metadata graph under `build/dependency-audit/`. It fails when a
third-party Rust package has no declared license expression. Reports contain
package coordinates and source identifiers only; they do not contain wallet or
signing data.

This inventory is not vulnerability certification. Android dependency locking
is enabled for all configurations; `android-native/app/gradle.lockfile` commits
the exact release-runtime selection reviewed below. Refresh lock state only as
an explicit, reviewed dependency change with `--write-locks`.

Run the fail-closed Android OSV gate:

```sh
nix develop -c tool/audit-android-vulnerabilities.sh
```

The script exports only resolved external Maven modules from
`releaseRuntimeClasspath`, proves that set exactly matches the committed lock
entries, and submits every coordinate to OSV's current Maven advisory API. A
missing/partial lock, resolution error, network error, malformed or incomplete
response, or any advisory fails the command. There is no ignore list or
severity threshold. The 2026-09-02 run passed all 165 components with zero
findings and zero suppressions: report
`build/vulnerability-audit/20260903T084111Z-android-osv.txt`, lock SHA-256
`adf692c10d89ac4573869bfc9301d3f5ea2325f6eb60802c5c35302b8934d448`.

Run the fail-closed RustSec gate separately:

```sh
nix shell nixpkgs#cargo-audit -c tool/audit-rust-vulnerabilities.sh
```

The script scans the exact `rust/Cargo.lock`, fetches the current RustSec
advisory database, records the lockfile hash and scanner version under
`build/vulnerability-audit/`, and propagates the scanner failure. It contains no
ignore list or advisory suppression. A failing report is evidence that the scan
ran, not cutover approval.

On 2026-09-02, compatible locked updates remediated three findings without a
Fedimint API migration:

| Advisory | Compatible locked update |
| --- | --- |
| `RUSTSEC-2026-0204` | `crossbeam-epoch` 0.9.18 -> 0.9.20 |
| `RUSTSEC-2026-0258` | `h2` 0.4.13 -> 0.4.16 |
| `RUSTSEC-2026-0185` | `quinn-proto` 0.11.14 -> 0.11.15 |

The current lockfile still fails with six vulnerabilities:

- `hickory-proto` 0.25.2: `RUSTSEC-2026-0118` and
  `RUSTSEC-2026-0119`. It is selected by `hickory-resolver` 0.25.2 through
  Iroh 0.35.0. The former has no fixed release; the latter requires 0.26.1.
- `rustls-webpki` 0.102.8: `RUSTSEC-2026-0104`,
  `RUSTSEC-2026-0099`, `RUSTSEC-2026-0049`, and
  `RUSTSEC-2026-0098`. It is selected by Iroh/Iroh Relay 0.35.0 and the fixes
  require the 0.103 line.

Both dependency paths originate in the Fedimint 0.11.1 graph. Resolving them
requires a reviewed Fedimint/Iroh upgrade or upstream backport and corresponding
wallet/network compatibility testing; it is deliberately outside a compatible
lockfile refresh. The scan also reports 16 unmaintained, unsound, or yanked
warnings. They remain visible for release triage and are not suppressed.

Before cutover, the Rust gate must pass against a current database. Both scans
must be rerun close to release; an offline dependency listing, cached PASS, or
stale advisory result must not close either gate.
