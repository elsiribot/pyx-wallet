use conduit::{
    ConduitClientFactory, open_database, parse_bitcoin_address, parse_bolt11_invoice, parse_ecash,
    parse_invite_code, parse_lnurl, parse_mnemonic,
};
use serde_json::json;

const TEST_MNEMONIC: [&str; 12] = [
    "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
    "abandon", "abandon", "abandon", "about",
];
const LOGICAL_SNAPSHOT_FNV1A64: u64 = 13_357_778_355_892_253_930;

fn runtime() -> tokio::runtime::Runtime {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .expect("test runtime")
}

/// Stable FNV-1a over a canonical logical export. RocksDB bytes are deliberately
/// not hashed because their encoding and file layout are not a portable contract.
fn logical_snapshot_hash(snapshot: &str) -> u64 {
    snapshot.bytes().fold(0xcbf29ce484222325, |hash, byte| {
        (hash ^ u64::from(byte)).wrapping_mul(0x100000001b3)
    })
}

#[test]
fn persisted_wallet_reopens_through_production_apis_without_schema_migration() {
    let storage = tempfile::tempdir().expect("temporary application files directory");
    let files_dir = storage
        .path()
        .to_str()
        .expect("temporary path must be valid UTF-8");
    let runtime = runtime();

    let database = runtime.block_on(open_database(files_dir));
    let mnemonic = parse_mnemonic(
        TEST_MNEMONIC
            .iter()
            .map(|word| (*word).to_owned())
            .collect(),
    )
    .expect("fixture mnemonic must be valid BIP39");
    let factory = runtime
        .block_on(ConduitClientFactory::init(&database, &mnemonic))
        .expect("initialize synthetic wallet");
    runtime.block_on(factory.set_currency("EUR"));
    let contact = parse_lnurl("fixture@example.com").expect("fixture LNURL address");
    let expected_lnurl = contact.encode();
    runtime.block_on(factory.save_contact(&contact, "Fixture Contact"));
    assert!(runtime.block_on(factory.list_federations()).is_empty());

    // Factory owns a clone of Database; both must be dropped before RocksDB can
    // be reopened at the same exact production path (`filesDir/client.db`).
    drop(factory);
    drop(database);

    let reopened_database = runtime.block_on(open_database(files_dir));
    let reopened_factory = runtime
        .block_on(ConduitClientFactory::try_load(&reopened_database))
        .expect("root entropy must be discoverable after reopen");

    assert_eq!(
        runtime.block_on(reopened_factory.seed_phrase()),
        TEST_MNEMONIC.map(str::to_owned)
    );
    assert_eq!(runtime.block_on(reopened_factory.get_currency()), "EUR");
    let contacts = runtime.block_on(reopened_factory.list_contacts());
    assert_eq!(contacts.len(), 1);
    assert_eq!(contacts[0].name(), "Fixture Contact");
    assert_eq!(contacts[0].lnurl().encode(), expected_lnurl);
    assert!(
        runtime
            .block_on(reopened_factory.list_federations())
            .is_empty()
    );

    // serde_json::Map is deterministically ordered without preserve_order. This
    // snapshot contains only public test-vector state read back through core APIs.
    let logical_snapshot = json!({
        "contacts": contacts.iter().map(|contact| json!({
            "lnurl": contact.lnurl().encode(),
            "name": contact.name(),
        })).collect::<Vec<_>>(),
        "currencyCode": runtime.block_on(reopened_factory.get_currency()),
        "federations": runtime.block_on(reopened_factory.list_federations())
            .into_iter().map(|federation| json!({
                "guardianCount": federation.guardians,
                "id": federation.id.to_string(),
                "name": federation.name,
            })).collect::<Vec<_>>(),
        "seedWords": runtime.block_on(reopened_factory.seed_phrase()),
    })
    .to_string();
    assert_eq!(
        logical_snapshot,
        include_str!("fixtures/wallet_snapshot.json").trim()
    );
    assert_eq!(
        logical_snapshot_hash(&logical_snapshot),
        LOGICAL_SNAPSHOT_FNV1A64
    );

    drop(contacts);
    drop(reopened_factory);
    drop(reopened_database);
    drop(runtime);
    drop(storage);
}

#[test]
fn public_parsers_freeze_malformed_prefix_and_network_behavior() {
    assert!(parse_mnemonic(vec!["abandon".into(); 12]).is_none()); // invalid checksum
    assert!(parse_mnemonic(vec!["abandon".into(); 11]).is_none());
    assert!(parse_invite_code("fedimint:not-an-invite").is_none());
    assert!(parse_ecash("fedimint:not-ecash").is_none());
    assert!(parse_bolt11_invoice("lightning:lnbc-not-an-invoice").is_none());
    assert!(parse_lnurl("lnurl:not valid").is_none());

    let mainnet = parse_bitcoin_address("bitcoin:1BoatSLRHtKNngkdXEeobR76b53LETtpyT?amount=1")
        .expect("valid mainnet BIP21 address");
    assert_eq!(mainnet.to_string(), "1BoatSLRHtKNngkdXEeobR76b53LETtpyT");

    // The public parser intentionally returns NetworkUnchecked, so a valid
    // testnet address is parseable even in a mainnet wallet. Network rejection
    // belongs to the payment module/quote step and must not be inferred here.
    let wrong_network = parse_bitcoin_address("mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn")
        .expect("valid testnet address remains syntactically parseable");
    assert_eq!(
        wrong_network.to_string(),
        "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"
    );

    for malformed in ["", "bitcoin:", "not an address", "bc1qinvalid"] {
        assert!(
            parse_bitcoin_address(malformed).is_none(),
            "accepted {malformed:?}"
        );
    }
}
