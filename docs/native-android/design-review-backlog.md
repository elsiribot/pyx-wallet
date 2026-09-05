# Design review backlog (2026-09-05 screen-by-screen pass)

A per-screen review against `docs/design/prototype.html` was completed and the
actionable visual/behavioral gaps were implemented. This file records what was
deliberately deferred and why, so nothing silently disappears.

## Data-limited (needs native API growth before the UI can be faithful)

- **Guardian detail sheet** (fed-details tap → address/connection/version/latency):
  `GuardianStatus` carries only `name` + `connected`. Cheapest unlock: plumb
  `PeerUrl.url` from `rust/src/client.rs` (currently dropped) through the JSON
  contract → `NativeWalletApi` → `GuardianStatus`. Latency/connection-type/
  fedimintd-version do not exist anywhere in the pipeline.
- **Join invite preview** (federation name + guardians before Confirm): the rust
  core previews invites internally (`factory.rs` preview) but no JNI binding
  exposes it. Add `previewFederationAsync(factoryHandle, invite)` returning
  `{name, guardianCount}` and upgrade the confirm dialog to a house card.
- **Wallets screen balances**: only the selected wallet's balance is known;
  non-selected cards show guardian count instead. Multiple wallets per
  federation (prototype) cannot be represented at all.
- **Federation description/welcome text + logo/brand color**: not exposed by
  `federation_details_async`; details screen omits the description paragraph.
- **Settings "Seed phrase secured" status**: no backed-up flag exists natively;
  the Backup row shows a neutral value.
- **Send note/memo field**: `prepareLightningAsync`/`prepareOnchainAsync` have
  no memo parameter, so the prototype's Note field is omitted rather than faked.
- **Network row**: shows "Bitcoin" (not "Bitcoin mainnet") because the API has
  no network field and dev federations may be signet.

## Deliberate deviations from the prototype

- Send keeps the segmented Lightning/On-chain/Ecash control and the two-step
  "Review and send" → quote → slide-to-send flow (prototype slides directly);
  quotes and the guarded/journaled execute path are security contracts.
- Ecash claim stays behind an explicit tap (prototype auto-claims after scan);
  claiming is a journaled irreversible operation.
- Seed copy keeps the confirmation dialog + one-minute sensitive clipboard
  expiry (prototype is one-tap copy).
- The biometric prompt is the Android system BiometricPrompt; the prototype's
  bio-sheet is only mocked. Quick-spend limit is intentionally not built
  (`PlatformUtilityPolicy.HAS_QUICK_SPEND_POLICY = false`).
- The prototype's `notes`, `note-detail`, `services`, and `limit` screens have
  no native counterpart (ecash denomination browsing, Lightning address/NWC
  services and spend limits are unbuilt features, not styling gaps).
- SCAN stays a full-screen route (camera lifecycle + permission machine)
  styled like the prototype sheet rather than being a literal bottom sheet.

## Deferred component extractions (from the reusable-component audit)

Extracted already: `PyxSheet`, `IconTile`, `AssetPill`, `AnnouncementText`,
`ScanPromptFrame`, `satAmountAnnotated`, `ScanFrame`, `PyxSlideToConfirm`,
`SeedWarnBanner`, `SeedWordGrid`, `CurrencyPickerSheet`/`CurrencyRow`/
`CurrencySearchField`, `Bip21Presentation`, `SendDestinationPresentation`,
`CurrencyPickerPresentation`. Dead code removed: unused `WalletModalRoute`
entries (PAYMENT_DETAIL, SEND_CONFIRMATION, ANOTHER_ADDRESS), dead
`onOperation` arms (backup/details/connection/refresh_home), duplicate
FLAG_SECURE implementation in `SensitiveResult`.

Still worthwhile, in order:
1. `QrPanel` (white QR surface ×3, incl. encode runCatching) — keep the 14/18dp
   padding split as a param to stay pixel-identical.
2. `UnitChip` (receive + send unit chips share the SATS/BTC/fiat `when` ×4).
3. `InvoiceExpiryText` (receive + send expiry countdowns; the send copy still
   uses raw MaterialTheme colors — restyle when extracting).
4. `readClipboardText` helper (QrScanner + JoinContent duplicate the 16KB
   clipboard guard).
5. `PyxBasicField` (cursor/placeholder conventions for the 4 send/receive
   BasicTextFields; not the seed cells).
6. Move `DetailSheetRow`, `FieldLabel`, `SendInputSurface` into ui/components.
7. `PyxAlertDialog` (6 dialogs use unstyled M3 Button/TextButton chrome).
8. `OnLifecycleEvent` helper (5 DisposableEffect+observer blocks; security
   tests are the safety net — mechanical change only).
9. Seed-cell grid unification (SeedWordGrid / restore inputs / placeholder
   grid share cell chrome) — highest semantic risk, do last with the Compose
   security tests green.
10. Add each extracted component to `debug/ComponentGalleryActivity` so the
    screenshot loop covers it.
