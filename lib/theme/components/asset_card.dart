import 'package:flutter/material.dart';
import 'package:conduit/theme/icons.dart';
import 'package:conduit/theme/tokens.dart';
import 'package:conduit/theme/components/inputs.dart';

/// Solo Bitcoin balance card — mirrors prototype.html `.asset-card` inside
/// `.asset-stack.solo` (lines 384-401, 1006-1015): ₿ badge + amount + unit,
/// ≈ fiat line, ghost Receive/Send buttons.
class AssetCard extends StatelessWidget {
  final String amount;
  final String unit;
  final String? fiat;
  final bool masked;
  final VoidCallback onReceive;
  final VoidCallback onSend;

  const AssetCard({
    super.key,
    required this.amount,
    required this.unit,
    this.fiat,
    this.masked = false,
    required this.onReceive,
    required this.onSend,
  });

  @override
  Widget build(BuildContext context) {
    final shownAmount = masked ? '•••••' : amount;
    final shownFiat = masked ? '≈ ••••' : fiat;
    return Container(
      padding: const EdgeInsets.symmetric(
        horizontal: Gaps.cardPad,
        vertical: Gaps.cardPadV,
      ),
      decoration: BoxDecoration(
        color: Palette.surface,
        border: Border.all(color: Palette.border),
        borderRadius: BorderRadius.circular(Radii.asset),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              const AssetBadge(size: 27),
              const SizedBox(width: 11),
              Flexible(
                child: Text.rich(
                  TextSpan(
                    text: shownAmount,
                    style: Type.assetAmount,
                    children: [
                      const TextSpan(text: ' '),
                      TextSpan(text: unit, style: Type.assetUnit),
                    ],
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
          if (shownFiat != null) ...[
            const SizedBox(height: 8),
            Text(shownFiat, style: Type.fiat),
          ],
          const SizedBox(height: 14),
          Row(
            children: [
              _AssetBtn(
                icon: PyxIcons.arrowDown,
                label: 'Receive',
                onTap: onReceive,
              ),
              const SizedBox(width: 10),
              _AssetBtn(
                icon: PyxIcons.arrowUp,
                label: 'Send',
                onTap: onSend,
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// Compact ghost action button used inside the asset card (`.asset-btn`).
class _AssetBtn extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback onTap;

  const _AssetBtn({
    required this.icon,
    required this.label,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Palette.surface,
      borderRadius: BorderRadius.circular(Radii.btnSmall),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(Radii.btnSmall),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          decoration: BoxDecoration(
            border: Border.all(color: Palette.border),
            borderRadius: BorderRadius.circular(Radii.btnSmall),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 16, color: Palette.text),
              const SizedBox(width: 8),
              Text(label, style: Type.button.copyWith(fontSize: 14)),
            ],
          ),
        ),
      ),
    );
  }
}
