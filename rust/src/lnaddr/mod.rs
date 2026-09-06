//! Local storage for claimed Lightning addresses (lnaddrd).
//!
//! This module only owns the durable record and the per-federation "primary
//! address" invariant. Networking (registering/renewing addresses with an
//! lnaddrd server) and UI are added by later tasks.

mod store;

#[allow(unused_imports)] // Consumed once Task 4-6 wire in the service layer.
pub(crate) use store::{LnAddressRecord, LnAddressStore};
