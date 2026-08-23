# Pyx Wallet Design-System Port — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the conduit Fedimint wallet (Flutter + Rust) to pixel-match the "Eric's Wallet" interactive prototype (`docs/design/prototype.html`) with the stable-balance module OFF, verified screen-by-screen against auto-generated reference screenshots.

**Architecture:** Conduit's Rust/fedimint core and flutter_rust_bridge API stay untouched. All work is in the Flutter layer: a single design-token file, a shared component library mirroring the prototype's CSS components, then screen-by-screen ports. A screenshot harness renders the prototype (Chromium headless) and the app (redroid + adb) at identical logical size (390×844) for a repeatable design-match loop.

**Tech Stack:** Flutter (stable), Rust (fedimint 0.11, FRB =2.10.0), cargo-ndk, Nix dev shell (`flake.nix`), redroid (Android 13 x86_64 container) for on-device verification, Chromium headless + ImageMagick for the comparison loop.

## Global Constraints

- Design scope: prototype with `CONFIG.stable = false`. **No** USD asset card, exchange screen, swap rows, USD receive/send, or USD address book. Fiat *display* currency (≈ €… lines, currency picker) IS in scope — it is separate from the stable module.
- Source of truth: `docs/design/prototype.html` (line refs below) + `docs/design/tokens.md`. When this plan and the prototype disagree, the prototype wins.
- All colors, radii, text styles come from `lib/theme/tokens.dart`. **Zero raw `Color(0x…)`/`Colors.*` outside that file** (checked by `tool/check_tokens.sh`).
- Fonts: Space Grotesk (display/numbers) + Inter (UI), vendored as assets — no runtime google_fonts fetch.
- Dark theme only (prototype has no light mode).
- No bottom tab bar. `home` is the navigation root; every other screen pushes onto the stack with a plain back chevron.
- Do not modify `rust/` API surfaces. Where the prototype shows data conduit's API doesn't provide (Services rows, note expiry metadata), render the design with whatever real data exists and static placeholder text otherwise — never invent fake balances/transactions in release paths.
- App name: "Pyx Wallet" (replaces "Conduit" user-facing strings; package ids may stay for now).
- NDK 28.2.13676358, Kotlin 2.1.0, FRB pinned `=2.10.0` — already pinned in repo; don't bump.
- Commit after every green task. Repo already carries conduit's git history.

---

## Phase 0 — Environment

### Task 0.1: Nix dev shell boots and builds the app

**Files:**
- Exists: `flake.nix` (already written — Flutter, Rust w/ android targets, cargo-ndk, Android SDK/NDK 28.2.13676358, JDK 17, chromium, imagemagick, adb; installs `flutter_rust_bridge_codegen` 2.10.0 into `.cargo-tools/` on first entry)
- Create: `.envrc` containing exactly `use flake`
- Modify: `.gitignore` — append `.cargo-tools/`, `.direnv/`, `docs/design/shots/`

- [ ] **Step 1:** `git init` is NOT needed (history came with the clone); run `git status` to confirm the repo is intact.
- [ ] **Step 2:** `nix develop -c flutter --version` — expect a Flutter stable ≥3.29 banner. If `androidenv` lacks NDK 28.2.13676358, list available versions with `nix eval nixpkgs#androidenv --apply 'a: 1'` failing fast and pin the nearest 28.x, updating `flake.nix` AND `android/app/build.gradle.kts` `ndkVersion` together.
- [ ] **Step 3:** `nix develop -c flutter pub get` — expect "Got dependencies".
- [ ] **Step 4:** `nix develop -c flutter_rust_bridge_codegen generate` — expect `lib/bridge_generated.dart/` to appear.
- [ ] **Step 5:** Build the Rust lib for both targets (replaces `build-android.sh`'s docker/`cross` approach, which doesn't fit NixOS):

```bash
cd rust
RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384" \
  cargo ndk -t arm64-v8a -t x86_64 -o ../android/app/src/main/jniLibs \
  build --release
```

Expect `android/app/src/main/jniLibs/{arm64-v8a,x86_64}/libconduit.so`. cargo-ndk copies `libc++_shared.so` itself when needed; if the app later crashes with a missing `libc++_shared.so`, copy it from `$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/<triple>/`.
- [ ] **Step 6:** `nix develop -c flutter build apk --debug` — expect `build/app/outputs/flutter-apk/app-debug.apk`.
- [ ] **Step 7:** Write `tool/build-android-nix.sh` wrapping steps 4–6 (codegen → cargo ndk both targets → flutter build), `chmod +x`.
- [ ] **Step 8:** Commit: `chore: nix dev shell + nix-native android build`

### Task 0.2: App runs on local Android (redroid)

Use the `running-local-android` skill verbatim (podman/adb paths, retry loops). **Start the container at 780×1688, dpi 320** so the logical viewport is 390×844 — identical to the prototype device frame:

```bash
$PODMAN run -d --name pyx --privileged docker.io/redroid/redroid:13.0.0-latest \
  androidboot.redroid_gpu_mode=guest \
  androidboot.redroid_width=780 androidboot.redroid_height=1688 androidboot.redroid_dpi=320
```

- [ ] **Step 1:** Start container, `adb connect`, wait for boot (per skill).
- [ ] **Step 2:** `adb -s $IP:5555 install build/app/outputs/flutter-apk/app-debug.apk`, launch via `adb shell monkey -p <appId> 1`, screenshot with `adb exec-out screencap -p > docs/design/shots/boot.png`, and view the PNG to confirm the app renders.
- [ ] **Step 3:** Commit any fixes needed: `fix: run on redroid x86_64`

---

## Phase 1 — Design-match harness (build this BEFORE styling anything)

This is the mechanism that "ensures the design matches": every screen gets a prototype reference PNG and an app PNG at the same logical size, compared side-by-side plus spot-checked pixel colors, every time.

### Task 1.1: Prototype reference-screenshot generator

**Files:**
- Create: `tool/proto_shot.sh`
- Create: `docs/design/harness.html`

`harness.html` loads `prototype.html` in an iframe-free way: it is a copy-free wrapper — a small HTML file that document.writes nothing; instead `proto_shot.sh` drives Chromium with `--run-all-compositor-stages-before-draw --virtual-time-budget` and query flags. Simplest robust approach: inject config/navigation via a tiny JS prelude appended to a temp copy.

- [ ] **Step 1:** Write `tool/proto_shot.sh`:

```bash
#!/usr/bin/env bash
# tool/proto_shot.sh <screen-id> [json-params] — renders one prototype screen
# at 390x844 logical px with the stable module OFF.
set -euo pipefail
SCREEN="${1:-home}"; PARAMS="${2:-{}}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/docs/design/shots/ref"; mkdir -p "$OUT"
TMP=$(mktemp -d)
cp "$ROOT/docs/design/prototype.html" "$TMP/p.html"
cat >> "$TMP/p.html" <<EOF
<script>
  CONFIG.stable=false;            // scope: stable balance module OFF
  stack=[{id:'home',p:{}}];
  if('$SCREEN'!=='home'){ stack.push({id:'$SCREEN', p:$PARAMS}); }
  render();
</script>
EOF
chromium --headless=new --disable-gpu --hide-scrollbars \
  --window-size=390,844 --force-device-scale-factor=2 \
  --virtual-time-budget=8000 \
  --screenshot="$OUT/$SCREEN.png" "file://$TMP/p.html"
echo "$OUT/$SCREEN.png"
```

(At ≤640px width the prototype's own media query renders full-bleed with no fake statusbar/device frame — exactly the app's chrome. `--force-device-scale-factor=2` matches redroid's 320dpi 2× scale: both PNGs come out 780×1688.)
- [ ] **Step 2:** Run `tool/proto_shot.sh home` and view the PNG: solo BTC card, orange accents, activity list. Fonts must be Space Grotesk/Inter — if Google Fonts is unreachable from the sandbox, download the four needed TTFs once into `docs/design/fonts/` and add a `<style>@font-face…</style>` injection to the script instead.
- [ ] **Step 3:** Generate the full reference set (screens: `home`, `receive`, `send`, `notes`, `note-detail {"denom":"2 000"}`, `activity`, `fed-details {"id":"gbf"}`, `guardians {"id":"gbf"}`, `wallets`, `services`, `settings`, `seed`, `access`, `limit`). Eyeball each for stable-off correctness (no USD anywhere).
- [ ] **Step 4:** Commit: `chore: prototype reference screenshot harness`

### Task 1.2: App-screenshot + comparison tooling

**Files:**
- Create: `tool/app_shot.sh` — `adb exec-out screencap -p` to `docs/design/shots/app/<name>.png`
- Create: `tool/compare.sh`

- [ ] **Step 1:** Write `tool/compare.sh`:

```bash
#!/usr/bin/env bash
# tool/compare.sh <name> — side-by-side montage + perceptual diff metric
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT/docs/design/shots"
montage "ref/$1.png" "app/$1.png" -tile 2x1 -geometry +8+8 \
  -background '#0B0D11' "cmp/$1.png"
compare -metric RMSE "ref/$1.png" "app/$1.png" null: 2>&1 || true; echo
identify -format '%[pixel:p{40,120}]\n' "app/$1.png"   # spot color sample
```

- [ ] **Step 2:** Write `tool/check_tokens.sh` — greps `lib/` (excluding `lib/theme/tokens.dart` and `lib/bridge_generated.dart/`) for `Color(0x`, `Colors\.`, `fontFamily:` string literals; exits 1 with the offending lines. Run it in every screen task.
- [ ] **Step 3:** Commit: `chore: design comparison tooling`

**The design-match loop used by every screen task below:**
1. `tool/proto_shot.sh <screen>` → reference.
2. Navigate the app to the screen (adb taps or a debug deep link), `tool/app_shot.sh <screen>`.
3. `tool/compare.sh <screen>`; **view the montage**.
4. Walk the checklist: element order & presence · spacing (22px gutters, card/row padding) · type scale & family per `tokens.md` · exact colors (sample 3–5 pixels: background, card surface, accent, text) · radii · icon style.
5. Fix, hot-reload/reinstall, repeat until no visible deviation. RMSE is a tripwire, not a gate (fonts/AA render differently) — the eyeball + checklist decides.
6. Record the final montage path in the task's commit message.

---

## Phase 2 — Token & theme layer

### Task 2.1: Vendored fonts

- [ ] **Step 1:** Download Space Grotesk + Inter (w400/500/600/700, latin) TTFs into `assets/fonts/`; declare both families in `pubspec.yaml` `fonts:`; remove the `google_fonts` dependency and its usages (grep `GoogleFonts`).
- [ ] **Step 2:** `flutter analyze` clean. Commit: `feat: vendor Space Grotesk + Inter`

### Task 2.2: `lib/theme/tokens.dart`

**Files:** Create `lib/theme/tokens.dart`; Modify `lib/utils/styles.dart` (gut it — re-export tokens during migration); Modify `lib/main.dart` (ThemeData).

**Produces (used by every later task):** `class Palette` (static consts: `bg, surface, surface2, surface3, border, borderStrong, text, muted, faint, accent, accentHover, onAccent, amber, burnt, moss, teal, green, onGreen, red`), `class Radii` (`card=14, btn=13, btnSmall=11, seg=13, segInner=9, input=12, icon=11, chip=9, badge=8, sheet=26, qr=18, asset=16`), `class Gaps` (`screenH=22.0, cardPad=16.0, rowV=13.0, …`), `class Type` — `TextStyle` getters per the table in `docs/design/tokens.md` (`display` = 'SpaceGrotesk', `ui` = 'Inter'): `screenTitle, titleCentered, balanceLarge, balanceUnit, assetAmount, bigAmount, sectionLabel, body, rowTitle, rowSub, rowAmount, input, fiat, chip, badge`.

- [ ] **Step 1:** Write the file — every hex from `docs/design/tokens.md`, e.g.:

```dart
abstract final class Palette {
  static const bg = Color(0xFF0B0D11);
  static const surface = Color(0xFF14171C);
  static const surface2 = Color(0xFF1B1F26);
  static const surface3 = Color(0xFF21262E);
  static const border = Color(0xFF262B33);
  static const borderStrong = Color(0xFF3A4049);
  static const text = Color(0xFFE8E6DF);
  static const muted = Color(0xFF9499A1);
  static const faint = Color(0xFF5A6069);
  static const accent = Color(0xFFFF9410);
  static const accentHover = Color(0xFFFFA838);
  static const onAccent = Color(0xFF1A0E00);
  static const amber = Color(0xFFFFB020);
  static const burnt = Color(0xFFFF5A1F);
  static const moss = Color(0xFF687C4D);
  static const teal = Color(0xFF2C8C76);
  static const green = Color(0xFF46CF7C);
  static const onGreen = Color(0xFF06301A);
  static const red = Color(0xFFFF6A4D);
}
```

and text styles like:

```dart
static const screenTitle = TextStyle(
  fontFamily: 'SpaceGrotesk', fontSize: 27,
  fontWeight: FontWeight.w700, letterSpacing: -0.54, color: Palette.text);
```

(letterSpacing in px = em × fontSize.)
- [ ] **Step 2:** Build `ThemeData` in `main.dart`: `scaffoldBackgroundColor: Palette.bg`, dark brightness, `fontFamily: 'Inter'`, page transitions = shared-axis-style slide+fade 260ms (see Task 6.1).
- [ ] **Step 3:** Migrate existing `styles.dart` call-sites mechanically to tokens; `tool/check_tokens.sh` passes; `flutter analyze` clean; app still boots on redroid.
- [ ] **Step 4:** Commit: `feat: design token layer`

### Task 2.3: Component library `lib/theme/components/`

One widget per prototype CSS component, one file each. **Produces (exact names later tasks consume):**

| Widget | Mirrors (prototype CSS / lines) | Key API |
|---|---|---|
| `PyxButton` | `.btn` `-primary/-ghost/-wide/-lg` (152–165) | `PyxButton.primary/ghost({icon, label, wide, large, onTap, enabled})` |
| `IconBtn` | `.iconbtn` (+`.bare`) (123–131) | `IconBtn({icon, bare, onTap, child?})` — 38×38, r11 |
| `PyxCard` | `.card` (+`.tap`, active ring) (168–174) | `PyxCard({child, onTap, activeRing})` |
| `Seg` | `.seg` (216–223) | `Seg({items, index, onChanged})` |
| `PyxChip` | `.chip` (227–228) | selected = accent bg + onAccent text |
| `DRow` | `.drow` k/v rows (231–234) | `DRow({k, v, onTap, trailing})` |
| `PyxInput` | `.input` (+mono, suffix, disabled, lock) (239–247, 309–316) | wraps `TextField`, Space Grotesk 18/600 |
| `SectionLabel` | `.label`/`.section-label` (138–141) | caps 11/600 ls .16em |
| `ActGroupLabel` | `.act-group-label` (411–413) | label + hairline |
| `TxRowTile` | `.tx` rows + corner asset badge + pending arc (191–213) | `TxRowTile({tx, onTap})` |
| `AssetCard` | `.asset-card` solo (384–401) | BTC badge, amount, fiat, Receive/Send ghost buttons |
| `CodeField` | `.code-field` (272–277) | truncated code + copy + share, divider before actions |
| `PyxSheet` | `.sheet` + scrim (490–500) | `showPyxSheet(context, child)` — r26 top, grip, 320ms curve |
| `PyxToast` | `.toast` (360–367) | via overlay_support (already a dep) |
| `SlideToSend` | `.slide-send` (317–326, JS 1707–1736) | `SlideToSend({enabled, onConfirm})` |
| `QrSurface` | `.qr-wrap` states (250–259) | white r18 panel / dashed empty / spinner, center bolt chip |
| `GuardianRing` | `.fed-ring` + path (427–430, JS 967–986) | rounded-rect progress ring, color by quorum |
| `StatusDot`/`Badge` | `.dot`, `.badge` (185–187, 333) | |
| `AvatarCircle` | `.avatar`, `.logo-avatar` (179–181) | gradient/emoji/logo variants |

- [ ] **Step 1:** Implement each widget file with a `// mirrors prototype.html:<lines>` header comment. Reuse conduit widgets where they already match structurally (`qr_code_widget`, `grouped_list_widget`, `async_button_widget`) by restyling rather than rewriting, deleting only what's unused at the end.
- [ ] **Step 2:** Add a debug-only gallery route (`lib/theme/gallery.dart`, reachable via `--dart-define=GALLERY=1`) laying the components out on `Palette.bg`; screenshot on redroid and check against the CSS values by eye + pixel sampling.
- [ ] **Step 3:** `flutter analyze`, `tool/check_tokens.sh`. Commit per 3–4 widgets.

---

## Phase 3 — Screens (each uses the Phase-1 design-match loop; commit per screen)

Conduit → prototype mapping. Prototype line refs are the spec; read them before implementing each task.

### Task 3.1: Home (`base_screen.dart` → prototype `home`, lines 987–1075)
Solo BTC `AssetCard` inside a collapsing sticky header (large card collapses/fades to a compact one-line ₿ balance over the scroll range, hand-off sequenced so both are never visible — port `mountHomeScroll` lines 1039–1072 with a `ScrollController` + `AnimatedBuilder`); topbar: settings gear (left), eye mask-toggle + federation button with `GuardianRing` (right); grouped activity feed with `ActGroupLabel` + `TxRowTile` + "End of history" faint line; empty state (`emptyHistory`, 1102–1107) when no history; scan FAB bottom-center that swaps for a "Top" pill past 85% scroll. Balance masking replaces digits with `•` (`maskNum`, 1133–1136).

### Task 3.2: Scan sheet (`scanner_drawer.dart` → `openScan`, 1077–1092) — `PyxSheet` + `mobile_scanner` viewfinder styled with corner brackets + sweeping accent line + "Paste from clipboard" ghost button.

### Task 3.3: Receive (merge `wallet_v2_receive_screen` / `invoice_amount` / `display_invoice` / `onchain_address` / `display_lnurl` → one `receive` screen, 1246–1533)
Bitcoin asset pill; `Seg` Lightning/On-Chain/Ecash; centered amount input with currency selector button; sats-equivalent sub-line (reserved height so the QR never shifts); `QrSurface` state machine per `recvCode()` (1377–1391): LN+no amount → LNURL QR (only if federation supports it, else placeholder), LN+amount → spinner then invoice QR, On-Chain → address/BIP21, Ecash → *scanner* with animated-QR capture progress bar then claiming spinner (1307–1358); `CodeField` under the QR; faint helper note. Wire to conduit's existing invoice/address/ecash APIs.

### Task 3.4: Currency picker sheet (`select_currency_screen` → 1533–1597) — search field, BTC units group (SATS/BTC), separator, fiat list with flag emoji + code + name, accent check on selection.

### Task 3.5: Send (merge `lightning_address_entry` / `confirm_*` drawers → `send`, 1600–1946)
To-field (mono) with scan icon and address-book dropdown (recents + "Add to contacts", 1826–1888, backed by conduit's contacts); amount gated until a destination parses, locked (lock icon, no unit chevron) when a BOLT11 has a fixed amount (`parseSendDest`/`applySendDest`, 1746–1781); note field; max-fee field; `SlideToSend` → sending state → success toast → pending tx appears at the top of home with the orbiting arc, settling in place (1912–1946).

### Task 3.6: Notes + note detail (new screens, 2022–2078) — filter chips, total balance block, denomination cards with note-pill icon; detail with status badge, `DRow` card, action row. Back with real mint-note data from conduit's rust API if exposed; otherwise show denominations derivable from balance and leave actions as toasts.

### Task 3.7: Activity (`payment_history_screen` → `activity`, 2288–2298) + Tx detail sheet (`payment_details_drawer` → 2189–2255): centered icon + type + signed `bigAmount` + state badge; user rows (date, fiat, memo, fee, gateway…); wrench `IconBtn` top-right toggling the technical section (invoice, hashes, operation id) with tap-to-copy rows.

### Task 3.8: Federation details + guardians (`federation_screen`, `connection_status_screen` → 2101–2286): fed header w/ avatar, online status, quorum; guardian rows (name, address, latency, connection type) opening a per-guardian sheet; modules pill list; meta rows. `GuardianRing` color thresholds: green all online, amber ≥ quorum, red below.

### Task 3.9: Wallets switcher (`wallets` + `wallet-detail`, 1152–1244): conduit's federations render as wallet cards grouped under their federation provider row, active card gets the accent ring + check badge; switching updates home.

### Task 3.10: Services (`services`, 2300–2331): static designed rows (Lightning Address active badge if conduit exposes one; others as designed placeholders with chevrons/toasts).

### Task 3.11: Settings, Seed, Access, Limit (`settings_card_widget`, `display_recovery_phrase_screen` → 2334–2476): grouped `DRow` cards (Wallet / Security / About); Backup gated behind the biometric sheet (Task 3.12) then the seed screen: red warning banner, blurred 12-word grid with "Tap to reveal" overlay, copy + "I've written it down"; Access = biometrics toggle backed by `local_auth`; Quick-spend limit = big centered amount field.

### Task 3.12: Biometric auth sheet (`auth_utils` → `requireAuth`, 2367–2395): ring idle → pulsing "Scanning…" → green check "Verified", driven by real `local_auth` callbacks.

### Task 3.13: Onboarding/recovery restyle (`landing_screen`, `input/confirm_recovery_phrase`, `invite_*`): no prototype reference exists — restyle with tokens + components only, keeping conduit's flow. Verified by checklist (tokens, spacing, type) rather than montage.

---

## Phase 4 — Motion & interaction pass

- [ ] **Task 4.1:** Page transition: 14px slide-in + fade, 260ms, cubic-bezier(.22,.61,.36,1) via a custom `PageTransitionsBuilder`.
- [ ] **Task 4.2:** Sheet timing 320ms cubic-bezier(.32,.72,0,1); scrim `rgba(4,6,9,.55)`.
- [ ] **Task 4.3:** Pending-tx orbiting arc; QR spinner; biometric pulse; toast rise/fade 250ms, 1.9s hold — record `adb screenrecord` clips and compare against the browser prototype side-by-side.

---

## Phase 5 — Final design QA

- [ ] **Task 5.1:** Regenerate ALL reference + app screenshots fresh; produce the full montage set; fix every visible deviation; attach the montage directory listing to the commit.
- [ ] **Task 5.2:** Pixel-sample audit: for each screen sample bg/surface/accent/text pixels with `identify -format '%[pixel:p{x,y}]'` and assert exact token hexes (fonts may anti-alias; flat fills must be exact).
- [ ] **Task 5.3:** `tool/check_tokens.sh` + `flutter analyze` + full manual walkthrough on redroid (receive→pay against a test federation if reachable). Use superpowers:verification-before-completion before claiming done.
- [ ] **Task 5.4:** `flutter build apk --release` (arm64) succeeds. Commit: `feat: pyx wallet v1 design`

## Self-review notes

- Prototype `wallets`-inside-federations doesn't map 1:1 to conduit's flat multi-federation model; Task 3.9 resolves it by rendering federations as the switcher entries — flagged as an intentional deviation, revisit if the rust API grows sub-wallets.
- `Seg` receive methods (Lightning/On-Chain/Ecash) must be hidden per-federation when a module is absent (conduit exposes module availability; LNURL gating mirrored from `CONFIG.lnurl`).
- Every screen task inherits the Phase-1 loop + Global Constraints; none may introduce raw colors.
