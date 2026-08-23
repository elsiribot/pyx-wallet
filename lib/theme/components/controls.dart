import 'package:flutter/material.dart';
import 'package:conduit/theme/tokens.dart';

/// Segmented toggle — mirrors prototype.html `.seg` (lines 216-223).
class Seg extends StatelessWidget {
  final List<String> items;
  final int index;
  final ValueChanged<int> onChanged;

  const Seg({
    super.key,
    required this.items,
    required this.index,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: Palette.surface,
        border: Border.all(color: Palette.border),
        borderRadius: BorderRadius.circular(Radii.seg),
      ),
      child: Row(
        children: [
          for (var i = 0; i < items.length; i++) ...[
            if (i > 0) const SizedBox(width: 4),
            Expanded(
              child: GestureDetector(
                onTap: () => onChanged(i),
                child: AnimatedContainer(
                  duration: Motion.fast,
                  padding: const EdgeInsets.symmetric(vertical: 11),
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: i == index ? Palette.accent : Colors.transparent,
                    borderRadius: BorderRadius.circular(Radii.segInner),
                  ),
                  child: Text(
                    items[i],
                    maxLines: 1,
                    style: Type.button.copyWith(
                      fontSize: 14,
                      color: i == index ? Palette.onAccent : Palette.muted,
                    ),
                  ),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// Filter chip — mirrors `.chip` (lines 227-228).
class PyxChip extends StatelessWidget {
  final String label;
  final bool selected;
  final VoidCallback? onTap;

  const PyxChip(
    this.label, {
    super.key,
    this.selected = false,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 7),
        decoration: BoxDecoration(
          color: selected ? Palette.accent : Palette.surface,
          border: Border.all(
            color: selected ? Palette.accent : Palette.border,
          ),
          borderRadius: BorderRadius.circular(Radii.chip),
        ),
        child: Text(
          label,
          style: Type.chip.copyWith(
            color: selected ? Palette.onAccent : Palette.muted,
          ),
        ),
      ),
    );
  }
}

/// Status dot — mirrors `.dot` (lines 185-187).
enum StatusKind { on, off, warn }

class StatusDot extends StatelessWidget {
  final StatusKind kind;

  const StatusDot(this.kind, {super.key});

  static Color colorOf(StatusKind kind) => switch (kind) {
        StatusKind.on => Palette.green,
        StatusKind.off => Palette.burnt,
        StatusKind.warn => Palette.amber,
      };

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 7,
      height: 7,
      decoration: BoxDecoration(
        color: colorOf(kind),
        shape: BoxShape.circle,
      ),
    );
  }
}

/// Inline badge pill — mirrors `.badge` (line 333).
class PyxBadge extends StatelessWidget {
  final String label;
  final Color? color;
  final Widget? leading;

  const PyxBadge(this.label, {super.key, this.color, this.leading});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        color: Palette.surface2,
        border: Border.all(color: color ?? Palette.border),
        borderRadius: BorderRadius.circular(Radii.badge),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (leading != null) ...[leading!, const SizedBox(width: 7)],
          Text(label, style: Type.badge.copyWith(color: color)),
        ],
      ),
    );
  }
}

/// Round avatar with gradient/emoji/logo variants — mirrors `.avatar` /
/// `.logo-avatar` (lines 179-181).
class AvatarCircle extends StatelessWidget {
  final double size;
  final String? label;
  final Color? color;
  final ImageProvider? image;

  const AvatarCircle({
    super.key,
    this.size = 42,
    this.label,
    this.color,
    this.image,
  });

  @override
  Widget build(BuildContext context) {
    final base = color ?? Palette.surface3;
    return Container(
      width: size,
      height: size,
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        gradient: image == null
            ? LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [base, Color.lerp(base, Palette.bg, 0.3)!],
              )
            : null,
        color: image != null ? Palette.logoAvatarBg : null,
      ),
      alignment: Alignment.center,
      child: image != null
          ? Image(
              image: image!,
              width: size * 0.8,
              height: size * 0.8,
              fit: BoxFit.contain,
            )
          : Text(
              label ?? '',
              style: TextStyle(
                fontFamily: Fonts.display,
                fontWeight: FontWeight.w700,
                fontSize: size * 0.38,
                color: Palette.text,
              ),
            ),
    );
  }
}
