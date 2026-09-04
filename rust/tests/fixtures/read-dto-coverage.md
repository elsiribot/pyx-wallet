# Deterministic Android read-DTO coverage

This inventory distinguishes serializer-contract coverage from compatibility
database coverage. A DTO assembled from a public test vector proves its JSON
shape, bounds, nullability, and redaction behavior; it does not prove that an
older federation database can construct that DTO.

`android_read_dto_snapshots.json` is read directly by Rust unit tests through
`android::assert_read_dto_snapshot`. Production serializers currently freeze
these deterministic read surfaces:

| Surface | Snapshot key | Source |
| --- | --- | --- |
| Input classification | `input.classificationUnknown` | public non-payment string |
| Parsed BIP21 | `input.bitcoinPayment` | public BIP21 vector |
| Fiat catalog entry | `catalog.currencies` | public currency metadata |
| On-chain address row | `catalog.addresses` | synthetic public address label |
| Contacts | `metadata.contacts` | synthetic LNURL address |
| Guardian connection | `metadata.connectionStatus` | synthetic public guardian status |
| Recovery expiry | `recovery.expiry` | synthetic timestamps/invite marker |
| Balance stream event | `subscriptions.balance` | synthetic amount |
| Home payment summary | `wallet.homePayment` | synthetic payment with secret fields checked absent |
| Selected wallet | `wallet.selectedSnapshot` | synthetic public federation summary and opaque handles |
| Payment details | `activity.paymentDetails` | explicit synthetic sensitive-field escaping contract |

Additional exact inline serializer tests cover bootstrap, payment pages and
cursors, connection/recovery/payment stream updates, fiat conversion, receive
requests, quotes, LNURL, ecash codec, contacts mutations, federation details,
operation reconciliation, shutdown, and close responses. These stay inline
where the setup or safety assertion is more important than sharing a fixture.

## Production database fixture boundary

`android_contract.rs` creates a wallet from the published BIP39 test vector via
production `open_database` and `ConduitClientFactory::init`, closes every owner,
then reopens it through production `try_load`. It proves seed-derived identity,
EUR currency, one contact, and the empty federation list against the canonical
`wallet_snapshot.json` and its stable logical hash.

The fixture deliberately has no federation config/client module database. A
real `ConduitClient`—and therefore balance, connection, recovery, address and
payment-history reads—cannot be reconstructed without a valid federation
configuration and initialized module state. Fabricating `FederationInfo`,
`ConduitPayment`, or a fake client would test serializers but would not prove
database compatibility. Closing the remaining fixture gate therefore requires
an upstream-approved, secret-free initialized federation database (or an
official deterministic federation harness/export) with a provenance/hash and
no live funds. Network-backed creation and live history remain release-lab
work, not an offline unit-test substitute.
