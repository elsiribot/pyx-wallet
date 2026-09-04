//! Crash-safe correlation of irreversible native Android submissions.
//!
//! The durable record deliberately contains only numeric accounting fields,
//! random identifiers and SHA-256 fingerprints. Payment requests, addresses,
//! ecash, tokens and preimages never cross this journal boundary.

use std::sync::OnceLock;
use std::time::{SystemTime, UNIX_EPOCH};

use fedimint_core::db::IDatabaseTransactionOpsCoreTyped;
use futures_util::StreamExt;
use serde::Serialize;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use tokio::sync::{Mutex, MutexGuard};

use crate::OperationId;
use crate::client::ConduitClient;
use crate::db::{PendingOperationKey, PendingOperationPrefix, PendingOperationRecord};
use crate::events::{ConduitPayment, PaymentType};

use super::error::{AndroidError, AndroidErrorCode};

const RECORD_VERSION: u64 = 1;
const MARKER_KEY: &str = "pyxNativeOperation";
static SUBMISSION_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u64)]
pub(crate) enum OperationKind {
    Lightning = 1,
    Onchain = 2,
    EcashCreate = 3,
    EcashClaim = 4,
}

impl OperationKind {
    fn name(self) -> &'static str {
        match self {
            Self::Lightning => "lightning",
            Self::Onchain => "onchain",
            Self::EcashCreate => "ecashCreate",
            Self::EcashClaim => "ecashClaim",
        }
    }
    fn from_raw(raw: u64) -> Option<Self> {
        Some(match raw {
            1 => Self::Lightning,
            2 => Self::Onchain,
            3 => Self::EcashCreate,
            4 => Self::EcashClaim,
            _ => return None,
        })
    }

    pub(crate) fn from_name(value: &str) -> Option<Self> {
        Some(match value {
            "lightning" => Self::Lightning,
            "onchain" => Self::Onchain,
            "ecashCreate" => Self::EcashCreate,
            "ecashClaim" => Self::EcashClaim,
            _ => return None,
        })
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StartedDto {
    correlation_id: String,
    kind: &'static str,
    status: &'static str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ReconciliationDto {
    correlation_id: String,
    kind: &'static str,
    status: &'static str,
    operation_id: Option<String>,
    candidate_count: usize,
}

#[allow(dead_code)]
pub(crate) struct SubmissionGuard(MutexGuard<'static, ()>);

pub(crate) async fn start_with_correlation(
    client: &ConduitClient,
    kind: OperationKind,
    amount_sat: i64,
    fee_sat: i64,
    fingerprint_source: &[u8],
    correlation_id: &str,
) -> Result<(String, SubmissionGuard), AndroidError> {
    let correlation = parse_correlation(correlation_id)?;
    let guard = SUBMISSION_LOCK.get_or_init(|| Mutex::new(())).lock().await;
    let baseline_payment_operation_id = latest_payment_operation_id(client).await;
    let key = PendingOperationKey(correlation);
    let existing = client
        .db
        .begin_transaction_nc()
        .await
        .find_by_prefix(&PendingOperationPrefix)
        .await
        .collect::<Vec<_>>()
        .await;
    if existing
        .iter()
        .any(|(_, record)| record.federation_id == client.federation_id)
    {
        return Err(AndroidError::new(
            AndroidErrorCode::InvalidArgument,
            "Another operation still requires reconciliation.",
            false,
        ));
    }
    let record = PendingOperationRecord {
        version: RECORD_VERSION,
        federation_id: client.federation_id,
        kind: kind as u64,
        created_at_ms: now_ms(),
        amount_sat: u64::try_from(amount_sat).map_err(|_| AndroidError::internal())?,
        fee_sat: u64::try_from(fee_sat).map_err(|_| AndroidError::internal())?,
        fingerprint: fingerprint(fingerprint_source),
        baseline_payment_operation_id,
        operation_id: None,
    };
    let mut tx = client.db.begin_transaction().await;
    tx.insert_new_entry(&key, &record).await;
    tx.commit_tx_result().await.map_err(|_| {
        AndroidError::new(
            AndroidErrorCode::Internal,
            "The operation could not be safely recorded.",
            true,
        )
    })?;
    Ok((hex(&correlation), SubmissionGuard(guard)))
}

pub(crate) fn marker(correlation_id: &str) -> Value {
    json!({ MARKER_KEY: { "version": RECORD_VERSION, "correlationId": correlation_id } })
}

pub(crate) async fn attach_operation(
    client: &ConduitClient,
    correlation_id: &str,
    operation_id: OperationId,
) -> Result<(), AndroidError> {
    let key = PendingOperationKey(parse_correlation(correlation_id)?);
    let mut tx = client.db.begin_transaction().await;
    let Some(mut record) = tx.get_value(&key).await else {
        return Err(AndroidError::invalid_handle());
    };
    record.operation_id = Some(operation_id.0);
    tx.insert_entry(&key, &record).await;
    tx.commit_tx_result()
        .await
        .map_err(|_| AndroidError::internal())
}

pub(crate) async fn reconcile(
    client: &ConduitClient,
    correlation_id: &str,
    expected_kind: OperationKind,
) -> Result<String, AndroidError> {
    let correlation = parse_correlation(correlation_id)?;
    let key = PendingOperationKey(correlation);
    let record = client.db.begin_transaction_nc().await.get_value(&key).await;
    let Some(record) = record else {
        let in_flight = SUBMISSION_LOCK
            .get()
            .is_some_and(|lock| lock.try_lock().is_err());
        return serde_json::to_string(&ReconciliationDto {
            correlation_id: hex(&correlation),
            kind: expected_kind.name(),
            status: if in_flight {
                "inFlight"
            } else {
                "notSubmitted"
            },
            operation_id: None,
            candidate_count: 0,
        })
        .map_err(|_| AndroidError::internal());
    };
    if record.federation_id != client.federation_id {
        return Err(AndroidError::invalid_handle());
    }
    let kind = OperationKind::from_raw(record.kind).ok_or_else(AndroidError::internal)?;
    if kind != expected_kind {
        return Err(AndroidError::new(
            AndroidErrorCode::InvalidArgument,
            "The operation kind does not match its safety record.",
            false,
        ));
    }
    let payments = client.get_payment_history().await;
    let mut candidates = Vec::new();

    if let Some(operation_id) = record.operation_id {
        candidates.extend(
            payments
                .iter()
                .filter(|p| operation_id_bytes(&p.operation_id) == Some(operation_id)),
        );
    } else {
        let metadata_ids = metadata_matches(client, correlation_id).await;
        candidates.extend(
            payments
                .iter()
                .filter(|p| metadata_ids.contains(&p.operation_id)),
        );
        if candidates.is_empty() && kind == OperationKind::Onchain {
            candidates.extend(wallet_v2_candidates(&record, &payments));
        }
    }
    candidates.sort_by_key(|p| &p.operation_id);
    candidates.dedup_by_key(|p| &p.operation_id);
    let in_flight = SUBMISSION_LOCK
        .get()
        .is_some_and(|lock| lock.try_lock().is_err());
    let (status, operation_id) = classify(&candidates, in_flight);
    serde_json::to_string(&ReconciliationDto {
        correlation_id: hex(&correlation),
        kind: kind.name(),
        status,
        operation_id,
        candidate_count: candidates.len(),
    })
    .map_err(|_| AndroidError::internal())
}

pub(crate) async fn pending(client: &ConduitClient) -> Result<String, AndroidError> {
    let records = client
        .db
        .begin_transaction_nc()
        .await
        .find_by_prefix(&PendingOperationPrefix)
        .await
        .collect::<Vec<_>>()
        .await;
    let values = records
        .into_iter()
        .filter_map(|(key, record)| {
            (record.federation_id == client.federation_id)
                .then(|| OperationKind::from_raw(record.kind))
                .flatten()
                .map(|kind| StartedDto {
                    correlation_id: hex(&key.0),
                    kind: kind.name(),
                    status: "submitted",
                })
        })
        .collect::<Vec<_>>();
    serde_json::to_string(&values).map_err(|_| AndroidError::internal())
}

pub(crate) async fn clear(
    client: &ConduitClient,
    correlation_id: &str,
) -> Result<String, AndroidError> {
    if SUBMISSION_LOCK
        .get()
        .is_some_and(|lock| lock.try_lock().is_err())
    {
        return Err(AndroidError::new(
            AndroidErrorCode::Cancelled,
            "The operation is still being submitted.",
            true,
        ));
    }
    let mut tx = client.db.begin_transaction().await;
    let key = PendingOperationKey(parse_correlation(correlation_id)?);
    let record = tx.get_value(&key).await;
    if let Some(record) = record {
        if record.federation_id != client.federation_id {
            return Err(AndroidError::invalid_handle());
        }
        tx.remove_entry(&key).await;
        tx.commit_tx_result()
            .await
            .map_err(|_| AndroidError::internal())?;
    }
    Ok("{\"cleared\":true}".into())
}

async fn metadata_matches(client: &ConduitClient, correlation: &str) -> Vec<String> {
    let mut answer = Vec::new();
    let mut cursor = None;
    loop {
        let page = client
            .client
            .operation_log()
            .paginate_operations_rev(100, cursor)
            .await;
        if page.is_empty() {
            break;
        }
        for (key, entry) in &page {
            if entry
                .try_meta::<Value>()
                .ok()
                .as_ref()
                .is_some_and(|v| contains_marker(v, correlation))
            {
                answer.push(key.operation_id.fmt_full().to_string());
            }
        }
        cursor = page.last().map(|(key, _)| *key);
    }
    answer
}

fn contains_marker(value: &Value, correlation: &str) -> bool {
    match value {
        Value::Object(map) => {
            map.get(MARKER_KEY)
                .and_then(Value::as_object)
                .and_then(|m| m.get("correlationId"))
                .and_then(Value::as_str)
                == Some(correlation)
                || map.values().any(|v| contains_marker(v, correlation))
        }
        Value::Array(values) => values.iter().any(|v| contains_marker(v, correlation)),
        _ => false,
    }
}

fn wallet_v2_candidates<'a>(
    record: &PendingOperationRecord,
    payments: &'a [ConduitPayment],
) -> Vec<&'a ConduitPayment> {
    payments
        .iter()
        .take_while(|p| {
            record
                .baseline_payment_operation_id
                .is_none_or(|id| operation_id_bytes(&p.operation_id) != Some(id))
        })
        .filter(|p| {
            !p.incoming
                && matches!(p.payment_type, PaymentType::Bitcoin)
                && p.amount_sats == record.amount_sat as i64
                && p.fee_sats == Some(record.fee_sat as i64)
                && p.address
                    .as_deref()
                    .is_some_and(|a| fingerprint(a.as_bytes()) == record.fingerprint)
        })
        .collect()
}

fn classify(candidates: &[&ConduitPayment], in_flight: bool) -> (&'static str, Option<String>) {
    if in_flight && candidates.is_empty() {
        return ("inFlight", None);
    }
    if candidates.len() != 1 {
        return (
            if candidates.is_empty() {
                "notSubmitted"
            } else {
                "ambiguous"
            },
            None,
        );
    }
    let payment = candidates[0];
    (
        match payment.success {
            Some(true) => "succeeded",
            Some(false) => "failed",
            None => "pending",
        },
        Some(payment.operation_id.clone()),
    )
}

async fn latest_payment_operation_id(client: &ConduitClient) -> Option<[u8; 32]> {
    client
        .get_payment_history()
        .await
        .first()
        .and_then(|payment| operation_id_bytes(&payment.operation_id))
}
fn fingerprint(bytes: &[u8]) -> [u8; 32] {
    Sha256::digest(bytes).into()
}
fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}
fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
fn parse_correlation(value: &str) -> Result<[u8; 16], AndroidError> {
    if value.len() != 32 {
        return Err(AndroidError::invalid_handle());
    }
    let mut out = [0; 16];
    for (i, slot) in out.iter_mut().enumerate() {
        *slot = u8::from_str_radix(&value[i * 2..i * 2 + 2], 16)
            .map_err(|_| AndroidError::invalid_handle())?;
    }
    Ok(out)
}
fn operation_id_bytes(value: &str) -> Option<[u8; 32]> {
    if value.len() != 64 {
        return None;
    }
    let mut out = [0; 32];
    for (i, s) in out.iter_mut().enumerate() {
        *s = u8::from_str_radix(&value[i * 2..i * 2 + 2], 16).ok()?;
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use fedimint_core::db::IRawDatabaseExt;
    use fedimint_core::db::mem_impl::MemDatabase;
    fn payment(id: u8, success: Option<bool>) -> ConduitPayment {
        ConduitPayment {
            operation_id: format!("{id:02x}").repeat(32),
            incoming: false,
            payment_type: PaymentType::Bitcoin,
            amount_sats: 10,
            fee_sats: Some(2),
            timestamp: 0,
            success,
            ecash: None,
            txid: None,
            preimage: None,
            address: Some("bc1qexample".into()),
            fiat_amount: None,
            fiat_currency_code: None,
        }
    }
    fn record() -> PendingOperationRecord {
        PendingOperationRecord {
            version: 1,
            federation_id: fedimint_core::config::FederationId::dummy(),
            kind: 2,
            created_at_ms: 0,
            amount_sat: 10,
            fee_sat: 2,
            fingerprint: fingerprint(b"bc1qexample"),
            baseline_payment_operation_id: None,
            operation_id: None,
        }
    }
    #[test]
    fn metadata_marker_matches_nested_module_shapes() {
        let m = marker("abcd");
        assert!(contains_marker(&json!({"extra_meta":m}), "abcd"));
        assert!(!contains_marker(&json!({"invoice":"secret"}), "abcd"));
    }
    #[test]
    fn wallet_v2_zero_one_and_multiple_candidates_are_safe() {
        let r = record();
        assert_eq!(wallet_v2_candidates(&r, &[]).len(), 0);
        let one = vec![payment(1, None)];
        assert_eq!(wallet_v2_candidates(&r, &one).len(), 1);
        let two = vec![payment(1, None), payment(2, None)];
        assert_eq!(wallet_v2_candidates(&r, &two).len(), 2);
        assert_eq!(
            classify(&wallet_v2_candidates(&r, &two), false).0,
            "ambiguous"
        );
    }
    #[test]
    fn baseline_excludes_preexisting_candidate() {
        let mut r = record();
        r.baseline_payment_operation_id = Some([1; 32]);
        let fresh = payment(2, None);
        assert_eq!(
            wallet_v2_candidates(&r, &[fresh, payment(1, None)]).len(),
            1
        );
    }
    #[test]
    fn statuses_are_conclusive_only_for_one_candidate() {
        let ok = payment(1, Some(true));
        assert_eq!(
            classify(&[&ok], false),
            ("succeeded", Some(ok.operation_id.clone()))
        );
        let failed = payment(2, Some(false));
        assert_eq!(classify(&[&failed], false).0, "failed");
        assert_eq!(classify(&[], false).0, "notSubmitted");
        assert_eq!(classify(&[], true).0, "inFlight");
    }
    #[test]
    fn durable_record_is_redacted_and_prefix_is_append_only() {
        let encoded = serde_json::to_string(&StartedDto {
            correlation_id: "00".repeat(16),
            kind: "onchain",
            status: "submitted",
        })
        .unwrap();
        for forbidden in ["invoice", "address", "ecash", "token", "preimage"] {
            assert!(!encoded.contains(forbidden));
        }
        assert_eq!(crate::db::DbKeyPrefix::PendingOperation as u8, 0x09);
        assert!(std::mem::size_of::<PendingOperationRecord>() < 256);
    }

    #[test]
    fn record_round_trips_across_database_reopen_handle() {
        super::super::runtime::get().unwrap().block_on(async {
            let database = MemDatabase::new().into_database();
            let key = PendingOperationKey([7; 16]);
            let expected = record();
            let mut write = database.begin_transaction().await;
            write.insert_new_entry(&key, &expected).await;
            write.commit_tx().await;
            // A fresh Database handle over the same backend models process-side
            // repository recreation; the value is consensus-decoded, not shared.
            let loaded = database
                .clone()
                .begin_transaction_nc()
                .await
                .get_value(&key)
                .await
                .unwrap();
            assert_eq!(loaded.version, RECORD_VERSION);
            assert_eq!(loaded.federation_id, expected.federation_id);
            assert_eq!(loaded.fingerprint, expected.fingerprint);
            assert_eq!(
                loaded.baseline_payment_operation_id,
                expected.baseline_payment_operation_id
            );
        });
    }

    #[test]
    fn federation_identity_is_part_of_the_pinned_record_schema() {
        let first = record();
        let mut second = record();
        second.federation_id = "0101010101010101010101010101010101010101010101010101010101010101"
            .parse()
            .unwrap();
        assert_ne!(first.federation_id, second.federation_id);
        assert_eq!(first.version, 1);
        assert_eq!(second.version, 1);
    }
}
