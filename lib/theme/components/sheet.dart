import 'package:flutter/material.dart';
import 'package:conduit/theme/tokens.dart';

/// Bottom sheet — mirrors prototype.html `.sheet` + `.sheet-back` scrim
/// (lines 490-500): r26 top corners, strong top border, grip, 320ms
/// cubic-bezier(.32,.72,0,1) slide.
Future<T?> showPyxSheet<T>(
  BuildContext context, {
  required Widget child,
  bool isDismissible = true,
}) {
  return showModalBottomSheet<T>(
    context: context,
    isDismissible: isDismissible,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: Palette.scrim,
    sheetAnimationStyle: const AnimationStyle(
      duration: Motion.sheet,
      curve: Motion.sheetCurve,
    ),
    builder: (context) => Container(
      width: double.infinity,
      // viewInsets covers the keyboard; the system navigation bar is a
      // viewPadding inset and modal sheets sit outside the screens'
      // SafeArea, so it must be handled here.
      padding: EdgeInsets.only(
        left: Gaps.screenH,
        right: Gaps.screenH,
        top: 8,
        bottom: 30 + MediaQuery.of(context).viewInsets.bottom,
      ),
      decoration: const BoxDecoration(
        color: Palette.surface,
        border: Border(top: BorderSide(color: Palette.borderStrong)),
        borderRadius:
            BorderRadius.vertical(top: Radius.circular(Radii.sheet)),
      ),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 40,
                height: 4,
                margin: const EdgeInsets.only(top: 6, bottom: 14),
                decoration: BoxDecoration(
                  color: Palette.borderStrong,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            Flexible(child: child),
          ],
        ),
      ),
    ),
  );
}
