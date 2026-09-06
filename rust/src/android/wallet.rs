use serde::Serialize;
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use tokio::sync::Mutex as AsyncMutex;
use zeroize::Zeroizing;

use crate::client::ConduitClient;
use crate::events::{ConduitPayment, PaymentType};
use crate::factory::ConduitClientFactory;
use crate::{DatabaseWrapper, generate_mnemonic, parse_invite_code, parse_mnemonic};

use super::bootstrap;
use super::error::{AndroidError, AndroidErrorCode};
use super::handles::{HandleKind, global_close, global_get, global_insert};
use super::subscriptions;

const MAX_WORDS_JSON_BYTES: usize = 1024;
const MAX_FEDERATIONS: usize = 256;
const MAX_PAYMENTS: usize = 100;
const MAX_DISPLAY_NAME_CHARS: usize = 256;
const MAX_INVITE_BYTES: usize = 64 * 1024;

#[derive(Default)]
struct SelectedClientCache {
    handles: HashMap<String, u64>,
}

impl SelectedClientCache {
    fn get(&self, federation_id: &str) -> Option<u64> {
        self.handles.get(federation_id).copied()
    }

    fn insert(&mut self, federation_id: String, handle: u64) {
        self.handles.insert(federation_id, handle);
    }

    fn remove(&mut self, federation_id: &str) -> Option<u64> {
        self.handles.remove(federation_id)
    }

    #[cfg(test)]
    fn get_or_insert_valid(
        &mut self,
        federation_id: &str,
        is_valid: impl FnOnce(u64) -> bool,
        create: impl FnOnce() -> u64,
    ) -> u64 {
        if let Some(handle) = self.get(federation_id)
            && is_valid(handle)
        {
            return handle;
        }
        let handle = create();
        self.insert(federation_id.to_owned(), handle);
        handle
    }
}

static SELECTED_CLIENTS: OnceLock<Mutex<SelectedClientCache>> = OnceLock::new();
static CLIENT_LIFECYCLE: OnceLock<AsyncMutex<()>> = OnceLock::new();

fn selected_clients() -> &'static Mutex<SelectedClientCache> {
    SELECTED_CLIENTS.get_or_init(|| Mutex::new(SelectedClientCache::default()))
}

fn client_lifecycle() -> &'static AsyncMutex<()> {
    CLIENT_LIFECYCLE.get_or_init(|| AsyncMutex::new(()))
}

pub(crate) async fn shutdown_cached_clients() -> Result<(), AndroidError> {
    let _lifecycle = client_lifecycle().lock().await;
    let handles = {
        let mut cache = selected_clients()
            .lock()
            .map_err(|_| AndroidError::internal())?;
        cache
            .handles
            .drain()
            .map(|(_, handle)| handle)
            .collect::<Vec<_>>()
    };
    for handle in handles {
        subscriptions::invalidate_client(handle)?;
        if let Ok(client) = global_get::<ConduitClient>(handle, HandleKind::Client) {
            client.shutdown().await;
        }
        let _ = global_close(handle, HandleKind::Client);
    }
    Ok(())
}

fn cached_client(federation_id: &str) -> Result<Option<(u64, Arc<ConduitClient>)>, AndroidError> {
    let handle = selected_clients()
        .lock()
        .map_err(|_| AndroidError::internal())?
        .get(federation_id);
    let Some(handle) = handle else {
        return Ok(None);
    };
    match global_get::<ConduitClient>(handle, HandleKind::Client) {
        Ok(client) => Ok(Some((handle, client))),
        Err(_) => {
            selected_clients()
                .lock()
                .map_err(|_| AndroidError::internal())?
                .remove(federation_id);
            Ok(None)
        }
    }
}

fn cache_client(client: ConduitClient) -> Result<(u64, Arc<ConduitClient>), AndroidError> {
    let federation_id = client.federation_id().to_string();
    let handle = global_insert(HandleKind::Client, client)?;
    selected_clients()
        .lock()
        .map_err(|_| AndroidError::internal())?
        .insert(federation_id, handle);
    Ok((handle, global_get(handle, HandleKind::Client)?))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CreateWalletDto {
    factory_handle: u64,
    seed_words: Vec<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct RestoreWalletDto {
    factory_handle: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct WalletSnapshotDto {
    currency_code: String,
    federations: Vec<FederationDto>,
    selected: Option<SelectedFederationDto>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct FederationDto {
    id: String,
    name: String,
    guardian_count: u32,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SelectedFederationDto {
    federation_id: String,
    name: String,
    balance_sat: i64,
    client_handle: u64,
    payments: Vec<HomePaymentDto>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct HomePaymentDto {
    operation_id: String,
    incoming: bool,
    #[serde(rename = "type")]
    payment_type: &'static str,
    amount_sat: i64,
    fee_sat: Option<i64>,
    timestamp_millis: i64,
    status: &'static str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CurrencyDto {
    currency_code: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SeedWordsDto {
    seed_words: Vec<String>,
}

#[derive(Serialize)]
struct LeaveDto {
    left: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct LeaveSafety {
    pending_recovery: bool,
    pending_payment: bool,
}

impl LeaveSafety {
    fn check(self) -> Result<(), AndroidError> {
        if self.pending_recovery || self.pending_payment {
            Err(unsafe_to_leave())
        } else {
            Ok(())
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct FederationDetailsDto {
    name: String,
    id: String,
    currency_code: String,
    stats: Option<FederationStatsDto>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct FederationStatsDto {
    total_value_sat: i64,
    block_count: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    feerate_sat_per_kvb: Option<i64>,
}

pub(crate) async fn create_async(database_handle: u64) -> Result<String, AndroidError> {
    let _mutation = bootstrap::lock_operation().await;
    if matches!(
        bootstrap::current()?,
        Some(bootstrap::BootstrapResult::Ready { .. })
    ) {
        return Err(startup_already_initialized());
    }
    let database = global_get::<DatabaseWrapper>(database_handle, HandleKind::Database)?;
    let mnemonic = generate_mnemonic();
    let seed_words = mnemonic.0.words().map(str::to_owned).collect::<Vec<_>>();
    let factory = ConduitClientFactory::init(&database, &mnemonic)
        .await
        .map_err(|e| AndroidError::internal_logged("factory_init", &e))?;
    let factory_handle = global_insert(HandleKind::Factory, factory)?;
    bootstrap::set_ready(database_handle, factory_handle)?;
    serialize(&CreateWalletDto {
        factory_handle,
        seed_words,
    })
}

pub(crate) async fn restore_async(
    database_handle: u64,
    words_json: Zeroizing<String>,
) -> Result<String, AndroidError> {
    let _mutation = bootstrap::lock_operation().await;
    if let Some(bootstrap::BootstrapResult::Ready { factory_handle, .. }) = bootstrap::current()? {
        return serialize(&RestoreWalletDto { factory_handle });
    }
    let words = parse_seed_words(&words_json)?;
    let mnemonic = parse_mnemonic(words).ok_or_else(invalid_seed)?;
    let database = global_get::<DatabaseWrapper>(database_handle, HandleKind::Database)?;
    let factory = ConduitClientFactory::init(&database, &mnemonic)
        .await
        .map_err(|e| AndroidError::internal_logged("factory_init", &e))?;
    let factory_handle = global_insert(HandleKind::Factory, factory)?;
    bootstrap::set_ready(database_handle, factory_handle)?;
    serialize(&RestoreWalletDto { factory_handle })
}

pub(crate) async fn snapshot_async(factory_handle: u64) -> Result<String, AndroidError> {
    snapshot_selected_async(factory_handle, None).await
}

async fn snapshot_selected_async(
    factory_handle: u64,
    requested_id: Option<&str>,
) -> Result<String, AndroidError> {
    let _lifecycle = client_lifecycle().lock().await;
    let factory = global_get::<ConduitClientFactory>(factory_handle, HandleKind::Factory)?;
    build_snapshot(&factory, requested_id, None).await
}

pub(crate) async fn join_federation_async(
    factory_handle: u64,
    invite: String,
    recover: bool,
) -> Result<String, AndroidError> {
    validate_invite(&invite)?;
    let invite = parse_invite_code(&invite).ok_or_else(invalid_invite)?;
    let factory = global_get::<ConduitClientFactory>(factory_handle, HandleKind::Factory)?;
    let _lifecycle = client_lifecycle().lock().await;
    let client = if recover {
        factory.recover(&invite).await
    } else {
        factory.join(&invite).await
    }
    .map_err(|e| AndroidError::internal_logged("join_or_recover_federation", &e))?;
    let selected_id = client.federation_id().to_string();
    build_snapshot(&factory, Some(&selected_id), Some(client)).await
}

pub(crate) async fn snapshot_for_async(
    factory_handle: u64,
    federation_id: String,
) -> Result<String, AndroidError> {
    validate_federation_id(&federation_id)?;
    snapshot_selected_async(factory_handle, Some(&federation_id)).await
}

async fn build_snapshot(
    factory: &ConduitClientFactory,
    requested_id: Option<&str>,
    selected_client: Option<ConduitClient>,
) -> Result<String, AndroidError> {
    let currency_code = factory.get_currency().await.chars().take(16).collect();
    let mut infos = factory.list_federations().await;
    infos.sort_by_key(|info| info.id.to_string());
    let selected_info = match requested_id {
        Some(id) => infos
            .iter()
            .find(|info| info.id.to_string() == id)
            .map(|info| (info.id, bounded_name(&info.name)))
            .ok_or_else(invalid_federation)?,
        None => match infos.first() {
            Some(info) => (info.id, bounded_name(&info.name)),
            None => {
                return serialize(&WalletSnapshotDto {
                    currency_code,
                    federations: Vec::new(),
                    selected: None,
                });
            }
        },
    };
    infos.truncate(MAX_FEDERATIONS);

    let federations = infos
        .iter()
        .map(|info| FederationDto {
            id: info.id.to_string(),
            name: bounded_name(&info.name),
            guardian_count: info.guardians,
        })
        .collect();

    let federation_id = selected_info.0.to_string();
    let (client_handle, client) = match cached_client(&federation_id)? {
        Some(cached) => {
            if let Some(new_client) = selected_client {
                if new_client.federation_id() != selected_info.0 {
                    return Err(AndroidError::internal());
                }
                // Join/recover may return another open of an already selected
                // federation. Keep the stable cached handle and stop the extra
                // executor immediately.
                new_client.shutdown().await;
            }
            cached
        }
        None => {
            let client = match selected_client {
                Some(client) if client.federation_id() == selected_info.0 => client,
                Some(_) => return Err(AndroidError::internal()),
                None => factory
                    .load(&selected_info.0)
                    .await
                    .ok_or_else(AndroidError::internal)?,
            };
            cache_client(client)?
        }
    };
    let balance_sat = client.balance_snapshot().await.unwrap_or(0);
    let payments = client
        .get_payment_history()
        .await
        .into_iter()
        .take(MAX_PAYMENTS)
        .map(HomePaymentDto::from)
        .collect();
    let selected = Some(SelectedFederationDto {
        federation_id: selected_info.0.to_string(),
        name: selected_info.1,
        balance_sat,
        client_handle,
        payments,
    });

    serialize(&WalletSnapshotDto {
        currency_code,
        federations,
        selected,
    })
}

pub(crate) async fn set_currency_async(
    factory_handle: u64,
    code: String,
) -> Result<String, AndroidError> {
    validate_currency_code(&code)?;
    let factory = global_get::<ConduitClientFactory>(factory_handle, HandleKind::Factory)?;
    factory.set_currency(&code).await;
    serialize(&CurrencyDto {
        currency_code: code,
    })
}

fn validate_currency_code(code: &str) -> Result<(), AndroidError> {
    if code.len() != 3 || !code.bytes().all(|byte| byte.is_ascii_uppercase()) {
        return Err(AndroidError::new(
            AndroidErrorCode::InvalidArgument,
            "The currency code is invalid.",
            false,
        ));
    }
    Ok(())
}

pub(crate) async fn seed_words_async(factory_handle: u64) -> Result<String, AndroidError> {
    let factory = global_get::<ConduitClientFactory>(factory_handle, HandleKind::Factory)?;
    let words = factory.seed_phrase().await;
    if words.len() != 12
        || words.iter().any(|word| {
            word.is_empty()
                || word.len() > 16
                || !word.bytes().all(|byte| byte.is_ascii_lowercase())
        })
    {
        return Err(AndroidError::internal());
    }
    serialize(&SeedWordsDto { seed_words: words })
}

pub(crate) async fn leave_federation_async(
    factory_handle: u64,
    federation_id: String,
) -> Result<String, AndroidError> {
    validate_federation_id(&federation_id)?;
    let factory = global_get::<ConduitClientFactory>(factory_handle, HandleKind::Factory)?;
    let _lifecycle = client_lifecycle().lock().await;
    let info = factory
        .list_federations()
        .await
        .into_iter()
        .find(|info| info.id.to_string() == federation_id)
        .ok_or_else(invalid_federation)?;
    let cached = cached_client(&federation_id)?;
    let (cached_handle, client) = match cached {
        Some((handle, client)) => (Some(handle), client),
        None => (
            None,
            Arc::new(
                factory
                    .load(&info.id)
                    .await
                    .ok_or_else(AndroidError::internal)?,
            ),
        ),
    };
    LeaveSafety {
        pending_recovery: client.has_pending_recoveries(),
        pending_payment: client
            .get_payment_history()
            .await
            .iter()
            .any(|payment| payment.success.is_none()),
    }
    .check()?;

    if let Some(handle) = cached_handle {
        subscriptions::invalidate_client(handle)?;
        selected_clients()
            .lock()
            .map_err(|_| AndroidError::internal())?
            .remove(&federation_id);
        client.shutdown().await;
        global_close(handle, HandleKind::Client)?;
    } else {
        client.shutdown().await;
    }
    factory.leave(&info.id).await;
    // Establish the deterministic first remaining federation as the safe
    // selection before returning. The legacy leave DTO remains compatible;
    // the next snapshot reuses this validated client handle.
    let _ = build_snapshot(&factory, None, None).await?;
    serialize(&LeaveDto { left: true })
}

pub(crate) async fn federation_details_async(client_handle: u64) -> Result<String, AndroidError> {
    let client = global_get::<crate::client::ConduitClient>(client_handle, HandleKind::Client)?;
    let id = client.federation_id().to_string();
    let name = client
        .federation_name()
        .await
        .map(|name| bounded_name(&name))
        .unwrap_or_else(|| id.clone());
    let stats = client
        .federation_stats()
        .await
        .map(|stats| FederationStatsDto {
            total_value_sat: stats.total_value_sat,
            block_count: stats.block_count,
            feerate_sat_per_kvb: stats.feerate,
        });
    serialize(&FederationDetailsDto {
        name,
        id,
        currency_code: client.currency_code().chars().take(16).collect(),
        stats,
    })
}

fn validate_federation_id(id: &str) -> Result<(), AndroidError> {
    if id.is_empty() || id.len() > 128 || !id.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(invalid_federation());
    }
    Ok(())
}

fn invalid_federation() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The federation is invalid.",
        false,
    )
}

fn validate_invite(invite: &str) -> Result<(), AndroidError> {
    if invite.is_empty() || invite.len() > MAX_INVITE_BYTES {
        return Err(invalid_invite());
    }
    Ok(())
}

fn invalid_invite() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The federation invite is invalid.",
        false,
    )
}

fn parse_seed_words(words_json: &str) -> Result<Vec<String>, AndroidError> {
    if words_json.is_empty() || words_json.len() > MAX_WORDS_JSON_BYTES {
        return Err(invalid_seed());
    }
    let words: Vec<String> = serde_json::from_str(words_json).map_err(|_| invalid_seed())?;
    if words.len() != 12
        || words.iter().any(|word| {
            word.is_empty() || word.len() > 16 || !word.bytes().all(|b| b.is_ascii_lowercase())
        })
    {
        return Err(invalid_seed());
    }
    Ok(words)
}

fn invalid_seed() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The recovery phrase is invalid.",
        false,
    )
}

fn startup_already_initialized() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The wallet is already initialized.",
        false,
    )
}

fn unsafe_to_leave() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "Finish wallet recovery and pending payments before leaving this federation.",
        false,
    )
}

fn serialize<T: Serialize>(value: &T) -> Result<String, AndroidError> {
    serde_json::to_string(value).map_err(|_| AndroidError::internal())
}

fn bounded_name(name: &str) -> String {
    name.chars().take(MAX_DISPLAY_NAME_CHARS).collect()
}

impl From<ConduitPayment> for HomePaymentDto {
    fn from(payment: ConduitPayment) -> Self {
        Self {
            operation_id: payment.operation_id,
            incoming: payment.incoming,
            payment_type: match payment.payment_type {
                PaymentType::Lightning => "lightning",
                PaymentType::Bitcoin => "onchain",
                PaymentType::Ecash => "ecash",
            },
            amount_sat: payment.amount_sats,
            fee_sat: payment.fee_sats,
            timestamp_millis: payment.timestamp,
            status: match payment.success {
                None => "pending",
                Some(true) => "succeeded",
                Some(false) => "failed",
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn restore_requires_exactly_twelve_bounded_lowercase_words() {
        let valid = serde_json::to_string(&vec!["abandon"; 12]).unwrap();
        assert_eq!(parse_seed_words(&valid).unwrap().len(), 12);
        assert!(parse_seed_words("[]").is_err());
        assert!(parse_seed_words(&serde_json::to_string(&vec!["abandon"; 11]).unwrap()).is_err());
        assert!(parse_seed_words(&serde_json::to_string(&vec!["UPPER"; 12]).unwrap()).is_err());
        assert!(parse_seed_words(&format!("[\"{}\"]", "x".repeat(1024))).is_err());
    }

    #[test]
    fn home_payment_serialization_omits_sensitive_fields() {
        let dto = HomePaymentDto::from(ConduitPayment {
            operation_id: "operation".into(),
            incoming: false,
            payment_type: PaymentType::Lightning,
            amount_sats: 42,
            fee_sats: Some(1),
            timestamp: 123,
            success: Some(true),
            ecash: Some("secret ecash".into()),
            txid: Some("txid".into()),
            preimage: Some("secret preimage".into()),
            address: Some("secret address".into()),
            fiat_amount: None,
            fiat_currency_code: None,
        });
        let json = serialize(&dto).unwrap();
        crate::android::assert_read_dto_snapshot("wallet.homePayment", &json);
        assert!(!json.contains("secret"));
        assert!(!json.contains("txid"));
        assert_eq!(
            json,
            "{\"operationId\":\"operation\",\"incoming\":false,\"type\":\"lightning\",\"amountSat\":42,\"feeSat\":1,\"timestampMillis\":123,\"status\":\"succeeded\"}"
        );
    }

    #[test]
    fn management_inputs_are_strictly_bounded() {
        assert!(validate_currency_code("USD").is_ok());
        assert!(validate_currency_code("usd").is_err());
        assert!(validate_currency_code("USDT").is_err());
        assert!(validate_federation_id(&"a".repeat(64)).is_ok());
        assert!(validate_federation_id("").is_err());
        assert!(validate_federation_id("not-a-hex-id").is_err());
        assert!(validate_invite("fed1invite").is_ok());
        assert!(validate_invite("").is_err());
        assert!(validate_invite(&"x".repeat(MAX_INVITE_BYTES + 1)).is_err());
    }

    #[test]
    fn management_json_matches_contract() {
        assert_eq!(
            serialize(&CurrencyDto {
                currency_code: "EUR".into(),
            })
            .unwrap(),
            "{\"currencyCode\":\"EUR\"}"
        );
        assert_eq!(
            serialize(&SeedWordsDto {
                seed_words: vec!["abandon".into(); 12],
            })
            .unwrap(),
            format!(
                "{{\"seedWords\":{}}}",
                serde_json::to_string(&vec!["abandon"; 12]).unwrap()
            )
        );
        assert_eq!(
            serialize(&LeaveDto { left: true }).unwrap(),
            "{\"left\":true}"
        );
        assert_eq!(
            serialize(&FederationDetailsDto {
                name: "Community".into(),
                id: "abcd".into(),
                currency_code: "USD".into(),
                stats: Some(FederationStatsDto {
                    total_value_sat: 10,
                    block_count: 20,
                    feerate_sat_per_kvb: None,
                }),
            })
            .unwrap(),
            "{\"name\":\"Community\",\"id\":\"abcd\",\"currencyCode\":\"USD\",\"stats\":{\"totalValueSat\":10,\"blockCount\":20}}"
        );
    }

    #[test]
    fn selected_snapshot_serialization_matches_join_contract() {
        let json = serialize(&WalletSnapshotDto {
            currency_code: "USD".into(),
            federations: vec![FederationDto {
                id: "abcd".into(),
                name: "Community".into(),
                guardian_count: 3,
            }],
            selected: Some(SelectedFederationDto {
                federation_id: "abcd".into(),
                name: "Community".into(),
                balance_sat: 5,
                client_handle: 7,
                payments: Vec::new(),
            }),
        })
        .unwrap();
        crate::android::assert_read_dto_snapshot("wallet.selectedSnapshot", &json);
        assert_eq!(
            json,
            "{\"currencyCode\":\"USD\",\"federations\":[{\"id\":\"abcd\",\"name\":\"Community\",\"guardianCount\":3}],\"selected\":{\"federationId\":\"abcd\",\"name\":\"Community\",\"balanceSat\":5,\"clientHandle\":7,\"payments\":[]}}"
        );
    }

    #[test]
    fn selected_client_cache_reuses_valid_handle_and_replaces_stale_handle() {
        let mut cache = SelectedClientCache::default();
        let mut creates = 0;
        let first = cache.get_or_insert_valid(
            "federation",
            |_| false,
            || {
                creates += 1;
                7
            },
        );
        let second = cache.get_or_insert_valid(
            "federation",
            |handle| handle == 7,
            || {
                creates += 1;
                8
            },
        );
        assert_eq!((first, second, creates), (7, 7, 1));

        let replacement = cache.get_or_insert_valid(
            "federation",
            |_| false,
            || {
                creates += 1;
                9
            },
        );
        assert_eq!((replacement, creates), (9, 2));
        assert_eq!(cache.remove("federation"), Some(9));
        assert_eq!(cache.get("federation"), None);
    }

    #[test]
    fn leave_safety_rejects_only_pending_recovery_or_payment() {
        assert!(
            LeaveSafety {
                pending_recovery: false,
                pending_payment: false
            }
            .check()
            .is_ok()
        );
        for safety in [
            LeaveSafety {
                pending_recovery: true,
                pending_payment: false,
            },
            LeaveSafety {
                pending_recovery: false,
                pending_payment: true,
            },
            LeaveSafety {
                pending_recovery: true,
                pending_payment: true,
            },
        ] {
            let error = safety.check().unwrap_err();
            assert_eq!(error.code, AndroidErrorCode::InvalidArgument);
            assert!(!error.retryable);
        }
    }

    #[test]
    fn removing_selected_client_does_not_disturb_safe_remaining_selection() {
        let mut cache = SelectedClientCache::default();
        cache.insert("left".into(), 7);
        cache.insert("remaining".into(), 8);
        assert_eq!(cache.remove("left"), Some(7));
        assert_eq!(cache.get("left"), None);
        assert_eq!(cache.get("remaining"), Some(8));
    }

    #[test]
    fn post_leave_snapshot_has_no_selection_when_last_federation_was_removed() {
        let json = serialize(&WalletSnapshotDto {
            currency_code: "USD".into(),
            federations: Vec::new(),
            selected: None,
        })
        .unwrap();
        assert_eq!(
            json,
            "{\"currencyCode\":\"USD\",\"federations\":[],\"selected\":null}"
        );
    }
}
