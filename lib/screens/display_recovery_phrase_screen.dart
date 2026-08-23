import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:conduit/theme/components/buttons.dart';
import 'package:conduit/theme/components/toast.dart';
import 'package:conduit/theme/icons.dart';
import 'package:conduit/theme/tokens.dart';

/// Recovery phrase — mirrors prototype.html `seed` (lines 2401-2419):
/// red warning banner, blurred two-column word grid with tap-to-reveal,
/// copy button, "I've written it down".
class DisplayRecoveryPhraseScreen extends StatefulWidget {
  final List<String> seedPhrase;

  const DisplayRecoveryPhraseScreen({super.key, required this.seedPhrase});

  @override
  State<DisplayRecoveryPhraseScreen> createState() =>
      _DisplayRecoveryPhraseScreenState();
}

class _DisplayRecoveryPhraseScreenState
    extends State<DisplayRecoveryPhraseScreen> {
  bool _revealed = false;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Centered topbar (prototype seed screen is centered)
            Padding(
              padding: const EdgeInsets.fromLTRB(
                  Gaps.screenH, 6, Gaps.screenH, 14),
              child: Stack(
                alignment: Alignment.center,
                children: [
                  const Center(
                    child:
                        Text('Recovery Phrase', style: Type.titleCentered),
                  ),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: IconBtn(
                      bare: true,
                      onTap: () => Navigator.of(context).pop(),
                      child: const Icon(PyxIcons.caretLeft),
                    ),
                  ),
                ],
              ),
            ),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.fromLTRB(
                    Gaps.screenH, 0, Gaps.screenH, 26),
                children: [
                  // Warning banner (prototype .seed-warn)
                  Container(
                    margin: const EdgeInsets.only(top: 4, bottom: 20),
                    padding: const EdgeInsets.fromLTRB(14, 13, 14, 13),
                    decoration: BoxDecoration(
                      color: Palette.redWarnBg,
                      border: Border.all(color: Palette.redWarnBorder),
                      borderRadius: BorderRadius.circular(Radii.btn),
                    ),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Padding(
                          padding: EdgeInsets.only(top: 1),
                          child: Icon(PyxIcons.warning,
                              size: 18, color: Palette.red),
                        ),
                        const SizedBox(width: 11),
                        Expanded(
                          child: Text(
                            'Anyone with these ${widget.seedPhrase.length} '
                            'words can spend your funds. Write them down, '
                            'store them offline, and never share or '
                            'screenshot them.',
                            style: Type.body.copyWith(
                                fontSize: 12.5, height: 1.5),
                          ),
                        ),
                      ],
                    ),
                  ),
                  // Blurred word grid with reveal overlay (prototype
                  // .seed-grid.blur + .seed-reveal)
                  Stack(
                    children: [
                      ImageFiltered(
                        imageFilter: _revealed
                            ? ImageFilter.blur()
                            : ImageFilter.blur(sigmaX: 7, sigmaY: 7),
                        child: GridView.count(
                          crossAxisCount: 2,
                          shrinkWrap: true,
                          physics: const NeverScrollableScrollPhysics(),
                          mainAxisSpacing: 9,
                          crossAxisSpacing: 9,
                          childAspectRatio: 3.6,
                          children: [
                            for (var i = 0;
                                i < widget.seedPhrase.length;
                                i++)
                              Container(
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 13),
                                decoration: BoxDecoration(
                                  color: Palette.surface,
                                  border:
                                      Border.all(color: Palette.border),
                                  borderRadius: BorderRadius.circular(
                                      Radii.seedWord),
                                ),
                                child: Row(
                                  children: [
                                    SizedBox(
                                      width: 17,
                                      child: Text(
                                        '${i + 1}',
                                        textAlign: TextAlign.right,
                                        style: const TextStyle(
                                          fontFamily: Fonts.display,
                                          fontSize: 11.5,
                                          fontWeight: FontWeight.w600,
                                          color: Palette.faint,
                                        ),
                                      ),
                                    ),
                                    const SizedBox(width: 9),
                                    Text(widget.seedPhrase[i],
                                        style: Type.seedWord),
                                  ],
                                ),
                              ),
                          ],
                        ),
                      ),
                      if (!_revealed)
                        Positioned.fill(
                          child: GestureDetector(
                            onTap: () =>
                                setState(() => _revealed = true),
                            child: Container(
                              decoration: BoxDecoration(
                                color: Palette.scrim.withValues(alpha: 0.2),
                                borderRadius: BorderRadius.circular(14),
                              ),
                              child: Column(
                                mainAxisAlignment:
                                    MainAxisAlignment.center,
                                children: [
                                  const Icon(PyxIcons.eye,
                                      size: 24, color: Palette.accent),
                                  const SizedBox(height: 7),
                                  Text('Tap to reveal',
                                      style: Type.button
                                          .copyWith(fontSize: 13)),
                                ],
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 20),
                  PyxButton.ghost(
                    label: 'Copy to clipboard',
                    icon: const Icon(PyxIcons.copy),
                    onTap: _revealed
                        ? () {
                            Clipboard.setData(ClipboardData(
                                text: widget.seedPhrase.join(' ')));
                            pyxToast('Copied to clipboard');
                          }
                        : null,
                  ),
                  const SizedBox(height: 10),
                  PyxButton.primary(
                    label: "I've written it down",
                    onTap: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
