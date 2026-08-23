import 'package:flutter/material.dart';
import 'package:pretty_qr_code/pretty_qr_code.dart';
import 'package:conduit/theme/tokens.dart';

/// QR display surface — mirrors prototype.html `.qr-wrap` states
/// (lines 250-259): white r18 panel with centered accent bolt chip;
/// dashed empty placeholder; spinner while generating.
class QrSurface extends StatelessWidget {
  final String? data;
  final bool loading;
  final Widget? placeholder;
  final Widget? centerChip;

  const QrSurface({
    super.key,
    this.data,
    this.loading = false,
    this.placeholder,
    this.centerChip,
  });

  @override
  Widget build(BuildContext context) {
    if (data == null) {
      return AspectRatio(
        aspectRatio: 1,
        child: Container(
          decoration: BoxDecoration(
            color: Palette.surface,
            borderRadius: BorderRadius.circular(Radii.qr),
            border: Border.all(color: Palette.borderStrong, width: 1),
          ),
          child: Center(
            child: loading
                ? const QrSpinner()
                : DefaultTextStyle(
                    style: Type.helper.copyWith(fontSize: 13.5),
                    textAlign: TextAlign.center,
                    child: IconTheme(
                      data: const IconThemeData(
                        size: 48,
                        color: Palette.faint,
                      ),
                      child: placeholder ?? const SizedBox.shrink(),
                    ),
                  ),
          ),
        ),
      );
    }
    return AspectRatio(
      aspectRatio: 1,
      child: Container(
        padding: const EdgeInsets.all(18),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(Radii.qr),
        ),
        child: Stack(
          alignment: Alignment.center,
          children: [
            PrettyQrView.data(
              data: data!,
              errorCorrectLevel: QrErrorCorrectLevel.M,
              decoration: const PrettyQrDecoration(
                shape: PrettyQrSmoothSymbol(
                  color: Colors.black,
                  roundFactor: 0,
                ),
              ),
            ),
            if (centerChip != null)
              Container(
                width: 50,
                height: 50,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: Palette.accent,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: Colors.white, width: 6),
                ),
                child: centerChip,
              ),
          ],
        ),
      ),
    );
  }
}

/// Accent top-arc spinner — mirrors `.qr-spinner` (800ms).
class QrSpinner extends StatelessWidget {
  final double size;

  const QrSpinner({super.key, this.size = 46});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: const CircularProgressIndicator(
        strokeWidth: 3,
        color: Palette.accent,
        backgroundColor: Palette.surface3,
      ),
    );
  }
}
