import 'package:flutter/widgets.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

/// Semantic icon names for Pyx Wallet, backed by Lucide — the stroke style
/// the design prototype's inline SVGs are drawn in.
///
/// Names keep conduit's original (phosphor) vocabulary so call-sites read
/// unchanged; the mapping to Lucide glyphs lives here only.
abstract final class PyxIcons {
  static const arrowDown = LucideIcons.arrowDown;
  static const arrowUp = LucideIcons.arrowUp;
  static const arrowRight = LucideIcons.arrowRight;
  static const arrowLeft = LucideIcons.arrowLeft;
  static const caretLeft = LucideIcons.chevronLeft;
  static const caretRight = LucideIcons.chevronRight;
  static const check = LucideIcons.check;
  static const checkCircle = LucideIcons.circleCheck;
  static const copy = LucideIcons.copy;
  static const export = LucideIcons.share;
  static const eye = LucideIcons.eye;
  static const eyeSlash = LucideIcons.eyeOff;
  static const gearSix = LucideIcons.settings;
  static const receipt = LucideIcons.receipt;
  static const scan = LucideIcons.scanLine;
  static const usersThree = LucideIcons.users;
  static const arrowsClockwise = LucideIcons.refreshCw;
  static const broadcast = LucideIcons.radio;
  static const clipboardText = LucideIcons.clipboardList;
  static const coinVertical = LucideIcons.coins;
  static const cube = LucideIcons.box;
  static const currencyBtc = LucideIcons.bitcoin;
  static const currencyDollar = LucideIcons.dollarSign;
  static const dotsNine = LucideIcons.grip;
  static const hardDrives = LucideIcons.hardDrive;
  static const info = LucideIcons.info;
  static const key = LucideIcons.key;
  static const lightning = LucideIcons.zap;
  static const link = LucideIcons.link;
  static const moon = LucideIcons.moon;
  static const network = LucideIcons.network;
  static const plus = LucideIcons.plus;
  static const speedometer = LucideIcons.gauge;
  static const trash = LucideIcons.trash2;
  static const user = LucideIcons.user;
  static const userPlus = LucideIcons.userPlus;
  static const wallet = LucideIcons.wallet;
  static const warning = LucideIcons.triangleAlert;
  static const warningCircle = LucideIcons.circleAlert;
  static const x = LucideIcons.x;
  static const xCircle = LucideIcons.circleX;

  /// Widget-typed default so `IconData`-consuming call-sites keep working.
  static const IconData placeholder = LucideIcons.circle;
}
