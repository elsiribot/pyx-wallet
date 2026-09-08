//! Local storage for claimed Lightning addresses (lnaddrd).
//!
//! This module only owns the durable record and the per-federation "primary
//! address" invariant. Networking (registering/renewing addresses with an
//! lnaddrd server) and UI are added by later tasks.

mod api;
mod discovery;
mod identity;
mod nip98;
mod service;
mod store;

pub(crate) use api::{
    ApiError, HttpTransport, LnaddrApi, OwnedAddress, QuoteResult, RegisterOk, ReqwestTransport,
};
#[allow(unused_imports)] // DEFAULT_SERVER stays unused now that callers go through
// default_server_origin()/default_server_domain(), which honour the debug override;
// it is kept exported as the documented shipped constant.
pub(crate) use discovery::{
    DEFAULT_RELAYS, DEFAULT_SERVER, DiscoveredServer, default_server_domain, default_server_origin,
    discover, is_public_domain, normalize_origin,
};
pub(crate) use identity::nostr_keypair;
#[allow(unused_imports)] // Consumed once Task 5+ wires in the lnaddrd HTTP client.
pub(crate) use nip98::nip98_header;
#[allow(unused_imports)] // is_valid_domain/is_valid_username are re-exported for the JNI
// bridge (`android::lnaddr_requests`), which only exists under the `android-jni`
// feature; `service` itself uses them through their local names.
pub(crate) use service::{LnAddressServiceImpl, is_valid_domain, is_valid_username};
pub(crate) use store::{LnAddressRecord, LnAddressStore};
