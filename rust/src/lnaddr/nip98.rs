//! NIP-98 HTTP Auth event construction.
//!
//! Builds and signs a kind-`27235` Nostr event per
//! [NIP-98](https://github.com/nostr-protocol/nips/blob/master/98.md), in the
//! exact shape `lnaddrd` verifies (see its
//! `docs/protocol/03-registration-api.md#authentication`): a `u` tag with the
//! full request URL (including query string), a `method` tag with the
//! uppercased HTTP method, and a `payload` tag with the lowercase-hex
//! SHA-256 of the request body — present if and only if the request has a
//! body. A `payload` tag on a bodyless request is rejected server-side, so
//! this module never emits one unless `body` is `Some`.
//!
//! Every event also carries a random `nonce` tag. Without it the event is
//! fully deterministic — the NIP-01 id is
//! `H(pubkey, created_at, kind, tags, content)` with a second-granular
//! `created_at`, and the signature is not part of the id — so two identical
//! requests inside the same second would produce the same event id, and the
//! second would trip lnaddrd's replay guard and surface as a misleading
//! "unauthorized — check your device clock". lnaddrd's NIP-98 verifier
//! matches on `u`/`method`/`payload` and ignores every other tag (see its
//! `src/nostr/http_auth.rs`), so the extra tag is inert on the wire.

use base64::Engine;
use bitcoin::secp256k1::rand::{self, RngCore};
use bitcoin::secp256k1::{Keypair, Message, Secp256k1};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

/// NIP-98 HTTP Auth event kind.
const KIND_HTTP_AUTH: u64 = 27235;

/// Computes a Nostr event id (NIP-01): the SHA-256 of the canonical
/// serialization `[0, pubkey, created_at, kind, tags, content]`. Shared by
/// event construction (here) and by announcement verification
/// (`discovery::parse_announcement`), so there is exactly one implementation
/// of the wire rule.
pub(crate) fn nostr_event_id(
    pubkey_hex: &str,
    created_at_secs: &u64,
    kind: u64,
    tags: &[Value],
    content: &str,
) -> [u8; 32] {
    let preimage = (0u8, pubkey_hex, created_at_secs, kind, tags, content);
    let preimage_json =
        serde_json::to_string(&preimage).expect("tuple of primitives always serializes");
    Sha256::digest(preimage_json.as_bytes()).into()
}

/// Builds, signs, and base64-encodes a NIP-98 HTTP Auth event, returning the
/// value for the `Authorization` header: `"Nostr <base64(event-json)>"`.
///
/// `body` is the raw request bytes; pass `None` for a bodyless request (e.g.
/// GET or a bodyless DELETE) so no `payload` tag is emitted.
pub(crate) fn nip98_header(
    keypair: &Keypair,
    url: &str,
    method: &str,
    body: Option<&[u8]>,
    created_at_secs: u64,
) -> String {
    let (x_only, _parity) = keypair.x_only_public_key();
    let pubkey_hex = fedimint_core::hex::encode(x_only.serialize());

    // 16 bytes of OS randomness, so two byte-identical requests in the same
    // second still produce different event ids. See the module doc.
    let mut nonce_bytes = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut nonce_bytes);
    let nonce = fedimint_core::hex::encode(nonce_bytes);

    let mut tags: Vec<Value> = vec![
        json!(["u", url]),
        json!(["method", method.to_uppercase()]),
        json!(["nonce", nonce]),
    ];
    if let Some(body) = body {
        let payload_hex = fedimint_core::hex::encode(Sha256::digest(body));
        tags.push(json!(["payload", payload_hex]));
    }

    let content = "";
    let kind = KIND_HTTP_AUTH;

    let id = nostr_event_id(&pubkey_hex, &created_at_secs, kind, &tags, content);
    let id_hex = fedimint_core::hex::encode(id);

    let secp = Secp256k1::new();
    let message = Message::from_digest(id);
    let sig = secp.sign_schnorr_no_aux_rand(&message, keypair);
    let sig_hex = fedimint_core::hex::encode(sig.serialize());

    let event = json!({
        "id": id_hex,
        "pubkey": pubkey_hex,
        "created_at": created_at_secs,
        "kind": kind,
        "tags": tags,
        "content": content,
        "sig": sig_hex,
    });
    let event_json = serde_json::to_string(&event).expect("json::Value always serializes");
    let encoded = base64::engine::general_purpose::STANDARD.encode(event_json);

    format!("Nostr {encoded}")
}

#[cfg(test)]
mod tests {
    use bitcoin::secp256k1::{Message, Secp256k1, XOnlyPublicKey, schnorr::Signature};
    use serde_json::Value;
    use sha2::{Digest, Sha256};

    use super::*;
    use crate::lnaddr::identity::nostr_keypair;

    const TEST_MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon abandon abandon \
         abandon abandon abandon about";

    fn test_keypair() -> Keypair {
        use std::str::FromStr;

        use fedimint_bip39::Mnemonic;

        nostr_keypair(&Mnemonic::from_str(TEST_MNEMONIC).expect("valid BIP-39 test mnemonic"))
    }

    /// Decodes the `"Nostr <base64>"` header value back into the event JSON.
    fn decode_header(header: &str) -> Value {
        let encoded = header
            .strip_prefix("Nostr ")
            .expect("header must start with \"Nostr \"");
        let decoded = base64::engine::general_purpose::STANDARD
            .decode(encoded)
            .expect("valid padded base64");
        serde_json::from_slice(&decoded).expect("valid event JSON")
    }

    /// Independently recomputes the NIP-01 id preimage hash from decoded
    /// event fields, mirroring the wire rule without reusing production code.
    fn recompute_id_hex(event: &Value) -> String {
        let preimage = (
            0u8,
            event["pubkey"].as_str().expect("pubkey is a string"),
            event["created_at"].as_u64().expect("created_at is a u64"),
            event["kind"].as_u64().expect("kind is a u64"),
            event["tags"].clone(),
            event["content"].as_str().expect("content is a string"),
        );
        let preimage_json = serde_json::to_string(&preimage).expect("tuple always serializes");
        let id: [u8; 32] = Sha256::digest(preimage_json.as_bytes()).into();
        fedimint_core::hex::encode(id)
    }

    #[test]
    fn bodyless_event_shape() {
        let keypair = test_keypair();
        let url = "https://pay.example.com/api/v1/addresses";

        let header = nip98_header(&keypair, url, "get", None, 1_700_000_000);
        let event = decode_header(&header);

        assert_eq!(event["kind"], 27235);
        assert_eq!(event["content"], "");

        let tags = event["tags"].as_array().expect("tags is an array");
        assert!(
            tags.iter().any(|tag| tag[0] == "u" && tag[1] == url),
            "expected a u tag with the full url, got {tags:?}"
        );
        assert!(
            tags.iter().any(|tag| tag[0] == "method" && tag[1] == "GET"),
            "expected an uppercased method tag, got {tags:?}"
        );
        assert!(
            !tags.iter().any(|tag| tag[0] == "payload"),
            "bodyless request must not carry a payload tag, got {tags:?}"
        );
    }

    #[test]
    fn body_event_has_payload_hash() {
        let keypair = test_keypair();
        let url = "https://pay.example.com/api/v1/register";
        let body = b"{}";

        let header = nip98_header(&keypair, url, "POST", Some(body), 1_700_000_000);
        let event = decode_header(&header);

        let expected_hash = fedimint_core::hex::encode(Sha256::digest(body));
        let tags = event["tags"].as_array().expect("tags is an array");
        let payload_tag = tags
            .iter()
            .find(|tag| tag[0] == "payload")
            .expect("payload tag must be present when body is Some");
        assert_eq!(payload_tag[1], expected_hash);
    }

    #[test]
    fn signature_verifies() {
        let keypair = test_keypair();
        let url = "https://pay.example.com/api/v1/addresses";

        let header = nip98_header(&keypair, url, "GET", None, 1_700_000_000);
        let event = decode_header(&header);

        let id_hex = recompute_id_hex(&event);
        let id: [u8; 32] = fedimint_core::hex::decode(&id_hex)
            .expect("valid hex")
            .try_into()
            .expect("32 bytes");
        let message = Message::from_digest(id);

        let pubkey_hex = event["pubkey"].as_str().expect("pubkey is a string");
        let pubkey_bytes = fedimint_core::hex::decode(pubkey_hex).expect("valid hex");
        let pubkey = XOnlyPublicKey::from_slice(&pubkey_bytes).expect("valid x-only public key");

        let sig_hex = event["sig"].as_str().expect("sig is a string");
        let sig_bytes = fedimint_core::hex::decode(sig_hex).expect("valid hex");
        let sig = Signature::from_slice(&sig_bytes).expect("valid schnorr signature");

        let secp = Secp256k1::new();
        secp.verify_schnorr(&sig, &message, &pubkey)
            .expect("signature must verify against the event's own pubkey and id");
    }

    /// Two identical bodyless requests signed inside the same `created_at`
    /// second must still be distinct events. Without the `nonce` tag the id
    /// preimage (`pubkey`, `created_at`, `kind`, `tags`, `content`) would be
    /// byte-identical, the ids would collide, and lnaddrd's replay guard
    /// would reject the retry as "unauthorized — check your device clock".
    #[test]
    fn successive_calls_produce_different_event_ids() {
        let keypair = test_keypair();
        let url = "https://pay.example.com/api/v1/addresses";
        let created_at = 1_700_000_000;

        let first = decode_header(&nip98_header(&keypair, url, "GET", None, created_at));
        let second = decode_header(&nip98_header(&keypair, url, "GET", None, created_at));

        assert_eq!(
            first["created_at"], second["created_at"],
            "this test is only meaningful when both events share a second"
        );
        assert_ne!(
            first["id"], second["id"],
            "same-second retries must not reuse an event id"
        );

        // Each id is still the honest NIP-01 hash of its own preimage.
        assert_eq!(first["id"], recompute_id_hex(&first));
        assert_eq!(second["id"], recompute_id_hex(&second));
    }

    #[test]
    fn nonce_tag_is_present_and_random() {
        let keypair = test_keypair();
        let url = "https://pay.example.com/api/v1/addresses";

        fn nonce_of(event: &Value) -> String {
            event["tags"]
                .as_array()
                .expect("tags is an array")
                .iter()
                .find(|tag| tag[0] == "nonce")
                .expect("every event carries a nonce tag")[1]
                .as_str()
                .expect("nonce is a string")
                .to_string()
        }

        let first = nonce_of(&decode_header(&nip98_header(
            &keypair,
            url,
            "GET",
            None,
            1_700_000_000,
        )));
        let second = nonce_of(&decode_header(&nip98_header(
            &keypair,
            url,
            "GET",
            None,
            1_700_000_000,
        )));

        assert_eq!(first.len(), 32, "16 random bytes, lowercase hex");
        assert!(first.chars().all(|c| c.is_ascii_hexdigit()));
        assert_ne!(first, second);
    }

    #[test]
    fn id_matches_nip01_serialization() {
        let keypair = test_keypair();
        let url = "https://pay.example.com/api/v1/register/start";
        let body = b"{\"domain\":\"pay.example.com\"}";

        let header = nip98_header(&keypair, url, "POST", Some(body), 1_700_000_042);
        let event = decode_header(&header);

        let expected_id = recompute_id_hex(&event);
        assert_eq!(event["id"], expected_id);
    }
}
