import 'package:flutter/material.dart';
import 'package:conduit/theme/tokens.dart';

/// Primary/ghost buttons — mirrors prototype.html `.btn` (lines 152-165).
class PyxButton extends StatelessWidget {
  final String label;
  final Widget? icon;
  final VoidCallback? onTap;
  final bool primary;
  final bool large;
  final EdgeInsetsGeometry? padding;

  const PyxButton.primary({
    super.key,
    required this.label,
    this.icon,
    this.onTap,
    this.large = false,
    this.padding,
  }) : primary = true;

  const PyxButton.ghost({
    super.key,
    required this.label,
    this.icon,
    this.onTap,
    this.large = false,
    this.padding,
  }) : primary = false;

  @override
  Widget build(BuildContext context) {
    final enabled = onTap != null;
    final style = (large ? Type.buttonLg : Type.button).copyWith(
      color: primary ? Palette.onAccent : Palette.text,
    );
    return Opacity(
      opacity: enabled ? 1 : 0.4,
      child: Material(
        color: primary ? Palette.accent : Palette.surface,
        borderRadius: BorderRadius.circular(Radii.btn),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(Radii.btn),
          child: Container(
            padding:
                padding ?? EdgeInsets.symmetric(vertical: large ? 17 : 15),
            decoration: primary
                ? null
                : BoxDecoration(
                    border: Border.all(color: Palette.border),
                    borderRadius: BorderRadius.circular(Radii.btn),
                  ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                if (icon != null) ...[
                  IconTheme(
                    data: IconThemeData(size: 18, color: style.color),
                    child: icon!,
                  ),
                  const SizedBox(width: 8),
                ],
                Text(label, style: style),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// 38x38 bordered icon button — mirrors `.iconbtn` (+`.bare`) (lines 123-131).
class IconBtn extends StatelessWidget {
  final Widget child;
  final VoidCallback? onTap;
  final bool bare;
  final bool activeRing;

  const IconBtn({
    super.key,
    required this.child,
    this.onTap,
    this.bare = false,
    this.activeRing = false,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: bare ? Colors.transparent : Palette.surface,
      borderRadius: BorderRadius.circular(Radii.icon),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(Radii.icon),
        child: Container(
          width: 38,
          height: 38,
          alignment: Alignment.center,
          decoration: bare
              ? null
              : BoxDecoration(
                  border: Border.all(
                    color: activeRing ? Palette.accent : Palette.border,
                  ),
                  borderRadius: BorderRadius.circular(Radii.icon),
                ),
          child: IconTheme(
            data: const IconThemeData(size: 20, color: Palette.text),
            child: child,
          ),
        ),
      ),
    );
  }
}
