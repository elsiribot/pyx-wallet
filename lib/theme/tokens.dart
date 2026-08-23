import 'package:flutter/material.dart';

/// Design tokens extracted from docs/design/prototype.html (`:root` custom
/// properties and component rules). See docs/design/tokens.md.
///
/// This is the ONLY file in lib/ that may contain raw colors or font family
/// names (enforced by tool/check_tokens.sh).

abstract final class Palette {
  static const bg = Color(0xFF0B0D11);
  static const bgGlow = Color(0xFF15181D); // radial glow at top center
  static const surface = Color(0xFF14171C);
  static const surface2 = Color(0xFF1B1F26);
  static const surface3 = Color(0xFF21262E);
  static const border = Color(0xFF262B33);
  static const borderStrong = Color(0xFF3A4049);
  static const text = Color(0xFFE8E6DF); // stone
  static const muted = Color(0xFF9499A1); // slate
  static const faint = Color(0xFF5A6069); // steel
  static const accent = Color(0xFFFF9410); // primary orange
  static const accentHover = Color(0xFFFFA838);
  static const onAccent = Color(0xFF1A0E00);
  static const amber = Color(0xFFFFB020);
  static const burnt = Color(0xFFFF5A1F);
  static const moss = Color(0xFF687C4D);
  static const teal = Color(0xFF2C8C76);
  static const green = Color(0xFF46CF7C);
  static const onGreen = Color(0xFF06301A);
  static const red = Color(0xFFFF6A4D);

  // Common alpha variants
  static const accentBadgeBg = Color(0x29FF9410); // accent @ .16
  static const accentNoteBg = Color(0x14FF9410); // accent @ .08
  static const greenBadgeBg = Color(0x2646CF7C); // green @ .15
  static const redWarnBg = Color(0x12FF6A4D); // red @ .07
  static const redWarnBorder = Color(0x52FF6A4D); // red @ .32
  static const scrim = Color(0x8C040609); // rgba(4,6,9,.55)
  static const logoAvatarBg = Color(0xFF0D1426); // `.logo-avatar` background
  static const shadow = Color(0xCC000000); // drop shadows (~.8 black)
}

abstract final class Fonts {
  /// Space Grotesk — numbers, amounts, titles, names, values, code.
  static const display = 'SpaceGrotesk';

  /// Inter — body copy, buttons, chips, labels.
  static const ui = 'Inter';
}

abstract final class Radii {
  static const card = 14.0;
  static const btn = 13.0;
  static const btnSmall = 11.0;
  static const seg = 13.0;
  static const segInner = 9.0;
  static const input = 12.0;
  static const icon = 11.0;
  static const chip = 9.0;
  static const badge = 8.0;
  static const sheet = 26.0;
  static const qr = 18.0;
  static const asset = 16.0;
  static const assetBadge = 7.0;
  static const modPill = 11.0;
  static const seedWord = 11.0;
}

abstract final class Gaps {
  /// Horizontal screen padding (prototype `.body` 0 22px).
  static const screenH = 22.0;
  static const cardPad = 16.0;
  static const cardPadV = 15.0;

  /// Vertical padding of a list/tx row.
  static const rowV = 13.0;

  /// Gap between icon and text in rows (prototype `.tx`/`.fed` gap 13px).
  static const rowGap = 13.0;
  static const btnGap = 12.0;
  static const sectionTop = 22.0;
  static const sectionBottom = 12.0;
}

/// Text styles per the table in docs/design/tokens.md.
/// letterSpacing is em × fontSize (CSS em → logical px).
abstract final class Type {
  static const screenTitle = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 27,
    fontWeight: FontWeight.w700,
    letterSpacing: -0.54,
    color: Palette.text,
    height: 1.2,
  );

  static const titleCentered = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 21,
    fontWeight: FontWeight.w700,
    letterSpacing: -0.42,
    color: Palette.text,
  );

  static const sheetTitle = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 20,
    fontWeight: FontWeight.w700,
    color: Palette.text,
  );

  static const balanceLarge = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 46,
    fontWeight: FontWeight.w700,
    letterSpacing: -1.38,
    color: Palette.text,
    height: 1,
  );

  static const balanceUnit = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 18,
    fontWeight: FontWeight.w500,
    color: Palette.muted,
  );

  static const assetAmount = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 31,
    fontWeight: FontWeight.w700,
    letterSpacing: -0.775,
    color: Palette.text,
    height: 1,
  );

  static const assetUnit = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 15,
    fontWeight: FontWeight.w500,
    color: Palette.muted,
  );

  static const bigAmount = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 40,
    fontWeight: FontWeight.w700,
    letterSpacing: -1.2,
    color: Palette.text,
    height: 1,
  );

  static const bigAmountUnit = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 16,
    fontWeight: FontWeight.w500,
    color: Palette.muted,
  );

  /// Uppercase 11px caps label (`.label`).
  static const sectionLabel = TextStyle(
    fontFamily: Fonts.ui,
    fontSize: 11,
    fontWeight: FontWeight.w600,
    letterSpacing: 1.76, // .16em
    color: Palette.faint,
  );

  static const body = TextStyle(
    fontFamily: Fonts.ui,
    fontSize: 15,
    fontWeight: FontWeight.w400,
    color: Palette.text,
    height: 1.5,
  );

  static const button = TextStyle(
    fontFamily: Fonts.ui,
    fontSize: 15,
    fontWeight: FontWeight.w600,
    color: Palette.text,
  );

  static const buttonLg = TextStyle(
    fontFamily: Fonts.ui,
    fontSize: 16,
    fontWeight: FontWeight.w600,
    color: Palette.text,
  );

  static const rowTitle = TextStyle(
    fontFamily: Fonts.ui,
    fontSize: 14.5,
    fontWeight: FontWeight.w600,
    color: Palette.text,
  );

  static const rowSub = TextStyle(
    fontFamily: Fonts.ui,
    fontSize: 12,
    fontWeight: FontWeight.w400,
    color: Palette.faint,
  );

  /// Federation/card name (`.fed .name` — Space Grotesk 15/600).
  static const cardName = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 15,
    fontWeight: FontWeight.w600,
    letterSpacing: -0.15,
    color: Palette.text,
  );

  static const cardSub = TextStyle(
    fontFamily: Fonts.ui,
    fontSize: 12.5,
    fontWeight: FontWeight.w400,
    color: Palette.muted,
  );

  static const rowAmount = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 14.5,
    fontWeight: FontWeight.w600,
    color: Palette.text,
  );

  static const rowAmountUnit = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 11,
    fontWeight: FontWeight.w500,
    color: Palette.faint,
  );

  /// `.drow .k` key column.
  static const drowKey = TextStyle(
    fontFamily: Fonts.ui,
    fontSize: 11,
    fontWeight: FontWeight.w600,
    letterSpacing: 1.54, // .14em
    color: Palette.faint,
  );

  /// `.drow .v` value column.
  static const drowValue = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 14.5,
    fontWeight: FontWeight.w600,
    color: Palette.text,
  );

  static const input = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 18,
    fontWeight: FontWeight.w600,
    color: Palette.text,
  );

  static const inputMono = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 15,
    fontWeight: FontWeight.w600,
    color: Palette.text,
  );

  static const inputSuffix = TextStyle(
    fontFamily: Fonts.ui,
    fontSize: 14,
    fontWeight: FontWeight.w500,
    color: Palette.muted,
  );

  /// ≈ fiat equivalent lines.
  static const fiat = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 13,
    fontWeight: FontWeight.w500,
    color: Palette.muted,
  );

  static const fiatLarge = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 15,
    fontWeight: FontWeight.w500,
    color: Palette.muted,
  );

  static const chip = TextStyle(
    fontFamily: Fonts.ui,
    fontSize: 12,
    fontWeight: FontWeight.w600,
    color: Palette.muted,
  );

  static const badge = TextStyle(
    fontFamily: Fonts.ui,
    fontSize: 12.5,
    fontWeight: FontWeight.w600,
    color: Palette.text,
  );

  /// Big centered amount entry (receive screen / quick-limit).
  static const amountEntry = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 40,
    fontWeight: FontWeight.w700,
    letterSpacing: -1.2,
    color: Palette.text,
  );

  static const code = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 14,
    fontWeight: FontWeight.w400,
    color: Palette.text,
  );

  static const seedWord = TextStyle(
    fontFamily: Fonts.display,
    fontSize: 14.5,
    fontWeight: FontWeight.w600,
    letterSpacing: -0.145,
    color: Palette.text,
  );

  static const helper = TextStyle(
    fontFamily: Fonts.ui,
    fontSize: 12.5,
    fontWeight: FontWeight.w400,
    letterSpacing: 0.5, // .04em
    color: Palette.faint,
  );
}

abstract final class Motion {
  /// Screen transition: 14px slide-in + fade.
  static const screen = Duration(milliseconds: 260);
  static const screenCurve = Cubic(.22, .61, .36, 1);

  /// Bottom sheet slide.
  static const sheet = Duration(milliseconds: 320);
  static const sheetCurve = Cubic(.32, .72, 0, 1);

  static const fast = Duration(milliseconds: 150);
  static const toastFade = Duration(milliseconds: 250);
  static const toastHold = Duration(milliseconds: 1900);
  static const pendingSpin = Duration(milliseconds: 1150);
  static const qrSpin = Duration(milliseconds: 800);
  static const slideSettle = Duration(milliseconds: 280);
}
