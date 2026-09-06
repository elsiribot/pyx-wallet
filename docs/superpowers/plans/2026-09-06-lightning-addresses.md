# Lightning Addresses (lnaddrd) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Users claim human-readable Lightning addresses (`name@pyx.cash`) on lnaddrd servers, owned by a seed-derived Nostr key (NIP-98), bound per federation with one primary shown on the Receive screen and managed from Settings.

**Architecture:** A new `rust/src/lnaddr/` module owns storage (factory-level DB, prefix `0x0A`), the seed-derived Nostr identity, NIP-98 signing, the lnaddrd HTTP client (transport behind a trait for tests), and NIP-78 relay discovery. It is exposed through the existing JNI snapshot/async-request plumbing to the native Compose UI (claim sheet, settings screen, receive-row integration). Spec: `docs/superpowers/specs/2026-09-06-lightning-address-design.md`. Protocol references live in the lnaddrd repo (`docs/protocol/02-service-announcements.md`, `03-registration-api.md`) — clone `https://github.com/elsirion/lnaddrd` to a scratch dir when exact wire behavior is needed.

**Tech Stack:** Rust (fedimint 0.11 stack, reqwest/rustls, `bitcoin::secp256k1`, new dep `tokio-tungstenite` for relay websockets), JNI (`rust/src/android/`), Kotlin/Compose (`android-native/`).

## Global Constraints

- Native Android lane only; do not touch the Flutter UI (`lib/`) or regenerate FRB bindings.
- New `DbKeyPrefix` entries are append-only; `LnAddress = 0x0A` and nothing else changes in that enum.
- All Compose colors/typography come from `cash.pyx.app.ui.theme` (`PyxOrange`, `PyxSurface`, `PyxType`, …) — no raw hex in UI code.
- `cargo fmt` + `cargo test` (in `rust/`) and `./gradlew :app:testDebugUnitTest` (in `android-native/`) must pass before every commit. Rust commands need `nix develop` (see memory: pyx-wallet-build).
- Free registrations only; a quote with `price_msat > 0` renders a disabled "paid" state, never a claim attempt.
- Nothing payload-bearing enters saved navigation state (existing routing rule); claim-sheet state is transient Compose state.
- Default server constant: origin `https://pyx.cash`, domain `pyx.cash` (confirm the deployment origin with elsirion before release; keep it a single named const).
- Commit trailer for every commit:
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_017ZHT8aYw3qpgcwXyXyhxY2`

---

### Task 1: DB records + store with primary invariant

**Files:**
- Modify: `rust/src/db.rs` (append `LnAddress = 0x0A` to `DbKeyPrefix`; add key/record types)
- Create: `rust/src/lnaddr/mod.rs`, `rust/src/lnaddr/store.rs`
- Modify: `rust/src/lib.rs` (add `pub(crate) mod lnaddr;`)

**Interfaces:**
- Consumes: `crate::db::DbKeyPrefix`, `fedimint_core::db::Database`, `FederationId`.
- Produces (used by Tasks 4–6):

```rust
pub(crate) struct LnAddressRecord {
    pub domain: String,
    pub username: String,
    pub server_origin: String,          // normalized https origin, no trailing slash
    pub federation_id: Option<FederationId>, // None = unassigned (post-recovery)
    pub destination: String,            // LNURL last pushed to the server
    pub is_primary: bool,               // invariant: ≤1 per Some(federation_id)
    pub claimed_at_secs: u64,
    pub management_token: Option<String>, // stored, unused escape hatch
}
pub(crate) struct LnAddressStore { db: Database }
impl LnAddressStore {
    pub fn new(db: Database) -> Self;
    pub async fn list(&self) -> Vec<LnAddressRecord>;
    pub async fn get(&self, domain: &str, username: &str) -> Option<LnAddressRecord>;
    /// Inserts/replaces. If record.is_primary, clears is_primary on every other
    /// record with the same federation_id. If it's the federation's first
    /// record, forces is_primary = true.
    pub async fn upsert(&self, record: LnAddressRecord);
    pub async fn set_primary(&self, domain: &str, username: &str) -> Result<(), String>;
    /// Removes; if it was primary, promotes the oldest (claimed_at) remaining
    /// record of the same federation.
    pub async fn remove(&self, domain: &str, username: &str);
    pub async fn primary_for(&self, federation: &FederationId) -> Option<LnAddressRecord>;
}
```

DB shape in `db.rs` (mirror `PendingOperationRecord`, `Encodable`/`Decodable`, `impl_db_record!`/`impl_db_lookup!`):

```rust
#[derive(Clone, Debug, Encodable, Decodable)]
pub(crate) struct LnAddressKey(pub(crate) String, pub(crate) String); // (domain, username)
#[derive(Clone, Debug, Encodable, Decodable)]
pub(crate) struct LnAddressPrefix;
impl_db_record!(key = LnAddressKey, value = LnAddressRecord, db_prefix = DbKeyPrefix::LnAddress);
impl_db_lookup!(key = LnAddressKey, query_prefix = LnAddressPrefix);
```

(`LnAddressRecord` itself lives in `lnaddr/store.rs` and derives `Encodable, Decodable`; `db.rs` just re-uses it — follow whichever import direction keeps `db.rs` free of business logic, e.g. define record + keys in `store.rs` and only the enum variant in `db.rs`.)

- [ ] **Step 1: Write failing tests** in `rust/src/lnaddr/store.rs` `#[cfg(test)]` using `fedimint_core::db::mem_impl::MemDatabase` (grep the fedimint checkout for `MemDatabase::new` usage if the constructor signature is unclear: `Database::new(MemDatabase::new(), Default::default())`). Test cases, each a `#[tokio::test]`:
  - `first_record_of_federation_becomes_primary` — upsert with `is_primary: false`, read back `is_primary == true`.
  - `second_primary_demotes_first` — two records same federation, second upserted with `is_primary: true`; first reads back `false`.
  - `set_primary_switches` and `set_primary_unknown_errors`.
  - `remove_primary_promotes_oldest` — three records, remove primary, oldest remaining is primary.
  - `unassigned_records_never_primary` — `federation_id: None` stays `is_primary: false` even if requested.
- [ ] **Step 2: Run** `cargo test -p conduit lnaddr::` — expect compile failure / test failures.
- [ ] **Step 3: Implement** the enum variant, record, keys, and `LnAddressStore` (single `dbtx` per method, `commit_tx` at the end, mirroring `factory.rs` usage of `IDatabaseTransactionOpsCoreTyped`).
- [ ] **Step 4: Run** `cargo test -p conduit lnaddr::` — expect PASS; run `cargo test` for no regressions.
- [ ] **Step 5: Commit** `feat(lnaddr): address store with per-federation primary invariant`.

---

### Task 2: Seed-derived Nostr keypair

**Files:**
- Create: `rust/src/lnaddr/identity.rs`
- Modify: `rust/src/factory.rs` (expose the derivation), `rust/src/lnaddr/mod.rs`

**Interfaces:**
- Consumes: `Bip39RootSecretStrategy::<12>::to_root_secret(&mnemonic)` (already used at `factory.rs:184`), `fedimint_derive_secret::{ChildId, DerivableSecret}` (crate `fedimint-derive-secret`, add to `rust/Cargo.toml` as `fedimint-derive-secret = "0.11.0"` if not already a transitive-visible dep).
- Produces:

```rust
/// global_root_secret/<key-type=nostr=1> per the spec.
pub(crate) fn nostr_keypair(mnemonic: &fedimint_bip39::Mnemonic) -> bitcoin::secp256k1::Keypair;
/// lowercase hex x-only pubkey (64 chars) — the wallet's npub in hex form.
pub(crate) fn nostr_pubkey_hex(keypair: &Keypair) -> String;
// On ConduitClientFactory:
pub(crate) fn nostr_keypair(&self) -> Keypair; // delegates with self.mnemonic
```

- [ ] **Step 1: Failing test** in `identity.rs`: derive from the BIP-39 test mnemonic `"abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"`; assert (a) derivation is deterministic across two calls, (b) pubkey hex is 64 lowercase hex chars, (c) the keypair differs from `child_key(ChildId(0))`'s (no collision with the per-federation tree).
- [ ] **Step 2: Run** `cargo test -p conduit identity` — FAIL.
- [ ] **Step 3: Implement** using `DerivableSecret::child_key(ChildId(1)).to_secp_key(secp)` with a `Secp256k1::new()` context. Once it passes, capture the derived pubkey hex for the test mnemonic and pin it in the test as a golden value (guards against accidental path changes forever).
- [ ] **Step 4: Run** tests — PASS.
- [ ] **Step 5: Commit** `feat(lnaddr): seed-derived nostr identity (root/child(1))`.

---

### Task 3: NIP-98 auth events

**Files:**
- Create: `rust/src/lnaddr/nip98.rs`

**Interfaces:**
- Consumes: `nostr_keypair` (Task 2), `bitcoin::hashes::sha256`, `serde_json`.
- Produces:

```rust
/// Returns the value for `Authorization`: "Nostr <base64(event-json)>".
/// `body` = raw request bytes; None for bodyless (GET/DELETE-without-body).
pub(crate) fn nip98_header(
    keypair: &Keypair, url: &str, method: &str, body: Option<&[u8]>, created_at_secs: u64,
) -> String;
```

Event rules (from lnaddrd `docs/protocol/03-registration-api.md`): kind `27235`, tags `["u", <full url incl. query>]`, `["method", <UPPERCASE method>]`, and `["payload", <lowercase hex sha256(body)>]` **iff** body is `Some` (a payload tag on a bodyless request is rejected server-side). Event id = sha256 of the canonical NIP-01 serialization `[0, pubkey_hex, created_at, kind, tags, content]` with `content: ""` (serde_json with no whitespace — `serde_json::to_string` of a tuple gives exactly that). Sign with `secp.sign_schnorr_no_aux_rand(&Message::from_digest(id), &keypair)`. Header = `"Nostr "` + standard **padded** base64 (`base64` crate is already in the tree via deps; if not directly usable, add `base64 = "0.22"`).

- [ ] **Step 1: Failing tests**:
  - `bodyless_event_shape` — decode the base64 back to JSON; assert kind, `u`, `method` tags present, **no** `payload` tag, `content == ""`.
  - `body_event_has_payload_hash` — known body `b"{}"`, assert payload tag equals the sha256 hex of `{}`.
  - `signature_verifies` — recompute the id from the decoded event fields and `secp.verify_schnorr` against the event's `pubkey`.
  - `id_matches_nip01_serialization` — recompute id independently in the test and compare with the event's `id` field.
- [ ] **Step 2: Run** `cargo test -p conduit nip98` — FAIL.
- [ ] **Step 3: Implement** (build tags vec conditionally, serialize, hash, sign, emit full event JSON with `id`, `pubkey`, `created_at`, `kind`, `tags`, `content`, `sig`).
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(lnaddr): NIP-98 auth event construction`.

---

### Task 4: lnaddrd HTTP client (transport behind a trait)

**Files:**
- Create: `rust/src/lnaddr/api.rs`

**Interfaces:**
- Consumes: `nip98_header` (Task 3).
- Produces:

```rust
#[async_trait::async_trait] // async-trait: add dep if absent (fedimint tree already carries it)
pub(crate) trait HttpTransport: Send + Sync {
    async fn execute(&self, method: &str, url: &str, headers: Vec<(String, String)>,
                     body: Option<Vec<u8>>) -> Result<(u16, Vec<u8>), String>;
}
pub(crate) struct ReqwestTransport; // impl over the crate's existing reqwest

pub(crate) struct LnaddrApi<T: HttpTransport> { transport: T, keypair: Keypair }
pub(crate) enum QuoteResult { Free, PricedMsat(u64), Taken, Reserved, Invalid(String), RateLimited }
pub(crate) struct RegisterOk { pub address: String, pub management_token: Option<String>, pub active: bool }
pub(crate) struct OwnedAddress { pub domain: String, pub username: String, pub destination: String }
impl<T: HttpTransport> LnaddrApi<T> {
    pub async fn quote(&self, origin: &str, domain: &str, username: &str) -> Result<QuoteResult, String>;
    /// NIP-98-signed; server binds owner to our pubkey.
    pub async fn register_free(&self, origin: &str, domain: &str, username: &str, destination: &str)
        -> Result<RegisterOk, String>;
    pub async fn list_owned(&self, origin: &str) -> Result<Vec<OwnedAddress>, String>;
    pub async fn update_destination(&self, origin: &str, domain: &str, username: &str, destination: &str)
        -> Result<(), String>;
    pub async fn remove(&self, origin: &str, domain: &str, username: &str) -> Result<(), String>;
}
```

Wire mapping (endpoints per protocol doc 03): `GET {origin}/api/v1/register/quote?domain=&username=` (unauthenticated; url-encode with the existing `strict_uri_encode` from `rust/src/lnurl.rs` — make it `pub(crate)`); `POST {origin}/api/v1/register` body `{"domain","username","destination"}` + NIP-98; `GET {origin}/api/v1/addresses` + NIP-98 (required); `PUT {origin}/lnaddress/update` body `{"domain","username","destination"}` + NIP-98 (errors are bare status codes, no JSON); `DELETE {origin}/lnaddress/remove` body `{"domain","username"}` + NIP-98, expect `204`. Error JSON on `/api/v1` is `{"error": "<code>"}`; map `taken`/`reserved`/`payment_required`→`PricedMsat` cases and 401 → `Err("unauthorized — check your device clock")` (the ±60 s NIP-98 freshness window is the common cause).

- [ ] **Step 1: Failing tests** with a `FakeTransport` (records the request, returns a canned response):
  - `quote_free`, `quote_priced`, `quote_taken` — parse `{"price_msat":0}`, `{"price_msat":100000}`, `{"error":"taken"}`.
  - `register_sends_nip98_and_parses` — assert the `Authorization` header starts with `Nostr `, decodes to an event whose `u` tag equals the exact URL and whose `payload` tag matches the body hash; parse `{"address":"a@d","management_token":"t","active":true}`.
  - `list_owned_requires_auth_header` + parses the `{"addresses":[...]}` shape.
  - `update_maps_400_and_401`, `remove_expects_204`.
- [ ] **Step 2: Run** `cargo test -p conduit lnaddr::api` — FAIL.
- [ ] **Step 3: Implement**, including `ReqwestTransport` (10 s timeout, rustls; no test needed beyond compilation — it is a thin adapter).
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(lnaddr): lnaddrd registration API client`.

---

### Task 5: NIP-78 service discovery

**Files:**
- Create: `rust/src/lnaddr/discovery.rs`
- Modify: `rust/Cargo.toml` (add `tokio-tungstenite = { version = "0.24", features = ["rustls-tls-webpki-roots"] }`; check the lockfile builds inside `nix develop` — if the version clashes with the pinned tree, pick the nearest compatible)

**Interfaces:**
- Produces:

```rust
pub(crate) struct DiscoveredServer {
    pub origin: String, pub name: String, pub domains: Vec<String>,
    pub nostr_auth: bool, pub registration_api: bool,
    /// informational only; live quote is authoritative
    pub free_domains: Vec<String>,
}
pub(crate) const DEFAULT_SERVER: (&str, &str) = ("https://pyx.cash", "pyx.cash");
pub(crate) const DEFAULT_RELAYS: [&str; 3] =
    ["wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net"];
/// Pure parser: one relay EVENT json -> validated server (None = reject).
pub(crate) fn parse_announcement(event_json: &serde_json::Value) -> Option<DiscoveredServer>;
/// Queries relays (5s budget), merges + dedupes by origin (newest created_at wins),
/// verifies event signatures, always prepends the DEFAULT_SERVER entry.
pub(crate) async fn discover(relays: &[&str]) -> Vec<DiscoveredServer>;
```

Announcement rules (protocol doc 02): kind `30078`, tag `t == "lightning-address-service"`, `d == "lnaddrd:service:v1:" + origin`; content JSON requires `schema == 1`, `origin`, `domains`, `registration_url`, `capabilities`; every advertised URL same origin; domain labels lowercase `a-z0-9-`, ≥2 labels, final label not all-digits and not in `{localhost, local, internal, test, invalid, example}`; reject non-conforming. `free_domains` = domains whose pricing tiers include a `max_length: 64`-ish tier with `price == 0`, or domains with no pricing entry. Only servers with both `registration-api-v1` and `nostr-auth` in the announcement's capability strings are offered (the capability strings live in doc 03's Discovery section; the content `capabilities` field of doc 02 carries them).

Relay query: open wss, send `["REQ","pyx",{"kinds":[30078],"#t":["lightning-address-service"]}]`, collect `["EVENT",...]` until `["EOSE","pyx"]` or the 5 s budget, close. Signature check: recompute NIP-01 id and `verify_schnorr` (reuse the id-serialization helper from Task 3 — extract it into `nip98.rs` as `pub(crate) fn nostr_event_id(pubkey,&created_at,kind,&tags,content) -> [u8;32]`).

- [ ] **Step 1: Failing tests** for `parse_announcement` (pure, no network): valid announcement → all fields; wrong `t` tag → None; `schema: 2` → None; origin/registration_url origin mismatch → None; bad domain (`single-label`, `127.0.0.1`, `foo.example`) → None; missing `nostr-auth` capability → `nostr_auth == false`. Plus a `dedupe_newest_wins` test over the pure merge helper (`fn merge(events) -> Vec<DiscoveredServer>` — make merging pure too, keep only websocket IO in `discover`).
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** — PASS (network path compiles; no live-relay test).
- [ ] **Step 5: Commit** `feat(lnaddr): NIP-78 server discovery with default pyx.cash entry`.

---

### Task 6: Service layer — claim/sync/recover wired to factory

**Files:**
- Create: `rust/src/lnaddr/service.rs`
- Modify: `rust/src/factory.rs` (construct + hold `Arc<LnAddressService>`; call `sync_destinations` fire-and-forget after a client loads), `rust/src/lnaddr/mod.rs`

**Interfaces:**
- Consumes: everything above plus `ConduitClient::lnurl()` (`rust/src/client.rs:740`, `pub async fn lnurl(&self) -> Result<String, String>`).
- Produces (the JNI layer calls exactly these):

```rust
pub(crate) struct LnAddressService {
    store: LnAddressStore, api: LnaddrApi<ReqwestTransport>, keypair: Keypair,
}
impl LnAddressService {
    pub fn new(db: Database, mnemonic: &Mnemonic) -> Self;
    pub async fn snapshot(&self) -> Vec<LnAddressRecord>;                  // list for UI
    pub async fn quote(&self, origin: &str, domain: &str, name: &str) -> Result<QuoteResult, String>;
    /// destination = client.lnurl() of the *claiming* federation.
    pub async fn claim(&self, origin: &str, domain: &str, name: &str,
                       federation: FederationId, destination: String) -> Result<LnAddressRecord, String>;
    pub async fn set_primary(&self, domain: &str, name: &str) -> Result<(), String>;
    pub async fn release(&self, domain: &str, name: &str) -> Result<(), String>; // remote DELETE, then local remove (local remove also on remote 401/"not found" so a dead server can't wedge the UI; surface other errors)
    /// re-point an unassigned record at a federation (PUT update + bind locally)
    pub async fn repoint(&self, domain: &str, name: &str, federation: FederationId,
                         destination: String) -> Result<(), String>;
    /// on wallet load: destination drift -> PUT update; silent failure
    pub async fn sync_destinations(&self, federation: FederationId, current_lnurl: &str);
    /// NIP-98 list on default+discovered servers; merge into store; match
    /// destinations against `known` (federation -> lnurl) else unassigned.
    pub async fn recover(&self, known: Vec<(FederationId, String)>) -> Result<u32, String>; // returns #new
}
```

- [ ] **Step 1: Failing tests** (FakeTransport + MemDatabase): `claim_persists_and_first_is_primary`; `release_promotes_next`; `sync_updates_on_drift` (record destination ≠ current lnurl → transport sees a PUT; equal → no request); `recover_matches_by_destination` (server returns two owned addresses, one matching a known federation lnurl → bound, other → `federation_id: None`); `recover_is_idempotent` (running twice adds nothing).
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement** (service is generic over transport for tests: `LnAddressService<T: HttpTransport>` with a `ReqwestTransport` alias used by the factory). **Step 4: Run** — PASS, plus full `cargo test`.
- [ ] **Step 5: Wire into factory**: build in `ConduitClientFactory::new`/open path with `self.db.clone()` (raw factory DB, not a client prefix) + mnemonic; after a successful client load/join, `tokio::spawn` `sync_destinations(federation_id, client.lnurl().await.ok()…)` guarded to never block or fail loudly. `cargo test` + `cargo fmt`.
- [ ] **Step 6: Commit** `feat(lnaddr): claim/sync/recover service wired into the factory`.

---

### Task 7: JNI surface

**Files:**
- Modify: `rust/src/android/async_requests.rs` (new `SnapshotRequest` variants + dispatch), `rust/src/android/exports.rs` (new externs), and a new `rust/src/android/lnaddr_requests.rs` for the handlers (mirror how `input.rs`/`contacts` handlers are organized; register in `rust/src/android/mod.rs`).

**Interfaces:**
- Consumes: `LnAddressService` via the factory handle (`HandleKind::Factory` — mirror how `SeedWords(u64)` resolves a factory).
- Produces — JNI methods on `cash.pyx.app.nativeapi.NativeBindings` (Task 8 declares the Kotlin side with identical names/signatures):

```
lnaddrSnapshotAsync(factoryHandle: Long, cb)                  -> {"addresses":[{"domain","username","serverOrigin","federationId":String?,"destination","isPrimary":Bool,"claimedAtSecs":Long}]}
lnaddrDiscoverAsync(factoryHandle: Long, cb)                  -> {"servers":[{"origin","name","domains":[...],"freeDomains":[...]}]}
lnaddrQuoteAsync(factoryHandle: Long, origin: String, domain: String, username: String, cb)
                                                              -> {"state":"free"|"paid"|"taken"|"reserved"|"invalid"|"rate_limited","priceMsat":Long?}
lnaddrClaimAsync(clientHandle: Long, origin: String, domain: String, username: String, cb)
                                                              -> the claimed address object (same shape as snapshot entries)
lnaddrSetPrimaryAsync(factoryHandle: Long, domain: String, username: String, cb) -> {"ok":true}
lnaddrReleaseAsync(factoryHandle: Long, domain: String, username: String, cb)    -> {"ok":true}
lnaddrRepointAsync(clientHandle: Long, domain: String, username: String, cb)     -> {"ok":true}
lnaddrRecoverAsync(factoryHandle: Long, cb)                   -> {"recovered":Long}
```

`lnaddrClaimAsync`/`lnaddrRepointAsync` take a **client** handle: the handler resolves the client, awaits `client.lnurl()`, then calls the service with that destination and the client's federation id (mirror how `ReceiveLnurl(u64)` resolves a client at `async_requests.rs:313`). `lnaddrRecoverAsync` builds `known` from every currently-loaded client's `(federation_id, lnurl)`.

- [ ] **Step 1:** Add the eight `SnapshotRequest` variants + dispatch arms + handler functions returning `RequestOutput::plain(json)`. Follow the exact serialization style of neighboring handlers (`serde_json::json!`).
- [ ] **Step 2:** Add the eight `#[unsafe(no_mangle)] pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_<name>` exports, copying the shape of `Java_..._receiveLnurlAsync` (`exports.rs:1125`) including `owned_payment_input`-style string extraction and error paths.
- [ ] **Step 3:** `cargo test --features android-jni` and `cargo fmt`; expect PASS (handlers get coverage through Task 6's service tests; the JNI layer is exercised by the device-cert lane later).
- [ ] **Step 4: Commit** `feat(android): JNI surface for lightning addresses`.

---

### Task 8: Kotlin bindings, models, parsing

**Files:**
- Modify: `android-native/app/src/main/java/cash/pyx/app/nativeapi/NativeBindings.kt` (8 `external fun`s, exact names from Task 7)
- Modify: `.../nativeapi/NativeModels.kt`:

```kotlin
data class LnAddress(val domain: String, val username: String, val serverOrigin: String,
    val federationId: String?, val destination: String, val isPrimary: Boolean, val claimedAtSecs: Long) {
    val display: String get() = "$username@$domain"
}
data class LnAddressSnapshot(val addresses: List<LnAddress>)
data class LnaddrServer(val origin: String, val name: String, val domains: List<String>, val freeDomains: List<String>)
data class LnaddrDiscovery(val servers: List<LnaddrServer>)
sealed interface LnaddrQuote { data object Free : LnaddrQuote; data class Paid(val priceMsat: Long) : LnaddrQuote
    data object Taken : LnaddrQuote; data object Reserved : LnaddrQuote
    data class Invalid(val reason: String) : LnaddrQuote; data object RateLimited : LnaddrQuote }
data class LnaddrMutation(val ok: Boolean)
data class LnaddrRecovery(val recovered: Long)
```

- Modify: `.../nativeapi/NativeWalletApi.kt` — interface methods (default `unavailable()`) + real implementations via `awaitNative`, strict parsing (`boundedObject`, `requiredString`, size caps: ≤64 addresses, ≤32 servers, username/domain ≤ 64 chars, reject otherwise — mirror `parseContacts`'s `MAX_CONTACTS` style), plus binding constructor params like `lnaddrSnapshotAsyncBinding`.
- Test: `android-native/app/src/test/java/cash/pyx/app/nativeapi/` — add `LnaddrParsingTest.kt` following the existing NativeWalletApi parsing tests (find them with `grep -rl "awaitNative\|FakeBindings" android-native/app/src/test`).

- [ ] **Step 1: Write failing parsing tests**: snapshot happy path; missing field → failure; oversized list → failure; quote variants (`free`, `paid` requires `priceMsat`, `taken`); recovery count non-negative.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*Lnaddr*'` — FAIL.
- [ ] **Step 3:** Implement bindings/models/parsers.
- [ ] **Step 4:** Test run — PASS; full `:app:testDebugUnitTest` green.
- [ ] **Step 5: Commit** `feat(android): native API for lightning addresses`.

---

### Task 9: State owner + claim-sheet presentation

**Files:**
- Create: `android-native/app/src/main/java/cash/pyx/app/ui/LnAddressStateOwner.kt`, `.../ui/LnaddrClaimPresentation.kt`
- Test: `android-native/app/src/test/java/cash/pyx/app/ui/LnaddrClaimPresentationTest.kt`, `LnAddressStateOwnerTest.kt`

**Interfaces:**
- Consumes: `NativeWalletApi` methods from Task 8.
- Produces (UI tasks consume exactly these):

```kotlin
data class LnAddressState(
    val addresses: List<LnAddress> = emptyList(),
    val servers: List<LnaddrServer> = emptyList(),
    val loading: Boolean = false, val message: String? = null)
class LnAddressStateOwner(private val api: NativeWalletApi, private val scope: CoroutineScope) {
    val state: StateFlow<LnAddressState>
    fun refresh(factoryHandle: Long)            // snapshot + discovery (stale-while-refresh)
    fun recover(factoryHandle: Long)            // then refresh
    fun claim(clientHandle: Long, factoryHandle: Long, origin: String, domain: String, username: String, onDone: (Boolean) -> Unit)
    fun setPrimary(factoryHandle: Long, a: LnAddress); fun release(factoryHandle: Long, a: LnAddress)
    fun repoint(clientHandle: Long, factoryHandle: Long, a: LnAddress)
    fun primaryFor(federationId: String?): LnAddress?
    fun clearMessage()
}
// Pure claim-sheet state machine:
sealed interface ClaimCheck { data object Idle: ClaimCheck; data object Checking: ClaimCheck
    data object Available: ClaimCheck; data class Paid(val priceMsat: Long): ClaimCheck
    data object Taken: ClaimCheck; data object Reserved: ClaimCheck; data class Error(val hint: String): ClaimCheck }
object LnaddrClaimPresentation {
    fun sanitizeUsername(raw: String): String          // lowercase, [a-z0-9-_.], ≤64
    fun checkFromQuote(q: LnaddrQuote): ClaimCheck
    fun canClaim(check: ClaimCheck, username: String): Boolean  // Available && non-blank
    fun clockHint(message: String): String             // maps "unauthorized" -> "…check your device clock"
}
```

- [ ] **Step 1: Failing tests** — sanitize (uppercase folded, spaces stripped, length cap), `checkFromQuote` mapping incl. `Paid` disabled (`canClaim == false`), `primaryFor` picks `isPrimary` of the matching federation only, release/claim update state via a fake `NativeWalletApi` (existing tests show the fake pattern), unauthorized → clock hint.
- [ ] **Step 2:** Run — FAIL. **Step 3:** Implement. **Step 4:** Run — PASS.
- [ ] **Step 5: Commit** `feat(android): lightning-address state owner and claim presentation`.

---

### Task 10: Claim sheet UI

**Files:**
- Create: `android-native/app/src/main/java/cash/pyx/app/ui/components/LnaddrClaimSheet.kt`
- Modify: `.../ui/PyxApp.kt` (host the sheet state; it is opened from Receive (Task 12) and Settings (Task 11))

**Interfaces:**
- Consumes: `LnAddressStateOwner`, `LnaddrClaimPresentation`, `PyxSheet`, `PyxType`, theme colors, the debounce pattern from `ReceiveContent` (`PyxApp.kt:1907` — `LaunchedEffect` + `delay(1000)`).
- Produces:

```kotlin
@Composable fun LnaddrClaimSheet(
    owner: LnAddressStateOwner, clientHandle: Long, factoryHandle: Long,
    onDismiss: () -> Unit)
```

Layout per the approved mockup ("Claim sheet" frame in the artifact): `PyxSheet` with grip; title "Claim your address"; explainer line "Anyone can pay this name from any Lightning wallet. It forwards to your Pyx wallet."; domain selector (flat list of `(origin, domain)` pairs from `state.servers`, default `pyx.cash` preselected, rendered as a chip row or dropdown via the existing `CurrencyPickerSheet` pattern if >3); username `BasicTextField` styled like the receive amount field (mono, `PyxOrange` cursor) with the `@domain` suffix in `PyxMuted`; debounced (1 s) quote → status line (green dot + "name is available" / red "taken" / muted "paid — not supported yet" / clock hint); full-width `PyxOrange` CTA "Claim name@domain" enabled per `canClaim`; fine print "Hosted by <domain> · you can release it anytime". On success: dismiss + owner.refresh.

- [ ] **Step 1:** Implement the composable (no unit test — logic already covered in Task 9; visual verification in Task 13).
- [ ] **Step 2:** `./gradlew :app:assembleDebug` compiles; `:app:testDebugUnitTest` green.
- [ ] **Step 3: Commit** `feat(android): claim-your-address sheet`.

---

### Task 11: Settings — list screen, detail, release

**Files:**
- Modify: `.../ui/WalletRoute.kt` (add `LNADDR("lnaddr", "Lightning addresses")` to `WalletRoute`; add `LNADDR_RELEASE("modal_lnaddr_release")` to `WalletModalRoute`)
- Modify: `.../ui/PyxApp.kt` (`ManageContent` settings list at ~`PyxApp.kt:1136` "Wallet" section: add row `CardRow("Lightning address", value = primary?.display ?: "Claim")` navigating to LNADDR; render the new screen in the same `listOf(WalletRoute...)` composable block at `PyxApp.kt:1135`)
- Create: `.../ui/components/LnaddrScreens.kt` (list + detail content)

**Interfaces:**
- Consumes: `LnAddressStateOwner` (Task 9), `LnaddrClaimSheet` (Task 10), `SectionLabel`, `CardRow`, `PyxGhostButton`, `PyxTopBar`, the confirmation-modal pattern used by `ADDRESS_MUTATION`.
- Produces: `@Composable fun LnaddrListContent(owner: LnAddressStateOwner, federations: List<FederationSummary>, clientHandle: Long?, factoryHandle: Long, back: () -> Unit)` — grouped by federation name (plus "Unassigned" group), star icon marks primary, "+" in the top bar opens the claim sheet, `owner.refresh` + `owner.recover` on first composition (recover only when the wallet was restored — gate on the existing recovery flag if available, else always: it is idempotent). Row tap → detail sheet: big mono address, status line ("Active · forwarding to <federation>" / "Unassigned"), Copy/Share ghost buttons (`QrPayload.copy` for clipboard), "Make primary" (hidden when already primary or unassigned), "Assign to this wallet" for unassigned (calls `repoint` with the selected client), red "Release address…" → `LNADDR_RELEASE` modal ("Release eric@pyx.cash? Anyone will be able to claim it.") → `owner.release`.

- [ ] **Step 1:** Implement route, settings row, list/detail composables, release modal.
- [ ] **Step 2:** `:app:testDebugUnitTest` green (route enum changes may touch navigation tests — fix any).
- [ ] **Step 3: Commit** `feat(android): lightning address settings screen`.

---

### Task 12: Receive-screen integration

**Files:**
- Modify: `.../ui/PyxApp.kt` `ReceiveContent` (~`PyxApp.kt:1873`) and the zone below `CodeField`.

**Interfaces:**
- Consumes: `owner.primaryFor(selectedFederationId)`, `LnaddrClaimSheet`.

Behavior (mockup frames A1/A2): on the Lightning tab in the amount-less LNURL state only —
- primary == null → below the `CodeField`, an accent banner (row: `@` icon tile on `PyxOrange @ .16`, texts "Claim your Lightning address" / "Get paid at a name, not a code", small `PyxOrange` "Claim" button) opening the claim sheet. Background `PyxOrange @ .08`, border `PyxOrange @ .35`, radius 12.dp — use existing alpha-variant helpers if present in `Color.kt`, else `PyxOrange.copy(alpha=…)`.
- primary != null → an address row replaces the `CodeField` as the primary share surface: `@` icon, `username` in `PyxText` + `@domain` in `PyxMuted` (mono `PyxType.inputMono` 14.5sp), copy + share icons (copy = address string; share sheet = address string); caption "Reusable — share it anywhere. Tap to copy." in `PyxFaint`; the raw LNURL `CodeField` collapses into a small "Show LNURL" text button beneath (tap toggles it visible). QR unchanged (still the LNURL).

- [ ] **Step 1:** Implement; ensure the banner/row does not appear while typing an amount or on invoice display (only the `WalletOperation.Success && title == "LNURL receive"` state, see `PyxApp.kt:1914`).
- [ ] **Step 2:** `:app:assembleDebug` + unit tests green.
- [ ] **Step 3: Commit** `feat(android): lightning address on the receive screen`.

---

### Task 13: Screenshot fixtures + visual pass + device cert

**Files:**
- Modify: the screenshot fixture activity (find it: `grep -rn "class ScreenshotFixtureActivity" android-native/` — fixtures like `home_status_activity`, `receive_lnurl` show the registration pattern) — add fixtures `receive_lnaddr_banner`, `receive_lnaddr_claimed`, `lnaddr_claim_sheet`, `lnaddr_settings_list` backed by canned `LnAddressState`.
- Modify: device-cert test list if the cert lane enumerates fixtures (see `docs/native-android/` cert docs).

- [ ] **Step 1:** Add fixtures with fake data (`eric@pyx.cash`, one unassigned `backup@tips.example.org`).
- [ ] **Step 2:** Build + install on redroid (`build-android.sh` / memory pyx-wallet-build), `tool/app_shot.sh` each fixture; visually review against the approved artifact mockups (no prototype.html reference exists for these screens — review by eye against the artifact, note deviations).
- [ ] **Step 3:** Run the existing device-cert suite to confirm no regressions; add the new fixtures to it.
- [ ] **Step 4: Commit** `feat(android): lightning address screenshot fixtures + cert coverage`.

---

### Task 14: End-to-end smoke against a real lnaddrd (manual/optional CI)

- [ ] **Step 1:** Run a local lnaddrd (clone, `cargo run` with a sqlite tmpdir, a test domain, no payment policy — its `justfile`/README documents env vars; no relays needed since the app always offers the default server — point the app at it by temporarily overriding `DEFAULT_SERVER` or making the origin overridable via an env-gated debug hook, whichever is less invasive).
- [ ] **Step 2:** On redroid: claim a name against the local server, kill + restart the app (records persist), verify receive shows the address, release it, claim again, then `restoreWallet` from seed and verify `recover` finds the claimed address again (NIP-98 listing round-trip).
- [ ] **Step 3:** Record results in `docs/native-android/cutover-evidence.md`-style notes (new file `docs/native-android/lnaddr-smoke.md`).
- [ ] **Step 4: Commit** `docs: lightning address smoke evidence` and, if a debug hook was added, ensure it is release-gated.

## Self-review notes

- Spec coverage: storage/invariant (T1), derivation (T2), NIP-98 (T3), API (T4), discovery (T5), sync+recovery (T6), JNI (T7), Kotlin API (T8), state/presentation incl. clock hint + paid-disabled (T9), claim sheet (T10), settings home incl. unassigned/repoint/release (T11), receive banner/claimed row (T12), screenshots/testing (T13), live round-trip (T14). Out-of-scope items from the spec have no tasks, as intended.
- Naming: `LnAddressService`/`LnAddressStore`/`LnAddressRecord` (rust), `LnAddress`/`Lnaddr*` (kotlin JSON-facing) used consistently across tasks.
- The `management_token` from register is stored into `LnAddressRecord.management_token` in Task 6's `claim` (spec's unused escape hatch).
