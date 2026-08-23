import 'package:flutter/material.dart';
import 'package:conduit/theme/tokens.dart';

/// Generic surface card — mirrors prototype.html `.card` (+`.tap`,
/// `.active-fed` accent ring) (lines 168-174).
class PyxCard extends StatelessWidget {
  final Widget child;
  final VoidCallback? onTap;
  final bool activeRing;
  final EdgeInsetsGeometry padding;
  final double radius;

  const PyxCard({
    super.key,
    required this.child,
    this.onTap,
    this.activeRing = false,
    this.padding = const EdgeInsets.all(Gaps.cardPad),
    this.radius = Radii.card,
  });

  @override
  Widget build(BuildContext context) {
    final card = Container(
      padding: padding,
      decoration: BoxDecoration(
        color: Palette.surface,
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: activeRing ? Palette.accent : Palette.border),
        boxShadow: activeRing
            ? const [BoxShadow(color: Palette.accent, spreadRadius: 0)]
            : null,
      ),
      foregroundDecoration: activeRing
          ? BoxDecoration(
              borderRadius: BorderRadius.circular(radius),
              border: Border.all(color: Palette.accent),
            )
          : null,
      child: child,
    );
    if (onTap == null) return card;
    return GestureDetector(onTap: onTap, child: card);
  }
}

/// Section heading row — mirrors `.section-label` (lines 138-141).
class SectionLabel extends StatelessWidget {
  final String label;
  final Widget? trailing;
  final EdgeInsetsGeometry margin;

  const SectionLabel(
    this.label, {
    super.key,
    this.trailing,
    this.margin =
        const EdgeInsets.only(top: Gaps.sectionTop, bottom: Gaps.sectionBottom),
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: margin,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(
            label.toUpperCase(),
            style: Type.sectionLabel.copyWith(color: Palette.muted),
          ),
          if (trailing != null) trailing!,
        ],
      ),
    );
  }
}

/// Activity group heading with trailing hairline — mirrors
/// `.act-group-label` (lines 411-413).
class ActGroupLabel extends StatelessWidget {
  final String label;

  const ActGroupLabel(this.label, {super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 24, bottom: 10),
      child: Row(
        children: [
          Text(
            label.toUpperCase(),
            style: Type.sectionLabel.copyWith(color: Palette.muted),
          ),
          const SizedBox(width: 14),
          const Expanded(child: Divider()),
        ],
      ),
    );
  }
}

/// Key/value data row — mirrors `.drow` (lines 231-234).
class DRow extends StatelessWidget {
  final String k;
  final Widget v;
  final VoidCallback? onTap;
  final bool showDivider;

  const DRow({
    super.key,
    required this.k,
    required this.v,
    this.onTap,
    this.showDivider = true,
  });

  /// Convenience for plain-text values.
  DRow.text({
    Key? key,
    required String k,
    required String value,
    VoidCallback? onTap,
    bool showDivider = true,
  }) : this(
          key: key,
          k: k,
          v: Text(value, style: Type.drowValue),
          onTap: onTap,
          showDivider: showDivider,
        );

  @override
  Widget build(BuildContext context) {
    final row = Container(
      padding: const EdgeInsets.symmetric(vertical: 15),
      decoration: showDivider
          ? const BoxDecoration(
              border: Border(bottom: BorderSide(color: Palette.border)),
            )
          : null,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(k.toUpperCase(), style: Type.drowKey),
          Flexible(child: v),
        ],
      ),
    );
    if (onTap == null) return row;
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: row,
    );
  }
}
