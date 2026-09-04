# Rust-to-native Android contract

The handwritten Rust API is authoritative. JNI is asynchronous except for bounded pure parsing/getters, runs on one managed Tokio runtime, catches panics, completes each request exactly once, and returns `Result<T, AndroidError { code, user_message, retryable }>` without secrets. Opaque handles are typed and generation-checked; close is idempotent.

## Public API inventory

| Group | Calls (arguments → result) | Lifetime / Flutter consumers |
|---|---|---|
| Parsing | `word_list → [String]`; `generate_mnemonic → Mnemonic`; `parse_mnemonic([word]) → Mnemonic?`; `parse_invite_code(String) → Invite?`; `parse_ecash(String) → Ecash?`; `parse_bolt11_invoice(String) → Invoice?`; `parse_bitcoin_address(String) → Address?`; `parse_lnurl(String) → Lnurl?` | Pure/bounded; parsed handles are flow-scoped. Onboarding, scanner and Send. All raw inputs sensitive. |
| Wrapper getters | ecash `amount_sats`, `to_string`; invoice `amount_sats`; address `to_string`; LNURL `encode`; pay response `min_sats`, `max_sats`, `is_fixed_amount` | Sync getters. Serialized ecash/invoice/address/LNURL are sensitive. |
| Database | `open_database(directory) → Database` | Worker I/O; opens `directory/client.db`. Application-scoped; path sensitive. `main.dart`. |
| Factory lifecycle | `init(Database,Mnemonic) → Factory`; `try_load(Database) → Factory?`; `seed_phrase → [word]` | Worker I/O. Factory application-scoped and owns mnemonic; seed result shortest possible lifetime. Landing/recovery/base/settings. |
| Federations | `join(Invite) → Client`; `recover(Invite) → Client`; `load(FederationId) → Client?`; `list_federations → [FederationInfo]`; `leave(FederationId) → unit` | Network/I/O except list. Client selected-federation-scoped and must `shutdown`; invite sensitive. Base/federation/recovery/leave sheet. |
| Settings/contacts | `set_currency(code)`, `get_currency → code`; `save_contact(Lnurl,name)`, `get_contact_name(Lnurl) → name?`, `list_contacts → [Contact]`, `delete_contact(Lnurl)`; contact getters `name`, `lnurl`, `match_query` | Worker DB I/O (getters/search sync). Factory-scoped. Settings, currency, contacts and LNURL send. LNURL sensitive. |
| Client identity/rates | `federation_name → String?`; `federation_id → id`; `currency_code → String`; `shutdown`; `prefetch_exchange_rates`; `fiat_to_sats(decimal) → sats`; `sats_to_fiat(sats) → decimal?` | Client lifetime. Network for prefetch/conversion; native decimal is a string DTO. Shell/amount widgets. |
| Client streams | `subscribe_balance → sats`; `subscribe_connection_status → [(guardian,bool)]`; `subscribe_recovery_progress → RecoveryProgress`; `subscribe_event_log → RecentPaymentsUpdate` | Long-running worker tasks, subscription-scoped; explicit cancel before client shutdown. Federation/home/status/recovery/recent payments. |
| Recovery/metadata | `has_pending_recoveries → bool`; `expiration_date → epochSeconds?`; `expiration_successor → Invite?`; `wait_for_all_recoveries → unit` | Client-scoped; wait is long-running. Federation/recovery. Successor invite sensitive. |
| Ecash | `ecash_send(amountSat) → Ecash`; `ecash_receive(Ecash) → unit` | Network/DB, flow-scoped token. Amount screen/display and ecash sheet. Token must never be logged or persisted by Kotlin. |
| Lightning receive/send | `ln_receive(amountSat) → LnReceiveInvoice`; `ln_calculate_fees(Invoice) → LnSendFees`; `ln_send(Invoice,gatewayUrl?) → OperationId`; `lnurl → String` | Network/DB. Quote and invoice flow-scoped; operation id may persist in UI state. Receive/send drawers/screens. Invoice, gateway and generated LNURL sensitive. |
| LNURL pay | `lnurl_fetch_limits(Lnurl) → PayResponse`; `lnurl_resolve(Lnurl,PayResponse,amountSat) → Invoice` | Network, flow-scoped. LNURL amount/send. Raw LNURL and resolved invoice sensitive. |
| On-chain | `onchain_calculate_fees(Address,amountSat) → feeSat`; `onchain_send(Address,amountSat) → unit`; `onchain_receive_address → String`; `onchain_list_addresses → [(tweakIdx,address)]`; `onchain_recheck_address(tweakIdx) → unit`; `wallet_v2_receive → String?` | Network/DB, client-scoped; quotes flow-scoped. Receive and on-chain send screens. Addresses sensitive. |
| Read models | `federation_stats → FederationStats?`; `get_payment_history → [ConduitPayment]` | Worker reads, client-scoped. Connection/wallet details/history. History can contain sensitive payment metadata. |
| Currency catalog | `list_fiat_currencies → [FiatCurrency]`; `find_fiat_currency(code) → FiatCurrency?` | Pure/bounded. Currency and formatting UI. |

## Stable native DTOs

JSON field names below are the initial JNI wire contract. Unknown fields are ignored; missing required fields fail mapping. Enum strings are explicit and versioned.

```text
FederationSummary { id:String, name:String, guardianCount:Int,
  expiresAtEpochSeconds:Long?, successorInviteHandle:Long?, recovering:Boolean }
Balance { federationId:String, availableSat:Long, fiat:FiatAmount? }
FiatAmount { decimal:String, currencyCode:String }
ConnectionStatus { federationId:String, guardians:[GuardianStatus], onlineCount:Int,
  requiredCount:Int?, state:"connected"|"degraded"|"offline" }
GuardianStatus { name:String, connected:Boolean }
Payment { operationId:String, direction:"incoming"|"outgoing",
  type:"lightning"|"onchain"|"ecash", amountSat:Long, feeSat:Long?,
  timestampMillis:Long, status:"pending"|"succeeded"|"failed",
  fiat:FiatAmount?, ecash:String?, txid:String?, address:String?, preimage:String? }
PaymentHistory { payments:[Payment] }
PaymentDetails { payment:Payment }
ReceiveRequest { type:"lightning"|"lnurl"|"onchain"|"wallet_v2"|"ecash",
  payload:String, amountSat:Long?, feeSat:Long?, gatewayUrl:String?, expiresAtEpochSeconds:Long? }
FeeQuote { type:"lightning"|"onchain", amountSat:Long, feeSat:Long,
  gatewayUrl:String?, direct:Boolean?, quoteHandle:Long }
RecoveryProgress { moduleId:Long, complete:Long, total:Long }
Contact { name:String, lnurl:String }
FiatCurrency { code:String, name:String, symbol:String, decimalDigits:Int }
```

`ConduitPayment` maps `success:null/true/false` to `pending/succeeded/failed`. `PaymentType` discriminants are frozen as `lightning`, `onchain`, and `ecash`; do not rely on Rust enum ordinal. Optional payment metadata is omitted/null when unavailable. Timestamp is milliseconds since Unix epoch (matching current event conversion).

Current Rust structs map as follows: `FederationInfo{id,name,guardians}` → `FederationSummary`; balance stream `i64` → `Balance`; guardian tuples → `GuardianStatus`; `ConduitRecoveryProgress{module_id,complete,total}` → `RecoveryProgress`; `LnSendFees{gateway_url,fee_sats,is_direct}` and on-chain fee → `FeeQuote`; `LnReceiveInvoice{invoice,gateway_url,fee_sats}` → `ReceiveRequest`; `FederationStats{total_value_sat,block_count,feerate}` remains an internal wallet-details DTO.

## Binding-generation threshold

Bounded UTF-8 JSON remains the JNI wire format while individual immutable
responses stay below 256 KiB, the schemas remain additive and reviewable, and
profiling shows serialization below 5% of the affected operation's wall time.
Bulk and changing data remain paged or callback-streamed rather than increasing
that bound. Adopt generated Kotlin/Rust bindings when any one of those limits is
exceeded in two representative measurements, or when a required schema change
cannot be made backward-compatibly. A change must include equivalent bounds,
panic/error mapping, stale-handle tests, and an upgrade-compatible transition;
code-generation convenience alone is not sufficient justification.

## Ownership and failure rules

- Handle dependency order is database → factory → client → parsed/quote/subscription. A parent cannot be freed while a child operation is executing; repository shutdown closes subscriptions, then client, factory, and database.
- Client calls and all I/O run off Android's main thread. Sync parsers/getters must enforce input/output size bounds. Stream callbacks immediately hand off to a bounded Kotlin channel; overflow triggers a fresh snapshot instead of silently dropping balance state.
- Cancellation closes the request/subscription handle and suppresses late delivery while still guaranteeing one terminal completion internally. Stale/wrong-type handles return a typed non-retryable error.
- Never include mnemonic, invoice, ecash, LNURL, invite, address, preimage, database path, raw JNI argument, or serialized DTO in logs/errors. Rust panics and Java exceptions are caught and mapped at the boundary.
