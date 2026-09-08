//! Seed-derived Nostr identity.
//!
//! The wallet derives a single app-global Nostr keypair from the BIP-39
//! mnemonic, at `global_root_secret/child_key(ChildId(1))`.
//!
//! Child `0` is reserved: `fedimint-client-module`'s
//! `get_default_client_secret` starts every federation's per-federation
//! client tree at `global_root_secret.child_key(ChildId(0))`. Using
//! `ChildId(1)` here guarantees the Nostr identity never collides with any
//! federation's derived keys.

use bitcoin::secp256k1::{Keypair, Secp256k1};
use fedimint_bip39::{Bip39RootSecretStrategy, Mnemonic};
use fedimint_client::secret::RootSecretStrategy;
use fedimint_derive_secret::ChildId;

/// `key-type` reserved for the wallet's Nostr identity under the global root
/// secret. Child `0` belongs to `fedimint-client`'s per-federation tree.
const NOSTR_CHILD_ID: ChildId = ChildId(1);

/// Derives the wallet's app-global Nostr keypair from its BIP-39 mnemonic, at
/// `global_root_secret/child_key(ChildId(1))` per the spec.
pub(crate) fn nostr_keypair(mnemonic: &Mnemonic) -> Keypair {
    let global_root_secret = Bip39RootSecretStrategy::<12>::to_root_secret(mnemonic);
    let secp = Secp256k1::new();

    global_root_secret
        .child_key(NOSTR_CHILD_ID)
        .to_secp_key(&secp)
}

/// Lowercase hex x-only pubkey (64 chars) — the wallet's npub in hex form.
///
/// Test-only: v1 deliberately does not surface the npub in the UI (see
/// `docs/superpowers/specs/2026-09-06-lightning-address-design.md`), so the
/// only consumer is the golden-value test below that pins the derivation
/// path. Drop the `cfg(test)` when a real caller (e.g. a JNI "my lnaddr
/// pubkey" call) appears.
#[cfg(test)]
pub(crate) fn nostr_pubkey_hex(keypair: &Keypair) -> String {
    let (x_only, _parity) = keypair.x_only_public_key();
    fedimint_core::hex::encode(x_only.serialize())
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use super::*;

    /// Standard BIP-39 test mnemonic (all-zero entropy).
    const TEST_MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon abandon abandon \
         abandon abandon abandon about";

    fn test_mnemonic() -> Mnemonic {
        Mnemonic::from_str(TEST_MNEMONIC).expect("valid BIP-39 test mnemonic")
    }

    #[test]
    fn derivation_is_deterministic() {
        let mnemonic = test_mnemonic();

        let a = nostr_keypair(&mnemonic);
        let b = nostr_keypair(&mnemonic);

        assert_eq!(a.secret_bytes(), b.secret_bytes());
    }

    #[test]
    fn pubkey_hex_is_64_lowercase_hex_chars() {
        let keypair = nostr_keypair(&test_mnemonic());
        let hex = nostr_pubkey_hex(&keypair);

        assert_eq!(hex.len(), 64, "expected 64 hex chars, got {hex:?}");
        assert!(
            hex.chars()
                .all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()),
            "expected lowercase hex, got {hex:?}"
        );
    }

    /// Child `0` is reserved for `fedimint-client`'s per-federation client
    /// tree (see `get_default_client_secret` in `fedimint-client-module`,
    /// which starts each federation's secret at
    /// `global_root_secret.child_key(ChildId(0))`). The Nostr identity at
    /// `ChildId(1)` must never coincide with it.
    #[test]
    fn does_not_collide_with_child_0_per_federation_tree() {
        let mnemonic = test_mnemonic();

        let nostr = nostr_keypair(&mnemonic);

        let global_root_secret = Bip39RootSecretStrategy::<12>::to_root_secret(&mnemonic);
        let secp = Secp256k1::new();
        let federation_tree_root = global_root_secret.child_key(ChildId(0)).to_secp_key(&secp);

        assert_ne!(
            nostr.secret_bytes(),
            federation_tree_root.secret_bytes(),
            "nostr identity (child 1) must not collide with the per-federation tree (child 0)"
        );
    }

    /// Golden value for the standard test mnemonic, pinned once the
    /// derivation is implemented. Guards against accidental changes to the
    /// derivation path (root secret salt, child id, or key scheme).
    #[test]
    fn pubkey_hex_matches_golden_value_for_test_mnemonic() {
        let keypair = nostr_keypair(&test_mnemonic());
        let hex = nostr_pubkey_hex(&keypair);

        assert_eq!(
            hex, "9fef2694c4d77e8143c4cbeed9fc6c9ee9762218dcc4440116c6c15bce33b81b",
            "derived pubkey changed — verify this is an intentional derivation-path change"
        );
    }
}
