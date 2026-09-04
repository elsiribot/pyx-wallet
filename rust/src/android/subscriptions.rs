use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use futures_util::{FutureExt, StreamExt};
use serde::Serialize;
use tokio::sync::Notify;

use crate::client::ConduitClient;
use crate::events::{ConduitPayment, PaymentNotification, PaymentType, RecentPaymentsUpdate};

use super::error::{AndroidError, AndroidErrorCode};
use super::handles::{HandleKind, global_close, global_get, global_insert, global_validate_type};
use super::runtime;

const MAX_GUARDIANS: usize = 256;
const MAX_GUARDIAN_NAME_CHARS: usize = 256;
const MAX_PAYMENT_SUMMARIES: usize = 100;
const MAX_OPERATION_ID_BYTES: usize = 256;
const MAX_PAYMENT_EVENT_JSON_BYTES: usize = 256 * 1024;

static CLIENT_SUBSCRIPTIONS: OnceLock<Mutex<HashMap<u64, HashSet<u64>>>> = OnceLock::new();

fn client_subscriptions() -> &'static Mutex<HashMap<u64, HashSet<u64>>> {
    CLIENT_SUBSCRIPTIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn forget_subscription(client_handle: u64, subscription_handle: u64) {
    if let Ok(mut subscriptions) = client_subscriptions().lock()
        && let Some(handles) = subscriptions.get_mut(&client_handle)
    {
        handles.remove(&subscription_handle);
        if handles.is_empty() {
            subscriptions.remove(&client_handle);
        }
    }
}

/// Stop every stream retaining a client before that client is shut down and
/// its handle is invalidated during federation removal.
pub(crate) fn invalidate_client(client_handle: u64) -> Result<(), AndroidError> {
    let handles = client_subscriptions()
        .lock()
        .map_err(|_| AndroidError::internal())?
        .remove(&client_handle)
        .unwrap_or_default();
    for handle in handles {
        if let Ok(state) = global_get::<Arc<SubscriptionState>>(handle, HandleKind::Subscription) {
            state.close();
        }
        let _ = global_close(handle, HandleKind::Subscription);
    }
    Ok(())
}

pub(crate) fn invalidate_all() -> Result<(), AndroidError> {
    let client_handles = client_subscriptions()
        .lock()
        .map_err(|_| AndroidError::internal())?
        .keys()
        .copied()
        .collect::<Vec<_>>();
    for client_handle in client_handles {
        invalidate_client(client_handle)?;
    }
    Ok(())
}

#[cfg(test)]
pub(crate) fn insert_test_subscription(
    client_handle: u64,
    sink: Arc<dyn SubscriptionSink>,
) -> Result<u64, AndroidError> {
    let state = Arc::new(SubscriptionState::new(sink));
    let handle = global_insert(HandleKind::Subscription, state)?;
    client_subscriptions()
        .lock()
        .map_err(|_| AndroidError::internal())?
        .entry(client_handle)
        .or_default()
        .insert(handle);
    Ok(handle)
}

pub(crate) trait SubscriptionSink: Send + Sync {
    fn event(&self, subscription_handle: u64, json: &str);
    fn error(&self, subscription_handle: u64, error: &AndroidError);
}

pub(crate) struct SubscriptionState {
    active: AtomicBool,
    delivery: Mutex<()>,
    closed: Notify,
    sink: Arc<dyn SubscriptionSink>,
}

impl SubscriptionState {
    fn new(sink: Arc<dyn SubscriptionSink>) -> Self {
        Self {
            active: AtomicBool::new(true),
            delivery: Mutex::new(()),
            closed: Notify::new(),
            sink,
        }
    }

    fn event(&self, handle: u64, json: &str) -> bool {
        if !self.active.load(Ordering::Acquire) {
            return false;
        }
        let Ok(_delivery) = self.delivery.lock() else {
            return false;
        };
        if !self.active.load(Ordering::Acquire) {
            return false;
        }
        self.sink.event(handle, json);
        true
    }

    fn fail(&self, handle: u64, error: &AndroidError) {
        if !self.active.swap(false, Ordering::AcqRel) {
            return;
        }
        if let Ok(_delivery) = self.delivery.lock() {
            self.sink.error(handle, error);
        }
        self.closed.notify_waiters();
    }

    fn close(&self) {
        self.active.store(false, Ordering::Release);
        self.closed.notify_waiters();
        // Waiting for this mutex guarantees an already-started callback has
        // returned before closeSubscription returns to Kotlin.
        drop(self.delivery.lock());
    }
}

pub(crate) enum SubscriptionKind {
    Balance,
    Connection,
    Recovery,
    Payments,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct BalanceEventDto {
    balance_sat: i64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ConnectionEventDto {
    guardians: Vec<GuardianDto>,
    online_count: usize,
    total_count: usize,
    required_count: usize,
    state: &'static str,
}

#[derive(Serialize)]
struct GuardianDto {
    name: String,
    connected: bool,
}

#[derive(Serialize)]
struct ClosedDto {
    closed: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct RecoveryEventDto {
    module_id: i64,
    complete: i64,
    total: i64,
    finished: bool,
    aggregate_complete: i64,
    aggregate_total: i64,
    all_finished: bool,
}

#[derive(Serialize)]
struct PaymentsEventDto {
    payments: Vec<PaymentSummaryDto>,
    notification: Option<PaymentNotificationDto>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PaymentSummaryDto {
    operation_id: String,
    #[serde(rename = "type")]
    payment_type: &'static str,
    direction: &'static str,
    amount_sat: i64,
    fee_sat: Option<i64>,
    timestamp_millis: i64,
    status: &'static str,
    fiat_amount: Option<String>,
    fiat_currency_code: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PaymentNotificationDto {
    direction: &'static str,
    success: bool,
    amount_sat: i64,
    #[serde(rename = "type")]
    payment_type: &'static str,
}

pub(crate) fn subscribe(
    client_handle: u64,
    kind: SubscriptionKind,
    sink: Arc<dyn SubscriptionSink>,
) -> Result<u64, AndroidError> {
    let runtime = runtime::get()?;
    let state = Arc::new(SubscriptionState::new(sink));
    let handle = global_insert(HandleKind::Subscription, state.clone())?;
    client_subscriptions()
        .lock()
        .map_err(|_| AndroidError::internal())?
        .entry(client_handle)
        .or_default()
        .insert(handle);
    runtime.spawn(async move {
        let client = match global_get::<ConduitClient>(client_handle, HandleKind::Client) {
            Ok(client) => client,
            Err(error) => {
                state.fail(handle, &error);
                let _ = global_close(handle, HandleKind::Subscription);
                forget_subscription(client_handle, handle);
                return;
            }
        };
        match kind {
            SubscriptionKind::Balance => {
                let mut stream = Box::pin(client.balance_updates().await);
                loop {
                    tokio::select! {
                        _ = state.closed.notified() => break,
                        update = stream.next() => match update {
                            Some(balance_sat) => {
                                let json = serialize(&BalanceEventDto { balance_sat });
                                if !deliver(&state, handle, json) { break; }
                            }
                            None => { state.fail(handle, &stream_ended()); break; }
                        }
                    }
                }
            }
            SubscriptionKind::Connection => {
                let Some(updates) = client.connection_updates().await else {
                    state.fail(handle, &stream_ended());
                    let _ = global_close(handle, HandleKind::Subscription);
                    return;
                };
                let mut stream = Box::pin(updates);
                loop {
                    tokio::select! {
                        _ = state.closed.notified() => break,
                        update = stream.next() => match update {
                            Some(statuses) => {
                                let json = connection_json(statuses);
                                if !deliver(&state, handle, json) { break; }
                            }
                            None => { state.fail(handle, &stream_ended()); break; }
                        }
                    }
                }
            }
            SubscriptionKind::Recovery => {
                let mut stream = Box::pin(client.recovery_updates());
                let mut modules = BTreeMap::new();
                loop {
                    tokio::select! {
                        _ = state.closed.notified() => break,
                        update = stream.next() => match update {
                            Some((module_id, complete, total)) => {
                                let json = recovery_json(
                                    &mut modules,
                                    module_id,
                                    complete,
                                    total,
                                    !client.has_pending_recoveries(),
                                );
                                if !deliver(&state, handle, json) { break; }
                            }
                            None => { state.fail(handle, &stream_ended()); break; }
                        }
                    }
                }
            }
            SubscriptionKind::Payments => {
                let mut stream = Box::pin(client.event_updates());
                loop {
                    tokio::select! {
                        _ = state.closed.notified() => break,
                        update = stream.next() => match update {
                            Some(mut update) => {
                                // Event-log notifications are edge-triggered. Drain every
                                // already-ready state and publish only the newest snapshot.
                                while let Some(Some(newer)) = stream.next().now_or_never() {
                                    merge_payment_update(&mut update, newer);
                                }
                                let json = payments_json(update);
                                if !deliver(&state, handle, json) { break; }
                            }
                            None => { state.fail(handle, &stream_ended()); break; }
                        }
                    }
                }
            }
        }
        forget_subscription(client_handle, handle);
        let _ = global_close(handle, HandleKind::Subscription);
    });
    Ok(handle)
}

pub(crate) fn close(handle: u64) -> Result<String, AndroidError> {
    match global_get::<Arc<SubscriptionState>>(handle, HandleKind::Subscription) {
        Ok(state) => state.close(),
        Err(error) if error.code == AndroidErrorCode::InvalidHandle => {
            global_validate_type::<Arc<SubscriptionState>>(handle, HandleKind::Subscription)?;
        }
        Err(error) => return Err(error),
    }
    global_close(handle, HandleKind::Subscription)?;
    serialize(&ClosedDto { closed: true })
}

fn deliver(state: &SubscriptionState, handle: u64, json: Result<String, AndroidError>) -> bool {
    match json {
        Ok(json) => state.event(handle, &json),
        Err(error) => {
            state.fail(handle, &error);
            false
        }
    }
}

fn connection_json(statuses: Vec<(String, bool)>) -> Result<String, AndroidError> {
    let guardians = statuses
        .into_iter()
        .take(MAX_GUARDIANS)
        .map(|(name, connected)| GuardianDto {
            name: name.chars().take(MAX_GUARDIAN_NAME_CHARS).collect(),
            connected,
        })
        .collect::<Vec<_>>();
    if guardians.is_empty() {
        return Err(stream_ended());
    }
    let online_count = guardians
        .iter()
        .filter(|guardian| guardian.connected)
        .count();
    let total_count = guardians.len();
    let required_count = total_count - (total_count - 1) / 3;
    let state = if online_count == 0 {
        "offline"
    } else if online_count == total_count {
        "connected"
    } else if online_count >= required_count {
        "degraded"
    } else {
        "offline"
    };
    serialize(&ConnectionEventDto {
        guardians,
        online_count,
        total_count,
        required_count,
        state,
    })
}

fn recovery_json(
    modules: &mut BTreeMap<u64, (u64, u64)>,
    module_id: u64,
    complete: u64,
    total: u64,
    all_finished: bool,
) -> Result<String, AndroidError> {
    if complete > total
        || module_id > i64::MAX as u64
        || complete > i64::MAX as u64
        || total > i64::MAX as u64
    {
        return Err(invalid_recovery_progress());
    }
    modules.insert(module_id, (complete, total));
    let (aggregate_complete, aggregate_total) = modules
        .values()
        .try_fold(
            (0_u64, 0_u64),
            |(aggregate_complete, aggregate_total), (complete, total)| {
                Some((
                    aggregate_complete.checked_add(*complete)?,
                    aggregate_total.checked_add(*total)?,
                ))
            },
        )
        .ok_or_else(invalid_recovery_progress)?;
    if aggregate_complete > i64::MAX as u64 || aggregate_total > i64::MAX as u64 {
        return Err(invalid_recovery_progress());
    }
    serialize(&RecoveryEventDto {
        module_id: module_id as i64,
        complete: complete as i64,
        total: total as i64,
        finished: complete == total,
        aggregate_complete: aggregate_complete as i64,
        aggregate_total: aggregate_total as i64,
        all_finished,
    })
}

fn invalid_recovery_progress() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::Internal,
        "The recovery progress is invalid.",
        false,
    )
}

fn payments_json(update: RecentPaymentsUpdate) -> Result<String, AndroidError> {
    let payments = update
        .payments
        .into_iter()
        .take(MAX_PAYMENT_SUMMARIES)
        .map(payment_summary)
        .collect::<Result<Vec<_>, _>>()?;
    let notification = update.notification.map(payment_notification).transpose()?;
    let json = serialize(&PaymentsEventDto {
        payments,
        notification,
    })?;
    if json.len() > MAX_PAYMENT_EVENT_JSON_BYTES {
        return Err(AndroidError::internal());
    }
    Ok(json)
}

fn merge_payment_update(latest: &mut RecentPaymentsUpdate, mut newer: RecentPaymentsUpdate) {
    // Snapshot state is latest-wins, while terminal notifications are edges.
    // Preserve the newest non-null edge across snapshots that carry no edge.
    let notification = newer
        .notification
        .take()
        .or_else(|| latest.notification.take());
    *latest = newer;
    latest.notification = notification;
}

fn payment_summary(payment: ConduitPayment) -> Result<PaymentSummaryDto, AndroidError> {
    if payment.operation_id.is_empty()
        || payment.operation_id.len() > MAX_OPERATION_ID_BYTES
        || payment.amount_sats < 0
        || payment.fee_sats.is_some_and(|fee| fee < 0)
        || payment.timestamp < 0
    {
        return Err(AndroidError::internal());
    }
    let (fiat_amount, fiat_currency_code) =
        frozen_fiat(payment.fiat_amount, payment.fiat_currency_code);
    Ok(PaymentSummaryDto {
        operation_id: payment.operation_id,
        payment_type: payment_type(&payment.payment_type),
        direction: direction(payment.incoming),
        amount_sat: payment.amount_sats,
        fee_sat: payment.fee_sats,
        timestamp_millis: payment.timestamp,
        status: match payment.success {
            None => "pending",
            Some(true) => "succeeded",
            Some(false) => "failed",
        },
        fiat_amount,
        fiat_currency_code,
    })
}

fn payment_notification(
    notification: PaymentNotification,
) -> Result<PaymentNotificationDto, AndroidError> {
    if notification.amount_sats < 0 {
        return Err(AndroidError::internal());
    }
    Ok(PaymentNotificationDto {
        direction: direction(notification.incoming),
        success: notification.success,
        amount_sat: notification.amount_sats,
        payment_type: payment_type(&notification.payment_type),
    })
}

fn frozen_fiat(amount: Option<f64>, code: Option<String>) -> (Option<String>, Option<String>) {
    match (amount, code) {
        (Some(amount), Some(code))
            if amount.is_finite()
                && amount >= 0.0
                && code.len() == 3
                && code.bytes().all(|byte| byte.is_ascii_uppercase()) =>
        {
            let fixed = format!("{amount:.8}");
            let decimal = fixed.trim_end_matches('0').trim_end_matches('.');
            (
                Some(if decimal.is_empty() {
                    "0".into()
                } else {
                    decimal.into()
                }),
                Some(code),
            )
        }
        _ => (None, None),
    }
}

const fn direction(incoming: bool) -> &'static str {
    if incoming { "incoming" } else { "outgoing" }
}

const fn payment_type(payment_type: &PaymentType) -> &'static str {
    match payment_type {
        PaymentType::Lightning => "lightning",
        PaymentType::Bitcoin => "onchain",
        PaymentType::Ecash => "ecash",
    }
}

fn stream_ended() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::Internal,
        "The wallet update stream ended.",
        true,
    )
}

fn serialize<T: Serialize>(value: &T) -> Result<String, AndroidError> {
    serde_json::to_string(value).map_err(|_| AndroidError::internal())
}

#[cfg(feature = "android-jni")]
pub(crate) mod jni_sink {
    use jni::JavaVM;
    use jni::objects::{GlobalRef, JValue};

    use super::*;

    pub(crate) struct JniSubscriptionSink {
        vm: JavaVM,
        callback: GlobalRef,
    }

    impl JniSubscriptionSink {
        pub(crate) fn new(vm: JavaVM, callback: GlobalRef) -> Self {
            Self { vm, callback }
        }
    }

    impl SubscriptionSink for JniSubscriptionSink {
        fn event(&self, handle: u64, json: &str) {
            let Ok(mut env) = self.vm.attach_current_thread() else {
                return;
            };
            let Ok(json) = env.new_string(json) else {
                return;
            };
            let _ = env.call_method(
                self.callback.as_obj(),
                "onEvent",
                "(JLjava/lang/String;)V",
                &[JValue::Long(handle as i64), JValue::Object(json.as_ref())],
            );
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
        }

        fn error(&self, handle: u64, error: &AndroidError) {
            let Ok(mut env) = self.vm.attach_current_thread() else {
                return;
            };
            let Ok(code) = env.new_string(error.code.as_str()) else {
                return;
            };
            let Ok(message) = env.new_string(error.user_message) else {
                return;
            };
            let _ = env.call_method(
                self.callback.as_obj(),
                "onError",
                "(JLjava/lang/String;Ljava/lang/String;Z)V",
                &[
                    JValue::Long(handle as i64),
                    JValue::Object(code.as_ref()),
                    JValue::Object(message.as_ref()),
                    JValue::Bool(error.retryable.into()),
                ],
            );
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Barrier;
    use std::thread;

    use super::super::handles::HandleRegistry;
    use super::*;

    #[derive(Default)]
    struct RecordingSink {
        events: Mutex<usize>,
        errors: Mutex<usize>,
    }

    impl SubscriptionSink for RecordingSink {
        fn event(&self, _: u64, _: &str) {
            *self.events.lock().unwrap() += 1;
        }
        fn error(&self, _: u64, _: &AndroidError) {
            *self.errors.lock().unwrap() += 1;
        }
    }

    fn payment(success: Option<bool>) -> ConduitPayment {
        ConduitPayment {
            operation_id: "stable-operation".into(),
            incoming: false,
            payment_type: PaymentType::Lightning,
            amount_sats: 42,
            fee_sats: Some(1),
            timestamp: 123,
            success,
            ecash: Some("secret-ecash".into()),
            txid: Some("txid".into()),
            preimage: Some("secret-preimage".into()),
            address: Some("secret-address".into()),
            fiat_amount: Some(1.25),
            fiat_currency_code: Some("USD".into()),
        }
    }

    #[test]
    fn one_hundred_subscribe_close_lifecycles_suppress_late_delivery() {
        for _ in 0..100 {
            let sink = Arc::new(RecordingSink::default());
            let state = SubscriptionState::new(sink.clone());
            assert!(state.event(1, "{}"));
            state.close();
            assert!(!state.event(1, "{}"));
            assert_eq!(*sink.events.lock().unwrap(), 1);
            assert_eq!(*sink.errors.lock().unwrap(), 0);
        }
    }

    #[test]
    fn close_waits_for_inflight_callback_and_blocks_future_callbacks() {
        struct BlockingSink {
            entered: Barrier,
            release: Barrier,
        }
        impl SubscriptionSink for BlockingSink {
            fn event(&self, _: u64, _: &str) {
                self.entered.wait();
                self.release.wait();
            }
            fn error(&self, _: u64, _: &AndroidError) {}
        }
        let sink = Arc::new(BlockingSink {
            entered: Barrier::new(2),
            release: Barrier::new(2),
        });
        let state = Arc::new(SubscriptionState::new(sink.clone()));
        let delivery = {
            let state = state.clone();
            thread::spawn(move || state.event(1, "{}"))
        };
        sink.entered.wait();
        let closer = {
            let state = state.clone();
            thread::spawn(move || state.close())
        };
        // Close is blocked on the delivery mutex until the callback returns.
        assert!(!closer.is_finished());
        sink.release.wait();
        assert!(delivery.join().unwrap());
        closer.join().unwrap();
        assert!(!state.event(1, "{}"));
    }

    #[test]
    fn subscription_handles_are_typed_generation_safe_and_idempotent() {
        let mut registry = HandleRegistry::default();
        let handle = registry.insert(HandleKind::Subscription, "subscription");
        assert!(registry.close(handle, HandleKind::Quote).is_err());
        registry.close(handle, HandleKind::Subscription).unwrap();
        registry.close(handle, HandleKind::Subscription).unwrap();
        let replacement = registry.insert(HandleKind::Subscription, "replacement");
        assert_ne!(handle, replacement);
        assert!(registry.close(handle, HandleKind::Subscription).is_err());
    }

    #[test]
    fn event_json_is_bounded_and_matches_contract() {
        let balance = serialize(&BalanceEventDto { balance_sat: 42 }).unwrap();
        crate::android::assert_read_dto_snapshot("subscriptions.balance", &balance);
        assert_eq!(balance, "{\"balanceSat\":42}");
        let json = connection_json(vec![("Guardian".into(), true)]).unwrap();
        assert_eq!(
            json,
            "{\"guardians\":[{\"name\":\"Guardian\",\"connected\":true}],\"onlineCount\":1,\"totalCount\":1,\"requiredCount\":1,\"state\":\"connected\"}"
        );
        let below_quorum = connection_json(vec![
            ("A".into(), true),
            ("B".into(), false),
            ("C".into(), false),
            ("D".into(), false),
        ])
        .unwrap();
        assert!(below_quorum.contains("\"requiredCount\":3"));
        assert!(below_quorum.contains("\"state\":\"offline\""));

        let mut modules = BTreeMap::new();
        assert_eq!(
            recovery_json(&mut modules, 1, 2, 5, false).unwrap(),
            "{\"moduleId\":1,\"complete\":2,\"total\":5,\"finished\":false,\"aggregateComplete\":2,\"aggregateTotal\":5,\"allFinished\":false}"
        );
        assert_eq!(
            recovery_json(&mut modules, 2, 3, 3, true).unwrap(),
            "{\"moduleId\":2,\"complete\":3,\"total\":3,\"finished\":true,\"aggregateComplete\":5,\"aggregateTotal\":8,\"allFinished\":true}"
        );
        assert!(recovery_json(&mut modules, 3, 2, 1, false).is_err());
        assert!(recovery_json(&mut modules, u64::MAX, 0, 0, false).is_err());
    }

    #[test]
    fn payment_snapshots_fold_terminal_state_and_omit_sensitive_fields() {
        let pending = payments_json(RecentPaymentsUpdate {
            payments: vec![payment(None)],
            notification: None,
        })
        .unwrap();
        assert!(pending.contains("\"operationId\":\"stable-operation\""));
        assert!(pending.contains("\"status\":\"pending\""));

        let terminal = payments_json(RecentPaymentsUpdate {
            payments: vec![payment(Some(true))],
            notification: Some(PaymentNotification {
                incoming: false,
                success: true,
                amount_sats: 42,
                payment_type: PaymentType::Lightning,
            }),
        })
        .unwrap();
        assert!(terminal.contains("\"operationId\":\"stable-operation\""));
        assert!(terminal.contains("\"status\":\"succeeded\""));
        assert!(terminal.contains("\"notification\":{\"direction\":\"outgoing\",\"success\":true,\"amountSat\":42,\"type\":\"lightning\"}"));
        for forbidden in ["ecash", "preimage", "address", "txid", "secret-"] {
            assert!(!terminal.contains(forbidden), "leaked {forbidden}");
        }
    }

    #[test]
    fn payment_snapshot_is_newest_first_and_capped() {
        let payments = (0..MAX_PAYMENT_SUMMARIES + 20)
            .map(|index| {
                let mut payment = payment(Some(true));
                payment.operation_id = format!("operation-{index}");
                payment.timestamp = (MAX_PAYMENT_SUMMARIES + 20 - index) as i64;
                payment
            })
            .collect();
        let json = payments_json(RecentPaymentsUpdate {
            payments,
            notification: None,
        })
        .unwrap();
        let value: serde_json::Value = serde_json::from_str(&json).unwrap();
        let summaries = value["payments"].as_array().unwrap();
        assert_eq!(summaries.len(), MAX_PAYMENT_SUMMARIES);
        assert_eq!(summaries[0]["operationId"], "operation-0");
        assert_eq!(summaries.last().unwrap()["operationId"], "operation-99");
        assert!(json.len() <= MAX_PAYMENT_EVENT_JSON_BYTES);
    }

    #[test]
    fn snapshot_coalescing_preserves_newest_non_null_notification() {
        let notification = PaymentNotification {
            incoming: true,
            success: true,
            amount_sats: 42,
            payment_type: PaymentType::Ecash,
        };
        let mut latest = RecentPaymentsUpdate {
            payments: vec![payment(Some(true))],
            notification: Some(notification),
        };
        merge_payment_update(
            &mut latest,
            RecentPaymentsUpdate {
                payments: vec![payment(Some(true)), payment(Some(false))],
                notification: None,
            },
        );
        assert_eq!(latest.payments.len(), 2);
        let notification = latest.notification.expect("terminal edge must survive");
        assert!(notification.incoming);
        assert!(notification.success);
        assert_eq!(notification.amount_sats, 42);
    }
}
