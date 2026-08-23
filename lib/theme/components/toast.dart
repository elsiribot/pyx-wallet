import 'package:flutter/material.dart';
import 'package:overlay_support/overlay_support.dart';
import 'package:conduit/theme/tokens.dart';

/// Bottom-center toast pill — mirrors prototype.html `.toast`
/// (lines 360-367): surface-2 pill, strong border, fade+rise 250ms,
/// auto-hide after 1.9s.
void pyxToast(String message) {
  showOverlay(
    (context, t) => Positioned(
      bottom: 34 + MediaQuery.of(context).padding.bottom,
      left: 0,
      right: 0,
      child: Center(
        child: Opacity(
          opacity: t,
          child: Transform.translate(
            offset: Offset(0, (1 - t) * 20),
            child: Material(
              color: Colors.transparent,
              child: Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 20, vertical: 13),
                decoration: BoxDecoration(
                  color: Palette.surface2,
                  border: Border.all(color: Palette.borderStrong),
                  borderRadius: BorderRadius.circular(Radii.input),
                  boxShadow: const [
                    BoxShadow(
                      color: Palette.shadow,
                      blurRadius: 40,
                      spreadRadius: -12,
                      offset: Offset(0, 18),
                    ),
                  ],
                ),
                child: Text(
                  message,
                  style: Type.body.copyWith(
                    fontSize: 13.5,
                    fontWeight: FontWeight.w500,
                    height: 1.2,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
    duration: Motion.toastHold,
    animationDuration: Motion.toastFade,
    reverseAnimationDuration: Motion.toastFade,
  );
}
