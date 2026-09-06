//! Native Android bridge infrastructure.
//!
//! This module intentionally contains no Flutter-specific types. JNI adapters
//! and Flutter adapters can therefore converge on the same service layer while
//! the two applications coexist during migration.

pub(crate) mod activity;
pub(crate) mod async_requests;
pub(crate) mod bip21;
pub(crate) mod bootstrap;
pub(crate) mod catalog;
pub(crate) mod ecash_codec;
pub(crate) mod error;
pub(crate) mod fiat;
pub(crate) mod handles;
pub(crate) mod input;
pub(crate) mod lnaddr_requests;
pub(crate) mod lnurl_pay;
pub(crate) mod logging;
pub(crate) mod metadata;
pub(crate) mod quotes;
pub(crate) mod reconciliation;
pub(crate) mod recovery;
pub(crate) mod runtime;
pub(crate) mod seed;
pub(crate) mod session;
pub(crate) mod subscriptions;
pub(crate) mod transfers;
pub(crate) mod transient;
pub(crate) mod wallet;

#[cfg(test)]
pub(crate) fn assert_read_dto_snapshot(name: &str, actual: &str) {
    let snapshots: serde_json::Value = serde_json::from_str(include_str!(
        "../../tests/fixtures/android_read_dto_snapshots.json"
    ))
    .expect("read DTO snapshot fixture must be valid JSON");
    let expected = snapshots
        .get(name)
        .unwrap_or_else(|| panic!("missing read DTO snapshot: {name}"));
    let actual: serde_json::Value =
        serde_json::from_str(actual).expect("production serializer must return valid JSON");
    assert_eq!(&actual, expected, "read DTO snapshot changed: {name}");
}

#[cfg(feature = "android-jni")]
mod exports;
