import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:conduit/theme/components/buttons.dart';
import 'package:conduit/theme/components/toast.dart';
import 'package:conduit/theme/icons.dart';
import 'package:conduit/theme/tokens.dart';

/// Scan sheet body — mirrors prototype.html `openScan` (lines 1077-1092):
/// title, viewfinder with accent corner brackets + sweeping line over the
/// camera feed, paste-from-clipboard ghost button.
class QrScannerWidget extends StatefulWidget {
  final void Function(String) onScan;

  const QrScannerWidget({super.key, required this.onScan});

  @override
  State<QrScannerWidget> createState() => _QrScannerWidgetState();
}

class _QrScannerWidgetState extends State<QrScannerWidget>
    with SingleTickerProviderStateMixin {
  final MobileScannerController _controller = MobileScannerController(
    formats: [BarcodeFormat.qrCode],
  );
  late final AnimationController _sweep = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 2600),
  )..repeat(reverse: true);

  void _onDetect(BarcodeCapture capture) {
    for (final barcode in capture.barcodes) {
      final value = barcode.rawValue;
      if (value != null) {
        widget.onScan(value);
        return;
      }
    }
  }

  Future<void> _handleClipboardPaste() async {
    try {
      final clipboardData = await Clipboard.getData(Clipboard.kTextPlain);
      final text = clipboardData?.text;
      if (text != null && text.isNotEmpty) {
        widget.onScan(text);
      } else if (mounted) {
        pyxToast('Clipboard is empty');
      }
    } catch (_) {
      if (mounted) pyxToast('Failed to access clipboard');
    }
  }

  @override
  void dispose() {
    _sweep.dispose();
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Center(
          child: Padding(
            padding: EdgeInsets.only(bottom: 18),
            child: Text('Scan QR code', style: Type.sheetTitle),
          ),
        ),
        AspectRatio(
          aspectRatio: 1,
          child: ClipRRect(
            borderRadius: BorderRadius.circular(24),
            child: Stack(
              fit: StackFit.expand,
              children: [
                MobileScanner(
                  controller: _controller,
                  onDetect: _onDetect,
                ),
                // Corner brackets
                for (final a in const [
                  Alignment.topLeft,
                  Alignment.topRight,
                  Alignment.bottomLeft,
                  Alignment.bottomRight,
                ])
                  Align(
                    alignment: a,
                    child: Padding(
                      padding: const EdgeInsets.all(14),
                      child: _Corner(alignment: a),
                    ),
                  ),
                // Sweeping scan line
                AnimatedBuilder(
                  animation: _sweep,
                  builder: (context, _) => Align(
                    alignment:
                        Alignment(0, -0.86 + 1.72 * _sweep.value),
                    child: Container(
                      margin:
                          const EdgeInsets.symmetric(horizontal: 18),
                      height: 2,
                      decoration: BoxDecoration(
                        gradient: const LinearGradient(
                          colors: [
                            Palette.bgTransparent,
                            Palette.accent,
                            Palette.bgTransparent,
                          ],
                        ),
                        boxShadow: [
                          BoxShadow(
                            color:
                                Palette.accent.withValues(alpha: 0.6),
                            blurRadius: 12,
                            spreadRadius: 1,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 20),
        PyxButton.ghost(
          label: 'Paste from clipboard',
          icon: const Icon(PyxIcons.copy),
          onTap: _handleClipboardPaste,
        ),
      ],
    );
  }
}

class _Corner extends StatelessWidget {
  final Alignment alignment;

  const _Corner({required this.alignment});

  @override
  Widget build(BuildContext context) {
    final top = alignment.y < 0;
    final left = alignment.x < 0;
    const side = BorderSide(color: Palette.accent, width: 3);
    return Container(
      width: 34,
      height: 34,
      decoration: BoxDecoration(
        border: Border(
          top: top ? side : BorderSide.none,
          bottom: !top ? side : BorderSide.none,
          left: left ? side : BorderSide.none,
          right: !left ? side : BorderSide.none,
        ),
        borderRadius: BorderRadius.only(
          topLeft: top && left ? const Radius.circular(11) : Radius.zero,
          topRight: top && !left ? const Radius.circular(11) : Radius.zero,
          bottomLeft:
              !top && left ? const Radius.circular(11) : Radius.zero,
          bottomRight:
              !top && !left ? const Radius.circular(11) : Radius.zero,
        ),
      ),
    );
  }
}
