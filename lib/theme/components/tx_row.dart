import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:conduit/theme/tokens.dart';

/// Direction/kind of a transaction row icon.
enum TxKind { received, sent, swap }

/// Activity list row — mirrors prototype.html `.tx` (lines 191-213):
/// 38x38 surface-2 icon with corner ₿ badge, title + subtitle, right-aligned
/// signed amount, hairline separator, optional orbiting pending arc.
class TxRowTile extends StatelessWidget {
  final TxKind kind;
  final Widget icon;
  final String title;
  final String subtitle;
  final String amount;
  final String unit;
  final bool positive;
  final bool pending;
  final bool showDivider;
  final VoidCallback? onTap;

  const TxRowTile({
    super.key,
    required this.kind,
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.amount,
    required this.unit,
    required this.positive,
    this.pending = false,
    this.showDivider = true,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: Gaps.rowV),
        decoration: showDivider
            ? const BoxDecoration(
                border: Border(bottom: BorderSide(color: Palette.border)),
              )
            : null,
        child: Row(
          children: [
            TxIcon(icon: icon, pending: pending),
            const SizedBox(width: Gaps.rowGap),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: Type.rowTitle),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: pending
                        ? Type.rowSub.copyWith(
                            color: Palette.amber,
                            fontWeight: FontWeight.w600,
                          )
                        : Type.rowSub,
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Text.rich(
              TextSpan(
                text: amount,
                style: Type.rowAmount.copyWith(
                  color: positive ? Palette.green : Palette.red,
                ),
                children: [
                  const TextSpan(text: ' '),
                  TextSpan(text: unit, style: Type.rowAmountUnit),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The 38x38 rounded icon with corner asset badge and optional spinning
/// pending arc (`.tx .ic`, `.tx-asset`, `.tx-ring`).
class TxIcon extends StatelessWidget {
  final Widget icon;
  final bool pending;
  final double size;

  const TxIcon({
    super.key,
    required this.icon,
    this.pending = false,
    this.size = 38,
  });

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Container(
            width: size,
            height: size,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: Palette.surface2,
              borderRadius: BorderRadius.circular(Radii.icon),
            ),
            child: IconTheme(
              data: const IconThemeData(size: 18, color: Palette.muted),
              child: icon,
            ),
          ),
          Positioned(
            bottom: -4,
            right: -4,
            child: Container(
              width: 17,
              height: 17,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: Palette.accent,
                borderRadius: BorderRadius.circular(5),
                border: Border.all(color: Palette.bg, width: 2),
              ),
              child: const Text(
                '₿',
                style: TextStyle(
                  fontFamily: Fonts.display,
                  fontWeight: FontWeight.w700,
                  fontSize: 8,
                  color: Palette.onAccent,
                  height: 1,
                ),
              ),
            ),
          ),
          if (pending)
            const Positioned(
              left: -3,
              top: -3,
              right: -3,
              bottom: -3,
              child: PendingRing(),
            ),
        ],
      ),
    );
  }
}

/// Accent arc traveling around a rounded-rect outline — mirrors `.tx-ring`
/// (22% arc, 1.15s linear orbit).
class PendingRing extends StatefulWidget {
  const PendingRing({super.key});

  @override
  State<PendingRing> createState() => _PendingRingState();
}

class _PendingRingState extends State<PendingRing>
    with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(
    vsync: this,
    duration: Motion.pendingSpin,
  )..repeat();

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _c,
      builder: (context, _) => CustomPaint(
        painter: _RoundedRectArcPainter(progress: _c.value),
      ),
    );
  }
}

class _RoundedRectArcPainter extends CustomPainter {
  final double progress;

  _RoundedRectArcPainter({required this.progress});

  @override
  void paint(Canvas canvas, Size size) {
    final rrect = RRect.fromRectAndRadius(
      Offset.zero & size,
      const Radius.circular(Radii.icon + 3),
    );
    final track = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3
      ..color = Palette.surface3;
    canvas.drawRRect(rrect, track);

    final path = Path()..addRRect(rrect);
    final metric = path.computeMetrics().first;
    final len = metric.length;
    final start = progress * len;
    final arcLen = 0.22 * len;
    final arc = Path();
    if (start + arcLen <= len) {
      arc.addPath(metric.extractPath(start, start + arcLen), Offset.zero);
    } else {
      arc.addPath(metric.extractPath(start, len), Offset.zero);
      arc.addPath(metric.extractPath(0, start + arcLen - len), Offset.zero);
    }
    final prog = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3
      ..strokeCap = StrokeCap.round
      ..color = Palette.accent;
    canvas.drawPath(arc, prog);
  }

  @override
  bool shouldRepaint(_RoundedRectArcPainter old) =>
      old.progress != progress;
}

/// Rounded-rect guardian-quorum progress ring — mirrors `.fed-ring`
/// (lines 427-430, JS 967-986). Drawn around a 38x38 icon button.
class GuardianRing extends StatelessWidget {
  final int online;
  final int total;

  const GuardianRing({super.key, required this.online, required this.total});

  Color get _color => online >= total
      ? Palette.green
      : (online / math.max(total, 1) >= 5 / 7 ? Palette.amber : Palette.red);

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: CustomPaint(
        painter: _RingPainter(
          fraction: total == 0 ? 0 : online / total,
          color: _color,
        ),
      ),
    );
  }
}

class _RingPainter extends CustomPainter {
  final double fraction;
  final Color color;

  _RingPainter({required this.fraction, required this.color});

  @override
  void paint(Canvas canvas, Size size) {
    final rrect = RRect.fromRectAndRadius(
      Offset.zero & size,
      const Radius.circular(11),
    );
    canvas.drawRRect(
      rrect,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.5
        ..color = Palette.surface3,
    );
    if (fraction <= 0) return;
    final path = Path()..addRRect(rrect);
    final metric = path.computeMetrics().first;
    // start at top-centre like the prototype's path
    final len = metric.length;
    final start = 0.0;
    final end = fraction.clamp(0.0, 1.0) * len;
    canvas.drawPath(
      metric.extractPath(start, end),
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.5
        ..strokeCap = StrokeCap.round
        ..color = color,
    );
  }

  @override
  bool shouldRepaint(_RingPainter old) =>
      old.fraction != fraction || old.color != color;
}
