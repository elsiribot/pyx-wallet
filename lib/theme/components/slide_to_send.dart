import 'package:flutter/material.dart';
import 'package:conduit/theme/icons.dart';
import 'package:conduit/theme/tokens.dart';

/// Slide-to-confirm control — mirrors prototype.html `.slide-send`
/// (lines 317-326, JS 1707-1736): draggable accent knob on a 58px track,
/// snap-back below 75%, green "done" state on completion.
class SlideToSend extends StatefulWidget {
  final bool enabled;
  final String label;
  final String doneLabel;
  final Future<void> Function() onConfirm;

  const SlideToSend({
    super.key,
    required this.enabled,
    required this.onConfirm,
    this.label = 'Slide to send',
    this.doneLabel = 'Sending…',
  });

  @override
  State<SlideToSend> createState() => _SlideToSendState();
}

class _SlideToSendState extends State<SlideToSend> {
  static const _knobW = 66.0;
  static const _height = 58.0;

  double _x = 0;
  bool _dragging = false;
  bool _done = false;

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: widget.enabled || _done ? 1 : 0.45,
      child: IgnorePointer(
        ignoring: !widget.enabled || _done,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final maxX = constraints.maxWidth - _knobW - 2;
            return Container(
              height: _height,
              decoration: BoxDecoration(
                color: _done ? Palette.green : Palette.surface,
                border: Border.all(
                  color: _done ? Palette.green : Palette.border,
                ),
                borderRadius: BorderRadius.circular(15),
              ),
              child: Stack(
                alignment: Alignment.center,
                children: [
                  Text(
                    _done ? widget.doneLabel : widget.label,
                    style: Type.button.copyWith(
                      letterSpacing: 0.3,
                      color: _done ? Palette.onGreen : Palette.muted,
                    ),
                  ),
                  AnimatedPositioned(
                    duration: _dragging
                        ? Duration.zero
                        : Motion.slideSettle,
                    curve: Motion.screenCurve,
                    left: _x,
                    top: 0,
                    bottom: 0,
                    child: GestureDetector(
                      onHorizontalDragStart: (_) =>
                          setState(() => _dragging = true),
                      onHorizontalDragUpdate: (d) => setState(
                        () => _x = (_x + d.delta.dx).clamp(0.0, maxX),
                      ),
                      onHorizontalDragEnd: (_) async {
                        _dragging = false;
                        if (_x >= maxX * 0.75) {
                          setState(() {
                            _x = maxX;
                            _done = true;
                          });
                          await widget.onConfirm();
                          if (mounted) {
                            setState(() {
                              _done = false;
                              _x = 0;
                            });
                          }
                        } else {
                          setState(() => _x = 0);
                        }
                      },
                      child: Container(
                        width: _knobW,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(
                          color: _done ? Palette.green : Palette.accent,
                          borderRadius: BorderRadius.circular(14),
                          boxShadow: _done
                              ? null
                              : const [
                                  BoxShadow(
                                    color: Palette.accentBadgeBg,
                                    blurRadius: 16,
                                    spreadRadius: -6,
                                    offset: Offset(0, 6),
                                  ),
                                ],
                        ),
                        child: Icon(
                          _done
                              ? PyxIcons.check
                              : PyxIcons.arrowRight,
                          size: 22,
                          color:
                              _done ? Palette.onGreen : Palette.onAccent,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            );
          },
        ),
      ),
    );
  }
}
