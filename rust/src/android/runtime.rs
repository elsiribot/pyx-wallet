use std::sync::OnceLock;

use tokio::runtime::{Builder, Runtime};

use super::error::{AndroidError, AndroidErrorCode};

// Keep only the failure state. Platform/runtime error text is neither useful
// after initialization nor safe to retain as an unbounded diagnostic in a
// wallet process.
static RUNTIME: OnceLock<Result<Runtime, ()>> = OnceLock::new();

/// Returns the one process-wide runtime used by all asynchronous JNI calls.
pub(crate) fn get() -> Result<&'static Runtime, AndroidError> {
    RUNTIME
        .get_or_init(|| {
            Builder::new_multi_thread()
                .worker_threads(2)
                .thread_name("pyx-native")
                .enable_all()
                .build()
                .map_err(|_| ())
        })
        .as_ref()
        .map_err(|_| {
            AndroidError::new(
                AndroidErrorCode::RuntimeUnavailable,
                "The wallet runtime is unavailable.",
                true,
            )
        })
}

pub(crate) fn is_ready() -> bool {
    get().is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn runtime_is_singleton_and_can_execute_work() {
        let first = get().unwrap();
        let second = get().unwrap();
        assert!(std::ptr::eq(first, second));
        assert_eq!(first.block_on(async { 2 + 2 }), 4);
    }
}
