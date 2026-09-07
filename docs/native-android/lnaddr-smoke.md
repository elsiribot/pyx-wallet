# Lightning address — end-to-end smoke against a real `lnaddrd`

This document records a live, manual end-to-end run of the Lightning-address
feature (rust `lnaddr` module → JNI → Kotlin/Compose) against a real `lnaddrd`
server, on a real Android device image. It is observation evidence for one
device and one server instance; it is not a substitute for the deterministic
unit/instrumentation suites, and it was not run in CI. Nothing here is
simulated: every screen state below came from the packaged debug APK talking to
a `lnaddrd` process over HTTP, and every server-side quote is a live query
against that server's SQLite database or its HTTP API.

## 1. Environment

| Component | Value |
|---|---|
| App | `cash.pyx.app.nativepreview`, `Pyx 0.6.0-debug (38)`, branch `lnaddr` at `4c96ad1` |
| Device | redroid container `pyx`, `10.88.0.13:5555`, 780×1688 @ 320 dpi (390×844 dp), Android 13 / SDK 33 |
| Server | `lnaddrd` at `9b9c164` (github.com/elsirion/lnaddrd), `cargo build` (debug), bound `127.0.0.1:8080` |
| Served domain | `pyx.test` (RFC 6761 reserved TLD — unmistakably a test domain, never a real one) |
| Payment policy | none configured → `GET /api/v1/register/quote` answers `{"price_msat":0}`, i.e. free registration |
| Nostr relay | a throwaway in-memory NIP-01 relay on `ws://127.0.0.1:7777` (stdlib Python, scratch dir) — `lnaddrd` refuses to initialize unless at least one relay acknowledges its backup event; nothing was written to any public relay |
| Wallet federation | E-Cash Club (`fed11qgqzggnhwden5te0v9cxjtn9vd3jue3wvfkxjmnyva6kzunyd9skutnwv46z7…`), joined by `fedimint:` deep link |

### Transport: why `http://127.0.0.1:8080`

The device reaches the server through `adb reverse tcp:8080 tcp:8080`, so the
origin the app requests is `http://127.0.0.1:8080`. Two alternatives were
rejected:

- **Direct `http://10.88.0.1:8080` over the podman bridge.** The device can
  reach the host (ICMP and TCP/22 both succeed from the container), but the
  NixOS host firewall drops inbound TCP/8080 on `podman0`
  (`nixos-fw` accepts only 22, 60000-61000/udp and ICMP echo, then
  `nixos-fw-log-refuse`). Using it would have meant editing the host firewall;
  `adb reverse` needs no host change at all.
- **A TLS reverse proxy.** The wallet's `reqwest` client is built with
  `rustls-tls` (`rust/Cargo.toml:51`), which trusts the bundled webpki root
  set, so a self-signed or locally-issued certificate cannot be made
  trusted — not by installing a CA into the Android trust store, and not
  without weakening the client's TLS verification, which was out of scope.

Android's `usesCleartextTraffic=false` (asserted by
`tool/build-native-android.sh`) is a platform policy consulted by Java/OkHttp
networking; the wallet's HTTP for this feature is issued by the Rust
`reqwest`/hyper stack, which does not consult `NetworkSecurityPolicy`, so
cleartext to the loopback forwarder works without any manifest change. **This
is exactly why the override is debug-only:** it exists to reach a plaintext
loopback service, which a shipped build must never do.

### Server-side accommodation (documented, local clone only)

NIP-98 verification in `lnaddrd` builds the expected `u` tag as
`normalized_origin(LNADDRD_PUBLIC_BASE_URL) + path_and_query`
(`src/api_v1.rs:53-81`), and `normalized_origin` requires an `https` scheme
(`src/nostr/announcement.rs:84`). With no way to give the device a trusted TLS
origin (above), the *only* way to make the server's expected `u` equal the URL
the client actually requests was to let the configured origin be `http`. One
line was relaxed in the scratch clone:

```diff
-    ensure!(url.scheme() == "https", "Public base URL must use HTTPS");
+    ensure!(
+        url.scheme() == "https" || url.scheme() == "http",
+        "Public base URL must use HTTPS"
+    );
```

Scope of that change: **only** the scheme constraint on the operator-configured
origin string. The NIP-98 path itself is untouched — the server still verifies
the event signature, the `u`/`method`/`payload` binding, the ±60 s clock-skew
window and the replay guard (`src/nostr/http_auth.rs`), and the client still
signs the exact URL it requests. The patch lives only in the scratch clone at
`/tmp/…/scratchpad/lnaddrd`; nothing was pushed anywhere, and no pyx-wallet
code depends on it.

Side effect worth recording: because `http://127.0.0.1:8080` is not a public
host, `lnaddrd` logged `Origin or domain is not public, skipping service
announcement` and published no NIP-78 announcement. That is correct behaviour
and did not affect the test — the wallet always offers its built-in server
entry without needing an announcement, which is precisely the entry the debug
override replaces.

## 2. The debug override, and how it is release-gated

`rust/src/lnaddr/discovery.rs` gained `configured_default_server()`, which
returns `DEFAULT_SERVER` (`https://pyx.cash` / `pyx.cash`) unless **all** of the
following hold:

1. the crate is compiled with the non-default cargo feature
   `lnaddr-debug-server` (`rust/Cargo.toml`), and
2. both `PYX_LNADDR_DEBUG_ORIGIN` and `PYX_LNADDR_DEBUG_DOMAIN` were set in the
   **build** environment (read with `option_env!`, i.e. at compile time).

There is no runtime input: even a debug APK reads nothing from the environment,
the filesystem, or an intent, so the hook adds no new input surface on device.

`tool/build-native-android.sh` refuses the combination for release builds,
before any build step runs:

```
$ PYX_LNADDR_DEBUG_ORIGIN=http://x PYX_LNADDR_DEBUG_DOMAIN=y tool/build-native-android.sh --release
PYX_LNADDR_DEBUG_ORIGIN/PYX_LNADDR_DEBUG_DOMAIN cannot be used with --release
exit=1
$ PYX_LNADDR_DEBUG_ORIGIN=http://x tool/build-native-android.sh --debug
PYX_LNADDR_DEBUG_ORIGIN and PYX_LNADDR_DEBUG_DOMAIN must be set together
```

So a release artifact contains neither the override value nor the code that
reads it: `--release` never passes `lnaddr-debug-server`, and without that
feature the `option_env!` calls are not compiled at all. A unit test
(`discovery::tests::built_in_server_is_the_default_without_the_debug_feature`)
pins that the built-in entry is exactly `DEFAULT_SERVER` in every build that
ships, and is `#[ignore]`d only when the debug feature is on.

The APK under test was built with:

```
nix develop -c bash -c 'PYX_ABI_X86_64=1 \
  PYX_LNADDR_DEBUG_ORIGIN=http://127.0.0.1:8080 \
  PYX_LNADDR_DEBUG_DOMAIN=pyx.test \
  tool/build-native-android.sh --debug'
```

which prints `WARNING: baking debug lnaddrd override http://127.0.0.1:8080
(pyx.test)`, and the string is observable in the packaged library
(`strings jniLibs/{arm64-v8a,x86_64}/libpyx.so | grep http://127.0.0.1:8080` →
one hit each). After the run the tree was rebuilt without the override.

## 3. Walkthrough — PASS

Fresh wallet: app data cleared, wallet created in-app, recovery words recorded,
E-Cash Club joined by deep link. The wallet's Lightning receive code resolved
via the LNv1 recurringd fallback (E-Cash Club advertises the lnv2 module but
registers no lnv2 gateways — the known gotcha), which is what makes the
destination below a `recurringd.ctrb.io/lnv1/paycodes/…` URL.

### (a) Claim from the receive-screen banner — PASS

Receive → Lightning, amountless: the claim banner rendered under the reusable
LNURL (`lnaddr_smoke_a1_receive_banner.png`). Tapping **Claim** opened the sheet
with four domains — `pyx.test` (the overridden built-in entry, preselected) plus
`lnaddr.dev` / `lnaddr.net` / `lnaddr.org`, which are the *real* announcements
the wallet discovered from the live public relays, so relay discovery was
exercised at the same time. Typing `eric` produced **"This name is available"**
from a live `GET /api/v1/register/quote?domain=pyx.test&username=eric`
(`lnaddr_smoke_a2_claim_sheet.png`). Tapping **Claim eric@pyx.test** succeeded.

Server-side confirmation, immediately after:

```
$ curl http://127.0.0.1:8080/lnaddress/pyx.test/eric
{"url":"https://recurringd.ctrb.io/lnv1/paycodes/09ff74967982b9e7e34da953757bfc0c65daaeb4f9fe638333fc8d420c6c35a2"}

sqlite> select id,username,domain,state,owner_pubkey from payment_addresses;
1|eric|pyx.test|active|5ea1b7b337ea0f2d14cedc49b45984e24d8a8bb38c5913da5eed4102442698f9
```

The stored `destination` is byte-identical to the `lnurl1dp68…z7czf` string the
receive screen was showing, and `owner_pubkey` is the wallet's seed-derived
Nostr key (`identity.rs`: `global_root_secret/child_key(ChildId(1))`) — the
server bound ownership from the NIP-98 signature, not from anything the client
asserted in the body.

### (b) Receive shows the address; settings lists it as primary — PASS

The receive screen replaced the code field with the address row
`eric` `@pyx.test` plus copy/share and the "Show LNURL" toggle
(`lnaddr_smoke_b1_receive_claimed.png`). Settings → Lightning address shows
`eric@pyx.test` as the row value; the Lightning addresses screen lists it under
the **E-Cash Club** group with the primary star
(`lnaddr_smoke_b2_settings_list.png`; uiautomator reports the star node's
`content-desc="Primary"`).

### (c) Kill and restart — records persist — PASS

`am force-stop cash.pyx.app.nativepreview` (process confirmed gone via `ps -A`),
then relaunch. Settings still reads `eric@pyx.test`, and the Lightning addresses
screen still groups it under E-Cash Club with the primary star
(`lnaddr_smoke_c1_after_restart.png`). No re-claim, no network round trip was
required to render it.

### (d) Release, then claim again — PASS

The address detail sheet reported `Active · forwarding to E-Cash Club`
(`lnaddr_smoke_d1_detail_sheet.png`). **Release address… → Release** emptied the
list ("No lightning addresses yet", `lnaddr_smoke_d2_released_empty.png`) and
the server agreed:

```
GET /lnaddress/pyx.test/eric -> 404
sqlite> select id,username,domain,state from payment_addresses;   -- (no rows)
```

Claiming `eric` again from the settings "+" quoted available and succeeded
(`lnaddr_smoke_d3_reclaimed.png`), producing a **new** server row with the same
owner:

```
sqlite> select id,username,domain,state,owner_pubkey,revision from payment_addresses;
2|eric|pyx.test|active|5ea1b7b337ea0f2d14cedc49b45984e24d8a8bb38c5913da5eed4102442698f9|1
```

### (e) Restore from seed → `recover` finds the address — PASS

This is the point of the seed-derived Nostr identity, so it was tested the
hard way: `pm clear cash.pyx.app.nativepreview` (the wallet database, the
lnaddr records and every trace of the claim are gone from the device), then
**Restore wallet** with the same 12 recovery words, into an app with **no
federation joined at all**.

Opening Settings → Lightning address (which runs `lnaddrRecoverAsync`, i.e. the
NIP-98-signed `GET /api/v1/addresses` against the built-in server) listed
`eric@pyx.test` under **Unassigned** (`lnaddr_smoke_e1_recovered_unassigned.png`).
The only thing the restored app had was the mnemonic; the address came back
because the re-derived Nostr key produced a NIP-98 event the server recognised
as the same owner (`5ea1b7b3…`). Recovery is correctly *unassigned*: at that
moment the wallet had no client, so `recover`'s `known` list was empty and there
was no destination to match against.

Rejoining E-Cash Club and using **Assign to this wallet** on the recovered row
(`lnaddr_smoke_e2_unassigned_sheet.png`) re-pointed it: the row moved under the
E-Cash Club group with the primary star (`lnaddr_smoke_e3_repointed_primary.png`),
the server's revision advanced (`revision` 1 → 2), and the receive screen shows
`eric@pyx.test` again (`lnaddr_smoke_e4_receive_after_restore.png`).

Worth noting: the destination stored after the repoint is byte-identical to the
pre-wipe one (same `…/lnv1/paycodes/09ff7496…` paycode), i.e. the recurringd
payment code is itself seed-stable across a full restore.

## 4. Artifacts

Screenshots are in `docs/design/shots/app/` (gitignored at `.gitignore:78`, so
they are on-disk review artifacts, not committed):

```
lnaddr_smoke_a1_receive_banner.png        (a) banner on the receive screen
lnaddr_smoke_a2_claim_sheet.png           (a) sheet, pyx.test selected, "available"
lnaddr_smoke_b1_receive_claimed.png       (b) address row replaces the code field
lnaddr_smoke_b2_settings_list.png         (b) primary under "E-Cash Club"
lnaddr_smoke_c1_after_restart.png         (c) same list after force-stop + relaunch
lnaddr_smoke_d1_detail_sheet.png          (d) detail sheet before release
lnaddr_smoke_d2_released_empty.png        (d) empty list after release
lnaddr_smoke_d3_reclaimed.png             (d) re-claimed
lnaddr_smoke_e1_recovered_unassigned.png  (e) recovered after seed restore, Unassigned
lnaddr_smoke_e2_unassigned_sheet.png      (e) "Assign to this wallet"
lnaddr_smoke_e3_repointed_primary.png     (e) bound + primary again
lnaddr_smoke_e4_receive_after_restore.png (e) receive screen after restore
```

The seed-entry screen could not be captured: `screencap` returns a zero-byte
file there, which is the app's `FLAG_SECURE` on sensitive screens working as
intended.

## 5. Scope limits and things this run did not prove

- **One device, one server, one federation.** redroid x86_64 / Android 13, a
  debug `lnaddrd` on loopback, E-Cash Club. No coverage of arm64 hardware, of a
  TLS origin, or of a federation with live lnv2 gateways.
- **No payment was made to the address.** The mapping was verified to resolve
  (`GET /lnaddress/pyx.test/eric` returns the wallet's LNURL) but no sender
  actually paid `eric@pyx.test`, so the LNURL-forwarding hop end-to-end is
  untested here.
- **No paid registration.** The server ran with no payment policy, so the
  priced/`payment_required` branches of quote and register were not exercised
  against a real server.
- **Cleartext loopback origin, not HTTPS.** The NIP-98 signing/verification
  path was exercised in full, but not over TLS, and against a server whose
  origin-scheme check was relaxed as documented in §1.
- **Not automated.** This was driven by hand over `adb`/uiautomator; there is no
  CI job that repeats it.
