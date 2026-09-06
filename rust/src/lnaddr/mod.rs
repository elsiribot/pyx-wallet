//! Local storage for claimed Lightning addresses (lnaddrd).
//!
//! This module only owns the durable record and the per-federation "primary
//! address" invariant. Networking (registering/renewing addresses with an
//! lnaddrd server) and UI are added by later tasks.

mod api;
mod identity;
mod nip98;
mod store;

#[allow(unused_imports)] // Consumed once Task 5+ wire in service discovery.
pub(crate) use api::{
    HttpTransport, LnaddrApi, OwnedAddress, QuoteResult, RegisterOk, ReqwestTransport,
};
#[allow(unused_imports)] // Consumed once Task 5+ wire in NIP-98 auth events.
pub(crate) use identity::{nostr_keypair, nostr_pubkey_hex};
#[allow(unused_imports)] // Consumed once Task 5+ wires in the lnaddrd HTTP client.
pub(crate) use nip98::nip98_header;
#[allow(unused_imports)] // Consumed once Task 5-6 wire in the service layer.
pub(crate) use store::{LnAddressRecord, LnAddressStore};
