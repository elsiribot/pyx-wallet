use std::panic::AssertUnwindSafe;
#[cfg(test)]
use std::panic::catch_unwind;
use std::sync::Arc;
use std::sync::atomic::{AtomicU8, Ordering};

use futures_util::FutureExt;
use zeroize::{Zeroize, Zeroizing};

use super::error::{AndroidError, AndroidErrorCode};
use super::handles::{HandleKind, global_close, global_get, global_handles, global_insert};
use super::{
    activity, bootstrap, catalog, fiat, input, lnaddr_requests, lnurl_pay, metadata, quotes,
    reconciliation, recovery, runtime, transfers, wallet,
};

const PENDING: u8 = 0;
const TERMINAL: u8 = 1;

pub(crate) trait TerminalSink: Send + Sync {
    fn success(&self, request_id: u64, json: &str);
    fn error(&self, request_id: u64, error: &AndroidError);
}

pub(crate) struct RequestState {
    terminal: AtomicU8,
    sink: Arc<dyn TerminalSink>,
}

impl RequestState {
    fn new(sink: Arc<dyn TerminalSink>) -> Self {
        Self {
            terminal: AtomicU8::new(PENDING),
            sink,
        }
    }

    fn success(&self, request_id: u64, json: &str) -> bool {
        if self
            .terminal
            .compare_exchange(PENDING, TERMINAL, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
        {
            self.sink.success(request_id, json);
            true
        } else {
            false
        }
    }

    fn error(&self, request_id: u64, error: &AndroidError) -> bool {
        if self
            .terminal
            .compare_exchange(PENDING, TERMINAL, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
        {
            self.sink.error(request_id, error);
            true
        } else {
            false
        }
    }

    fn is_pending(&self) -> bool {
        self.terminal.load(Ordering::Acquire) == PENDING
    }
}

pub(crate) enum SnapshotRequest {
    Wallet(u64),
    Connection(u64),
    FiatToSats(u64, String),
    RecoveryExpiry(u64),
    PrepareLightning(u64, String),
    ExecuteLightning(u64, u64, String),
    PrepareOnchain(u64, String, i64),
    ExecuteOnchain(u64, u64, String),
    ReceiveLightning(u64, i64),
    ReceiveOnchain(u64),
    CreateEcash(u64, i64, String),
    ClaimEcash(u64, String, String),
    PrepareLnurlQuote(u64, u64, i64),
    JoinFederation(u64, String, bool),
    SnapshotFederation(u64, String),
    LeaveFederation(u64, String),
    FederationDetails(u64),
    ListContacts(u64),
    SaveContact(u64, String, String),
    DeleteContact(u64, String),
    SetCurrency(u64, String),
    OnchainAddresses(u64),
    RecheckOnchainAddress(u64, i64),
    PaymentDetails(u64, String),
    PendingOperations(u64),
    ReconcileOperation(u64, String, reconciliation::OperationKind),
    ClearOperation(u64, String),
    PaymentHistoryPage(u64, String, i32),
    Bootstrap(String),
    CreateWallet(u64),
    RestoreWallet(u64, Zeroizing<String>),
    SeedWords(u64),
    ReceiveLnurl(u64),
    PrepareLnurl(String),
    LnaddrSnapshot(u64),
    LnaddrDiscover(u64),
    LnaddrQuote(u64, String, String, String),
    LnaddrClaim(u64, String, String, String),
    LnaddrSetPrimary(u64, String, String),
    LnaddrRelease(u64, String, String),
    LnaddrRepoint(u64, String, String),
    LnaddrRecover(u64),
    ShutdownAndroidSession,
}

struct RequestOutput {
    json: String,
    cleanup_quote: Option<u64>,
    cleanup_parsed: Option<u64>,
}

impl RequestOutput {
    fn plain(json: String) -> Self {
        Self {
            json,
            cleanup_quote: None,
            cleanup_parsed: None,
        }
    }

    fn quote((json, handle): (String, u64)) -> Self {
        Self {
            json,
            cleanup_quote: Some(handle),
            cleanup_parsed: None,
        }
    }

    fn parsed((json, handle): (String, u64)) -> Self {
        Self {
            json,
            cleanup_quote: None,
            cleanup_parsed: Some(handle),
        }
    }

    fn cleanup_suppressed(&self) {
        if let Some(handle) = self.cleanup_quote {
            quotes::close_quote_after_suppressed_delivery(handle);
        }
        if let Some(handle) = self.cleanup_parsed {
            let _ = global_close(handle, HandleKind::Parsed);
        }
    }
}

impl Drop for RequestOutput {
    fn drop(&mut self) {
        self.json.zeroize();
    }
}

pub(crate) fn start(
    request: SnapshotRequest,
    sink: Arc<dyn TerminalSink>,
) -> Result<u64, AndroidError> {
    let runtime = runtime::get()?;
    let state = Arc::new(RequestState::new(sink));
    let request_id = global_insert(HandleKind::Request, state.clone())?;
    runtime.spawn(async move {
        if !state.is_pending() {
            let _ = global_close(request_id, HandleKind::Request);
            return;
        }
        let operation = async move {
            match request {
                SnapshotRequest::Wallet(handle) => wallet::snapshot_async(handle)
                    .await
                    .map(RequestOutput::plain),
                SnapshotRequest::Connection(handle) => metadata::connection_status_async(handle)
                    .await
                    .map(RequestOutput::plain),
                SnapshotRequest::FiatToSats(handle, amount) => fiat::fiat_to_sats(handle, amount)
                    .await
                    .map(RequestOutput::plain),
                SnapshotRequest::RecoveryExpiry(handle) => {
                    recovery::snapshot(handle).await.map(RequestOutput::plain)
                }
                SnapshotRequest::PrepareLightning(handle, invoice) => {
                    quotes::prepare_lightning_async_with_handle(handle, invoice)
                        .await
                        .map(RequestOutput::quote)
                }
                SnapshotRequest::ExecuteLightning(handle, quote, correlation) => {
                    quotes::execute_lightning_async(handle, quote, correlation)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::PrepareOnchain(handle, address, amount) => {
                    quotes::prepare_onchain_async_with_handle(handle, address, amount)
                        .await
                        .map(RequestOutput::quote)
                }
                SnapshotRequest::ExecuteOnchain(handle, quote, correlation) => {
                    quotes::execute_onchain_async(handle, quote, correlation)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::ReceiveLightning(handle, amount) => {
                    transfers::receive_lightning_async(handle, amount)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::ReceiveOnchain(handle) => transfers::receive_onchain_async(handle)
                    .await
                    .map(RequestOutput::plain),
                SnapshotRequest::CreateEcash(handle, amount, correlation) => {
                    transfers::create_ecash_async(handle, amount, correlation)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::ClaimEcash(handle, payload, correlation) => {
                    transfers::claim_ecash_async(handle, payload, correlation)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::PrepareLnurlQuote(handle, session, amount) => {
                    lnurl_pay::prepare_quote_async_with_handle(handle, session, amount)
                        .await
                        .map(RequestOutput::quote)
                }
                SnapshotRequest::JoinFederation(factory, invite, recover) => {
                    wallet::join_federation_async(factory, invite, recover)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::SnapshotFederation(factory, id) => {
                    wallet::snapshot_for_async(factory, id)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::LeaveFederation(factory, id) => {
                    wallet::leave_federation_async(factory, id)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::FederationDetails(client) => {
                    wallet::federation_details_async(client)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::ListContacts(factory) => metadata::list_contacts_async(factory)
                    .await
                    .map(RequestOutput::plain),
                SnapshotRequest::SaveContact(factory, lnurl, name) => {
                    metadata::save_contact_async(factory, lnurl, name)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::DeleteContact(factory, lnurl) => {
                    metadata::delete_contact_async(factory, lnurl)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::SetCurrency(factory, code) => {
                    wallet::set_currency_async(factory, code)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::OnchainAddresses(client) => {
                    catalog::onchain_addresses_async(client)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::RecheckOnchainAddress(client, index) => {
                    catalog::recheck_onchain_address_async(client, index)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::PaymentDetails(client, operation) => {
                    activity::payment_details_async(client, operation)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::PendingOperations(client) => {
                    let client = global_get(client, HandleKind::Client)?;
                    reconciliation::pending(&client)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::ReconcileOperation(client, correlation, kind) => {
                    let client = global_get(client, HandleKind::Client)?;
                    reconciliation::reconcile(&client, &correlation, kind)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::ClearOperation(client, correlation) => {
                    let client = global_get(client, HandleKind::Client)?;
                    reconciliation::clear(&client, &correlation)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::PaymentHistoryPage(client, cursor, page_size) => {
                    activity::payment_history_page_async(client, cursor, page_size)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::Bootstrap(files_dir) => bootstrap::run_async(files_dir)
                    .await
                    .map(|result| RequestOutput::plain(result.to_json())),
                SnapshotRequest::CreateWallet(database) => wallet::create_async(database)
                    .await
                    .map(RequestOutput::plain),
                SnapshotRequest::RestoreWallet(database, words) => {
                    wallet::restore_async(database, words)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::SeedWords(factory) => wallet::seed_words_async(factory)
                    .await
                    .map(RequestOutput::plain),
                SnapshotRequest::ReceiveLnurl(client) => input::receive_lnurl_async(client)
                    .await
                    .map(RequestOutput::plain),
                SnapshotRequest::PrepareLnurl(request) => {
                    lnurl_pay::prepare_async_with_handle(request)
                        .await
                        .map(RequestOutput::parsed)
                }
                SnapshotRequest::LnaddrSnapshot(factory) => {
                    lnaddr_requests::snapshot_async(factory)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::LnaddrDiscover(factory) => {
                    lnaddr_requests::discover_async(factory)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::LnaddrQuote(factory, origin, domain, username) => {
                    lnaddr_requests::quote_async(factory, origin, domain, username)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::LnaddrClaim(client, origin, domain, username) => {
                    lnaddr_requests::claim_async(client, origin, domain, username)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::LnaddrSetPrimary(factory, domain, username) => {
                    lnaddr_requests::set_primary_async(factory, domain, username)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::LnaddrRelease(factory, domain, username) => {
                    lnaddr_requests::release_async(factory, domain, username)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::LnaddrRepoint(client, domain, username) => {
                    lnaddr_requests::repoint_async(client, domain, username)
                        .await
                        .map(RequestOutput::plain)
                }
                SnapshotRequest::LnaddrRecover(factory) => lnaddr_requests::recover_async(factory)
                    .await
                    .map(RequestOutput::plain),
                SnapshotRequest::ShutdownAndroidSession => {
                    super::session::shutdown(Some(request_id))
                        .await
                        .map(RequestOutput::plain)
                }
            }
        };
        let result = AssertUnwindSafe(operation)
            .catch_unwind()
            .await
            .unwrap_or_else(|panic| Err(AndroidError::from_panic(panic)));

        match result {
            Ok(output) => {
                if !state.success(request_id, &output.json) {
                    output.cleanup_suppressed();
                }
            }
            Err(error) => {
                state.error(request_id, &error);
            }
        }
        let _ = global_close(request_id, HandleKind::Request);
    });
    Ok(request_id)
}

pub(crate) fn cancel(request_id: u64) -> Result<bool, AndroidError> {
    let state = match global_get::<Arc<RequestState>>(request_id, HandleKind::Request) {
        Ok(state) => state,
        Err(error) if error.code == AndroidErrorCode::InvalidHandle => return Ok(false),
        Err(error) => return Err(error),
    };
    let cancelled = state.error(
        request_id,
        &AndroidError::new(
            AndroidErrorCode::Cancelled,
            "The request was cancelled.",
            false,
        ),
    );
    let _ = global_close(request_id, HandleKind::Request);
    Ok(cancelled)
}

pub(crate) fn cancel_all_except(excluded: Option<u64>) -> Result<(), AndroidError> {
    for request_id in global_handles(HandleKind::Request)? {
        if Some(request_id) != excluded {
            let _ = cancel(request_id);
        }
    }
    Ok(())
}

#[cfg(test)]
pub(crate) fn insert_pending_test_request(
    sink: Arc<dyn TerminalSink>,
) -> Result<u64, AndroidError> {
    global_insert(HandleKind::Request, Arc::new(RequestState::new(sink)))
}

#[cfg(feature = "android-jni")]
pub(crate) mod jni_sink {
    use jni::JavaVM;
    use jni::objects::{GlobalRef, JValue};

    use super::*;

    pub(crate) struct JniTerminalSink {
        vm: JavaVM,
        callback: GlobalRef,
    }

    impl JniTerminalSink {
        pub(crate) fn new(vm: JavaVM, callback: GlobalRef) -> Self {
            Self { vm, callback }
        }
    }

    impl TerminalSink for JniTerminalSink {
        fn success(&self, request_id: u64, json: &str) {
            let Ok(mut env) = self.vm.attach_current_thread() else {
                return;
            };
            let Ok(json) = env.new_string(json) else {
                return;
            };
            let json_object = json.as_ref();
            let _ = env.call_method(
                self.callback.as_obj(),
                "onSuccess",
                "(JLjava/lang/String;)V",
                &[JValue::Long(request_id as i64), JValue::Object(json_object)],
            );
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
        }

        fn error(&self, request_id: u64, error: &AndroidError) {
            let Ok(mut env) = self.vm.attach_current_thread() else {
                return;
            };
            let Ok(code) = env.new_string(error.code.as_str()) else {
                return;
            };
            let Ok(message) = env.new_string(error.user_message) else {
                return;
            };
            let code_object = code.as_ref();
            let message_object = message.as_ref();
            let _ = env.call_method(
                self.callback.as_obj(),
                "onError",
                "(JLjava/lang/String;Ljava/lang/String;Z)V",
                &[
                    JValue::Long(request_id as i64),
                    JValue::Object(code_object),
                    JValue::Object(message_object),
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
    use std::sync::{Barrier, Mutex};
    use std::thread;

    use super::*;

    #[derive(Default)]
    struct RecordingSink {
        terminals: Mutex<Vec<&'static str>>,
    }

    impl TerminalSink for RecordingSink {
        fn success(&self, _: u64, _: &str) {
            self.terminals.lock().unwrap().push("success");
        }

        fn error(&self, _: u64, _: &AndroidError) {
            self.terminals.lock().unwrap().push("error");
        }
    }

    #[test]
    fn success_error_and_panic_paths_are_exactly_once() {
        for terminal in ["success", "error", "panic"] {
            let sink = Arc::new(RecordingSink::default());
            let state = RequestState::new(sink.clone());
            match terminal {
                "success" => assert!(state.success(1, "{}")),
                "error" => assert!(state.error(1, &AndroidError::internal())),
                "panic" => {
                    let panic = catch_unwind(|| panic!("synthetic panic")).unwrap_err();
                    assert!(state.error(1, &AndroidError::from_panic(panic)));
                }
                _ => unreachable!(),
            }
            assert!(!state.success(1, "{}"));
            assert!(!state.error(1, &AndroidError::internal()));
            assert_eq!(sink.terminals.lock().unwrap().len(), 1);
        }
    }

    #[test]
    fn cancellation_and_completion_race_has_one_terminal_callback() {
        for _ in 0..64 {
            let sink = Arc::new(RecordingSink::default());
            let state = Arc::new(RequestState::new(sink.clone()));
            let barrier = Arc::new(Barrier::new(3));
            let success = {
                let state = state.clone();
                let barrier = barrier.clone();
                thread::spawn(move || {
                    barrier.wait();
                    state.success(1, "{}")
                })
            };
            let cancel = {
                let state = state.clone();
                let barrier = barrier.clone();
                thread::spawn(move || {
                    barrier.wait();
                    state.error(
                        1,
                        &AndroidError::new(
                            AndroidErrorCode::Cancelled,
                            "The request was cancelled.",
                            false,
                        ),
                    )
                })
            };
            barrier.wait();
            assert_ne!(success.join().unwrap(), cancel.join().unwrap());
            assert_eq!(sink.terminals.lock().unwrap().len(), 1);
        }
    }

    #[test]
    fn async_snapshot_services_run_inside_managed_runtime_without_nested_block_on() {
        runtime::get().unwrap().block_on(async {
            let wallet_result = AssertUnwindSafe(wallet::snapshot_async(1))
                .catch_unwind()
                .await;
            assert!(
                wallet_result.is_ok(),
                "wallet service must not panic in runtime"
            );
            assert!(wallet_result.unwrap().is_err());

            let connection_result = AssertUnwindSafe(metadata::connection_status_async(1))
                .catch_unwind()
                .await;
            assert!(
                connection_result.is_ok(),
                "connection service must not panic in runtime"
            );
            assert!(connection_result.unwrap().is_err());
        });
    }

    #[test]
    fn late_cancelled_prepare_closes_undelivered_quote_handle() {
        let sink = Arc::new(RecordingSink::default());
        let state = RequestState::new(sink);
        assert!(state.error(
            7,
            &AndroidError::new(
                AndroidErrorCode::Cancelled,
                "The request was cancelled.",
                false,
            )
        ));
        let quote_handle = global_insert(HandleKind::Quote, 42_u64).unwrap();
        let output = RequestOutput::quote(("{\"quoteHandle\":1}".into(), quote_handle));
        assert!(!state.success(7, &output.json));
        output.cleanup_suppressed();
        assert!(global_get::<u64>(quote_handle, HandleKind::Quote).is_err());
    }

    #[test]
    fn late_cancelled_lnurl_prepare_closes_undelivered_session_handle() {
        let sink = Arc::new(RecordingSink::default());
        let state = RequestState::new(sink);
        assert!(state.error(
            11,
            &AndroidError::new(
                AndroidErrorCode::Cancelled,
                "The request was cancelled.",
                false,
            )
        ));
        let session_handle = global_insert(HandleKind::Parsed, 42_u64).unwrap();
        let output = RequestOutput::parsed(("{\"sessionHandle\":1}".into(), session_handle));
        assert!(!state.success(11, &output.json));
        output.cleanup_suppressed();
        assert!(global_get::<u64>(session_handle, HandleKind::Parsed).is_err());
    }

    #[test]
    fn cancel_before_start_prevents_operation_from_beginning() {
        let sink = Arc::new(RecordingSink::default());
        let state = RequestState::new(sink);
        assert!(state.is_pending());
        assert!(state.error(
            5,
            &AndroidError::new(
                AndroidErrorCode::Cancelled,
                "The request was cancelled.",
                false,
            )
        ));
        assert!(!state.is_pending());
    }

    #[test]
    fn cancellation_suppresses_late_sensitive_terminal_output() {
        let sink = Arc::new(RecordingSink::default());
        let state = RequestState::new(sink.clone());
        assert!(state.error(
            9,
            &AndroidError::new(
                AndroidErrorCode::Cancelled,
                "The request was cancelled.",
                false,
            )
        ));
        let output = RequestOutput::plain("{\"payload\":\"secret-ecash\"}".into());
        assert!(!state.success(9, &output.json));
        assert_eq!(sink.terminals.lock().unwrap().as_slice(), &["error"]);
    }
}
