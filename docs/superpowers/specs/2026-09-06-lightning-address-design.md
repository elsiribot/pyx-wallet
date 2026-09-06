# Lightning addresses (lnaddrd) — design

Date: 2026-09-06
Status: approved by elsirion (placement, binding, discovery, auth model)
Mockups: claude.ai artifact "Pyx · Lightning address placement options"
(wireframes for the receive banner/claimed row, settings list + detail,
claim sheet).

## Goal

Let a Pyx user claim human-readable Lightning addresses
(`eric@pyx.cash`) that forward to the wallet's existing reusable LNURL,
using [lnaddrd](https://github.com/elsirion/lnaddrd) servers. Addresses
are owned by a seed-derived Nostr identity, so they survive wallet
recovery.

## Decisions (user-approved)

- **Placement:** Receive-screen entry point + durable Settings home. No
  home-feed card, no onboarding step.
- **Model:** an address *list*; each address is bound to one federation
  (its LNURL); multiple addresses per federation allowed; exactly one
  primary per federation; the selected federation's primary shows on the
  Receive screen.
- **Servers:** Nostr service discovery (lnaddrd microstandard 02,
  NIP-78 `kind:30078`, tag `t=lightning-address-service`) merged with a
  hardcoded default entry for `pyx.cash`, which is pre-selected in the
  claim sheet and works even when relays are unreachable.
- **Auth:** NIP-98 (microstandard 03) signed with an app-global Nostr
  key derived from the wallet seed. No reliance on bearer tokens.
- **Free-only v1:** paid names/tiers are shown disabled ("paid") in the
  claim sheet; the LUD-21 invoice flow is a follow-up.
- **UI lane:** native Compose (`android-native/`) only. The Flutter
  oracle is untouched.
- The npub is invisible plumbing in v1 (not surfaced in the UI).

## Key derivation

Per fedimint's `docs/secret_derivation.md`, top-level child 0 of
`global_root_secret` is the per-federation client tree and other indices
belong to the app. We define:

```
global_root_secret/<key-type=nostr=1>
  = Bip39RootSecretStrategy::<12>::to_root_secret(mnemonic)
      .child_key(ChildId(1))
      .to_secp_key(secp)
```

The keypair's x-only pubkey is the wallet's Nostr identity for lnaddrd.
One identity owns all addresses across all federations; ownership
(npub) and payment mapping (per-federation LNURL) are separate concerns.
The factory already holds the mnemonic (`rust/src/factory.rs`), so the
key is derivable without touching fedimint-client internals.

## lnaddrd API usage

All requests target a server *origin* from discovery or the default.
NIP-98 header: kind 27235 event, `u` = full request URL incl. query,
`method` tag, `payload` tag = lowercase-hex SHA-256 of the body iff a
body exists, `created_at` within ±60 s, base64 in
`Authorization: Nostr <b64>`; events are single-use.

| Action | Endpoint | Auth |
|---|---|---|
| Availability + price | `GET /api/v1/register/quote?domain&username` | none |
| Claim (free) | `POST /api/v1/register` `{domain, username, destination}` | NIP-98 (binds owner) |
| List owned (recovery) | `GET /api/v1/addresses` | NIP-98 required |
| Re-point LNURL | `PUT /lnaddress/update` `{domain, username, destination}` | NIP-98 |
| Release | `DELETE /lnaddress/remove` `{domain, username}` | NIP-98 |

`destination` is the federation's LNURL from the existing
`ConduitClient::lnurl()` (LNv2 via lnurl.fedimint.org; LNv1 via the
federation's recurringd) — both deterministic for a given seed +
federation, which recovery relies on.

The `management_token` returned once by register is stored in the local
record as an unused escape hatch but no flow depends on it.

Quote errors map to claim-sheet states: `taken`, `reserved`,
`unsupported_domain`, `length_disabled`, `rate_limited`;
`price_msat > 0` renders the name as "paid — not supported yet"
(disabled CTA).

## Discovery

Query a small hardcoded relay list for `kind:30078` events with tag
`t=lightning-address-service`, parse per microstandard 02 (validate
`schema:1`, required fields, domain rules; ignore unknown capabilities).
Only servers advertising `registration-api-v1` **and** `nostr-auth` are
offered. Results are cached in the app DB with fetch timestamp;
refreshed in the background when the claim sheet opens (stale cache is
shown immediately). The `pyx.cash` default entry is always present and
pre-selected. Pricing from announcements is informational; the live
quote is authoritative.

## Data model & storage

Address records live in the factory-level app database under a new
`DbKeyPrefix` (not inside fedimint-client's DB), keyed by
`(domain, username)`:

```
LnAddressRecord {
  domain, username,            // the claimed address
  server_origin,               // https origin that holds it
  federation_id: Option<...>,  // None = unassigned (post-recovery)
  destination,                 // LNURL last pushed to the server
  is_primary: bool,            // invariant: ≤1 per federation
  claimed_at,
  management_token: Option<String>, // stored, unused
}
```

Rust module `rust/src/lnaddr.rs` owns the records, the NIP-98 signer,
the HTTP client, and discovery; exposed over the existing JNI surface
(`rust/src/android/`) as snapshot + async request calls, mirroring the
current patterns (`SnapshotRequest`, `NativeBindings`).

Dependency note: prefer the `nostr` crate (event building/signing,
NIP-98 helper) plus a minimal relay fetch; `nostr-sdk` (what lnaddrd
uses) is acceptable if binary-size impact is tolerable. HTTP via the
reqwest/rustls stack already in the dependency tree.

## Sync & recovery

- **On wallet load (per federation):** if a record's `destination`
  differs from the federation's current `lnurl()`, `PUT` the update;
  failures retry silently on next load. Never block wallet startup.
- **After seed recovery / on Settings-addresses screen open:** re-derive
  the npub, call `GET /api/v1/addresses` against the default server plus
  discovered servers, and merge results into local records. Each
  recovered address's server-side `destination` is matched against the
  LNURLs of currently-joined federations; a match binds it, otherwise
  `federation_id = None` ("unassigned") and the user can re-point it
  from the detail view (which issues a `PUT` with the chosen
  federation's LNURL). First recovered/claimed address of a federation
  becomes its primary.
- **Caveat (accepted):** recovery only finds addresses on servers the
  wallet can see (default + currently-announced). A server that stopped
  announcing and isn't the default won't be searched.

## UI (native Compose)

- **Receive → Lightning tab (amountless):**
  - No primary address for the selected federation → accent-tinted
    banner under the LNURL code field: "Claim your Lightning address" →
    opens the claim sheet.
  - Primary exists → the address becomes the main share row
    (tap-to-copy + share), the raw LNURL demoted behind the share/copy
    icons. QR keeps encoding the LNURL.
- **Settings:** new "Lightning addresses" row → new `WalletRoute`
  screen (distinct from the existing on-chain `ADDRESSES` route):
  addresses listed grouped by federation (plus an "Unassigned" group
  after recovery), star marks the primary, "+" opens the claim sheet.
  Tapping a row opens a detail view: address, status, copy/share, make
  primary, re-point (for unassigned), release.
- **Claim sheet** (shared; transient state only, nothing
  payload-bearing enters saved navigation state, per the existing
  routing rule): server/domain picker (discovered + default,
  pre-selected `pyx.cash`) → name field with debounced quote check
  (same debounce pattern as receive) → availability/price/error line →
  "Claim name@domain" CTA. The claiming federation is the currently
  selected wallet.
- **Release** is confirmed via a modal (same pattern as
  `ADDRESS_MUTATION`); primary reassignment on release: oldest
  remaining address of that federation, else none.

## Error handling

- Claim sheet: inline errors for taken/reserved/invalid/paid/rate-
  limited; network failure → retryable error state.
- Discovery failure → default server only (no error surfaced beyond the
  shorter list).
- Update/release failures surface as snackbar-level errors in Settings;
  sync failures are silent-with-retry.
- NIP-98 requires roughly-correct client clock (±60 s); a 401 on an
  otherwise-valid request surfaces as "check your device clock" hint.

## Testing

- **Rust:** unit tests for record invariants (single primary), NIP-98
  event construction verified against lnaddrd's verification rules
  (`u`/`method`/`payload`/kind/freshness), announcement parsing
  (valid/invalid vectors from microstandard 02), and HTTP client against
  a mocked server. Optional CI integration test spinning up a real
  lnaddrd binary.
- **Kotlin:** presentation objects (claim-sheet state machine, quote →
  UI state mapping, primary resolution, receive-row selection) as plain
  unit-tested classes, like `LnurlQuotePresentation`.
- **Screenshots:** `ScreenshotFixtureActivity` fixtures for
  receive-with-banner, receive-with-address, claim sheet, settings list;
  verified via the existing proto_shot/app_shot/compare loop where the
  prototype has an equivalent, visually reviewed otherwise.

## Out of scope (v1)

- Paid registration (LUD-21 invoice flow), including paying from the
  wallet itself.
- Surfacing the npub / Nostr identity in the UI.
- lnaddrd's encrypted Nostr backup records (microstandard 01) — the
  NIP-98 owned-addresses listing is our recovery mechanism.
- Flutter-lane parity.
- iOS.
