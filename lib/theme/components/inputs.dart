import 'package:flutter/material.dart';
import 'package:conduit/theme/tokens.dart';

/// Labeled field wrapper — mirrors prototype.html `.field` (lines 237-238).
class Field extends StatelessWidget {
  final String label;
  final Widget child;

  const Field({super.key, required this.label, required this.child});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label.toUpperCase(), style: Type.sectionLabel),
          const SizedBox(height: 8),
          child,
        ],
      ),
    );
  }
}

/// Bordered input surface — mirrors `.input` (+`.mono`, suffix, disabled,
/// lock) (lines 239-247, 309-316). Wraps any child (usually a TextField).
class PyxInput extends StatelessWidget {
  final Widget child;
  final String? suffix;
  final Widget? trailing;
  final bool disabled;
  final bool focused;

  const PyxInput({
    super.key,
    required this.child,
    this.suffix,
    this.trailing,
    this.disabled = false,
    this.focused = false,
  });

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: disabled ? 0.5 : 1,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 15),
        constraints: const BoxConstraints(minHeight: 56),
        decoration: BoxDecoration(
          color: Palette.surface,
          border: Border.all(
            color: focused ? Palette.accent : Palette.border,
          ),
          borderRadius: BorderRadius.circular(Radii.input),
        ),
        child: Row(
          children: [
            Expanded(child: child),
            if (suffix != null) ...[
              const SizedBox(width: 10),
              Text(suffix!, style: Type.inputSuffix),
            ],
            if (trailing != null) ...[
              const SizedBox(width: 10),
              trailing!,
            ],
          ],
        ),
      ),
    );
  }
}

/// Bare text field styled for use inside [PyxInput].
class PyxTextField extends StatelessWidget {
  final TextEditingController? controller;
  final String? hint;
  final bool mono;
  final bool enabled;
  final TextInputType? keyboardType;
  final ValueChanged<String>? onChanged;
  final FocusNode? focusNode;
  final double? fontSize;

  const PyxTextField({
    super.key,
    this.controller,
    this.hint,
    this.mono = false,
    this.enabled = true,
    this.keyboardType,
    this.onChanged,
    this.focusNode,
    this.fontSize,
  });

  @override
  Widget build(BuildContext context) {
    var style = mono ? Type.inputMono : Type.input;
    if (fontSize != null) style = style.copyWith(fontSize: fontSize);
    return TextField(
      controller: controller,
      focusNode: focusNode,
      enabled: enabled,
      keyboardType: keyboardType,
      onChanged: onChanged,
      style: style,
      cursorColor: Palette.accent,
      decoration: InputDecoration(
        isDense: true,
        border: InputBorder.none,
        hintText: hint,
        hintStyle: style.copyWith(color: Palette.faint),
        contentPadding: const EdgeInsets.symmetric(vertical: 17),
      ),
    );
  }
}

/// Asset identity pill — mirrors `.asset-pill` (lines 414-415).
class AssetPill extends StatelessWidget {
  final String label;

  const AssetPill({super.key, required this.label});

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          padding:
              const EdgeInsets.only(left: 8, right: 13, top: 7, bottom: 7),
          decoration: BoxDecoration(
            color: Palette.surface,
            border: Border.all(color: Palette.border),
            borderRadius: BorderRadius.circular(10),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              const AssetBadge(),
              const SizedBox(width: 9),
              Text(
                label,
                style: Type.button.copyWith(fontSize: 13),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// Small ₿ square badge — mirrors `.asset-badge.btc` (lines 388-389).
class AssetBadge extends StatelessWidget {
  final double size;

  const AssetBadge({super.key, this.size = 20});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: Palette.accentBadgeBg,
        borderRadius: BorderRadius.circular(size * 0.3),
      ),
      child: Text(
        '₿',
        style: TextStyle(
          fontFamily: Fonts.display,
          fontWeight: FontWeight.w700,
          fontSize: size * 0.6,
          color: Palette.accent,
          height: 1,
        ),
      ),
    );
  }
}
