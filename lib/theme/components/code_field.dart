import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:share_plus/share_plus.dart';
import 'package:conduit/theme/tokens.dart';
import 'package:conduit/theme/components/toast.dart';
import 'package:conduit/theme/icons.dart';

/// Copyable code row with copy + share actions — mirrors prototype.html
/// `.code-field` (lines 272-277).
class CodeField extends StatelessWidget {
  final String value;
  final String shareSubject;

  const CodeField({
    super.key,
    required this.value,
    this.shareSubject = 'Pyx Wallet',
  });

  void _copy(BuildContext context) {
    Clipboard.setData(ClipboardData(text: value));
    pyxToast('Copied to clipboard');
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(top: 16),
      padding: const EdgeInsets.only(left: 16, right: 5, top: 5, bottom: 5),
      decoration: BoxDecoration(
        color: Palette.surface,
        border: Border.all(color: Palette.border),
        borderRadius: BorderRadius.circular(Radii.card),
      ),
      child: Row(
        children: [
          Expanded(
            child: GestureDetector(
              onTap: () => _copy(context),
              child: Text(
                value,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: Type.code,
              ),
            ),
          ),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.only(left: 5),
            decoration: const BoxDecoration(
              border: Border(left: BorderSide(color: Palette.border)),
            ),
            child: Row(
              children: [
                _CodeAct(
                  icon: PyxIcons.copy,
                  onTap: () => _copy(context),
                ),
                _CodeAct(
                  icon: PyxIcons.export,
                  onTap: () =>
                      SharePlus.instance.share(ShareParams(text: value)),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _CodeAct extends StatelessWidget {
  final IconData icon;
  final VoidCallback onTap;

  const _CodeAct({required this.icon, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(10),
        child: Container(
          width: 40,
          height: 40,
          alignment: Alignment.center,
          child: Icon(icon, size: 19, color: Palette.muted),
        ),
      ),
    );
  }
}
