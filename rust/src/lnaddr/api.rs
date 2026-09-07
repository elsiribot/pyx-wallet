//! HTTP client for the lnaddrd registration protocol.
//!
//! Talks to an lnaddrd instance over the wire mapping in
//! `docs/protocol/03-registration-api.md`: an unauthenticated quote endpoint,
//! and NIP-98-signed registration/listing/update/removal endpoints. The
//! transport is behind the [`HttpTransport`] trait so tests can substitute a
//! `FakeTransport` that records requests and returns canned responses,
//! without any real network I/O.

use std::time::{SystemTime, UNIX_EPOCH};

use bitcoin::secp256k1::Keypair;
use serde::Deserialize;
use serde_json::json;

use crate::lnaddr::nip98::nip98_header;
use crate::lnurl::strict_uri_encode;

/// Message returned whenever a call gets a `401`. NIP-98 events are only
/// valid for a narrow window around `created_at` (typically ±60s on the
/// server), so a `401` is almost always a clock-skew problem rather than a
/// wrong signature.
const UNAUTHORIZED_MSG: &str = "unauthorized — check your device clock";

/// Abstraction over "send an HTTP request, get back a status and body",
/// implemented for real by [`ReqwestTransport`] and in tests by a
/// request-recording fake.
pub(crate) trait HttpTransport: Send + Sync {
    /// Sends `method` to `url` with `headers` and an optional `body`,
    /// returning the response status code and raw body bytes. Transport-level
    /// failures (DNS, connect, timeout, ...) are surfaced as `Err`.
    fn execute(
        &self,
        method: &str,
        url: &str,
        headers: Vec<(String, String)>,
        body: Option<Vec<u8>>,
    ) -> impl std::future::Future<Output = Result<(u16, Vec<u8>), String>> + Send;
}

/// Production [`HttpTransport`] backed by the crate's pinned `reqwest`
/// client (rustls, 10s timeout).
pub(crate) struct ReqwestTransport {
    client: reqwest::Client,
}

impl ReqwestTransport {
    pub(crate) fn new() -> Self {
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(10))
            .build()
            .expect("reqwest client with the crate's pinned rustls-tls backend always builds");
        Self { client }
    }
}

impl HttpTransport for ReqwestTransport {
    async fn execute(
        &self,
        method: &str,
        url: &str,
        headers: Vec<(String, String)>,
        body: Option<Vec<u8>>,
    ) -> Result<(u16, Vec<u8>), String> {
        let method = reqwest::Method::from_bytes(method.as_bytes())
            .map_err(|e| format!("invalid HTTP method {method}: {e}"))?;
        let mut builder = self.client.request(method, url);
        for (name, value) in headers {
            builder = builder.header(name, value);
        }
        if let Some(body) = body {
            builder = builder.body(body);
        }

        let response = builder
            .send()
            .await
            .map_err(|e| format!("request to {url} failed: {e}"))?;
        let status = response.status().as_u16();
        let bytes = response
            .bytes()
            .await
            .map_err(|e| format!("failed to read response body from {url}: {e}"))?;
        Ok((status, bytes.to_vec()))
    }
}

pub(crate) struct LnaddrApi<T: HttpTransport> {
    transport: T,
    keypair: Keypair,
}

/// Result of an unauthenticated quote for a `domain`/`username` pair.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum QuoteResult {
    /// Free to claim.
    Free,
    /// Claimable for a fee, in millisatoshis.
    PricedMsat(u64),
    /// Already claimed by someone else.
    Taken,
    /// Reserved (e.g. premium/short username) and not claimable this way.
    Reserved,
    /// Server rejected the request; carries its error code.
    Invalid(String),
    /// Too many requests; back off and retry later.
    RateLimited,
}

/// Successful `POST /api/v1/register` response.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct RegisterOk {
    pub address: String,
    pub management_token: Option<String>,
    pub active: bool,
}

/// A failed call, carrying the HTTP status when the failure came from a
/// response rather than from the transport itself.
///
/// Only [`LnaddrApi::remove`] returns this today, because
/// [`crate::lnaddr::LnAddressService::release`] has to decide whether local
/// removal may proceed based on *which* status came back — a decision that
/// was previously made by substring-matching `"401"`/`"404"` against the
/// rendered message, which any URL containing those digits would satisfy.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ApiError {
    pub status: Option<u16>,
    pub message: String,
}

impl ApiError {
    fn from_status(status: u16, message: String) -> Self {
        Self {
            status: Some(status),
            message,
        }
    }

    fn from_transport(message: String) -> Self {
        Self {
            status: None,
            message,
        }
    }
}

impl std::fmt::Display for ApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

/// One entry from `GET /api/v1/addresses`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct OwnedAddress {
    pub domain: String,
    pub username: String,
    pub destination: String,
}

/// `GET /api/v1/register/quote` response body on success.
#[derive(Deserialize)]
struct QuoteResponse {
    price_msat: u64,
}

/// `/api/v1` error body shape: `{"error": "<code>"}`.
#[derive(Deserialize)]
struct ApiErrorBody {
    error: String,
}

/// `POST /api/v1/register` success body.
#[derive(Deserialize)]
struct RegisterResponse {
    address: String,
    management_token: Option<String>,
    active: bool,
}

/// `GET /api/v1/addresses` success body.
#[derive(Deserialize)]
struct AddressesResponse {
    addresses: Vec<OwnedAddressWire>,
}

#[derive(Deserialize)]
struct OwnedAddressWire {
    domain: String,
    username: String,
    destination: String,
}

impl<T: HttpTransport> LnaddrApi<T> {
    pub(crate) fn new(transport: T, keypair: Keypair) -> Self {
        Self { transport, keypair }
    }

    /// Seconds since the Unix epoch, for the NIP-98 `created_at` field.
    fn created_at_secs() -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system clock is after the Unix epoch")
            .as_secs()
    }

    /// Signs a NIP-98 `Authorization` header for `method url`/`body`.
    fn nip98_auth_header(&self, url: &str, method: &str, body: Option<&[u8]>) -> String {
        nip98_header(&self.keypair, url, method, body, Self::created_at_secs())
    }

    /// `Authorization` (NIP-98-signed) + `Content-Type: application/json`
    /// headers for a JSON-body request. Every authenticated endpoint here
    /// except the bodyless `list_owned` GET sends exactly this pair.
    fn json_auth_headers(&self, url: &str, method: &str, body: &[u8]) -> Vec<(String, String)> {
        vec![
            (
                "Authorization".to_string(),
                self.nip98_auth_header(url, method, Some(body)),
            ),
            ("Content-Type".to_string(), "application/json".to_string()),
        ]
    }

    /// `/api/v1` error bodies are JSON `{"error": "<code>"}`; anything that
    /// doesn't parse that way falls back to a status-code message.
    fn api_error_message(status: u16, body: &[u8]) -> String {
        match serde_json::from_slice::<ApiErrorBody>(body) {
            Ok(ApiErrorBody { error }) => format!("lnaddrd error: {error}"),
            Err(_) => format!("lnaddrd request failed with status {status}"),
        }
    }

    /// `POST /api/v1/register` error mapping. Per protocol doc 03,
    /// `payment_required` is specific to this endpoint (the name stopped
    /// being free between quote and claim, e.g. the price changed), so it
    /// gets a distinct, actionable message rather than the generic
    /// `lnaddrd error: <code>` fallback — the UI needs to tell this apart
    /// from an opaque failure.
    fn register_error_message(status: u16, body: &[u8]) -> String {
        match serde_json::from_slice::<ApiErrorBody>(body) {
            Ok(ApiErrorBody { error }) if error == "payment_required" => {
                "payment_required — this name is not free; re-check the quote".to_string()
            }
            Ok(ApiErrorBody { error }) => format!("lnaddrd error: {error}"),
            Err(_) => format!("lnaddrd request failed with status {status}"),
        }
    }

    /// Legacy `/lnaddress/*` endpoints answer errors with a bare status code
    /// and no JSON body.
    fn legacy_error_message(status: u16) -> String {
        format!("lnaddrd request failed with status {status}")
    }

    pub async fn quote(
        &self,
        origin: &str,
        domain: &str,
        username: &str,
    ) -> Result<QuoteResult, String> {
        let url = format!(
            "{origin}/api/v1/register/quote?domain={}&username={}",
            strict_uri_encode(domain),
            strict_uri_encode(username)
        );

        let (status, body) = self
            .transport
            .execute("GET", &url, Vec::new(), None)
            .await?;

        if status == 401 {
            return Err(UNAUTHORIZED_MSG.to_string());
        }

        if let Ok(ApiErrorBody { error }) = serde_json::from_slice::<ApiErrorBody>(&body) {
            // Per protocol doc 03, quote's error set is invalid_input /
            // unsupported_domain / taken / reserved / length_disabled /
            // rate_limited — a priced-but-claimable name is a 200 with
            // `price_msat` set, not an error. `payment_required` is not in
            // this list (it's `register`-only, thrown when the name stops
            // being free between quote and claim), so it falls through to
            // the `Invalid` catch-all here deliberately, same as any other
            // unrecognized code.
            return Ok(match error.as_str() {
                "taken" => QuoteResult::Taken,
                "reserved" => QuoteResult::Reserved,
                "rate_limited" => QuoteResult::RateLimited,
                other => QuoteResult::Invalid(other.to_string()),
            });
        }

        let QuoteResponse { price_msat } = serde_json::from_slice(&body)
            .map_err(|e| format!("invalid quote response body: {e}"))?;
        Ok(if price_msat == 0 {
            QuoteResult::Free
        } else {
            QuoteResult::PricedMsat(price_msat)
        })
    }

    /// NIP-98-signed; server binds owner to our pubkey.
    pub async fn register_free(
        &self,
        origin: &str,
        domain: &str,
        username: &str,
        destination: &str,
    ) -> Result<RegisterOk, String> {
        let url = format!("{origin}/api/v1/register");
        let body = json!({ "domain": domain, "username": username, "destination": destination })
            .to_string()
            .into_bytes();
        let headers = self.json_auth_headers(&url, "POST", &body);

        let (status, response_body) = self
            .transport
            .execute("POST", &url, headers, Some(body))
            .await?;

        if status == 401 {
            return Err(UNAUTHORIZED_MSG.to_string());
        }
        if !(200..300).contains(&status) {
            return Err(Self::register_error_message(status, &response_body));
        }

        let RegisterResponse {
            address,
            management_token,
            active,
        } = serde_json::from_slice(&response_body)
            .map_err(|e| format!("invalid register response body: {e}"))?;
        Ok(RegisterOk {
            address,
            management_token,
            active,
        })
    }

    pub async fn list_owned(&self, origin: &str) -> Result<Vec<OwnedAddress>, String> {
        let url = format!("{origin}/api/v1/addresses");
        let headers = vec![(
            "Authorization".to_string(),
            self.nip98_auth_header(&url, "GET", None),
        )];

        let (status, body) = self.transport.execute("GET", &url, headers, None).await?;

        if status == 401 {
            return Err(UNAUTHORIZED_MSG.to_string());
        }
        if !(200..300).contains(&status) {
            return Err(Self::api_error_message(status, &body));
        }

        let AddressesResponse { addresses } = serde_json::from_slice(&body)
            .map_err(|e| format!("invalid addresses response body: {e}"))?;
        Ok(addresses
            .into_iter()
            .map(|a| OwnedAddress {
                domain: a.domain,
                username: a.username,
                destination: a.destination,
            })
            .collect())
    }

    pub async fn update_destination(
        &self,
        origin: &str,
        domain: &str,
        username: &str,
        destination: &str,
    ) -> Result<(), String> {
        let url = format!("{origin}/lnaddress/update");
        let body = json!({ "domain": domain, "username": username, "destination": destination })
            .to_string()
            .into_bytes();
        let headers = self.json_auth_headers(&url, "PUT", &body);

        let (status, _body) = self
            .transport
            .execute("PUT", &url, headers, Some(body))
            .await?;

        if status == 401 {
            return Err(UNAUTHORIZED_MSG.to_string());
        }
        if !(200..300).contains(&status) {
            return Err(Self::legacy_error_message(status));
        }
        Ok(())
    }

    /// Any `2xx` is success: lnaddrd answers `204`, but a proxy or a future
    /// server version answering `200` still means the address is gone, and
    /// treating that as a failure would strand the local record.
    pub async fn remove(&self, origin: &str, domain: &str, username: &str) -> Result<(), ApiError> {
        let url = format!("{origin}/lnaddress/remove");
        let body = json!({ "domain": domain, "username": username })
            .to_string()
            .into_bytes();
        let headers = self.json_auth_headers(&url, "DELETE", &body);

        let (status, _body) = self
            .transport
            .execute("DELETE", &url, headers, Some(body))
            .await
            .map_err(ApiError::from_transport)?;

        if status == 401 {
            return Err(ApiError::from_status(status, UNAUTHORIZED_MSG.to_string()));
        }
        if !(200..300).contains(&status) {
            return Err(ApiError::from_status(
                status,
                Self::legacy_error_message(status),
            ));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;
    use std::sync::Mutex;

    use base64::Engine;
    use fedimint_bip39::Mnemonic;
    use serde_json::Value;
    use sha2::{Digest, Sha256};

    use super::*;
    use crate::lnaddr::identity::nostr_keypair;

    const TEST_MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon abandon abandon \
         abandon abandon abandon about";

    fn test_keypair() -> Keypair {
        nostr_keypair(&Mnemonic::from_str(TEST_MNEMONIC).expect("valid BIP-39 test mnemonic"))
    }

    #[derive(Debug, Clone)]
    struct RecordedRequest {
        method: String,
        url: String,
        headers: Vec<(String, String)>,
        body: Option<Vec<u8>>,
    }

    /// Records every request it receives and always answers with one canned
    /// `(status, body)` response.
    struct FakeTransport {
        status: u16,
        body: Vec<u8>,
        recorded: Mutex<Vec<RecordedRequest>>,
    }

    impl FakeTransport {
        fn new(status: u16, body: &str) -> Self {
            Self {
                status,
                body: body.as_bytes().to_vec(),
                recorded: Mutex::new(Vec::new()),
            }
        }

        fn last_request(&self) -> RecordedRequest {
            self.recorded
                .lock()
                .unwrap()
                .last()
                .cloned()
                .expect("expected at least one recorded request")
        }
    }

    impl HttpTransport for FakeTransport {
        async fn execute(
            &self,
            method: &str,
            url: &str,
            headers: Vec<(String, String)>,
            body: Option<Vec<u8>>,
        ) -> Result<(u16, Vec<u8>), String> {
            self.recorded.lock().unwrap().push(RecordedRequest {
                method: method.to_string(),
                url: url.to_string(),
                headers,
                body,
            });
            Ok((self.status, self.body.clone()))
        }
    }

    fn header_value<'a>(request: &'a RecordedRequest, name: &str) -> Option<&'a str> {
        request
            .headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }

    /// Decodes a NIP-98 `Authorization: Nostr <base64>` header back into the
    /// event JSON.
    fn decode_nostr_auth(header: &str) -> Value {
        let encoded = header
            .strip_prefix("Nostr ")
            .expect("Authorization header must start with \"Nostr \"");
        let decoded = base64::engine::general_purpose::STANDARD
            .decode(encoded)
            .expect("valid padded base64");
        serde_json::from_slice(&decoded).expect("valid event JSON")
    }

    fn tag<'a>(event: &'a Value, name: &str) -> &'a Value {
        event["tags"]
            .as_array()
            .expect("tags is an array")
            .iter()
            .find(|t| t[0] == name)
            .unwrap_or_else(|| panic!("expected a {name} tag, got {:?}", event["tags"]))
    }

    #[tokio::test]
    async fn quote_free() {
        let transport = FakeTransport::new(200, r#"{"price_msat":0}"#);
        let api = LnaddrApi::new(transport, test_keypair());

        let result = api
            .quote("https://pay.example.com", "example.com", "alice")
            .await
            .expect("quote should succeed");

        assert_eq!(result, QuoteResult::Free);
    }

    #[tokio::test]
    async fn quote_priced() {
        let transport = FakeTransport::new(200, r#"{"price_msat":100000}"#);
        let api = LnaddrApi::new(transport, test_keypair());

        let result = api
            .quote("https://pay.example.com", "example.com", "alice")
            .await
            .expect("quote should succeed");

        assert_eq!(result, QuoteResult::PricedMsat(100_000));
    }

    #[tokio::test]
    async fn quote_taken() {
        let transport = FakeTransport::new(409, r#"{"error":"taken"}"#);
        let api = LnaddrApi::new(transport, test_keypair());

        let result = api
            .quote("https://pay.example.com", "example.com", "alice")
            .await
            .expect("quote should succeed even when the name is taken");

        assert_eq!(result, QuoteResult::Taken);
    }

    #[tokio::test]
    async fn quote_is_unauthenticated_and_url_encodes_params() {
        let transport = FakeTransport::new(200, r#"{"price_msat":0}"#);
        let api = LnaddrApi::new(transport, test_keypair());

        api.quote("https://pay.example.com", "example.com", "a b")
            .await
            .expect("quote should succeed");

        let request = api.transport.last_request();
        assert_eq!(request.method, "GET");
        assert_eq!(
            request.url,
            "https://pay.example.com/api/v1/register/quote?domain=example.com&username=a%20b"
        );
        assert!(
            header_value(&request, "Authorization").is_none(),
            "quote must be unauthenticated"
        );
    }

    #[tokio::test]
    async fn register_sends_nip98_and_parses() {
        let transport = FakeTransport::new(
            200,
            r#"{"address":"alice@example.com","management_token":"t","active":true}"#,
        );
        let api = LnaddrApi::new(transport, test_keypair());

        let result = api
            .register_free(
                "https://pay.example.com",
                "example.com",
                "alice",
                "LNURL1DUMMY",
            )
            .await
            .expect("register should succeed");

        assert_eq!(
            result,
            RegisterOk {
                address: "alice@example.com".to_string(),
                management_token: Some("t".to_string()),
                active: true,
            }
        );

        let request = api.transport.last_request();
        assert_eq!(request.method, "POST");
        assert_eq!(request.url, "https://pay.example.com/api/v1/register");

        let auth = header_value(&request, "Authorization").expect("Authorization header present");
        assert!(auth.starts_with("Nostr "), "got {auth:?}");
        let event = decode_nostr_auth(auth);
        assert_eq!(tag(&event, "u")[1], request.url);

        let body = request.body.clone().expect("register has a body");
        let expected_hash = fedimint_core::hex::encode(Sha256::digest(body.as_slice()));
        assert_eq!(tag(&event, "payload")[1], expected_hash);

        let sent: Value = serde_json::from_slice(&body).expect("body is JSON");
        assert_eq!(sent["domain"], "example.com");
        assert_eq!(sent["username"], "alice");
        assert_eq!(sent["destination"], "LNURL1DUMMY");
    }

    /// `register` (unlike `quote`) can answer `payment_required` when the
    /// name stopped being free between quote and claim (e.g. the price
    /// changed) — per protocol doc 03 this is specific to this endpoint, and
    /// must be surfaced distinctly so the UI can tell "re-check the quote"
    /// apart from an opaque failure.
    #[tokio::test]
    async fn register_maps_payment_required() {
        let transport = FakeTransport::new(402, r#"{"error":"payment_required"}"#);
        let api = LnaddrApi::new(transport, test_keypair());

        let err = api
            .register_free(
                "https://pay.example.com",
                "example.com",
                "alice",
                "LNURL1DUMMY",
            )
            .await
            .expect_err("payment_required must be an error");

        assert!(
            err.contains("payment_required"),
            "expected the error to mention payment_required, got {err:?}"
        );
        assert!(
            err.to_lowercase().contains("quote"),
            "expected the error to point back at re-checking the quote, got {err:?}"
        );
    }

    #[tokio::test]
    async fn list_owned_requires_auth_header() {
        let transport = FakeTransport::new(
            200,
            r#"{"addresses":[{"domain":"example.com","username":"alice","destination":"LNURL1DUMMY"}]}"#,
        );
        let api = LnaddrApi::new(transport, test_keypair());

        let owned = api
            .list_owned("https://pay.example.com")
            .await
            .expect("list_owned should succeed");

        assert_eq!(
            owned,
            vec![OwnedAddress {
                domain: "example.com".to_string(),
                username: "alice".to_string(),
                destination: "LNURL1DUMMY".to_string(),
            }]
        );

        let request = api.transport.last_request();
        assert_eq!(request.method, "GET");
        assert_eq!(request.url, "https://pay.example.com/api/v1/addresses");
        let auth = header_value(&request, "Authorization").expect("Authorization header present");
        assert!(auth.starts_with("Nostr "), "got {auth:?}");
        assert!(request.body.is_none(), "GET must not carry a body");
    }

    #[tokio::test]
    async fn update_maps_400_and_401() {
        let transport = FakeTransport::new(400, "");
        let api = LnaddrApi::new(transport, test_keypair());

        let err = api
            .update_destination(
                "https://pay.example.com",
                "example.com",
                "alice",
                "LNURL1NEW",
            )
            .await
            .expect_err("400 must be an error");
        assert!(!err.to_lowercase().contains("unauthorized"), "got {err:?}");

        let transport = FakeTransport::new(401, "");
        let api = LnaddrApi::new(transport, test_keypair());

        let err = api
            .update_destination(
                "https://pay.example.com",
                "example.com",
                "alice",
                "LNURL1NEW",
            )
            .await
            .expect_err("401 must be an error");
        assert!(
            err.to_lowercase().contains("unauthorized"),
            "expected an unauthorized message mentioning the device clock, got {err:?}"
        );
        assert!(err.to_lowercase().contains("clock"), "got {err:?}");

        let request = api.transport.last_request();
        assert_eq!(request.method, "PUT");
        assert_eq!(request.url, "https://pay.example.com/lnaddress/update");
        let auth = header_value(&request, "Authorization").expect("Authorization header present");
        assert!(auth.starts_with("Nostr "), "got {auth:?}");
    }

    #[tokio::test]
    async fn remove_accepts_any_2xx() {
        let transport = FakeTransport::new(204, "");
        let api = LnaddrApi::new(transport, test_keypair());

        api.remove("https://pay.example.com", "example.com", "alice")
            .await
            .expect("204 must be treated as success");

        let request = api.transport.last_request();
        assert_eq!(request.method, "DELETE");
        assert_eq!(request.url, "https://pay.example.com/lnaddress/remove");
        let auth = header_value(&request, "Authorization").expect("Authorization header present");
        assert!(auth.starts_with("Nostr "), "got {auth:?}");

        // lnaddrd answers 204, but a proxy (or a future server version) may
        // answer 200 for the same outcome; the address is gone either way.
        let transport = FakeTransport::new(200, "");
        let api = LnaddrApi::new(transport, test_keypair());
        api.remove("https://pay.example.com", "example.com", "alice")
            .await
            .expect("any 2xx means the address is gone");
    }

    /// `release` decides whether to drop the local record from
    /// [`ApiError::status`], so the status has to survive the call.
    #[tokio::test]
    async fn remove_errors_carry_their_status() {
        for status in [401u16, 404, 500] {
            let transport = FakeTransport::new(status, "");
            let api = LnaddrApi::new(transport, test_keypair());

            let error = api
                .remove("https://pay.example.com", "example.com", "alice")
                .await
                .expect_err("a non-2xx must be an error");

            assert_eq!(error.status, Some(status));
        }

        // A transport-level failure has no status at all, and must not be
        // mistaken for a recoverable one.
        struct DeadTransport;
        impl HttpTransport for DeadTransport {
            async fn execute(
                &self,
                _method: &str,
                _url: &str,
                _headers: Vec<(String, String)>,
                _body: Option<Vec<u8>>,
            ) -> Result<(u16, Vec<u8>), String> {
                Err("connection refused after 401 attempts".to_string())
            }
        }
        let api = LnaddrApi::new(DeadTransport, test_keypair());
        let error = api
            .remove("https://pay.example.com", "example.com", "alice")
            .await
            .expect_err("a transport failure is an error");
        assert_eq!(error.status, None);
    }
}
