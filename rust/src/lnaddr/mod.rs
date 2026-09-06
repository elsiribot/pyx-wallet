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
    HttpTransport, LnaddrApi, OwnedAddress, QuoteResult, RegisterOk, ReqwestTransport,
};
#[allow(unused_imports)] // DEFAULT_SERVER/DiscoveredServer stay unused until a later
// task exposes discovery results directly (service::recover only needs discover()
// and DEFAULT_RELAYS).
pub(crate) use discovery::{DEFAULT_RELAYS, DEFAULT_SERVER, DiscoveredServer, discover};
#[allow(unused_imports)] // nostr_pubkey_hex stays unused until a later task exposes
// the wallet's Nostr identity directly (e.g. a JNI "my lnaddr pubkey" call).
pub(crate) use identity::{nostr_keypair, nostr_pubkey_hex};
#[allow(unused_imports)] // Consumed once Task 5+ wires in the lnaddrd HTTP client.
pub(crate) use nip98::nip98_header;
pub(crate) use service::LnAddressServiceImpl;
pub(crate) use store::{LnAddressRecord, LnAddressStore};
