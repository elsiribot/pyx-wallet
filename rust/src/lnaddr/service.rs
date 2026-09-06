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
    DEFAULT_RELAYS, HttpTransport, LnAddressRecord, LnAddressStore, LnaddrApi, OwnedAddress,
    QuoteResult, RegisterOk, ReqwestTransport, discover, nostr_keypair,
};

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

/// Seconds since the Unix epoch, for `claimed_at_secs`.
fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

/// Whether a failed `release` remote call should still let local removal
/// proceed: a `401` (device clock skew, per [`LnaddrApi::remove`]'s own
/// message) or a "not found" response. Either way the server can't do
/// anything useful with this record either, so treating it as an error
/// would just wedge the UI on a dead/unreachable-auth server.
fn is_release_recoverable(error: &str) -> bool {
    let lower = error.to_lowercase();
    lower.contains("401")
        || lower.contains("unauthorized")
        || lower.contains("404")
        || lower.contains("not found")
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
    /// primary invariant forces `is_primary` on the federation's first
    /// record regardless of what's passed in, so the returned record is
    /// re-read from the store rather than built by hand.
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

        self.store
            .upsert(LnAddressRecord {
                domain: domain.to_string(),
                username: name.to_string(),
                server_origin: origin.to_string(),
                federation_id: Some(federation),
                destination,
                is_primary: false,
                claimed_at_secs: now_secs(),
                management_token,
            })
            .await;

        Ok(self
            .store
            .get(domain, name)
            .await
            .expect("just upserted this exact domain/username"))
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
            Err(error) => Err(error),
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
                    tracing::warn!(
                        target: "conduit",
                        domain = %record.domain,
                        username = %record.username,
                        error,
                        "lnaddr: destination sync failed",
                    );
                }
            }
        }
    }

    /// NIP-98 `list_owned` against the default server plus every discovered
    /// one, merging results into the store (records already present are
    /// left untouched) and binding each owned address's federation by
    /// matching its destination against `known` (federation -> current
    /// lnurl); no match leaves it unassigned. Returns the number of newly
    /// added records.
    pub async fn recover(&self, known: Vec<(FederationId, String)>) -> Result<u32, String> {
        let origins: Vec<String> = discover(&DEFAULT_RELAYS)
            .await
            .into_iter()
            .map(|server| server.origin)
            .collect();

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

        fn last_method(&self) -> String {
            self.recorded
                .lock()
                .unwrap()
                .last()
                .cloned()
                .expect("expected at least one recorded request")
                .0
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

    #[tokio::test]
    async fn claim_persists_and_first_is_primary() {
        let transport = FakeTransport::new(
            200,
            r#"{"address":"alice@example.com","management_token":"secret-token","active":true}"#,
        );
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport);
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

        let stored = svc.snapshot().await;
        assert_eq!(stored.len(), 1);
        assert_eq!(stored[0].username, "alice");
    }

    #[tokio::test]
    async fn release_promotes_next() {
        let transport = FakeTransport::new(204, "");
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport);
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
        let transport = FakeTransport::new(401, "");
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport);
        svc.store.upsert(record(None, "LNURL1A", 100)).await;

        svc.release("example.com", "alice")
            .await
            .expect("a 401 must not wedge local removal");

        assert!(svc.store.get("example.com", "alice").await.is_none());
    }

    #[tokio::test]
    async fn release_surfaces_other_errors_without_local_removal() {
        let transport = FakeTransport::new(500, "");
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport);
        svc.store.upsert(record(None, "LNURL1A", 100)).await;

        let result = svc.release("example.com", "alice").await;

        assert!(result.is_err(), "a 500 must surface as an error");
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
        assert_eq!(transport.last_method(), "PUT");
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

    #[tokio::test]
    async fn recover_matches_by_destination() {
        let body = r#"{"addresses":[
            {"domain":"example.com","username":"alice","destination":"LNURL1KNOWN"},
            {"domain":"example.com","username":"ghost","destination":"LNURL1UNKNOWN"}
        ]}"#;
        let transport = FakeTransport::new(200, body);
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport);
        let fed = FederationId::dummy();
        let known = vec![(fed, "LNURL1KNOWN".to_string())];

        let added = svc
            .recover_from_origins(&["https://pay.example.com".to_string()], &known)
            .await;

        assert_eq!(added, 2);
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
        let transport = FakeTransport::new(200, body);
        let svc = LnAddressService::for_test(test_db(), test_keypair(), transport);
        let origins = vec!["https://pay.example.com".to_string()];
        let known: Vec<(FederationId, String)> = Vec::new();

        let first = svc.recover_from_origins(&origins, &known).await;
        assert_eq!(first, 1);

        let second = svc.recover_from_origins(&origins, &known).await;
        assert_eq!(second, 0, "already-known records must not be re-added");
    }
}
