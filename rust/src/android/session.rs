use serde::Serialize;

use super::async_requests;
use super::bootstrap::{self, BootstrapResult};
use super::error::AndroidError;
use super::handles::{HandleKind, global_close, global_close_all};
use super::{subscriptions, wallet};

#[derive(Serialize)]
struct ShutdownDto {
    shutdown: bool,
}

/// Tears down the process-owned Android wallet graph in child-before-parent
/// order. Serialized with bootstrap/create/restore so the database cannot be
/// reopened while old executors still retain it.
pub(crate) async fn shutdown(excluded_request: Option<u64>) -> Result<String, AndroidError> {
    let _operation = bootstrap::lock_operation().await;

    async_requests::cancel_all_except(excluded_request)?;
    subscriptions::invalidate_all()?;
    for kind in [
        HandleKind::Encoder,
        HandleKind::Decoder,
        HandleKind::Parsed,
        HandleKind::Quote,
    ] {
        global_close_all(kind)?;
    }
    wallet::shutdown_cached_clients().await?;
    global_close_all(HandleKind::Client)?;

    if let Some(state) = bootstrap::take_current()? {
        match state {
            BootstrapResult::Ready {
                factory_handle,
                database_handle,
            } => {
                let _ = global_close(factory_handle, HandleKind::Factory);
                let _ = global_close(database_handle, HandleKind::Database);
            }
            BootstrapResult::Uninitialized { database_handle } => {
                let _ = global_close(database_handle, HandleKind::Database);
            }
        }
    }
    global_close_all(HandleKind::Factory)?;
    global_close_all(HandleKind::Database)?;

    serde_json::to_string(&ShutdownDto { shutdown: true }).map_err(|_| AndroidError::internal())
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Condvar, Mutex};
    use std::time::Duration;

    use super::*;
    use crate::android::async_requests::{SnapshotRequest, TerminalSink};
    use crate::android::error::AndroidError;
    use crate::android::handles::{global_handles, global_insert};
    use crate::android::runtime;
    use crate::android::subscriptions::SubscriptionSink;

    #[derive(Default)]
    struct Terminals {
        values: Mutex<Vec<&'static str>>,
        changed: Condvar,
    }

    impl Terminals {
        fn wait_for_one(&self) -> Vec<&'static str> {
            let values = self.values.lock().unwrap();
            let (values, timeout) = self
                .changed
                .wait_timeout_while(values, Duration::from_secs(10), |v| v.is_empty())
                .unwrap();
            assert!(!timeout.timed_out(), "shutdown callback timed out");
            values.clone()
        }

        fn record(&self, value: &'static str) {
            self.values.lock().unwrap().push(value);
            self.changed.notify_all();
        }
    }

    impl TerminalSink for Terminals {
        fn success(&self, _: u64, _: &str) {
            self.record("success");
        }
        fn error(&self, _: u64, _: &AndroidError) {
            self.record("error");
        }
    }

    #[derive(Default)]
    struct SilentSubscription;
    impl SubscriptionSink for SilentSubscription {
        fn event(&self, _: u64, _: &str) {}
        fn error(&self, _: u64, _: &AndroidError) {}
    }

    #[test]
    fn async_shutdown_invalidates_composed_graph_and_allows_rebootstrap() {
        let first_dir = tempfile::tempdir().unwrap();
        let second_dir = tempfile::tempdir().unwrap();
        let runtime = runtime::get().unwrap();

        // Use the real Android bootstrap path for the database node.
        let initial = runtime
            .block_on(bootstrap::run_async(
                first_dir.path().to_string_lossy().into_owned(),
            ))
            .unwrap();
        let database = match initial {
            BootstrapResult::Uninitialized { database_handle }
            | BootstrapResult::Ready {
                database_handle, ..
            } => database_handle,
        };
        let factory = global_insert(HandleKind::Factory, "test-factory").unwrap();
        bootstrap::set_ready(database, factory).unwrap();

        // A ConduitClient constructor requires a live federation. This still
        // exercises the composed global client invalidation; real client
        // shutdown is separately covered by wallet lifecycle tests.
        let client = global_insert(HandleKind::Client, "test-client").unwrap();
        let subscription =
            subscriptions::insert_test_subscription(client, Arc::new(SilentSubscription)).unwrap();
        let parsed = global_insert(HandleKind::Parsed, "parsed").unwrap();
        let quote = global_insert(HandleKind::Quote, "quote").unwrap();
        let encoder = global_insert(HandleKind::Encoder, "encoder").unwrap();
        let decoder = global_insert(HandleKind::Decoder, "decoder").unwrap();

        let cancelled_sink = Arc::new(Terminals::default());
        let pending_request =
            async_requests::insert_pending_test_request(cancelled_sink.clone()).unwrap();
        let shutdown_sink = Arc::new(Terminals::default());
        let shutdown_request = async_requests::start(
            SnapshotRequest::ShutdownAndroidSession,
            shutdown_sink.clone(),
        )
        .unwrap();

        assert_eq!(shutdown_sink.wait_for_one(), vec!["success"]);
        assert_eq!(cancelled_sink.wait_for_one(), vec!["error"]);
        assert_eq!(shutdown_sink.values.lock().unwrap().len(), 1);
        assert_eq!(cancelled_sink.values.lock().unwrap().len(), 1);

        for (handle, kind) in [
            (database, HandleKind::Database),
            (factory, HandleKind::Factory),
            (client, HandleKind::Client),
            (subscription, HandleKind::Subscription),
            (parsed, HandleKind::Parsed),
            (quote, HandleKind::Quote),
            (encoder, HandleKind::Encoder),
            (decoder, HandleKind::Decoder),
            (pending_request, HandleKind::Request),
            (shutdown_request, HandleKind::Request),
        ] {
            for _ in 0..100 {
                if !global_handles(kind).unwrap().contains(&handle) {
                    break;
                }
                std::thread::yield_now();
            }
            assert!(
                !global_handles(kind).unwrap().contains(&handle),
                "{kind:?} handle must be stale after shutdown"
            );
        }
        assert!(bootstrap::current().unwrap().is_none());

        let second_sink = Arc::new(Terminals::default());
        async_requests::start(SnapshotRequest::ShutdownAndroidSession, second_sink.clone())
            .unwrap();
        assert_eq!(second_sink.wait_for_one(), vec!["success"]);
        assert_eq!(second_sink.values.lock().unwrap().len(), 1);

        let rebootstrapped = runtime
            .block_on(bootstrap::run_async(
                second_dir.path().to_string_lossy().into_owned(),
            ))
            .unwrap();
        assert_eq!(bootstrap::current().unwrap(), Some(rebootstrapped));
        runtime.block_on(shutdown(None)).unwrap();
    }
}
