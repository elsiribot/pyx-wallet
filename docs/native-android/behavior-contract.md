# Flutter behavior contract

This freezes observable behavior before the native Android port. Flutter remains the oracle when this document is ambiguous.

## Startup and persisted state

- The app opens `<application documents>/client.db` through `open_database`. On Android this is the app's files directory; the native app must pass that directory, not a new database path.
- If `ConduitClientFactory.try_load` finds root entropy, startup enters `BaseScreen`; otherwise it enters `LandingScreen`. Never infer initialization only from file existence.
- Root entropy, federation configs and databases, selected fiat currency (default `USD`), contacts, operation fiat snapshots, and the copied event log live in RocksDB. No Flutter `SharedPreferences` keys are used by current Dart code.
- One selected `ConduitClient` is loaded at a time. Switching/leave/recovery shuts down the old client and starts subscriptions for the new one.
- Balance, guardian connectivity, recovery progress, and recent payments are streams. Closing the Flutter stream ends the Rust loop; native subscriptions require an explicit idempotent close.

## Payment and navigation rules

- Scanner/paste dispatch order is Bolt11, Bitcoin/BIP21, LNURL, ecash, then Fedimint invite. Parsing is non-destructive and never sends automatically.
- `lightning:` and `bitcoin:` prefixes are accepted; Bitcoin query parameters are ignored. `fedimint:` is accepted for ecash and invite links. Manifest schemes are `bitcoin`, `lightning`, `lnurl`, and `fedimint`.
- Sending always has a review/confirmation step after fee calculation. A send button is disabled while work is in flight; success closes the flow and failure remains retryable with an error toast.
- Receiving exposes QR plus copy/shareable text. Lightning receive may charge a gateway fee; ecash tokens and invoices remain visible only for the current flow.
- Payment history is newest first and updated by operation id. Pending outgoing Lightning becomes success/refund; a notification is emitted only on meaningful updates. Empty history shows an empty state, not an error.
- Fiat conversion is display/input assistance. Settlement uses integer sats. A missing/stale exchange rate leaves fiat unavailable rather than changing the sat amount.

## Common UI states

Every native screen must model: initial/loading (progress and disabled primary action), ready, empty where applicable, recoverable error (message plus retry), and offline/degraded where guardian status permits viewing cached data. Async actions must not execute twice after recomposition or process recreation. Back cancels only UI work; it must not pretend an already-submitted payment was cancelled.

Current feedback is an in-app top overlay lasting about 1.5 seconds: green success or red failure. Payment event notifications are also in-app overlays; the current app does not request Android notification permission or post system notifications.

## Android identity and platform contract

| Item | Frozen value |
|---|---|
| Package/application ID | `cash.pyx.app` |
| Label | `Pyx Wallet` |
| Minimum SDK | 24 |
| Main activity | `cash.pyx.app.MainActivity`, exported, `singleTop` |
| Deep links | browsable `bitcoin:`, `lightning:`, `lnurl:`, `fedimint:` |
| Permission | `USE_BIOMETRIC` (camera must be added when native scanning is implemented) |
| Release ABI | `arm64-v8a`; optional `x86_64` only when `PYX_ABI_X86_64=1` for redroid |
| Signing | `android/key.properties` values feed the existing release keystore; preserve certificate and version lineage |
| Theme | dark only; preserve Pyx colors while preferring standard Material 3/system components and behavior |

## Security and lifetimes

- Mnemonic/root entropy: persisted only by Rust in RocksDB; words may exist in memory during create, confirmation, recovery input, or authenticated seed display. Never save in navigation state, clipboard by default, logs, analytics, screenshots, crash data, or JNI error text; clear references when the screen backgrounds/exits.
- Invoice, LNURL, ecash token, on-chain address, preimage, invite code, and raw deep-link payload are sensitive. Do not log them. Retain only through the operation/display flow; history fields are retained only where Rust already persists them.
- Database path and native handle values are operational secrets: never log them. A database/factory handle is application-scoped; a client is selected-federation-scoped; parsed request and quote handles are flow-scoped; subscriptions are repository/ViewModel-scoped.
- Seed and ecash display must use `FLAG_SECURE`. Authentication grants an in-memory, short-lived capability rather than a persisted preference.
- Amounts crossing JNI are signed integer sats/msats after range validation. New JNI fiat DTOs use decimal strings; do not reproduce the current Rust `f64` bridge in Kotlin.
