//! Durable store for claimed Lightning addresses, with the invariant that at
//! most one record per assigned federation is marked primary.
//!
//! Unassigned records (`federation_id: None`, e.g. right after wallet
//! recovery before the address is re-linked to a federation) are outside the
//! invariant's domain and are never primary.

use fedimint_core::config::FederationId;
use fedimint_core::db::{Database, IDatabaseTransactionOpsCoreTyped};
use fedimint_core::encoding::{Decodable, Encodable};
use fedimint_core::{impl_db_lookup, impl_db_record};
use futures_util::StreamExt;

use crate::db::DbKeyPrefix;

#[derive(Clone, Debug, Encodable, Decodable)]
pub(crate) struct LnAddressKey(pub(crate) String, pub(crate) String);

#[derive(Clone, Debug, Encodable, Decodable)]
pub(crate) struct LnAddressPrefix;

#[derive(Clone, Debug, Encodable, Decodable)]
pub(crate) struct LnAddressRecord {
    pub domain: String,
    pub username: String,
    /// Normalized https origin, no trailing slash.
    pub server_origin: String,
    /// `None` means unassigned (e.g. post-recovery, before the address is
    /// re-linked to a federation).
    pub federation_id: Option<FederationId>,
    /// LNURL last pushed to the server.
    pub destination: String,
    /// Invariant: at most one `true` per distinct `Some(federation_id)`.
    pub is_primary: bool,
    pub claimed_at_secs: u64,
    /// Stored, unused escape hatch.
    pub management_token: Option<String>,
}

impl_db_record!(
    key = LnAddressKey,
    value = LnAddressRecord,
    db_prefix = DbKeyPrefix::LnAddress,
);

impl_db_lookup!(key = LnAddressKey, query_prefix = LnAddressPrefix);

pub(crate) struct LnAddressStore {
    db: Database,
}

impl LnAddressStore {
    pub fn new(db: Database) -> Self {
        Self { db }
    }

    pub async fn list(&self) -> Vec<LnAddressRecord> {
        self.db
            .begin_transaction_nc()
            .await
            .find_by_prefix(&LnAddressPrefix)
            .await
            .map(|(_, record)| record)
            .collect()
            .await
    }

    pub async fn get(&self, domain: &str, username: &str) -> Option<LnAddressRecord> {
        self.db
            .begin_transaction_nc()
            .await
            .get_value(&LnAddressKey(domain.to_string(), username.to_string()))
            .await
    }

    /// Inserts/replaces. If `record.is_primary`, clears `is_primary` on every
    /// other record with the same `federation_id`. If it's the federation's
    /// first record, forces `is_primary = true`. Unassigned records
    /// (`federation_id: None`) are always forced to `is_primary = false`.
    ///
    /// Invariant: after this call returns, every federation with at least one
    /// record has exactly one primary. Two things follow from that: a
    /// re-upsert of an existing primary record that doesn't request
    /// `is_primary` preserves its primary status instead of vacating the
    /// seat, and if the record moved out of (or dropped) a federation it
    /// used to be primary for, the oldest remaining record of that old
    /// federation is promoted.
    pub async fn upsert(&self, mut record: LnAddressRecord) {
        let key = LnAddressKey(record.domain.clone(), record.username.clone());
        let mut dbtx = self.db.begin_transaction().await;

        let existing = dbtx.get_value(&key).await;
        let was_primary_in_same_federation = existing
            .as_ref()
            .is_some_and(|old| old.is_primary && old.federation_id == record.federation_id);

        match record.federation_id {
            Some(federation_id) => {
                let others = other_records_for_federation(&mut dbtx, &key, federation_id).await;

                if others.is_empty() {
                    // Only record claimed for this federation is always primary.
                    record.is_primary = true;
                } else if record.is_primary {
                    demote_primaries(&mut dbtx, others).await;
                } else if was_primary_in_same_federation {
                    // Don't silently vacate the primary seat just because a
                    // re-upsert (e.g. a destination refresh) didn't ask for it.
                    record.is_primary = true;
                }
            }
            None => record.is_primary = false,
        }

        dbtx.insert_entry(&key, &record).await;

        // Safety net: if the record moved away from (or dropped out of) a
        // federation it used to be primary for, that federation may now have
        // records but no primary. Promote its oldest remaining record.
        if let Some(old_federation_id) = existing.and_then(|old| old.federation_id) {
            if Some(old_federation_id) != record.federation_id {
                ensure_primary_exists(&mut dbtx, old_federation_id).await;
            }
        }

        dbtx.commit_tx().await;
    }

    pub async fn set_primary(&self, domain: &str, username: &str) -> Result<(), String> {
        let key = LnAddressKey(domain.to_string(), username.to_string());
        let mut dbtx = self.db.begin_transaction().await;

        let Some(mut target) = dbtx.get_value(&key).await else {
            return Err(format!(
                "No lightning address record for {username}@{domain}"
            ));
        };

        let Some(federation_id) = target.federation_id else {
            return Err(format!(
                "{username}@{domain} is unassigned and cannot be made primary"
            ));
        };

        let others = other_records_for_federation(&mut dbtx, &key, federation_id).await;
        demote_primaries(&mut dbtx, others).await;

        target.is_primary = true;
        dbtx.insert_entry(&key, &target).await;
        dbtx.commit_tx().await;
        Ok(())
    }

    /// Removes; if it was primary, promotes the oldest (`claimed_at_secs`)
    /// remaining record of the same federation.
    pub async fn remove(&self, domain: &str, username: &str) {
        let key = LnAddressKey(domain.to_string(), username.to_string());
        let mut dbtx = self.db.begin_transaction().await;

        let Some(removed) = dbtx.get_value(&key).await else {
            dbtx.commit_tx().await;
            return;
        };

        dbtx.remove_entry(&key).await;

        if removed.is_primary {
            if let Some(federation_id) = removed.federation_id {
                ensure_primary_exists(&mut dbtx, federation_id).await;
            }
        }

        dbtx.commit_tx().await;
    }

    pub async fn primary_for(&self, federation: &FederationId) -> Option<LnAddressRecord> {
        self.list()
            .await
            .into_iter()
            .find(|record| record.federation_id == Some(*federation) && record.is_primary)
    }
}

/// All stored records for `federation_id` other than `exclude`.
async fn other_records_for_federation<Cap: Send>(
    dbtx: &mut fedimint_core::db::DatabaseTransaction<'_, Cap>,
    exclude: &LnAddressKey,
    federation_id: FederationId,
) -> Vec<(LnAddressKey, LnAddressRecord)> {
    dbtx.find_by_prefix(&LnAddressPrefix)
        .await
        .collect::<Vec<_>>()
        .await
        .into_iter()
        .filter(|(key, record)| {
            (key.0 != exclude.0 || key.1 != exclude.1)
                && record.federation_id == Some(federation_id)
        })
        .collect()
}

/// Clears `is_primary` on every record in `records` that currently has it set.
async fn demote_primaries<Cap: Send>(
    dbtx: &mut fedimint_core::db::DatabaseTransaction<'_, Cap>,
    records: Vec<(LnAddressKey, LnAddressRecord)>,
) {
    for (key, mut record) in records {
        if record.is_primary {
            record.is_primary = false;
            dbtx.insert_entry(&key, &record).await;
        }
    }
}

/// If `federation_id` has at least one record but none of them are primary,
/// promotes the oldest (`claimed_at_secs`) one. No-op if it already has a
/// primary or has no records at all.
async fn ensure_primary_exists<Cap: Send>(
    dbtx: &mut fedimint_core::db::DatabaseTransaction<'_, Cap>,
    federation_id: FederationId,
) {
    let mut records: Vec<(LnAddressKey, LnAddressRecord)> = dbtx
        .find_by_prefix(&LnAddressPrefix)
        .await
        .collect::<Vec<_>>()
        .await
        .into_iter()
        .filter(|(_, record)| record.federation_id == Some(federation_id))
        .collect();

    if records.iter().any(|(_, record)| record.is_primary) {
        return;
    }

    records.sort_by_key(|(_, record)| record.claimed_at_secs);

    if let Some((oldest_key, mut oldest_record)) = records.into_iter().next() {
        oldest_record.is_primary = true;
        dbtx.insert_entry(&oldest_key, &oldest_record).await;
    }
}

#[cfg(test)]
mod tests {
    use fedimint_core::config::FederationId;
    use fedimint_core::db::Database;
    use fedimint_core::db::mem_impl::MemDatabase;

    use super::*;

    fn test_store() -> LnAddressStore {
        LnAddressStore::new(Database::new(MemDatabase::new(), Default::default()))
    }

    fn record(
        domain: &str,
        username: &str,
        federation_id: Option<FederationId>,
        is_primary: bool,
        claimed_at_secs: u64,
    ) -> LnAddressRecord {
        LnAddressRecord {
            domain: domain.to_string(),
            username: username.to_string(),
            server_origin: "https://example.com".to_string(),
            federation_id,
            destination: "LNURL1DUMMY".to_string(),
            is_primary,
            claimed_at_secs,
            management_token: None,
        }
    }

    #[tokio::test]
    async fn first_record_of_federation_becomes_primary() {
        let store = test_store();
        let fed = FederationId::dummy();

        store
            .upsert(record("example.com", "alice", Some(fed), false, 100))
            .await;

        let stored = store.get("example.com", "alice").await.unwrap();
        assert!(stored.is_primary);
    }

    #[tokio::test]
    async fn second_primary_demotes_first() {
        let store = test_store();
        let fed = FederationId::dummy();

        store
            .upsert(record("example.com", "alice", Some(fed), true, 100))
            .await;
        store
            .upsert(record("example.com", "bob", Some(fed), true, 200))
            .await;

        assert!(!store.get("example.com", "alice").await.unwrap().is_primary);
        assert!(store.get("example.com", "bob").await.unwrap().is_primary);
    }

    #[tokio::test]
    async fn set_primary_switches() {
        let store = test_store();
        let fed = FederationId::dummy();

        store
            .upsert(record("example.com", "alice", Some(fed), true, 100))
            .await;
        store
            .upsert(record("example.com", "bob", Some(fed), false, 200))
            .await;

        store.set_primary("example.com", "bob").await.unwrap();

        assert!(!store.get("example.com", "alice").await.unwrap().is_primary);
        assert!(store.get("example.com", "bob").await.unwrap().is_primary);
    }

    #[tokio::test]
    async fn set_primary_unknown_errors() {
        let store = test_store();

        let result = store.set_primary("example.com", "ghost").await;

        assert!(result.is_err());
    }

    #[tokio::test]
    async fn set_primary_on_unassigned_record_errors() {
        let store = test_store();

        store
            .upsert(record("example.com", "alice", None, false, 100))
            .await;

        let result = store.set_primary("example.com", "alice").await;

        assert!(result.is_err());
    }

    #[tokio::test]
    async fn reupsert_without_primary_flag_preserves_existing_primary() {
        let store = test_store();
        let fed = FederationId::dummy();

        // A is the federation's first record, forced primary.
        store
            .upsert(record("example.com", "alice", Some(fed), false, 100))
            .await;
        // B joins, not primary.
        store
            .upsert(record("example.com", "bob", Some(fed), false, 200))
            .await;
        assert!(store.get("example.com", "alice").await.unwrap().is_primary);

        // Re-upsert A with is_primary: false and a changed destination, e.g.
        // a routine destination refresh that doesn't know or care it's
        // touching the primary record.
        let mut refreshed_alice = record("example.com", "alice", Some(fed), false, 100);
        refreshed_alice.destination = "LNURL1CHANGED".to_string();
        store.upsert(refreshed_alice).await;

        let alice = store.get("example.com", "alice").await.unwrap();
        assert!(
            alice.is_primary,
            "re-upsert must not vacate the primary seat"
        );
        assert_eq!(alice.destination, "LNURL1CHANGED");
        assert!(!store.get("example.com", "bob").await.unwrap().is_primary);

        let primary = store.primary_for(&fed).await;
        assert_eq!(
            primary.map(|record| record.username),
            Some("alice".to_string())
        );
    }

    #[tokio::test]
    async fn remove_primary_promotes_oldest() {
        let store = test_store();
        let fed = FederationId::dummy();

        // First upsert of the federation, forced primary regardless of the flag.
        store
            .upsert(record("example.com", "alice", Some(fed), false, 300))
            .await;
        // Second record, not primary, but the oldest by claimed_at_secs.
        store
            .upsert(record("example.com", "bob", Some(fed), false, 100))
            .await;
        // Third record takes over as primary, demoting alice.
        store
            .upsert(record("example.com", "carol", Some(fed), true, 200))
            .await;
        assert!(store.get("example.com", "carol").await.unwrap().is_primary);

        store.remove("example.com", "carol").await;

        assert!(store.get("example.com", "bob").await.unwrap().is_primary);
        assert!(!store.get("example.com", "alice").await.unwrap().is_primary);
    }

    #[tokio::test]
    async fn unassigned_records_never_primary() {
        let store = test_store();

        store
            .upsert(record("example.com", "alice", None, true, 100))
            .await;

        assert!(!store.get("example.com", "alice").await.unwrap().is_primary);
    }

    #[tokio::test]
    async fn primary_for_returns_current_primary() {
        let store = test_store();
        let fed = FederationId::dummy();

        store
            .upsert(record("example.com", "alice", Some(fed), false, 100))
            .await;

        let primary = store.primary_for(&fed).await.unwrap();
        assert_eq!(primary.username, "alice");
    }

    #[tokio::test]
    async fn get_returns_none_for_unknown_address() {
        let store = test_store();

        assert!(store.get("example.com", "ghost").await.is_none());
    }
}
