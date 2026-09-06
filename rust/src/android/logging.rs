//! Persistent diagnostics for the native wallet.
//!
//! Nothing in the process installed a tracing subscriber before this module
//! existed, so every fedimint/client log line was silently dropped and payment
//! failures were undiagnosable in the field. Logs are written to
//! `<files_dir>/logs/` (daily-rolled, bounded count) so the Kotlin side can
//! offer a user-initiated debug export, and mirrored to logcat on device.
//!
//! Log files stay inside app-private storage and only leave the device through
//! the explicit, biometric-gated share flow in Settings.

use std::fs;
use std::path::Path;
use std::sync::OnceLock;

use tracing_appender::non_blocking::WorkerGuard;
use tracing_subscriber::EnvFilter;
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;

pub(crate) const LOG_DIR_NAME: &str = "logs";
const LOG_FILE_PREFIX: &str = "pyx.log";
/// Daily files kept on disk; ~a work week of history bounds disk usage.
const KEPT_LOG_FILES: usize = 5;
/// Debug for the crates involved in payment flows, info for everything else.
/// `fm` covers fedimint's custom `fm::...` log targets.
const DEFAULT_DIRECTIVES: &str = "info,conduit=debug,fm=debug,fedimint_client=debug,\
     fedimint_api_client=debug,fedimint_ln_client=debug,fedimint_lnv2_client=debug";

// The non-blocking writer flushes only while its guard is alive; the wallet
// process never uninstalls logging, so the guard lives for the process.
static GUARD: OnceLock<Option<WorkerGuard>> = OnceLock::new();

/// Installs the process-wide subscriber once; later calls are no-ops. Failure
/// leaves the wallet fully functional, just without diagnostics.
pub(crate) fn init(files_dir: &str) {
    let installed = GUARD.get_or_init(|| try_init(files_dir)).is_some();
    if installed {
        tracing::debug!(target: "conduit", "logging active");
    }
}

/// Creates the log directory and bounds its size. Split from `try_init` so
/// tests can exercise it without touching the process-global subscriber.
fn prepare_log_dir(files_dir: &str) -> Option<std::path::PathBuf> {
    let log_dir = Path::new(files_dir).join(LOG_DIR_NAME);
    fs::create_dir_all(&log_dir).ok()?;
    prune_old_logs(&log_dir);
    Some(log_dir)
}

fn try_init(files_dir: &str) -> Option<WorkerGuard> {
    let log_dir = prepare_log_dir(files_dir)?;

    let (writer, guard) = tracing_appender::non_blocking(tracing_appender::rolling::daily(
        &log_dir,
        LOG_FILE_PREFIX,
    ));
    let file_layer = tracing_subscriber::fmt::layer()
        .with_writer(writer)
        .with_ansi(false);
    let registry = tracing_subscriber::registry()
        .with(EnvFilter::new(DEFAULT_DIRECTIVES))
        .with(file_layer);

    #[cfg(target_os = "android")]
    let installed = match tracing_android::layer("PyxRust") {
        Ok(logcat) => registry.with(logcat).try_init().is_ok(),
        Err(_) => registry.try_init().is_ok(),
    };
    #[cfg(not(target_os = "android"))]
    let installed = registry.try_init().is_ok();

    if !installed {
        return None;
    }
    tracing::info!(
        target: "conduit",
        version = env!("CARGO_PKG_VERSION"),
        "native logging initialized"
    );
    Some(guard)
}

/// Keeps the newest `KEPT_LOG_FILES` daily files. The rolling appender names
/// files `pyx.log.YYYY-MM-DD`, so lexicographic order is chronological.
fn prune_old_logs(log_dir: &Path) {
    let Ok(entries) = fs::read_dir(log_dir) else {
        return;
    };
    let mut logs: Vec<_> = entries
        .filter_map(|entry| entry.ok())
        .map(|entry| entry.path())
        .filter(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.starts_with(LOG_FILE_PREFIX))
        })
        .collect();
    logs.sort();
    let excess = logs.len().saturating_sub(KEPT_LOG_FILES);
    for stale in &logs[..excess] {
        let _ = fs::remove_file(stale);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pruning_keeps_only_the_newest_files_and_ignores_foreign_names() {
        let dir = tempfile::tempdir().unwrap();
        for day in 1..=8 {
            fs::write(dir.path().join(format!("pyx.log.2026-09-{day:02}")), b"x").unwrap();
        }
        fs::write(dir.path().join("unrelated.txt"), b"x").unwrap();

        prune_old_logs(dir.path());

        let mut remaining: Vec<_> = fs::read_dir(dir.path())
            .unwrap()
            .map(|entry| entry.unwrap().file_name().into_string().unwrap())
            .collect();
        remaining.sort();
        assert_eq!(
            remaining,
            vec![
                "pyx.log.2026-09-04",
                "pyx.log.2026-09-05",
                "pyx.log.2026-09-06",
                "pyx.log.2026-09-07",
                "pyx.log.2026-09-08",
                "unrelated.txt",
            ]
        );
    }

    #[test]
    fn prepare_creates_the_log_directory_and_init_is_idempotent() {
        let dir = tempfile::tempdir().unwrap();
        let files_dir = dir.path().to_str().unwrap();
        assert_eq!(
            prepare_log_dir(files_dir).unwrap(),
            dir.path().join(LOG_DIR_NAME)
        );
        assert!(dir.path().join(LOG_DIR_NAME).is_dir());
        // Process-global: other tests may have installed the subscriber first,
        // so only idempotency and absence of panics can be asserted here.
        init(files_dir);
        init(files_dir);
    }
}
