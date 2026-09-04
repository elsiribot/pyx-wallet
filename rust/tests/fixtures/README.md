# Android persistence contract fixtures

`android_contract.rs` creates its RocksDB fixture in a fresh temporary application-files directory
and exercises the same production API path used by Flutter and native Android:
`open_database(filesDir)` opens `filesDir/client.db`, and `ConduitClientFactory::init` / `try_load`
write and read the existing schema.

The mnemonic is the standard public BIP39 test vector (`abandon` repeated eleven times followed by
`about`). It is synthetic test data, is never funded, and must never be used as a real wallet. The
fixture joins no federation and performs no network or payment operation. Currency and contact rows
exist solely to detect persistence/schema incompatibility across close and reopen.

No binary RocksDB snapshot is checked in: constructing it through production APIs avoids
platform-specific RocksDB files while still testing the authoritative path and drop/reopen ordering.

`wallet_snapshot.json` is the canonical logical export read back through public core APIs. The test
compares both its exact JSON and an explicitly stable FNV-1a hash. This detects accidental contract
changes without pretending RocksDB's nondeterministic binary files are portable. Parser cases also
freeze malformed input, URI-prefix behavior, and the important fact that Bitcoin address parsing is
network-unchecked: wrong-network rejection belongs to the later payment/quote layer.

`android_read_dto_snapshots.json` centralizes deterministic JSON values consumed
by production Android serializers. See `read-dto-coverage.md` for the exact
coverage matrix and the reason federation-backed database reads cannot be
claimed from this empty, offline fixture.

There is intentionally no fixture-generation shell script. The test creates only a temporary wallet
from a published BIP39 vector, which avoids providing tooling that could accidentally export a real
developer wallet or its secrets.
