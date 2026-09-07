//! Service layer: claim/sync/recover orchestration over the store, the
//! lnaddrd HTTP client, and NIP-78 discovery.
//!
//! Generic over [`HttpTransport`] so unit tests never touch the network;
//! [`LnAddressServiceImpl`] is the production alias (`ReqwestTransport`) that
//! [`crate::factory::ConduitClientFactory`] actually constructs and holds.

use std::time::{SystemTime, UNIX_EPOCH};

use bitcoin::secp256k1::Keypair;
use fedimint_bip39::Mnemonic;
use fedimint_core::config::FederationId;
use fedimint_core::db::Database;

use super::{
    ApiError, HttpTransport, LnAddressRecord, LnAddressStore, LnaddrApi, OwnedAddress, QuoteResult,
    RegisterOk, ReqwestTransport, default_server_domain, default_server_origin, is_public_domain,
    nostr_keypair,
};

/// lnaddrd's own `Username` cap (`docs/protocol` / its `domain.rs`): at most
/// 64 bytes of ASCII `a-z0-9-_.`.
const MAX_USERNAME_BYTES: usize = 64;

/// RFC 1035's maximum DNS name length, which lnaddrd's `Domain` also enforces.
const MAX_DOMAIN_BYTES: usize = 253;

/// Production alias: the service backed by the crate's pinned reqwest
/// transport. This is what [`crate::factory::ConduitClientFactory`]
/// constructs and holds behind an `Arc` for the wallet's lifetime.
pub(crate) type LnAddressServiceImpl = LnAddressService<ReqwestTransport>;

/// Claim/sync/recover orchestration over [`LnAddressStore`] and
/// [`LnaddrApi`]. Generic over [`HttpTransport`] purely so tests can
/// substitute a request-recording fake; production code only ever
/// instantiates [`LnAddressServiceImpl`] via [`LnAddressService::new`].
pub(crate) struct LnAddressService<T: HttpTransport> {
    store: LnAddressStore,
    api: LnaddrApi<T>,
    /// Reserved for a future surface that exposes the wallet's Nostr
    /// identity directly (e.g. a "my lnaddr pubkey" JNI call); not read by
    /// anything in this task, since [`LnaddrApi`] already carries its own
    /// copy for signing.
    #[allow(dead_code)]
    keypair: Keypair,
}

/// Whether a server-supplied `username` is one this wallet is willing to
/// persist: lnaddrd's own rule (non-empty, at most 64 bytes, lowercase ASCII
/// `a-z0-9`, `-`, `_`, `.`), which is also exactly what the claim path can
/// produce (the claim sheet sanitizes to the same character set).
fn is_valid_username(username: &str) -> bool {
    !username.is_empty()
        && username.len() <= MAX_USERNAME_BYTES
        && username.bytes().all(|byte| {
            byte.is_ascii_lowercase() || byte.is_ascii_digit() || matches!(byte, b'-' | b'_' | b'.')
        })
}

/// Whether a server-supplied `domain` is one this wallet is willing to
/// persist: the same public-registrable-domain rule the claim path's
/// announcements are held to, plus RFC 1035's length cap. The built-in
/// server's own domain is always accepted — under the `lnaddr-debug-server`
/// feature it is deliberately a non-public one.
fn is_valid_recovered_domain(domain: &str) -> bool {
    domain.len() <= MAX_DOMAIN_BYTES
        && (is_public_domain(domain) || domain == default_server_domain())
}

/// Seconds since the Unix epoch, for `claimed_at_secs`.
fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

/// Whether a failed `release` remote call should still let local removal
/// proceed: a `401` (device clock skew, per [`LnaddrApi::remove`]'s own
/// message) or a `404`. Either way the server can't do anything useful with
/// this record either, so treating it as an error would just wedge the UI on
/// a dead/unreachable-auth server.
///
/// Matches on [`ApiError::status`], not on the rendered message: an earlier
/// version substring-matched `"401"`/`"404"` against the error text, which
/// any origin or address containing those digits would satisfy — and which
/// silently classified a transport error mentioning them as recoverable.
fn is_release_recoverable(error: &ApiError) -> bool {
    matches!(error.status, Some(401) | Some(404))
}

impl LnAddressService<ReqwestTransport> {
    /// Production constructor: `db` is the raw factory database (not a
    /// per-client prefix — lnaddr records outlive any single federation),
    /// and `mnemonic` derives both the store's keypair and its own.
    pub fn new(db: Database, mnemonic: &Mnemonic) -> Self {
        let keypair = nostr_keypair(mnemonic);
        Self {
            store: LnAddressStore::new(db),
            api: LnaddrApi::new(ReqwestTransport::new(), keypair),
            keypair,
        }
    }
}

impl<T: HttpTransport> LnAddressService<T> {
    #[cfg(test)]
    fn for_test(db: Database, keypair: Keypair, transport: T) -> Self {
        Self {
            store: LnAddressStore::new(db),
            api: LnaddrApi::new(transport, keypair),
            keypair,
        }
    }

    /// Full list of claimed records, for the UI.
    pub async fn snapshot(&self) -> Vec<LnAddressRecord> {
        self.store.list().await
    }

    pub async fn quote(
        &self,
        origin: &str,
        domain: &str,
        name: &str,
    ) -> Result<QuoteResult, String> {
        self.api.quote(origin, domain, name).await
    }

    /// `destination` is `client.lnurl()` of the federation claiming the
    /// address. Persists the server's `management_token`; the store's
    /// primary invariant may force `is_primary` on the federation's first
    /// record regardless of what's passed in, so the returned record is
    /// preferably re-read from the store rather than the one built here.
    /// That re-read is best-effort, not load-bearing: a concurrent
    /// `release` of this exact domain/username between the `upsert` and
    /// the `get` (a vanishingly rare race, but one this crate has already
    /// been burned by a native panic once) would otherwise turn a `.expect`
    /// into an unwind across the JNI boundary, so a `None` here falls back
    /// to the just-built record instead of panicking.
    pub async fn claim(
        &self,
        origin: &str,
        domain: &str,
        name: &str,
        federation: FederationId,
        destination: String,
    ) -> Result<LnAddressRecord, String> {
        let RegisterOk {
            management_token, ..
        } = self
            .api
            .register_free(origin, domain, name, &destination)
            .await?;

        let built = LnAddressRecord {
            domain: domain.to_string(),
            username: name.to_string(),
            server_origin: origin.to_string(),
            federation_id: Some(federation),
            destination,
            is_primary: false,
            claimed_at_secs: now_secs(),
            management_token,
        };

        self.store.upsert(built.clone()).await;

        Ok(self.store.get(domain, name).await.unwrap_or(built))
    }

    pub async fn set_primary(&self, domain: &str, name: &str) -> Result<(), String> {
        self.store.set_primary(domain, name).await
    }

    /// Remote `DELETE` first; local removal proceeds on success, and also
    /// on a remote `401`/"not found" (see [`is_release_recoverable`]) so a
    /// dead or clock-skewed server can never wedge the UI. Any other remote
    /// error is surfaced without touching the local record. A record that
    /// was already gone locally is a no-op success.
    pub async fn release(&self, domain: &str, name: &str) -> Result<(), String> {
        let Some(record) = self.store.get(domain, name).await else {
            return Ok(());
        };

        match self.api.remove(&record.server_origin, domain, name).await {
            Ok(()) => {
                self.store.remove(domain, name).await;
                Ok(())
            }
            Err(error) if is_release_recoverable(&error) => {
                self.store.remove(domain, name).await;
                Ok(())
            }
            Err(error) => Err(error.message),
        }
    }

    /// Re-points an existing record (typically unassigned, post-recovery)
    /// at `federation`: `PUT`s the new destination, then binds it locally
    /// only once the server accepts it.
    pub async fn repoint(
        &self,
        domain: &str,
        name: &str,
        federation: FederationId,
        destination: String,
    ) -> Result<(), String> {
        let Some(mut record) = self.store.get(domain, name).await else {
            return Err(format!("No lightning address record for {name}@{domain}"));
        };

        self.api
            .update_destination(&record.server_origin, domain, name, &destination)
            .await?;

        record.federation_id = Some(federation);
        record.destination = destination;
        self.store.upsert(record).await;
        Ok(())
    }

    /// On wallet load: for every record bound to `federation` whose stored
    /// destination has drifted from `current_lnurl`, pushes the new
    /// destination and persists it on success. Failures are logged, never
    /// propagated — this runs fire-and-forget off the load path.
    pub async fn sync_destinations(&self, federation: FederationId, current_lnurl: &str) {
        for record in self.store.list().await {
            if record.federation_id != Some(federation) || record.destination == current_lnurl {
                continue;
            }

            match self
                .api
                .update_destination(
                    &record.server_origin,
                    &record.domain,
                    &record.username,
                    current_lnurl,
                )
                .await
            {
                Ok(()) => {
                    let mut updated = record.clone();
                    updated.destination = current_lnurl.to_string();
                    self.store.upsert(updated).await;
                }
                Err(error) => {
                    // Deliberately identifier-free: this log is persisted and
                    // user-exportable (see `logging.rs`), and the address is
                    // a public handle tied to this wallet.
                    tracing::warn!(
                        target: "conduit",
                        error,
                        "lnaddr: destination sync failed",
                    );
                }
            }
        }
    }

    /// NIP-98 `list_owned` against **only** the servers the user already has
    /// a relationship with — the built-in default origin plus every origin
    /// already present in the local store — merging results into the store
    /// (records already present are left untouched) and binding each owned
    /// address's federation by matching its destination against `known`
    /// (federation -> current lnurl); no match leaves it unassigned. Returns
    /// the number of newly added records.
    ///
    /// Deliberately **not** relay discovery: a `list_owned` call attaches a
    /// NIP-98 event signed with the wallet's permanent, seed-derived
    /// identity, so querying a relay-announced origin would hand the wallet's
    /// stable npub (plus IP and timing) to anyone who managed to publish one
    /// valid `kind:30078` announcement, with no user interaction at all —
    /// this runs on every visit to Settings → Lightning addresses. Recovery
    /// still finds everything it promised to for servers the user actually
    /// claimed against; a server the user has never used has nothing of
    /// theirs to return.
    pub async fn recover(&self, known: Vec<(FederationId, String)>) -> Result<u32, String> {
        let mut origins: Vec<String> = vec![default_server_origin().to_string()];
        for record in self.store.list().await {
            if !origins.contains(&record.server_origin) {
                origins.push(record.server_origin);
            }
        }

        Ok(self.recover_from_origins(&origins, &known).await)
    }

    /// [`Self::recover`]'s network-free core: takes the server origin list
    /// as a parameter instead of running discovery itself, so unit tests
    /// exercise the merge/match logic without any I/O.
    async fn recover_from_origins(
        &self,
        origins: &[String],
        known: &[(FederationId, String)],
    ) -> u32 {
        let mut new_count = 0u32;

        for origin in origins {
            // One unreachable/misbehaving server must not sink recovery
            // against every other server.
            let Ok(owned): Result<Vec<OwnedAddress>, String> = self.api.list_owned(origin).await
            else {
                continue;
            };

            for address in owned {
                // A `list_owned` body is server-controlled input. Hold it to
                // the same domain/username rules the claim path enforces, so
                // a compromised or hostile server can't inject a record whose
                // display string impersonates another address (or whose
                // domain/username the rest of the wallet never expected to
                // see) into the local store.
                if !is_valid_recovered_domain(&address.domain)
                    || !is_valid_username(&address.username)
                {
                    continue;
                }

                if self
                    .store
                    .get(&address.domain, &address.username)
                    .await
                    .is_some()
                {
                    continue;
                }

                let federation_id = known
                    .iter()
                    .find(|(_, lnurl)| *lnurl == address.destination)
                    .map(|(federation, _)| *federation);

                self.store
                    .upsert(LnAddressRecord {
                        domain: address.domain,
                        username: address.username,
                        server_origin: origin.clone(),
                        federation_id,
                        destination: address.destination,
                        is_primary: false,
                        // The server does not report when the address was
                        // originally claimed, so every record recovered in
                        // one pass gets the same "now". Accepted: it makes
                        // the store's "promote the oldest remaining record"
                        // rule effectively insertion-ordered after a
                        // recovery, which is arbitrary but stable, and the
                        // user can pick a primary explicitly.
                        claimed_at_secs: now_secs(),
                        management_token: None,
                    })
                    .await;
                new_count += 1;
            }
        }

        new_count
    }
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;
    use std::sync::{Arc, Mutex};

    use fedimint_core::db::mem_impl::MemDatabase;

    use super::*;

    const TEST_MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon abandon abandon \
         abandon abandon abandon about";

    fn test_keypair() -> Keypair {
        nostr_keypair(&Mnemonic::from_str(TEST_MNEMONIC).expect("valid BIP-39 test mnemonic"))
    }

    fn test_db() -> Database {
        Database::new(MemDatabase::new(), Default::default())
    }

    fn record(
        federation_id: Option<FederationId>,
        destination: &str,
        claimed_at_secs: u64,
    ) -> LnAddressRecord {
        LnAddressRecord {
            domain: "example.com".to_string(),
            username: "alice".to_string(),
            server_origin: "https://pay.example.com".to_string(),
            federation_id,
            destination: destination.to_string(),
            is_primary: false,
            claimed_at_secs,
            management_token: None,
        }
    }

    /// Records every request and always answers with one canned
    /// `(status, body)` — enough here since each test drives at most one
    /// endpoint shape per service call under test. Mirrors the
    /// `FakeTransport` in `api::tests` (private to that module, so
    /// duplicated rather than shared).
    struct FakeTransport {
        status: u16,
        body: Vec<u8>,
        recorded: Mutex<Vec<(String, String)>>,
    }

    impl FakeTransport {
        fn new(status: u16, body: &str) -> Self {
            Self {
                status,
                body: body.as_bytes().to_vec(),
                recorded: Mutex::new(Vec::new()),
            }
        }

        fn request_count(&self) -> usize {
            self.recorded.lock().unwrap().len()
        }

        /// `(method, url)` of the most recent request.
        fn last_request(&self) -> (String, String) {
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
            _headers: Vec<(String, String)>,
            _body: Option<Vec<u8>>,
        ) -> Result<(u16, Vec<u8>), String> {
            self.recorded
                .lock()
                .unwrap()
                .push((method.to_string(), url.to_string()));
            Ok((self.status, self.body.clone()))
        }
    }

    /// Lets a test retain its own handle to a `FakeTransport` (to inspect
    /// recorded requests afterwards) while still handing an owned
    /// `HttpTransport` into the service, which takes its transport by
    /// value. Blanket impl is legal here (orphan rules only require the
    /// *trait* to be local, which `HttpTransport` is).
    impl<U: HttpTransport> HttpTransport for Arc<U> {
        async fn execute(
            &self,
            method: &str,
            url: &str,
            headers: Vec<(String, String)>,
            body: Option<Vec<u8>>,
        ) -> Result<(u16, Vec<u8>), String> {
            self.as_ref().execute(method, url, headers, body).await
        }
    }

    /// Answers with different `(status, body)` per URL prefix, or an `Err`
    /// for anything unlisted. Lets `recover_skips_failing_server_but_uses_others`
    /// exercise per-server failure isolation, which a single-canned-response
    /// `FakeTransport` can't: each origin needs its own outcome.
    struct MixedTransport {
        responses: Vec<(String, Result<(u16, Vec<u8>), String>)>,
        recorded: Mutex<Vec<String>>,
    }

    impl MixedTransport {
        fn new(responses: Vec<(String, Result<(u16, Vec<u8>), String>)>) -> Self {
            Self {
                responses,
                recorded: Mutex::new(Vec::new()),
            }
        }

        /// Every URL this transport was asked for, in order.
        fn urls(&self) -> Vec<String> {
            self.recorded.lock().unwrap().clone()
        }
    }

    impl HttpTransport for MixedTransport {
        async fn execute(
            &self,
            _method: &str,
            url: &str,
            _headers: Vec<(String, String)>,
            _body: Option<Vec<u8>>,
        ) -> Result<(u16, Vec<u8>), String> {
            self.recorded.lock().unwrap().push(url.to_string());
            self.responses
                .iter()
                .find(|(prefix, _)| url.starts_with(prefix.as_str()))
                .map(|(_, result)| result.clone())
                .unwrap_or_else(|| Err(format!("unexpected url in test transport: {url}")))
        }
    }

    #[tokio::test]
    async fn claim_persists_and_first_is_primary() {
        let transport = Arc::new(FakeTransport::new(
            200,
            r#"{"address":"alice@example.com","management_token":"secret-token","active":true}"#,
        ));
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport.clone());
        let fed = FederationId::dummy();

        let record = svc
            .claim(
                "https://pay.example.com",
                "example.com",
                "alice",
                fed,
                "LNURL1DUMMY".to_string(),
            )
            .await
            .expect("claim should succeed");

        assert_eq!(record.domain, "example.com");
        assert_eq!(record.username, "alice");
        assert_eq!(record.federation_id, Some(fed));
        assert_eq!(record.destination, "LNURL1DUMMY");
        assert!(
            record.is_primary,
            "first record of a federation must be primary"
        );
        assert_eq!(record.management_token, Some("secret-token".to_string()));

        assert_eq!(transport.request_count(), 1);
        let (method, url) = transport.last_request();
        assert_eq!(method, "POST");
        assert_eq!(url, "https://pay.example.com/api/v1/register");

        let stored = svc.snapshot().await;
        assert_eq!(stored.len(), 1);
        assert_eq!(stored[0].username, "alice");
    }

    #[tokio::test]
    async fn release_promotes_next() {
        let transport = Arc::new(FakeTransport::new(204, ""));
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport.clone());
        let fed = FederationId::dummy();

        svc.store
            .upsert(LnAddressRecord {
                username: "alice".to_string(),
                is_primary: true,
                claimed_at_secs: 100,
                ..record(Some(fed), "LNURL1A", 100)
            })
            .await;
        svc.store
            .upsert(LnAddressRecord {
                username: "bob".to_string(),
                is_primary: false,
                claimed_at_secs: 200,
                ..record(Some(fed), "LNURL1B", 200)
            })
            .await;

        svc.release("example.com", "alice")
            .await
            .expect("release should succeed on a 204");

        assert_eq!(transport.request_count(), 1);
        let (method, url) = transport.last_request();
        assert_eq!(method, "DELETE");
        assert_eq!(url, "https://pay.example.com/lnaddress/remove");

        assert!(svc.store.get("example.com", "alice").await.is_none());
        assert!(
            svc.store
                .get("example.com", "bob")
                .await
                .expect("bob still present")
                .is_primary,
            "the remaining record must be promoted to primary"
        );
    }

    #[tokio::test]
    async fn release_removes_locally_on_remote_401() {
        let transport = Arc::new(FakeTransport::new(401, ""));
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport.clone());
        svc.store.upsert(record(None, "LNURL1A", 100)).await;

        svc.release("example.com", "alice")
            .await
            .expect("a 401 must not wedge local removal");

        assert_eq!(
            transport.request_count(),
            1,
            "release must still attempt the remote DELETE before falling back"
        );
        assert_eq!(transport.last_request().0, "DELETE");
        assert!(svc.store.get("example.com", "alice").await.is_none());
    }

    #[tokio::test]
    async fn release_removes_locally_on_remote_404() {
        let transport = Arc::new(FakeTransport::new(404, ""));
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport.clone());
        svc.store.upsert(record(None, "LNURL1A", 100)).await;

        svc.release("example.com", "alice")
            .await
            .expect("a 404 must not wedge local removal");

        assert_eq!(transport.request_count(), 1);
        assert!(svc.store.get("example.com", "alice").await.is_none());
    }

    #[tokio::test]
    async fn release_surfaces_other_errors_without_local_removal() {
        let transport = Arc::new(FakeTransport::new(500, ""));
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport.clone());
        svc.store.upsert(record(None, "LNURL1A", 100)).await;

        let result = svc.release("example.com", "alice").await;

        assert!(result.is_err(), "a 500 must surface as an error");
        assert_eq!(
            transport.request_count(),
            1,
            "the remote DELETE must still have been attempted"
        );
        assert!(
            svc.store.get("example.com", "alice").await.is_some(),
            "the record must survive an unrecoverable remote error"
        );
    }

    /// The recoverable-release decision keys off the real HTTP status, not
    /// off digits in the rendered message. A `500` from a server whose origin
    /// happens to contain "404" used to be classified recoverable — the local
    /// record was deleted while the server still held the address.
    #[tokio::test]
    async fn release_does_not_treat_a_404_shaped_url_as_a_404() {
        let transport = Arc::new(FakeTransport::new(500, ""));
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport.clone());
        svc.store
            .upsert(LnAddressRecord {
                server_origin: "https://pay404.example.com".to_string(),
                ..record(None, "LNURL1A", 100)
            })
            .await;

        let result = svc.release("example.com", "alice").await;

        assert!(
            result.is_err(),
            "a 500 is a 500 however the origin is spelled"
        );
        assert!(
            svc.store.get("example.com", "alice").await.is_some(),
            "the record must survive an unrecoverable remote error"
        );
    }

    #[tokio::test]
    async fn sync_updates_on_drift() {
        let transport = Arc::new(FakeTransport::new(200, ""));
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport.clone());
        let fed = FederationId::dummy();

        svc.store.upsert(record(Some(fed), "LNURL1OLD", 100)).await;

        svc.sync_destinations(fed, "LNURL1NEW").await;

        assert_eq!(
            transport.request_count(),
            1,
            "drifted destination must trigger exactly one request"
        );
        assert_eq!(transport.last_request().0, "PUT");
        assert_eq!(
            svc.store
                .get("example.com", "alice")
                .await
                .expect("record still present")
                .destination,
            "LNURL1NEW"
        );

        svc.sync_destinations(fed, "LNURL1NEW").await;

        assert_eq!(
            transport.request_count(),
            1,
            "a destination that already matches must not trigger a request"
        );
    }

    /// Recovery only ever talks to servers the user already has a
    /// relationship with: the built-in default origin, plus origins already
    /// present in the local store. An origin that merely announced itself on
    /// a public relay must never be queried — `list_owned` attaches a NIP-98
    /// event signed with the wallet's permanent seed-derived identity, so a
    /// single query would disclose the wallet's stable npub to whoever
    /// published that announcement.
    #[tokio::test]
    async fn recover_queries_only_the_default_and_locally_known_origins() {
        let ok_body = br#"{"addresses":[]}"#;
        let transport = Arc::new(MixedTransport::new(vec![
            (
                default_server_origin().to_string(),
                Ok((200, ok_body.to_vec())),
            ),
            (
                "https://claimed.example.com".to_string(),
                Ok((200, ok_body.to_vec())),
            ),
        ]));
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport.clone());

        // One record the user actually claimed, on a non-default server.
        svc.store
            .upsert(LnAddressRecord {
                server_origin: "https://claimed.example.com".to_string(),
                ..record(None, "LNURL1A", 100)
            })
            .await;

        svc.recover(Vec::new())
            .await
            .expect("recover must not fail when every queried origin answers");

        let urls = transport.urls();
        assert_eq!(
            urls.len(),
            2,
            "expected exactly the default origin and the one claimed origin: {urls:?}"
        );
        assert!(
            urls.iter()
                .any(|url| url.starts_with(default_server_origin())),
            "the built-in default server must always be queried: {urls:?}"
        );
        assert!(
            urls.iter()
                .any(|url| url.starts_with("https://claimed.example.com")),
            "an origin the user has claimed against must be queried: {urls:?}"
        );
        assert!(
            !urls
                .iter()
                .any(|url| url.contains("attacker") || url.contains("relay")),
            "no relay-discovered origin may be queried: {urls:?}"
        );
    }

    /// The same guarantee stated as a property: an origin that is neither the
    /// default nor in the store is never contacted, however it was announced.
    #[tokio::test]
    async fn recover_never_queries_an_unknown_origin() {
        let transport = Arc::new(MixedTransport::new(vec![(
            default_server_origin().to_string(),
            Ok((200, br#"{"addresses":[]}"#.to_vec())),
        )]));
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport.clone());

        svc.recover(Vec::new()).await.expect("recover succeeds");

        let urls = transport.urls();
        assert_eq!(
            urls,
            vec![format!("{}/api/v1/addresses", default_server_origin())],
            "an empty store must yield exactly one query, to the built-in default"
        );
    }

    /// `list_owned` bodies are server-controlled. Anything failing the
    /// claim-path domain/username rules is dropped rather than persisted.
    #[tokio::test]
    async fn recover_drops_records_failing_validation() {
        let body = r#"{"addresses":[
            {"domain":"example.com","username":"alice","destination":"LNURL1KNOWN"},
            {"domain":"localhost","username":"bob","destination":"LNURL1KNOWN"},
            {"domain":"single-label","username":"carol","destination":"LNURL1KNOWN"},
            {"domain":"evil.example","username":"dave","destination":"LNURL1KNOWN"},
            {"domain":"example.com","username":"Eve Smith!","destination":"LNURL1KNOWN"},
            {"domain":"example.com","username":"","destination":"LNURL1KNOWN"}
        ]}"#;
        let transport = Arc::new(FakeTransport::new(200, body));
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport.clone());

        let added = svc
            .recover_from_origins(&["https://pay.example.com".to_string()], &[])
            .await;

        assert_eq!(added, 1, "only the well-formed record may be persisted");
        let stored = svc.snapshot().await;
        assert_eq!(stored.len(), 1);
        assert_eq!(stored[0].username, "alice");
        assert_eq!(stored[0].domain, "example.com");
    }

    #[tokio::test]
    async fn recover_rejects_an_over_long_username_or_domain() {
        let long_username = "a".repeat(MAX_USERNAME_BYTES + 1);
        // Four maximum-length labels: 4*63 + 3 dots = 255 bytes, over RFC
        // 1035's 253-byte cap, while every individual label stays legal.
        let label = "a".repeat(63);
        let long_domain = [label.as_str(); 4].join(".");
        assert!(long_domain.len() > MAX_DOMAIN_BYTES);
        let body = format!(
            r#"{{"addresses":[
                {{"domain":"example.com","username":"{long_username}","destination":"L"}},
                {{"domain":"{long_domain}","username":"bob","destination":"L"}}
            ]}}"#
        );
        let transport = Arc::new(FakeTransport::new(200, &body));
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport.clone());

        let added = svc
            .recover_from_origins(&["https://pay.example.com".to_string()], &[])
            .await;

        assert_eq!(added, 0, "both records exceed a documented cap");
        assert!(svc.snapshot().await.is_empty());
    }

    #[tokio::test]
    async fn recover_matches_by_destination() {
        let body = r#"{"addresses":[
            {"domain":"example.com","username":"alice","destination":"LNURL1KNOWN"},
            {"domain":"example.com","username":"ghost","destination":"LNURL1UNKNOWN"}
        ]}"#;
        let transport = Arc::new(FakeTransport::new(200, body));
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport.clone());
        let fed = FederationId::dummy();
        let known = vec![(fed, "LNURL1KNOWN".to_string())];

        let added = svc
            .recover_from_origins(&["https://pay.example.com".to_string()], &known)
            .await;

        assert_eq!(added, 2);
        assert_eq!(transport.request_count(), 1, "one GET per origin");
        let (method, url) = transport.last_request();
        assert_eq!(method, "GET");
        assert_eq!(url, "https://pay.example.com/api/v1/addresses");

        assert_eq!(
            svc.store
                .get("example.com", "alice")
                .await
                .expect("alice present")
                .federation_id,
            Some(fed)
        );
        assert_eq!(
            svc.store
                .get("example.com", "ghost")
                .await
                .expect("ghost present")
                .federation_id,
            None,
            "an owned address with no matching known destination stays unassigned"
        );
    }

    #[tokio::test]
    async fn recover_is_idempotent() {
        let body = r#"{"addresses":[{"domain":"example.com","username":"alice","destination":"LNURL1KNOWN"}]}"#;
        let transport = Arc::new(FakeTransport::new(200, body));
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport.clone());
        let origins = vec!["https://pay.example.com".to_string()];
        let known: Vec<(FederationId, String)> = Vec::new();

        let first = svc.recover_from_origins(&origins, &known).await;
        assert_eq!(first, 1);

        let second = svc.recover_from_origins(&origins, &known).await;
        assert_eq!(second, 0, "already-known records must not be re-added");

        assert_eq!(
            transport.request_count(),
            2,
            "each recover call still lists the server, even when nothing new comes of it"
        );
    }

    #[tokio::test]
    async fn recover_skips_failing_server_but_uses_others() {
        let ok_body = br#"{"addresses":[{"domain":"example.com","username":"alice","destination":"LNURL1KNOWN"}]}"#;
        let transport = MixedTransport::new(vec![
            (
                "https://dead.example.com".to_string(),
                Err("connection refused".to_string()),
            ),
            (
                "https://pay.example.com".to_string(),
                Ok((200, ok_body.to_vec())),
            ),
        ]);
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport);
        let origins = vec![
            "https://dead.example.com".to_string(),
            "https://pay.example.com".to_string(),
        ];
        let known: Vec<(FederationId, String)> = Vec::new();

        let added = svc.recover_from_origins(&origins, &known).await;

        assert_eq!(
            added, 1,
            "a failing server must not block recovery from the others"
        );
        assert!(svc.store.get("example.com", "alice").await.is_some());
    }
}
