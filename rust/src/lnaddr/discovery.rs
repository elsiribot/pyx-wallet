//! NIP-78 Lightning Address service discovery.
//!
//! Parses and validates `lnaddrd` service announcements — NIP-78 `kind:30078`
//! events tagged `t=lightning-address-service` — per
//! `docs/protocol/02-service-announcements.md`, and fetches them from a small
//! set of relays over websockets. All network I/O lives in [`discover`];
//! [`parse_announcement`] and [`merge`] are pure so they can be unit-tested
//! without a network.
//!
//! `free_domains` is computed with a heuristic documented on
//! [`compute_free_domains`]: it is informational only, the live quote from
//! the server is always authoritative before a claim is attempted.

use std::collections::HashMap;
use std::time::Duration;

use bitcoin::secp256k1::schnorr::Signature;
use bitcoin::secp256k1::{Message, Secp256k1, XOnlyPublicKey};
use futures_util::{SinkExt, StreamExt};
use serde_json::Value;
use tokio_tungstenite::tungstenite::Message as WsMessage;

use crate::lnaddr::nip98::nostr_event_id;

/// NIP-78 "arbitrary app data" kind used for service announcements.
const KIND_SERVICE_ANNOUNCEMENT: u64 = 30078;

/// Exact `t` tag value announcements must carry (also the relay filter's
/// `#t` value).
const SERVICE_TAG: &str = "lightning-address-service";

/// Prefix of the required `d` tag value: `"lnaddrd:service:v1:" + origin`.
const D_TAG_PREFIX: &str = "lnaddrd:service:v1:";

/// Reserved final labels no public registrable domain may end in (mirrors
/// the main lnaddrd specification's domain-normalization rule).
const RESERVED_FINAL_LABELS: [&str; 6] = [
    "localhost",
    "local",
    "internal",
    "test",
    "invalid",
    "example",
];

/// A minimum tier `max_length` for a domain to count as "free for typical
/// names" in [`compute_free_domains`]. Documented there.
const TYPICAL_USERNAME_LENGTH: u64 = 8;

/// Overall time budget for a single relay's REQ/EOSE round trip, per the
/// design note in the task brief ("5s overall budget"). Relays are queried
/// concurrently in [`discover`], so this is also the wall-clock budget for
/// `discover` as a whole, not `5s * relays.len()`.
const RELAY_QUERY_BUDGET: Duration = Duration::from_secs(5);

/// Subscription id used on every relay query; arbitrary, only needs to match
/// between our REQ and the relay's EVENT/EOSE replies.
const SUBSCRIPTION_ID: &str = "pyx";

/// A validated, deduplicated Lightning Address service, ready to offer as a
/// registration destination.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct DiscoveredServer {
    pub origin: String,
    pub name: String,
    pub domains: Vec<String>,
    pub nostr_auth: bool,
    pub registration_api: bool,
    /// Informational only; the live quote from the server is authoritative.
    pub free_domains: Vec<String>,
}

/// The wallet's built-in fallback server: `(origin, domain)`. Always present
/// in [`discover`]'s output, first, regardless of relay results.
pub(crate) const DEFAULT_SERVER: (&str, &str) = ("https://pyx.cash", "pyx.cash");

/// Relays queried by [`discover`] when the caller has no override list.
pub(crate) const DEFAULT_RELAYS: [&str; 3] = [
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://relay.primal.net",
];

/// The always-present built-in entry: known to be free, and to support both
/// required capabilities, without needing a relay announcement at all.
fn default_server() -> DiscoveredServer {
    DiscoveredServer {
        origin: DEFAULT_SERVER.0.to_string(),
        name: DEFAULT_SERVER.1.to_string(),
        domains: vec![DEFAULT_SERVER.1.to_string()],
        nostr_auth: true,
        registration_api: true,
        free_domains: vec![DEFAULT_SERVER.1.to_string()],
    }
}

/// A domain label rule check per the main specification's normalization
/// rule: at least two dot-separated labels, each 1-63 chars of lowercase
/// `a-z0-9-` not starting/ending with `-`, final label neither all-digits
/// nor a reserved name (`localhost`, `local`, `internal`, `test`,
/// `invalid`, `example`).
fn is_public_domain(domain: &str) -> bool {
    let labels: Vec<&str> = domain.split('.').collect();
    if labels.len() < 2 {
        return false;
    }
    for label in &labels {
        if label.is_empty() || label.len() > 63 {
            return false;
        }
        if !label
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-')
        {
            return false;
        }
        if label.starts_with('-') || label.ends_with('-') {
            return false;
        }
    }
    let final_label = labels[labels.len() - 1];
    if final_label.chars().all(|c| c.is_ascii_digit()) {
        return false;
    }
    if RESERVED_FINAL_LABELS.contains(&final_label) {
        return false;
    }
    true
}

/// `origin` must be a bare `https://` URL (no path/query/fragment) whose
/// host is a public registrable domain per [`is_public_domain`].
fn is_valid_origin(origin: &str) -> bool {
    let Ok(url) = url::Url::parse(origin) else {
        return false;
    };
    if url.scheme() != "https" {
        return false;
    }
    if !matches!(url.path(), "" | "/") || url.query().is_some() || url.fragment().is_some() {
        return false;
    }
    match url.host_str() {
        Some(host) => is_public_domain(host),
        None => false,
    }
}

/// Whether `url` shares its origin (scheme + host + port) with `origin`.
fn same_origin(origin: &str, url: &str) -> bool {
    match (url::Url::parse(origin), url::Url::parse(url)) {
        (Ok(a), Ok(b)) => a.origin() == b.origin(),
        _ => false,
    }
}

/// `free_domains` heuristic: a domain counts as free if the announcement's
/// `pricing` array has no entry for it at all (silence is informational,
/// not a promise, per doc 02), or if it has an entry whose `tiers` include a
/// tier with `price == 0` and `max_length >= 8` — i.e. free for names at
/// least as long as a typical handle, not just for very short ones that
/// happen to be priced at zero. This is a display heuristic only: the live
/// `/api/v1/register/quote` call is always authoritative before a claim.
fn compute_free_domains(domains: &[String], pricing: Option<&Value>) -> Vec<String> {
    let pricing_entries = pricing.and_then(Value::as_array);

    domains
        .iter()
        .filter(|domain| {
            let entry = pricing_entries.and_then(|entries| {
                entries
                    .iter()
                    .find(|e| e.get("domain").and_then(Value::as_str) == Some(domain.as_str()))
            });
            let Some(entry) = entry else {
                // No pricing entry at all for this domain: informational
                // silence, treat as free per doc 02.
                return true;
            };
            let Some(tiers) = entry.get("tiers").and_then(Value::as_array) else {
                return false;
            };
            tiers.iter().any(|tier| {
                let max_length = tier.get("max_length").and_then(Value::as_u64).unwrap_or(0);
                let price = tier.get("price").and_then(Value::as_u64);
                max_length >= TYPICAL_USERNAME_LENGTH && price == Some(0)
            })
        })
        .cloned()
        .collect()
}

/// Verifies a NIP-01 event's schnorr signature against its own `id`/`pubkey`
/// fields, recomputing the id from the canonical preimage rather than
/// trusting the `id` field at face value.
fn verify_event_signature(
    pubkey_hex: &str,
    created_at: u64,
    kind: u64,
    tags: &[Value],
    content: &str,
    id_hex: &str,
    sig_hex: &str,
) -> bool {
    let computed_id = nostr_event_id(pubkey_hex, &created_at, kind, tags, content);

    let Ok(id_bytes) = fedimint_core::hex::decode(id_hex) else {
        return false;
    };
    let Ok(id_bytes): Result<[u8; 32], _> = id_bytes.try_into() else {
        return false;
    };
    if computed_id != id_bytes {
        return false;
    }

    let Ok(pubkey_bytes) = fedimint_core::hex::decode(pubkey_hex) else {
        return false;
    };
    let Ok(pubkey) = XOnlyPublicKey::from_slice(&pubkey_bytes) else {
        return false;
    };
    let Ok(sig_bytes) = fedimint_core::hex::decode(sig_hex) else {
        return false;
    };
    let Ok(sig) = Signature::from_slice(&sig_bytes) else {
        return false;
    };

    let message = Message::from_digest(id_bytes);
    let secp = Secp256k1::verification_only();
    secp.verify_schnorr(&sig, &message, &pubkey).is_ok()
}

/// Pure parser: validates one relay `EVENT` payload (the event object, not
/// the `["EVENT", subId, {...}]` wrapper) into a [`DiscoveredServer`], or
/// `None` if it fails any structural, signature, or content rule from doc
/// 02. Does no I/O.
pub(crate) fn parse_announcement(event: &Value) -> Option<DiscoveredServer> {
    if event.get("kind")?.as_u64()? != KIND_SERVICE_ANNOUNCEMENT {
        return None;
    }
    let pubkey_hex = event.get("pubkey")?.as_str()?;
    let created_at = event.get("created_at")?.as_u64()?;
    let tags = event.get("tags")?.as_array()?;
    let content_str = event.get("content")?.as_str()?;
    let id_hex = event.get("id")?.as_str()?;
    let sig_hex = event.get("sig")?.as_str()?;

    let has_service_tag = tags.iter().any(|tag| {
        let tag = tag.as_array();
        tag.is_some_and(|t| t.len() >= 2 && t[0] == "t" && t[1] == SERVICE_TAG)
    });
    if !has_service_tag {
        return None;
    }

    if !verify_event_signature(
        pubkey_hex,
        created_at,
        KIND_SERVICE_ANNOUNCEMENT,
        tags,
        content_str,
        id_hex,
        sig_hex,
    ) {
        return None;
    }

    let content: Value = serde_json::from_str(content_str).ok()?;

    if content.get("schema")?.as_u64()? != 1 {
        return None;
    }

    let origin = content.get("origin")?.as_str()?.to_string();
    let registration_url = content.get("registration_url")?.as_str()?.to_string();
    let domains: Vec<String> = content
        .get("domains")?
        .as_array()?
        .iter()
        .map(|v| v.as_str().map(str::to_string))
        .collect::<Option<Vec<_>>>()?;
    let capabilities: Vec<&str> = content
        .get("capabilities")?
        .as_array()?
        .iter()
        .map(Value::as_str)
        .collect::<Option<Vec<_>>>()?;

    if !is_valid_origin(&origin) {
        return None;
    }
    let expected_d_tag = format!("{D_TAG_PREFIX}{origin}");
    let has_matching_d_tag = tags.iter().any(|tag| {
        let tag = tag.as_array();
        tag.is_some_and(|t| t.len() >= 2 && t[0] == "d" && t[1] == expected_d_tag.as_str())
    });
    if !has_matching_d_tag {
        return None;
    }
    if !same_origin(&origin, &registration_url) {
        return None;
    }
    if domains.is_empty() || !domains.iter().all(|d| is_public_domain(d)) {
        return None;
    }

    let nostr_auth = capabilities.contains(&"nostr-auth");
    let registration_api = capabilities.contains(&"registration-api-v1");
    let name = content
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or(&origin)
        .to_string();
    let free_domains = compute_free_domains(&domains, content.get("pricing"));

    Some(DiscoveredServer {
        origin,
        name,
        domains,
        nostr_auth,
        registration_api,
        free_domains,
    })
}

/// Pure merge: parses each raw relay `EVENT` payload, drops invalid ones,
/// deduplicates by origin (the newest `created_at` wins, per NIP-01
/// replaceable-event semantics), and keeps only servers advertising both
/// `registration-api-v1` and `nostr-auth` — the two capabilities doc 02/03
/// require before a wallet offers a server for registration. No I/O.
pub(crate) fn merge(events: &[Value]) -> Vec<DiscoveredServer> {
    let mut by_origin: HashMap<String, (u64, DiscoveredServer)> = HashMap::new();

    for event in events {
        let Some(created_at) = event.get("created_at").and_then(Value::as_u64) else {
            continue;
        };
        let Some(server) = parse_announcement(event) else {
            continue;
        };

        by_origin
            .entry(server.origin.clone())
            .and_modify(|existing| {
                if created_at > existing.0 {
                    *existing = (created_at, server.clone());
                }
            })
            .or_insert((created_at, server));
    }

    let mut servers: Vec<DiscoveredServer> = by_origin
        .into_values()
        .map(|(_, server)| server)
        .filter(|server| server.nostr_auth && server.registration_api)
        .collect();
    // Deterministic order for callers/tests; discover() re-sorts the
    // default entry to the front regardless.
    servers.sort_by(|a, b| a.origin.cmp(&b.origin));
    servers
}

/// Opens `relay`, sends the standard REQ filter, and collects `EVENT`
/// payloads until `EOSE` or [`RELAY_QUERY_BUDGET`] elapses, then closes.
/// Any failure (connect, protocol, timeout) is swallowed and yields an empty
/// list — per-relay failures are silently skipped so one bad relay never
/// blocks discovery.
async fn query_relay(relay: &str) -> Vec<Value> {
    match tokio::time::timeout(RELAY_QUERY_BUDGET, query_relay_inner(relay)).await {
        Ok(Ok(events)) => events,
        _ => Vec::new(),
    }
}

async fn query_relay_inner(relay: &str) -> Result<Vec<Value>, String> {
    let (ws_stream, _) = tokio_tungstenite::connect_async(relay)
        .await
        .map_err(|e| format!("connect to {relay} failed: {e}"))?;
    let (mut write, mut read) = ws_stream.split();

    let req = serde_json::json!([
        "REQ",
        SUBSCRIPTION_ID,
        {"kinds": [KIND_SERVICE_ANNOUNCEMENT], "#t": [SERVICE_TAG]}
    ]);
    write
        .send(WsMessage::Text(req.to_string()))
        .await
        .map_err(|e| format!("send REQ to {relay} failed: {e}"))?;

    let mut events = Vec::new();
    while let Some(msg) = read.next().await {
        let msg = msg.map_err(|e| format!("read from {relay} failed: {e}"))?;
        let WsMessage::Text(text) = msg else {
            continue;
        };
        let Ok(parsed) = serde_json::from_str::<Value>(&text) else {
            continue;
        };
        let Some(arr) = parsed.as_array() else {
            continue;
        };
        match arr.first().and_then(Value::as_str) {
            Some("EVENT") => {
                if let Some(event) = arr.get(2) {
                    events.push(event.clone());
                }
            }
            Some("EOSE") => break,
            _ => {}
        }
    }

    let _ = write.close().await;
    Ok(events)
}

/// Queries `relays` concurrently (each bounded by [`RELAY_QUERY_BUDGET`]),
/// merges and validates results with [`merge`], then always places the
/// built-in [`DEFAULT_SERVER`] first — deduping it against a relay
/// announcement for the same origin if one exists, rather than showing it
/// twice.
pub(crate) async fn discover(relays: &[&str]) -> Vec<DiscoveredServer> {
    let queries = relays.iter().map(|relay| query_relay(relay));
    let results = futures_util::future::join_all(queries).await;
    let events: Vec<Value> = results.into_iter().flatten().collect();

    let mut servers = merge(&events);
    servers.retain(|server| server.origin != DEFAULT_SERVER.0);

    let mut result = vec![default_server()];
    result.append(&mut servers);
    result
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use bitcoin::secp256k1::Keypair;
    use fedimint_bip39::Mnemonic;
    use serde_json::json;

    use super::*;
    use crate::lnaddr::identity::nostr_keypair;

    const TEST_MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon abandon abandon \
         abandon abandon abandon about";

    fn test_keypair() -> Keypair {
        nostr_keypair(&Mnemonic::from_str(TEST_MNEMONIC).expect("valid BIP-39 test mnemonic"))
    }

    /// Builds and signs a valid `kind:30078` announcement event around
    /// `content`, mirroring the shape `lnaddrd` publishes and this module's
    /// own `nip98_header` construction (same NIP-01 id preimage rule). The
    /// `d` tag is derived from `content.origin` so it matches by
    /// construction; tests that only vary other fields don't need to think
    /// about it.
    fn make_announcement(keypair: &Keypair, content: &Value, created_at: u64) -> Value {
        let origin = content
            .get("origin")
            .and_then(Value::as_str)
            .unwrap_or("https://pay.example.com");
        make_announcement_with_tags(
            keypair,
            content,
            created_at,
            vec![
                json!(["d", format!("{D_TAG_PREFIX}{origin}")]),
                json!(["t", SERVICE_TAG]),
            ],
        )
    }

    fn make_announcement_with_tags(
        keypair: &Keypair,
        content: &Value,
        created_at: u64,
        tags: Vec<Value>,
    ) -> Value {
        let (x_only, _parity) = keypair.x_only_public_key();
        let pubkey_hex = fedimint_core::hex::encode(x_only.serialize());
        let content_str = serde_json::to_string(content).expect("content always serializes");
        let kind = KIND_SERVICE_ANNOUNCEMENT;

        let id = nostr_event_id(&pubkey_hex, &created_at, kind, &tags, &content_str);
        let id_hex = fedimint_core::hex::encode(id);

        let secp = Secp256k1::new();
        let message = Message::from_digest(id);
        let sig = secp.sign_schnorr_no_aux_rand(&message, keypair);
        let sig_hex = fedimint_core::hex::encode(sig.serialize());

        json!({
            "id": id_hex,
            "pubkey": pubkey_hex,
            "created_at": created_at,
            "kind": kind,
            "tags": tags,
            "content": content_str,
            "sig": sig_hex,
        })
    }

    fn valid_content() -> Value {
        json!({
            "schema": 1,
            "name": "Example Lightning Addresses",
            "origin": "https://pay.example.com",
            "domains": ["pay.example.com"],
            "registration_url": "https://pay.example.com/register",
            "capabilities": ["free-registration", "registration-api-v1", "nostr-auth"],
            "pricing": [
                {
                    "domain": "pay.example.com",
                    "currency": "msat",
                    "tiers": [
                        {"max_length": 2, "price": 1000000},
                        {"max_length": 64, "price": 0}
                    ]
                }
            ],
        })
    }

    #[test]
    fn valid_announcement_parses_all_fields() {
        let keypair = test_keypair();
        let event = make_announcement(&keypair, &valid_content(), 1_750_000_000);

        let server = parse_announcement(&event).expect("valid announcement must parse");

        assert_eq!(server.origin, "https://pay.example.com");
        assert_eq!(server.name, "Example Lightning Addresses");
        assert_eq!(server.domains, vec!["pay.example.com".to_string()]);
        assert!(server.nostr_auth);
        assert!(server.registration_api);
        assert_eq!(server.free_domains, vec!["pay.example.com".to_string()]);
    }

    #[test]
    fn wrong_t_tag_is_rejected() {
        let keypair = test_keypair();
        let event = make_announcement_with_tags(
            &keypair,
            &valid_content(),
            1_750_000_000,
            vec![json!(["t", "something-else"])],
        );

        assert!(parse_announcement(&event).is_none());
    }

    #[test]
    fn missing_t_tag_is_rejected() {
        let keypair = test_keypair();
        let event = make_announcement_with_tags(&keypair, &valid_content(), 1_750_000_000, vec![]);

        assert!(parse_announcement(&event).is_none());
    }

    #[test]
    fn schema_2_is_rejected() {
        let keypair = test_keypair();
        let mut content = valid_content();
        content["schema"] = json!(2);
        let event = make_announcement(&keypair, &content, 1_750_000_000);

        assert!(parse_announcement(&event).is_none());
    }

    #[test]
    fn origin_registration_url_mismatch_is_rejected() {
        let keypair = test_keypair();
        let mut content = valid_content();
        content["registration_url"] = json!("https://evil.example.com/register");
        let event = make_announcement(&keypair, &content, 1_750_000_000);

        assert!(parse_announcement(&event).is_none());
    }

    #[test]
    fn bad_domain_single_label_is_rejected() {
        let keypair = test_keypair();
        let mut content = valid_content();
        content["domains"] = json!(["single-label"]);
        let event = make_announcement(&keypair, &content, 1_750_000_000);

        assert!(parse_announcement(&event).is_none());
    }

    #[test]
    fn bad_domain_ip_literal_is_rejected() {
        let keypair = test_keypair();
        let mut content = valid_content();
        content["domains"] = json!(["127.0.0.1"]);
        let event = make_announcement(&keypair, &content, 1_750_000_000);

        assert!(parse_announcement(&event).is_none());
    }

    #[test]
    fn bad_domain_reserved_final_label_is_rejected() {
        let keypair = test_keypair();
        let mut content = valid_content();
        content["domains"] = json!(["foo.example"]);
        let event = make_announcement(&keypair, &content, 1_750_000_000);

        assert!(parse_announcement(&event).is_none());
    }

    #[test]
    fn mismatched_d_tag_is_rejected() {
        let keypair = test_keypair();
        let event = make_announcement_with_tags(
            &keypair,
            &valid_content(),
            1_750_000_000,
            vec![
                json!(["d", "lnaddrd:service:v1:https://not-the-origin.example.com"]),
                json!(["t", SERVICE_TAG]),
            ],
        );

        assert!(parse_announcement(&event).is_none());
    }

    #[test]
    fn missing_d_tag_is_rejected() {
        let keypair = test_keypair();
        let event = make_announcement_with_tags(
            &keypair,
            &valid_content(),
            1_750_000_000,
            vec![json!(["t", SERVICE_TAG])],
        );

        assert!(parse_announcement(&event).is_none());
    }

    #[test]
    fn missing_nostr_auth_capability_sets_flag_false_but_still_parses() {
        let keypair = test_keypair();
        let mut content = valid_content();
        content["capabilities"] = json!(["free-registration", "registration-api-v1"]);
        let event = make_announcement(&keypair, &content, 1_750_000_000);

        let server = parse_announcement(&event).expect("still structurally valid");

        assert!(!server.nostr_auth);
        assert!(server.registration_api);
    }

    #[test]
    fn tampered_content_fails_signature_check() {
        let keypair = test_keypair();
        let mut event = make_announcement(&keypair, &valid_content(), 1_750_000_000);
        // Mutate content after signing without updating id/sig.
        event["content"] = json!("{\"schema\":1,\"origin\":\"https://pay.example.com\"}");

        assert!(parse_announcement(&event).is_none());
    }

    #[test]
    fn wrong_signer_fails_signature_check() {
        let signer = test_keypair();
        let mut event = make_announcement(&signer, &valid_content(), 1_750_000_000);
        // Swap in a different pubkey than the one that actually signed.
        let other_mnemonic = Mnemonic::from_str(
            "legal winner thank year wave sausage worth useful legal winner thank yellow",
        )
        .expect("valid BIP-39 test mnemonic");
        let other = nostr_keypair(&other_mnemonic);
        let (other_x_only, _) = other.x_only_public_key();
        event["pubkey"] = json!(fedimint_core::hex::encode(other_x_only.serialize()));

        assert!(parse_announcement(&event).is_none());
    }

    /// `tampered_content_fails_signature_check` and
    /// `wrong_signer_fails_signature_check` (above) both mutate a field the
    /// id preimage covers, so `parse_announcement` rejects them at the
    /// recomputed-id-equality check — `verify_schnorr` is never reached.
    /// This test instead builds an event that is fully self-consistent (the
    /// recomputed NIP-01 id matches the wire `id`, and `pubkey`/`content`/
    /// `tags`/`created_at` all agree with each other) but signs that
    /// correct id with a *different* keypair than the one named in
    /// `pubkey`, producing a structurally valid 64-byte schnorr signature
    /// that simply does not verify against the claimed signer. This is the
    /// only way to exercise the `verify_schnorr` rejection branch itself
    /// rather than short-circuiting on the id check first.
    #[test]
    fn self_consistent_event_with_invalid_signature_is_rejected() {
        let claimed_signer = test_keypair();
        let actual_signer = nostr_keypair(
            &Mnemonic::from_str(
                "legal winner thank year wave sausage worth useful legal winner thank yellow",
            )
            .expect("valid BIP-39 test mnemonic"),
        );

        let content = valid_content();
        let content_str = serde_json::to_string(&content).expect("content always serializes");
        let created_at = 1_750_000_000u64;
        let tags = vec![
            json!(["d", format!("{D_TAG_PREFIX}https://pay.example.com")]),
            json!(["t", SERVICE_TAG]),
        ];

        let (claimed_x_only, _parity) = claimed_signer.x_only_public_key();
        let pubkey_hex = fedimint_core::hex::encode(claimed_x_only.serialize());

        // Id is computed from claimed_signer's pubkey plus the real
        // content/tags/created_at, so it is exactly the id a genuine event
        // from `claimed_signer` would carry.
        let id = nostr_event_id(
            &pubkey_hex,
            &created_at,
            KIND_SERVICE_ANNOUNCEMENT,
            &tags,
            &content_str,
        );
        let id_hex = fedimint_core::hex::encode(id);

        // Sign that correct id with a DIFFERENT keypair. The signature is
        // well-formed (64 bytes, parses fine) but was never produced by
        // claimed_signer's secret key.
        let secp = Secp256k1::new();
        let message = Message::from_digest(id);
        let sig = secp.sign_schnorr_no_aux_rand(&message, &actual_signer);
        let sig_hex = fedimint_core::hex::encode(sig.serialize());

        let event = json!({
            "id": id_hex,
            "pubkey": pubkey_hex,
            "created_at": created_at,
            "kind": KIND_SERVICE_ANNOUNCEMENT,
            "tags": tags,
            "content": content_str,
            "sig": sig_hex,
        });

        assert!(
            parse_announcement(&event).is_none(),
            "a self-consistent event signed by the wrong key must fail verify_schnorr"
        );
    }

    #[test]
    fn dedupe_newest_wins() {
        let keypair = test_keypair();
        let mut older_content = valid_content();
        older_content["name"] = json!("Old Name");
        let older = make_announcement(&keypair, &older_content, 1_750_000_000);

        let mut newer_content = valid_content();
        newer_content["name"] = json!("New Name");
        let newer = make_announcement(&keypair, &newer_content, 1_750_000_500);

        let servers = merge(&[older, newer]);

        assert_eq!(
            servers.len(),
            1,
            "expected one entry per origin: {servers:?}"
        );
        assert_eq!(servers[0].name, "New Name");
    }

    #[test]
    fn dedupe_newest_wins_regardless_of_array_order() {
        let keypair = test_keypair();
        let mut older_content = valid_content();
        older_content["name"] = json!("Old Name");
        let older = make_announcement(&keypair, &older_content, 1_750_000_000);

        let mut newer_content = valid_content();
        newer_content["name"] = json!("New Name");
        let newer = make_announcement(&keypair, &newer_content, 1_750_000_500);

        // Newer event listed first this time.
        let servers = merge(&[newer, older]);

        assert_eq!(servers.len(), 1);
        assert_eq!(servers[0].name, "New Name");
    }

    #[test]
    fn merge_drops_servers_missing_required_capabilities() {
        let keypair = test_keypair();
        let mut content = valid_content();
        content["capabilities"] = json!(["free-registration"]);
        let event = make_announcement(&keypair, &content, 1_750_000_000);

        let servers = merge(&[event]);

        assert!(
            servers.is_empty(),
            "server missing registration-api-v1/nostr-auth must not be offered: {servers:?}"
        );
    }

    #[test]
    fn merge_ignores_invalid_events_alongside_valid_ones() {
        let keypair = test_keypair();
        let valid = make_announcement(&keypair, &valid_content(), 1_750_000_000);
        let mut bad_content = valid_content();
        bad_content["schema"] = json!(2);
        let invalid = make_announcement(&keypair, &bad_content, 1_750_000_100);

        let servers = merge(&[invalid, valid]);

        assert_eq!(servers.len(), 1);
    }

    #[test]
    fn free_domains_with_no_pricing_entry_counts_as_free() {
        let keypair = test_keypair();
        let mut content = valid_content();
        content["domains"] = json!(["pay.example.com", "tips.example.org"]);
        content.as_object_mut().unwrap().remove("pricing");
        // pricing omitted entirely: both domains are informationally free.
        let event = make_announcement(&keypair, &content, 1_750_000_000);

        let server = parse_announcement(&event).expect("valid announcement must parse");

        assert_eq!(
            server.free_domains,
            vec![
                "pay.example.com".to_string(),
                "tips.example.org".to_string()
            ]
        );
    }

    #[test]
    fn free_domains_excludes_domain_priced_for_typical_lengths() {
        let keypair = test_keypair();
        let mut content = valid_content();
        content["pricing"] = json!([
            {
                "domain": "pay.example.com",
                "currency": "msat",
                "tiers": [
                    {"max_length": 4, "price": 100000}
                ]
            }
        ]);
        let event = make_announcement(&keypair, &content, 1_750_000_000);

        let server = parse_announcement(&event).expect("valid announcement must parse");

        assert!(
            server.free_domains.is_empty(),
            "no zero-price tier covering typical lengths: {:?}",
            server.free_domains
        );
    }

    /// Pins the `TYPICAL_USERNAME_LENGTH` (8) threshold's exclusive side: a
    /// `price == 0` tier whose `max_length` is one below the threshold does
    /// not make the domain free-eligible, even though the price is zero —
    /// the heuristic is about "free for typical-length names", not "has
    /// any zero-price tier at all".
    #[test]
    fn free_domains_excludes_zero_price_tier_below_length_threshold() {
        let keypair = test_keypair();
        let mut content = valid_content();
        content["pricing"] = json!([
            {
                "domain": "pay.example.com",
                "currency": "msat",
                "tiers": [
                    {"max_length": 7, "price": 0}
                ]
            }
        ]);
        let event = make_announcement(&keypair, &content, 1_750_000_000);

        let server = parse_announcement(&event).expect("valid announcement must parse");

        assert!(
            server.free_domains.is_empty(),
            "max_length=7 is below the typical-length threshold of 8: {:?}",
            server.free_domains
        );
    }

    /// Pins the threshold's inclusive side: `max_length == 8` exactly does
    /// count as covering typical lengths.
    #[test]
    fn free_domains_includes_zero_price_tier_at_length_threshold() {
        let keypair = test_keypair();
        let mut content = valid_content();
        content["pricing"] = json!([
            {
                "domain": "pay.example.com",
                "currency": "msat",
                "tiers": [
                    {"max_length": 8, "price": 0}
                ]
            }
        ]);
        let event = make_announcement(&keypair, &content, 1_750_000_000);

        let server = parse_announcement(&event).expect("valid announcement must parse");

        assert_eq!(
            server.free_domains,
            vec!["pay.example.com".to_string()],
            "max_length=8 meets the threshold and should count as free"
        );
    }
}
