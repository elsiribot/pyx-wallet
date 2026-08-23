# Pyx Wallet design tokens

Extracted from `docs/design/prototype.html` (the authoritative interactive
mockup, "Eric's Wallet — Fedimint"). The prototype is a single self-contained
HTML file; its `:root` CSS custom properties and component rules are the
source of truth. **Scope: `CONFIG.stable = false`** — everything USD/stable
(green `$` asset, exchange screen, swap rows, USD send/receive) is out of
scope for now.

## Colors

| Token | Hex | Use |
|---|---|---|
| `bg` | `#0B0D11` | app background (plus a subtle radial `#15181d` glow at top center) |
| `surface` | `#14171C` | cards, buttons (ghost), inputs, sheets |
| `surface2` | `#1B1F26` | hover/nested surfaces, tx icons, compact chips |
| `surface3` | `#21262E` | deepest nesting, toggles, ring tracks |
| `border` | `#262B33` | default 1px borders, dividers |
| `borderStrong` | `#3A4049` | hover borders, sheet top border, grips |
| `text` | `#E8E6DF` | primary text ("stone") |
| `muted` | `#9499A1` | secondary text ("slate") |
| `faint` | `#5A6069` | tertiary text, labels ("steel") |
| `accent` | `#FF9410` | primary orange — CTAs, active states, BTC, FAB |
| `accentHover` | `#FFA838` | primary button hover |
| `onAccent` | `#1A0E00` | text/icon on accent |
| `amber` | `#FFB020` | warning status, pending |
| `burnt` | `#FF5A1F` | offline dot |
| `moss` | `#687C4D` | guardian avatar palette |
| `teal` | `#2C8C76` | guardian avatar palette |
| `green` | `#46CF7C` | positive amounts, online, success |
| `onGreen` | `#06301A` | text on green |
| `red` | `#FF6A4D` | negative amounts, errors, seed warning |

Alpha variants used: `accent @ .16` (btc badge bg), `accent @ .08` (note
pill bg), `red @ .07` bg + `.32` border (seed warning), `rgba(4,6,9,.55)`
(sheet scrim, blurred 2px).

## Typography

- **Display**: Space Grotesk (400/500/600/700) — all numbers/amounts,
  screen titles, names, key-value values, code fields, badges.
- **UI**: Inter (400/500/600/700) — body copy, buttons, chips, labels.

| Style | Font | Size | Weight | Extras |
|---|---|---|---|---|
| screen title (topbar h1) | Space Grotesk | 27 | 700 | ls -0.02em |
| centered topbar title | Space Grotesk | 21 | 700 | |
| large balance | Space Grotesk | 46 | 700 | ls -0.03em, unit 18/500 muted |
| asset-card amount | Space Grotesk | 31 | 700 | ls -0.025em, unit 15/500 muted |
| big amount (receive/detail) | Space Grotesk | 40 | 700 | ls -0.03em, unit 16/500 muted |
| section label | UI caps | 11 | 600 | ls .16em, uppercase, faint/muted |
| body/button | Inter | 15 | 600 | |
| card row title | Inter | 14.5–15 | 600 | |
| row subtitle | Inter | 12–12.5 | 400 | muted/faint |
| amount in tx row | Space Grotesk | 14.5 | 600 | unit 11/500 faint |
| input text | Space Grotesk | 18 (15 mono) | 600 | |
| fiat approx line | Space Grotesk | 13–15 | 500 | muted |

## Shape & spacing

| Element | Radius |
|---|---|
| cards, code field | 14 |
| buttons | 13 (asset-btn 11) |
| segmented control | 13 outer / 9 inner |
| inputs | 12 |
| icon buttons, tx icons | 11 |
| chips | 9 |
| badges | 8 |
| bottom sheet | 26 (top corners) |
| QR surface | 18 |
| asset card | 16 |
| avatars, FAB, dial knob | circle |

Screen padding: 22px horizontal. Card padding: 15–16px. List row: 13px
vertical, 1px `border` separators, none on last. Buttons: 15px padding
(17 for `lg`). Section label margin: 22px top / 12px bottom.

## Iconography

Inline stroke SVGs, ~1.8–2.2 stroke width, round caps/joins (Feather/Lucide
style). In Flutter use `phosphor_flutter` (already a conduit dep) with
regular weight, or vendored Lucide SVGs where a close match is needed.

## Motion

- Screen change: slide-in 14px + fade, 260ms cubic-bezier(.22,.61,.36,1).
- Bottom sheet: translateY, 320ms cubic-bezier(.32,.72,0,1) + scrim fade 250ms.
- Collapsing home header: balance card collapses/fades over ~0–35% of a
  scroll range (max(140px, 78% of header height)); compact one-line balance
  fades in after the large one is gone (never both); scan FAB swaps for
  "Top" pill at 85% of the range.
- Slide-to-send: draggable knob, snap-back <75%, settle 280ms; track turns
  green ("Sending…" flow) on completion.
- Pending tx: 3px accent arc orbiting the 11px-radius icon rect, 1.15s linear.
- Guardian ring: rounded-rect progress ring around the federation button;
  color green ≥7/7, amber ≥5, red below.
- Biometric ring: pulse 1.1s while scanning, green + check on success.
- Toast: bottom-center pill, fade+rise 250ms, auto-hide 1.9s.
- QR pending: spinner (accent top arc, 800ms) inside dashed empty QR frame.

## Screen inventory (stable=off)

| # | Screen (prototype id) | Notes |
|---|---|---|
| 1 | `home` | solo BTC asset card (`asset-stack solo`), collapsing header, grouped activity, scan FAB, settings gear left, eye + federation-ring buttons right |
| 2 | scan sheet | viewfinder w/ corner brackets + sweeping line, paste-from-clipboard |
| 3 | `receive` (btc) | seg Lightning/On-Chain/Ecash; amount + currency; QR states: LNURL (no amt), invoice (amt, async spinner), address/BIP21, ecash=scanner+capture progress+claim |
| 4 | currency picker sheet | search, BTC units (SATS/BTC) + fiat list w/ flags, check on active |
| 5 | `send` (btc) | To field w/ address-book dropdown + scan; amount gated & locked for fixed invoices; note; max fee; slide-to-send |
| 6 | `notes` | filter chips, total, denomination cards |
| 7 | `note-detail` | denom, status badge, drows, actions |
| 8 | `activity` | full grouped history (no swap/USD rows) |
| 9 | tx detail sheet | icon, type, amount, state pill, drows, wrench toggles technical rows w/ copy |
| 10 | `fed-details` | fed header, guardian ring/quorum, guardian rows (tap → guardian sheet), modules pills, meta |
| 11 | `guardians` | full guardian list |
| 12 | `wallets` + `wallet-detail` | wallet switcher cards grouped by federation, active check |
| 13 | `services` | Lightning address, NWC, etc. rows w/ badges |
| 14 | `settings` | grouped drow cards: Wallet / Security / About |
| 15 | `seed` | red warning, blurred 12-word grid, tap-to-reveal, copy, done |
| 16 | `access` | biometrics toggle |
| 17 | `limit` | big centered amount field w/ unit |
| 18 | bio auth sheet | ring, scanning/verified states, gates Backup |

Navigation: no bottom tab bar is rendered; `home` is the root of a push
stack. Back = plain chevron icon button, top-left.
