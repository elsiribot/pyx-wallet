//! JNI handlers for the `lnaddr*` surface (claim/discover/quote/manage
//! Lightning addresses). Mirrors `input.rs`/`metadata.rs`: thin adapters
//! that validate bridge inputs, resolve handles, call into
//! `crate::lnaddr::LnAddressServiceImpl` (held by the factory) or
//! `ConduitClient`, and serialize the result with `serde_json::json!`.
//!
//! `lnaddrClaimAsync`/`lnaddrRepointAsync` take a **client** handle rather
//! than a factory handle (they need `client.lnurl()` and the client's
//! federation id), so they resolve the wallet's one live factory through
//! `bootstrap::current()` instead of an explicit factory handle — this app
//! only ever has one factory alive at a time (see `bootstrap::run_async`),
//! so that lookup is equivalent to threading a factory handle through.
//!
//! Note for whoever writes the Kotlin-side parser: this crate's `serde_json`
//! has no `preserve_order`/`indexmap` dependency, so `json!`'s object keys
//! serialize in **alphabetical** order, not the field order written here —
//! parse by key name, not position.

use std::sync::Arc;

use serde_json::{Value, json};

use crate::client::ConduitClient;
use crate::factory::ConduitClientFactory;
use crate::lnaddr::{
    DEFAULT_RELAYS, DiscoveredServer, LnAddressRecord, QuoteResult, discover, is_valid_domain,
    is_valid_username, normalize_origin,
};

use super::bootstrap;
use super::error::{AndroidError, AndroidErrorCode};
use super::handles::{HandleKind, global_get, global_handles};

const MAX_ORIGIN_BYTES: usize = 512;
const MAX_DOMAIN_BYTES: usize = 253;
const MAX_USERNAME_BYTES: usize = 64;

pub(crate) async fn snapshot_async(factory_handle: u64) -> Result<String, AndroidError> {
    let factory = factory(factory_handle)?;
    let addresses: Vec<Value> = factory
        .lnaddr()
        .snapshot()
        .await
        .into_iter()
        .map(address_json)
        .collect();
    serialize(json!({ "addresses": addresses }))
}

pub(crate) async fn discover_async(factory_handle: u64) -> Result<String, AndroidError> {
    // Discovery itself needs no factory state, but the handle is still
    // validated so a torn-down/uninitialized wallet can't reach the network.
    let _factory = factory(factory_handle)?;
    let servers: Vec<Value> = discover(&DEFAULT_RELAYS)
        .await
        .into_iter()
        .map(server_json)
        .collect();
    serialize(json!({ "servers": servers }))
}

pub(crate) async fn quote_async(
    factory_handle: u64,
    origin: String,
    domain: String,
    username: String,
) -> Result<String, AndroidError> {
    // Shadowed with the canonical form: request URLs are built by
    // interpolation, so the origin that reaches the API client must already be
    // trailing-slash-free.
    let origin = validate_origin(&origin)?;
    validate_domain(&domain)?;
    validate_username(&username)?;
    let factory = factory(factory_handle)?;
    let result = factory
        .lnaddr()
        .quote(&origin, &domain, &username)
        .await
        .map_err(|e| AndroidError::internal_logged("lnaddr_quote", &e))?;
    serialize(quote_json(&result))
}

pub(crate) async fn claim_async(
    client_handle: u64,
    origin: String,
    domain: String,
    username: String,
) -> Result<String, AndroidError> {
    // See quote_async: the canonical origin is what gets stored on the record
    // and reused by every later update/release call.
    let origin = validate_origin(&origin)?;
    validate_domain(&domain)?;
    validate_username(&username)?;
    // Resolve the client, its federation id, and the service before the
    // first await: a concurrent shutdown+restore rebinds `current_factory()`
    // to a new session's factory (see `current_factory`'s doc), so both
    // handles must be captured in the same synchronous span, before
    // `client.lnurl()` gives another task a chance to run.
    let client = global_get::<ConduitClient>(client_handle, HandleKind::Client)?;
    let federation_id = client.federation_id();
    let lnaddr = current_factory()?.lnaddr();
    let destination = client
        .lnurl()
        .await
        .map_err(|e| AndroidError::from_lightning(&e))?;
    let record = lnaddr
        .claim(&origin, &domain, &username, federation_id, destination)
        .await
        .map_err(|e| AndroidError::internal_logged("lnaddr_claim", &e))?;
    serialize(address_json(record))
}

pub(crate) async fn set_primary_async(
    factory_handle: u64,
    domain: String,
    username: String,
) -> Result<String, AndroidError> {
    validate_domain(&domain)?;
    validate_username(&username)?;
    let factory = factory(factory_handle)?;
    factory
        .lnaddr()
        .set_primary(&domain, &username)
        .await
        .map_err(|e| AndroidError::internal_logged("lnaddr_set_primary", &e))?;
    serialize(json!({ "ok": true }))
}

pub(crate) async fn release_async(
    factory_handle: u64,
    domain: String,
    username: String,
) -> Result<String, AndroidError> {
    validate_domain(&domain)?;
    validate_username(&username)?;
    let factory = factory(factory_handle)?;
    factory
        .lnaddr()
        .release(&domain, &username)
        .await
        .map_err(|e| AndroidError::internal_logged("lnaddr_release", &e))?;
    serialize(json!({ "ok": true }))
}

pub(crate) async fn repoint_async(
    client_handle: u64,
    domain: String,
    username: String,
) -> Result<String, AndroidError> {
    validate_domain(&domain)?;
    validate_username(&username)?;
    // See claim_async: resolve client + federation id + service before the
    // first await so a concurrent shutdown+restore can't rebind this call
    // onto a different session's factory mid-flight.
    let client = global_get::<ConduitClient>(client_handle, HandleKind::Client)?;
    let federation_id = client.federation_id();
    let lnaddr = current_factory()?.lnaddr();
    let destination = client
        .lnurl()
        .await
        .map_err(|e| AndroidError::from_lightning(&e))?;
    lnaddr
        .repoint(&domain, &username, federation_id, destination)
        .await
        .map_err(|e| AndroidError::internal_logged("lnaddr_repoint", &e))?;
    serialize(json!({ "ok": true }))
}

/// Builds `known` from every currently-loaded client's
/// `(federation_id, lnurl)`: enumerates every live `Client` handle
/// process-wide (this app runs one wallet/one factory at a time, so that is
/// exactly "every client this factory has open"), fetching each one's lnurl
/// sequentially. A client whose handle has since gone stale, or whose
/// `lnurl()` call fails, contributes nothing rather than failing the whole
/// recovery.
pub(crate) async fn recover_async(factory_handle: u64) -> Result<String, AndroidError> {
    let factory = factory(factory_handle)?;

    let mut known = Vec::new();
    for handle in global_handles(HandleKind::Client)? {
        let Ok(client) = global_get::<ConduitClient>(handle, HandleKind::Client) else {
            continue;
        };
        let federation_id = client.federation_id();
        if let Ok(lnurl) = client.lnurl().await {
            known.push((federation_id, lnurl));
        }
    }

    let recovered = factory
        .lnaddr()
        .recover(known)
        .await
        .map_err(|e| AndroidError::internal_logged("lnaddr_recover", &e))?;
    serialize(json!({ "recovered": i64::from(recovered) }))
}

fn factory(handle: u64) -> Result<Arc<ConduitClientFactory>, AndroidError> {
    global_get(handle, HandleKind::Factory)
}

/// Resolves the wallet's one live factory without an explicit handle
/// parameter (`lnaddrClaimAsync`/`lnaddrRepointAsync` only carry a client
/// handle on the wire). See the module doc for why this is safe here, and
/// call this — like `global_get` — before the caller's first `await` so the
/// resolved `Arc` can't drift out from under a concurrent shutdown+restore.
fn current_factory() -> Result<Arc<ConduitClientFactory>, AndroidError> {
    match bootstrap::current()? {
        Some(bootstrap::BootstrapResult::Ready { factory_handle, .. }) => {
            global_get(factory_handle, HandleKind::Factory)
        }
        other => Err(AndroidError::internal_logged(
            "lnaddr_current_factory",
            other,
        )),
    }
}

fn address_json(record: LnAddressRecord) -> Value {
    json!({
        "domain": record.domain,
        "username": record.username,
        "serverOrigin": record.server_origin,
        "federationId": record.federation_id.map(|id| id.to_string()),
        "destination": record.destination,
        "isPrimary": record.is_primary,
        "claimedAtSecs": record.claimed_at_secs as i64,
    })
}

fn server_json(server: DiscoveredServer) -> Value {
    json!({
        "origin": server.origin,
        "name": server.name,
        "domains": server.domains,
        "freeDomains": server.free_domains,
    })
}

/// Longest server-supplied `reason` code forwarded to Kotlin. lnaddrd's own
/// codes are short slugs; anything longer is a misbehaving server and is
/// truncated rather than allowed to grow the bridge payload.
const MAX_QUOTE_REASON_BYTES: usize = 64;

/// `invalid` folds together lnaddrd's `invalid_input`, `unsupported_domain`
/// and `length_disabled` (see `LnaddrApi::quote`), and the spec requires the
/// last two to be distinguishable in the claim sheet — so the code has to
/// cross the bridge. It is emitted only for `invalid`, since that is the only
/// state carrying one, and only when non-empty, so Kotlin's `optionalPayload`
/// read stays the "absent" case for every other state.
fn quote_json(result: &QuoteResult) -> Value {
    let (state, price_msat): (&str, Option<i64>) = match result {
        QuoteResult::Free => ("free", None),
        QuoteResult::PricedMsat(msat) => ("paid", Some(*msat as i64)),
        QuoteResult::Taken => ("taken", None),
        QuoteResult::Reserved => ("reserved", None),
        QuoteResult::Invalid(_) => ("invalid", None),
        QuoteResult::RateLimited => ("rate_limited", None),
    };
    let mut value = json!({ "state": state, "priceMsat": price_msat });
    if let QuoteResult::Invalid(reason) = result {
        let reason: String = reason.chars().take(MAX_QUOTE_REASON_BYTES).collect();
        if !reason.is_empty() {
            value["reason"] = Value::String(reason);
        }
    }
    value
}

/// `origin` is interpolated straight into request URLs and is what the wallet
/// signs a NIP-98 event for, so the bridge goes through the same
/// accept-and-canonicalize boundary announcement parsing uses
/// ([`crate::lnaddr::normalize_origin`]): a bare `https://` origin on a public
/// registrable host with no path, query or fragment, returned **without a
/// trailing slash** so `format!("{origin}/api/v1/...")` can never produce a
/// double slash. The byte cap stays as a cheap pre-filter so a pathological
/// input never reaches the URL parser.
///
/// Under the non-default `lnaddr-debug-server` feature `normalize_origin`
/// additionally admits the compiled-in loopback debug origin, keeping the
/// documented end-to-end smoke build working; release builds cannot enable
/// that feature.
fn validate_origin(origin: &str) -> Result<String, AndroidError> {
    if origin.is_empty() || origin.len() > MAX_ORIGIN_BYTES {
        return Err(invalid_lnaddr());
    }
    normalize_origin(origin).ok_or_else(invalid_lnaddr)
}

/// Reuses the service layer's own predicate rather than a length-only check,
/// so the bridge, the store and Kotlin's `sanitizeUsername` all agree on what
/// a domain is. `MAX_DOMAIN_BYTES` is kept as a pre-filter and is also the cap
/// `is_valid_domain` enforces.
fn validate_domain(domain: &str) -> Result<(), AndroidError> {
    if domain.is_empty() || domain.len() > MAX_DOMAIN_BYTES || !is_valid_domain(domain) {
        return Err(invalid_lnaddr());
    }
    Ok(())
}

/// See [`validate_domain`]: the same predicate the service layer applies to
/// server-supplied names, applied here to caller-supplied ones.
fn validate_username(username: &str) -> Result<(), AndroidError> {
    if username.is_empty() || username.len() > MAX_USERNAME_BYTES || !is_valid_username(username) {
        return Err(invalid_lnaddr());
    }
    Ok(())
}

fn invalid_lnaddr() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The lightning address is invalid.",
        false,
    )
}

fn serialize(value: Value) -> Result<String, AndroidError> {
    serde_json::to_string(&value).map_err(|_| AndroidError::internal())
}

#[cfg(test)]
mod tests {
    use fedimint_core::config::FederationId;

    use super::*;

    fn record(federation_id: Option<FederationId>) -> LnAddressRecord {
        LnAddressRecord {
            domain: "example.com".to_string(),
            username: "alice".to_string(),
            server_origin: "https://pay.example.com".to_string(),
            federation_id,
            destination: "LNURL1DUMMY".to_string(),
            is_primary: true,
            claimed_at_secs: 100,
            management_token: None,
        }
    }

    #[test]
    fn address_json_matches_contract() {
        let fed = FederationId::dummy();
        let json = serialize(address_json(record(Some(fed)))).unwrap();
        assert_eq!(
            json,
            format!(
                "{{\"claimedAtSecs\":100,\"destination\":\"LNURL1DUMMY\",\"domain\":\"example.com\",\"federationId\":\"{fed}\",\"isPrimary\":true,\"serverOrigin\":\"https://pay.example.com\",\"username\":\"alice\"}}"
            )
        );
    }

    #[test]
    fn address_json_serializes_unassigned_federation_as_null() {
        let json = serialize(address_json(record(None))).unwrap();
        assert!(json.contains("\"federationId\":null"));
    }

    #[test]
    fn server_json_matches_contract() {
        let json = serialize(server_json(DiscoveredServer {
            origin: "https://pay.example.com".to_string(),
            name: "Example".to_string(),
            domains: vec!["pay.example.com".to_string()],
            nostr_auth: true,
            registration_api: true,
            free_domains: vec!["pay.example.com".to_string()],
        }))
        .unwrap();
        assert_eq!(
            json,
            "{\"domains\":[\"pay.example.com\"],\"freeDomains\":[\"pay.example.com\"],\"name\":\"Example\",\"origin\":\"https://pay.example.com\"}"
        );
    }

    #[test]
    fn quote_json_covers_every_state() {
        assert_eq!(
            serialize(quote_json(&QuoteResult::Free)).unwrap(),
            "{\"priceMsat\":null,\"state\":\"free\"}"
        );
        assert_eq!(
            serialize(quote_json(&QuoteResult::PricedMsat(1_000))).unwrap(),
            "{\"priceMsat\":1000,\"state\":\"paid\"}"
        );
        assert_eq!(
            serialize(quote_json(&QuoteResult::Taken)).unwrap(),
            "{\"priceMsat\":null,\"state\":\"taken\"}"
        );
        assert_eq!(
            serialize(quote_json(&QuoteResult::Reserved)).unwrap(),
            "{\"priceMsat\":null,\"state\":\"reserved\"}"
        );
        assert_eq!(
            serialize(quote_json(&QuoteResult::RateLimited)).unwrap(),
            "{\"priceMsat\":null,\"state\":\"rate_limited\"}"
        );
    }

    /// `invalid` folds three spec'd, separately-renderable quote errors
    /// together, so the server's code has to survive the bridge or the claim
    /// sheet shows a red dot with no text.
    #[test]
    fn invalid_quote_json_carries_the_reason_code() {
        for code in ["invalid_input", "unsupported_domain", "length_disabled"] {
            assert_eq!(
                serialize(quote_json(&QuoteResult::Invalid(code.to_string()))).unwrap(),
                format!("{{\"priceMsat\":null,\"reason\":\"{code}\",\"state\":\"invalid\"}}")
            );
        }
    }

    #[test]
    fn quote_reason_is_omitted_when_empty_and_bounded_when_long() {
        assert_eq!(
            serialize(quote_json(&QuoteResult::Invalid(String::new()))).unwrap(),
            "{\"priceMsat\":null,\"state\":\"invalid\"}",
            "an empty code must not become an empty-string reason key"
        );

        let long = "x".repeat(MAX_QUOTE_REASON_BYTES * 4);
        let json = serialize(quote_json(&QuoteResult::Invalid(long))).unwrap();
        let parsed: Value = serde_json::from_str(&json).unwrap();
        assert_eq!(
            parsed["reason"].as_str().unwrap().len(),
            MAX_QUOTE_REASON_BYTES
        );
    }

    #[test]
    fn ok_and_recovered_json_match_contract() {
        assert_eq!(serialize(json!({"ok": true})).unwrap(), "{\"ok\":true}");
        assert_eq!(
            serialize(json!({"recovered": i64::from(3u32)})).unwrap(),
            "{\"recovered\":3}"
        );
    }

    /// The bridge must not be a laxer door into the signing path than
    /// announcement parsing is: every origin shape `is_allowed_origin`
    /// rejects has to be rejected here too, because `origin` is interpolated
    /// raw into the request URL that the NIP-98 `u` tag then commits to.
    #[test]
    fn origin_validation_matches_the_announcement_rule() {
        assert_eq!(
            validate_origin("https://pay.example.com").unwrap(),
            "https://pay.example.com"
        );

        for rejected in [
            "http://pay.example.com",
            "https://pay.example.com/api/v1",
            "https://pay.example.com/?x=1",
            "https://pay.example.com#f",
            "https://127.0.0.1",
            "https://localhost",
            "https://pay.example",
            "https://nodots",
            "not a url",
            "  https://pay.example.com",
        ] {
            assert!(
                validate_origin(rejected).is_err(),
                "{rejected:?} must not reach the signing path"
            );
        }
    }

    /// Requests are built as `format!("{origin}/api/v1/...")`. An origin that
    /// kept its trailing slash would yield `//api/v1/...`, and since lnaddrd
    /// derives the `u` tag it expects from its own normalized origin, every
    /// NIP-98 request to that server would fail — including the release that
    /// would let the user get rid of the address. The bridge therefore
    /// canonicalizes at the accept boundary rather than merely validating.
    #[test]
    fn origin_validation_canonicalizes_trailing_slashes() {
        for input in [
            "https://pay.example.com",
            "https://pay.example.com/",
            "https://pay.example.com//",
        ] {
            let canonical = validate_origin(input).expect("all three name the same origin");
            assert_eq!(canonical, "https://pay.example.com");
            assert_eq!(
                format!("{canonical}/api/v1/register"),
                "https://pay.example.com/api/v1/register"
            );
        }
    }

    /// The bridge applies the service layer's own predicates, not a
    /// length-only check, so Kotlin's `sanitizeUsername`, the bridge and the
    /// store cannot drift apart on what a name or a domain is.
    #[test]
    fn domain_and_username_validation_reuse_the_service_predicates() {
        assert!(validate_domain("example.com").is_ok());
        for rejected in [
            "",
            "localhost",
            "single-label",
            "evil.example",
            "EXAMPLE.com",
        ] {
            assert!(validate_domain(rejected).is_err(), "{rejected:?}");
        }

        assert!(validate_username("alice").is_ok());
        assert!(validate_username("a-b_c.d1").is_ok());
        for rejected in ["", "Alice", "eve smith", "bob!", "üser"] {
            assert!(validate_username(rejected).is_err(), "{rejected:?}");
        }
    }

    #[test]
    fn inputs_are_bounded_and_non_empty() {
        assert!(validate_origin("https://pay.example.com").is_ok());
        assert!(validate_origin("").is_err());
        assert!(validate_origin(&"x".repeat(MAX_ORIGIN_BYTES + 1)).is_err());
        assert!(validate_domain("example.com").is_ok());
        assert!(validate_domain("").is_err());
        assert!(validate_domain(&"x".repeat(MAX_DOMAIN_BYTES + 1)).is_err());
        assert!(validate_username("alice").is_ok());
        assert!(validate_username("").is_err());
        assert!(validate_username(&"x".repeat(MAX_USERNAME_BYTES + 1)).is_err());
    }
}
