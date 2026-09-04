# Screen and sheet inventory

Prototype names refer to `docs/design/prototype.html`; where no exact prototype exists, use the closest named view and Flutter as the behavioral oracle. All rows require loading/error handling for listed async calls.

## Screens

| Flutter screen | Native destination / entry | Core calls and notable states |
|---|---|---|
| `landing_screen` | onboarding; no factory at startup | create generates mnemonic then factory init; restore opens recovery phrase input. Initializing/error. |
| `input_recovery_phrase_screen` | recovery input; onboarding restore | `word_list`, `parse_mnemonic`, factory init; autocomplete, invalid/incomplete phrase, initializing/error. |
| `confirm_recovery_phrase_screen` | create confirmation | `parse_mnemonic`, factory init; wrong order/words and init failure; success clears stack to wallet shell. |
| `base_screen` | root shell / initialized startup | factory list/load/join/recover/get currency/seed; selected federation loading, no federations, load failure, pending recovery. Owns scan and wallet switcher. |
| `home_screen_body` | prototype `home` | consumes balance, currency, connection and recent-payment state supplied by federation shell; masked balance, empty activity, offline/degraded. |
| `federation_screen` | selected wallet shell / prototype `home` | client balance/connection/event streams, history, exchange prefetch, expiry and recovery flags; loading, offline, recovery, expired/successor, stream error. |
| `receive_screen` | root Receive action / prototype receive modes | `fiat_to_sats`, `ln_receive`, `lnurl`, `onchain_receive_address`, `wallet_v2_receive`; mode chooser, amount entry, generating/error/unsupported. |
| `invoice_amount_screen` | Lightning amount route | `ln_receive` or `lnurl`; invalid amount, generating/error, then invoice/LNURL display. |
| `display_invoice_screen` | generated Lightning request | QR/copy/share; invoice is sensitive and ephemeral. |
| `display_lnurl_screen` | generated personal LNURL | QR/copy/share; request is sensitive and ephemeral. |
| `onchain_address_screen` | on-chain receive/history | list/generate/recheck address; loading, empty, generation/recheck error. |
| `wallet_v2_receive_screen` | wallet-v2 receive result | federation stats plus address display; unavailable stats/address. |
| `send_screen` | root Send action or incoming URI | parse/dispatch request; Lightning fees/send, on-chain send, contacts; invalid/unsupported, quote loading/error, submit/pending/error. |
| `lightning_address_entry_screen` | send to Lightning address | parse LNURL, contact lookup, then limits; invalid address/LNURL and lookup error. |
| `lnurl_amount_screen` | valid pay LNURL | `lnurl_fetch_limits`, `lnurl_resolve`, `ln_calculate_fees`, contact name route; fixed/ranged amount, limits/resolve/quote errors. |
| `confirm_lnurl_send_screen` | LNURL quote ready | `ln_send`; submitting, settled/error; never auto-send. |
| `onchain_amount_screen` | parsed Bitcoin destination | `onchain_calculate_fees`; invalid amount, quote loading/error, then confirmation. |
| `confirm_onchain_send_screen` | on-chain quote ready | `onchain_send`; submitting/success/error; never auto-send. |
| `ecash_amount_screen` | receive mode: create ecash | `ecash_send`; invalid/insufficient amount, creating/error, then token display. |
| `display_ecash_screen` | generated or scanned token | token QR/copy; `ecash_receive` when redeeming; redeeming/success/error. Token is highly sensitive. |
| `payment_history_screen` | prototype activity / See all | supplied history; newest-first, empty state and payment detail sheet. |
| `connection_status_screen` | guardian/status header | federation name/stats and connection stream; connecting, partial quorum/offline, stats unavailable. |
| `settings_screen` | prototype settings | currency, federations, seed phrase; list/load error; routes to currency, contacts, backup. |
| `select_currency_screen` | settings currency | `list_fiat_currencies`, factory get/set currency; searchable list, saving/error. |
| `display_contacts_screen` | contacts / send chooser | factory list/delete contacts; loading, empty, search/no matches, delete error. |
| `contact_name_entry_screen` | save/edit LNURL contact | factory save contact; blank/duplicate policy follows Flutter, saving/error. |
| `display_recovery_phrase_screen` | authenticated backup/seed display | factory seed phrase; authentication denied/error; secure display and explicit exit. |

## Modal sheets/drawers

| Flutter drawer | Entry and behavior |
|---|---|
| `scanner_drawer` | Root scan action. Camera permission, scan/paste, same parser dispatch as Send; invalid input remains open with feedback. |
| `invite_drawer` | No-wallet/add-wallet chooser for scan/paste plus Join or Recover. |
| `invite_scanner_drawer` | Camera invite scan; `parse_invite_code`; invalid scan error. |
| `recovery_drawer` | Pending recovery progress stream; wait, shutdown/reload on completion; partial/error/retry states. |
| `confirm_lightning_send_drawer` | Bolt11 fee summary and `ln_send`; submitting/success/error. |
| `lightning_invoice_drawer` | Parsed invoice quote via `ln_calculate_fees`; then confirm/send. |
| `lnurl_drawer` | Parsed LNURL, contact lookup, then fixed/ranged amount route. |
| `ecash_drawer` | Parsed token summary and `ecash_receive`; redeeming/success/error. |
| `generate_onchain_address_drawer` | Confirms creation of another legacy on-chain address. |
| `payment_details_drawer` | Payment direction/type, sats, fiat snapshot, fee, status/time and optional address/txid/preimage/ecash; unavailable fields omitted. |
| `wallet_v2_wallet_details_drawer` | Federation wallet-v2 total value, block count and optional feerate; unavailable state. |
| `leave_federation_drawer` | Destructive confirmation; factory `leave`, then switch/root update. No implicit undo. |

## External entry mapping

| Input | Destination |
|---|---|
| Bolt11 or `lightning:` | invoice quote → Lightning confirmation |
| Bitcoin address or `bitcoin:` BIP21 | on-chain amount/quote → confirmation |
| LNURL / `lnurl:` / Lightning address | limits/amount → invoice resolution → confirmation |
| Fedimint ecash or `fedimint:` ecash | ecash redeem sheet |
| Fedimint invite / `fedimint:` invite | join/recover chooser |

Deep links are queued until bootstrap and any authentication completes, consumed once across activity recreation, length-limited, and never trigger payment without review.
