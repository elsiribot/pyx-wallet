use std::path::{Component, Path};
use std::sync::{Mutex, OnceLock};
use tokio::sync::Mutex as AsyncMutex;

use crate::factory::ConduitClientFactory;
use crate::open_database;

use super::error::{AndroidError, AndroidErrorCode};
use super::handles::{HandleKind, global_insert};

const MAX_FILES_DIR_BYTES: usize = 4096;
const NATIVE_VERSION: &str = env!("CARGO_PKG_VERSION");

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum BootstrapResult {
    Uninitialized {
        database_handle: u64,
    },
    Ready {
        database_handle: u64,
        factory_handle: u64,
    },
}

impl BootstrapResult {
    pub(crate) fn to_json(self) -> String {
        match self {
            Self::Uninitialized { database_handle } => format!(
                "{{\"status\":\"uninitialized\",\"nativeVersion\":\"{NATIVE_VERSION}\",\"databaseHandle\":{database_handle}}}"
            ),
            Self::Ready {
                database_handle,
                factory_handle,
            } => format!(
                "{{\"status\":\"ready\",\"nativeVersion\":\"{NATIVE_VERSION}\",\"databaseHandle\":{database_handle},\"factoryHandle\":{factory_handle}}}"
            ),
        }
    }
}

static BOOTSTRAP: OnceLock<Mutex<Option<BootstrapResult>>> = OnceLock::new();
static BOOTSTRAP_OPERATION: OnceLock<AsyncMutex<()>> = OnceLock::new();

pub(crate) async fn lock_operation() -> tokio::sync::MutexGuard<'static, ()> {
    BOOTSTRAP_OPERATION
        .get_or_init(|| AsyncMutex::new(()))
        .lock()
        .await
}

pub(crate) fn set_ready(database_handle: u64, factory_handle: u64) -> Result<(), AndroidError> {
    let mut state = BOOTSTRAP
        .get_or_init(|| Mutex::new(None))
        .lock()
        .map_err(|_| AndroidError::internal())?;
    *state = Some(BootstrapResult::Ready {
        database_handle,
        factory_handle,
    });
    Ok(())
}

pub(crate) fn current() -> Result<Option<BootstrapResult>, AndroidError> {
    BOOTSTRAP
        .get_or_init(|| Mutex::new(None))
        .lock()
        .map(|state| *state)
        .map_err(|_| AndroidError::internal())
}

pub(crate) fn take_current() -> Result<Option<BootstrapResult>, AndroidError> {
    BOOTSTRAP
        .get_or_init(|| Mutex::new(None))
        .lock()
        .map_err(|_| AndroidError::internal())
        .map(|mut state| state.take())
}

/// Opens `<dir>/client.db` in the directory the caller resolved and checks for
/// its existing root entropy. The Kotlin side is responsible for passing the
/// directory that actually holds the wallet: Flutter created it under the
/// path_provider documents directory (`<dataDir>/app_flutter` on Android), so
/// upgraded installs must pass that legacy directory while fresh native
/// installs pass `filesDir` (see `WalletDataDirectory.resolve`). The mutex
/// prevents concurrent RocksDB opens and also makes repeated
/// Activity/repository bootstrap calls idempotent.
pub(crate) async fn run_async(files_dir: String) -> Result<BootstrapResult, AndroidError> {
    validate_files_dir(&files_dir)?;
    let _operation = lock_operation().await;

    if let Some(result) = *BOOTSTRAP
        .get_or_init(|| Mutex::new(None))
        .lock()
        .map_err(|_| AndroidError::internal())?
    {
        return Ok(result);
    }

    let database = open_database(&files_dir).await;
    let factory = ConduitClientFactory::try_load(&database).await;
    let database_handle = global_insert(HandleKind::Database, database)?;
    let result = match factory {
        Some(factory) => BootstrapResult::Ready {
            database_handle,
            factory_handle: global_insert(HandleKind::Factory, factory)?,
        },
        None => BootstrapResult::Uninitialized { database_handle },
    };
    *BOOTSTRAP
        .get_or_init(|| Mutex::new(None))
        .lock()
        .map_err(|_| AndroidError::internal())? = Some(result);
    Ok(result)
}

fn validate_files_dir(files_dir: &str) -> Result<(), AndroidError> {
    let path = Path::new(files_dir);
    if files_dir.is_empty()
        || files_dir.len() > MAX_FILES_DIR_BYTES
        || files_dir.as_bytes().contains(&0)
        || !path.is_absolute()
        || path.components().any(|part| part == Component::ParentDir)
    {
        return Err(AndroidError::new(
            AndroidErrorCode::InvalidArgument,
            "The application storage directory is invalid.",
            false,
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::android::runtime;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[test]
    fn path_validation_is_bounded_and_requires_normalized_absolute_path() {
        assert!(validate_files_dir("/data/user/0/cash.pyx.app/files").is_ok());
        assert!(validate_files_dir("").is_err());
        assert!(validate_files_dir("relative/files").is_err());
        assert!(validate_files_dir("/data/user/../other").is_err());
        assert!(validate_files_dir(&format!("/{}", "x".repeat(MAX_FILES_DIR_BYTES))).is_err());
    }

    #[test]
    fn bootstrap_json_has_only_stable_non_secret_fields() {
        assert_eq!(
            BootstrapResult::Uninitialized { database_handle: 7 }.to_json(),
            format!(
                "{{\"status\":\"uninitialized\",\"nativeVersion\":\"{NATIVE_VERSION}\",\"databaseHandle\":7}}"
            )
        );
        assert_eq!(
            BootstrapResult::Ready {
                database_handle: 7,
                factory_handle: 9,
            }
            .to_json(),
            format!(
                "{{\"status\":\"ready\",\"nativeVersion\":\"{NATIVE_VERSION}\",\"databaseHandle\":7,\"factoryHandle\":9}}"
            )
        );
    }

    #[test]
    fn startup_operation_lock_serializes_bootstrap_create_and_restore() {
        let active = Arc::new(AtomicUsize::new(0));
        let maximum = Arc::new(AtomicUsize::new(0));
        runtime::get().unwrap().block_on(async {
            let tasks = (0..8)
                .map(|_| {
                    let active = active.clone();
                    let maximum = maximum.clone();
                    tokio::spawn(async move {
                        let _guard = lock_operation().await;
                        let now = active.fetch_add(1, Ordering::SeqCst) + 1;
                        maximum.fetch_max(now, Ordering::SeqCst);
                        tokio::task::yield_now().await;
                        active.fetch_sub(1, Ordering::SeqCst);
                    })
                })
                .collect::<Vec<_>>();
            for task in tasks {
                task.await.unwrap();
            }
        });
        assert_eq!(maximum.load(Ordering::SeqCst), 1);
    }
}
