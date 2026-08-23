import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:conduit/theme/icons.dart';
import 'package:conduit/bridge_generated.dart/events.dart';
import 'package:conduit/theme/tokens.dart';
import 'package:conduit/theme/components/asset_card.dart';
import 'package:conduit/theme/components/cards.dart';
import 'package:conduit/theme/components/tx_row.dart';
import 'package:conduit/utils/payment_utils.dart';

/// Grouped-activity home body — mirrors prototype.html `home`
/// (lines 987-1075): collapsing balance header, day-grouped activity rows,
/// end-of-history line / empty state.
///
/// Pure presentation: state (balance, payments, masking) is passed in from
/// the screen that owns the streams.
class HomeBody extends StatefulWidget {
  final String amount;
  final String? fiat;
  final bool masked;
  final List<ConduitPayment> payments;
  final VoidCallback onReceive;
  final VoidCallback onSend;
  final VoidCallback onScan;
  final void Function(ConduitPayment) onPaymentTap;

  const HomeBody({
    super.key,
    required this.amount,
    required this.fiat,
    required this.masked,
    required this.payments,
    required this.onReceive,
    required this.onSend,
    required this.onScan,
    required this.onPaymentTap,
  });

  @override
  State<HomeBody> createState() => _HomeBodyState();
}

class _HomeBodyState extends State<HomeBody> {
  final _scroll = ScrollController();
  bool _scrolled = false;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
  }

  void _onScroll() {
    // Swap the scan FAB for the jump-to-top pill once the header range has
    // mostly collapsed (prototype: 85% of the collapse range).
    final scrolled = _scroll.offset > _HeaderDelegate.collapseRange * 0.85;
    if (scrolled != _scrolled) setState(() => _scrolled = scrolled);
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  /// Prototype `activityGroups`: Today / Yesterday / "May 11" day buckets.
  List<(String, List<ConduitPayment>)> _groups() {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final groups = <(String, List<ConduitPayment>)>[];
    for (final p in widget.payments) {
      final d = DateTime.fromMillisecondsSinceEpoch(p.timestamp);
      final day = DateTime(d.year, d.month, d.day);
      final label = day == today
          ? 'Today'
          : day == today.subtract(const Duration(days: 1))
              ? 'Yesterday'
              : DateFormat.MMMd().format(day);
      if (groups.isEmpty || groups.last.$1 != label) {
        groups.add((label, [p]));
      } else {
        groups.last.$2.add(p);
      }
    }
    return groups;
  }

  @override
  Widget build(BuildContext context) {
    final groups = _groups();
    return Stack(
      children: [
        CustomScrollView(
          controller: _scroll,
          slivers: [
            SliverPersistentHeader(
              pinned: true,
              delegate: _HeaderDelegate(
                amount: widget.amount,
                fiat: widget.fiat,
                masked: widget.masked,
                onReceive: widget.onReceive,
                onSend: widget.onSend,
                onTapCompact: () => _scroll.animateTo(
                  0,
                  duration: Motion.screen,
                  curve: Motion.screenCurve,
                ),
              ),
            ),
            SliverPadding(
              padding: const EdgeInsets.symmetric(horizontal: Gaps.screenH),
              sliver: SliverList.list(
                children: [
                  if (groups.isEmpty)
                    const _EmptyHistory()
                  else ...[
                    for (final (label, items) in groups) ...[
                      ActGroupLabel(label),
                      for (var i = 0; i < items.length; i++)
                        _paymentRow(items[i], i == items.length - 1),
                    ],
                    Padding(
                      padding: const EdgeInsets.only(top: 22, bottom: 4),
                      child: Center(
                        child: Text(
                          'End of history',
                          style: Type.helper.copyWith(fontSize: 12.5),
                        ),
                      ),
                    ),
                  ],
                  const SizedBox(height: 100),
                ],
              ),
            ),
          ],
        ),
        // Scan FAB <-> jump-to-top pill share the bottom-centre slot.
        Positioned(
          left: 0,
          right: 0,
          bottom: 24,
          child: Center(
            child: AnimatedSwitcher(
              duration: const Duration(milliseconds: 220),
              child: _scrolled
                  ? _JumpTopPill(
                      key: const ValueKey('top'),
                      onTap: () => _scroll.animateTo(
                        0,
                        duration: Motion.screen,
                        curve: Motion.screenCurve,
                      ),
                    )
                  : _ScanFab(
                      key: const ValueKey('scan'),
                      onTap: widget.onScan,
                    ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _paymentRow(ConduitPayment p, bool last) {
    final incoming = p.incoming;
    final pending = p.success == null;
    final failed = p.success == false;
    final date = DateTime.fromMillisecondsSinceEpoch(p.timestamp);
    final amount = NumberFormat('#,###').format(p.amountSats).replaceAll(
        ',', ' '); // thin space grouping like the prototype's "12 340"
    return TxRowTile(
      kind: incoming ? TxKind.received : TxKind.sent,
      icon: Icon(
        incoming
            ? PyxIcons.arrowDown
            : PyxIcons.arrowUp,
        color: failed ? Palette.amber : Palette.muted,
      ),
      title: PaymentTypeUtils.getLabel(p.paymentType),
      subtitle: pending
          ? 'Pending'
          : failed
              ? 'Failed · ${formatRelativeTime(date)}'
              : formatRelativeTime(date),
      amount: widget.masked ? '•••••' : '${incoming ? '+' : '-'}$amount',
      unit: 'SATS',
      positive: incoming,
      pending: pending,
      showDivider: !last,
      onTap: () => widget.onPaymentTap(p),
    );
  }
}

/// Collapsing balance header: large asset card shrinks/fades into a compact
/// one-line ₿ balance (prototype `.bal-sticky` / `mountHomeScroll`).
class _HeaderDelegate extends SliverPersistentHeaderDelegate {
  static const compactH = 54.0;
  static const cardH = 190.0;
  static const collapseRange = cardH;

  final String amount;
  final String? fiat;
  final bool masked;
  final VoidCallback onReceive;
  final VoidCallback onSend;
  final VoidCallback onTapCompact;

  _HeaderDelegate({
    required this.amount,
    required this.fiat,
    required this.masked,
    required this.onReceive,
    required this.onSend,
    required this.onTapCompact,
  });

  @override
  double get minExtent => compactH;

  @override
  double get maxExtent => compactH + cardH;

  @override
  Widget build(
    BuildContext context,
    double shrinkOffset,
    bool overlapsContent,
  ) {
    final t = (shrinkOffset / cardH).clamp(0.0, 1.0);
    // Sequenced hand-off (prototype): large gone by ~36%, compact in from ~36%.
    final largeOpacity = (1 - t * 2.8).clamp(0.0, 1.0);
    final compactOpacity = (t * 1.555 - 0.555).clamp(0.0, 1.0);
    return Container(
      // Opaque header so list rows dissolve underneath, fading only at the
      // bottom edge (prototype `.bal-sticky` gradient).
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          stops: [0, 0.8, 1],
          colors: [Palette.bg, Palette.bg, Palette.bgTransparent],
        ),
      ),
      padding: const EdgeInsets.symmetric(horizontal: Gaps.screenH),
      child: Stack(
        children: [
          // Large card, clipped away as it collapses
          Positioned(
            left: 0,
            right: 0,
            top: 6 - shrinkOffset * 0.4,
            child: Opacity(
              opacity: largeOpacity,
              child: AssetCard(
                amount: amount,
                unit: 'SATS',
                fiat: fiat,
                masked: masked,
                onReceive: onReceive,
                onSend: onSend,
              ),
            ),
          ),
          // Compact one-line balance
          Positioned(
            left: 0,
            right: 0,
            top: 0,
            height: compactH,
            child: IgnorePointer(
              ignoring: compactOpacity < 0.5,
              child: Opacity(
                opacity: compactOpacity,
                child: GestureDetector(
                  onTap: onTapCompact,
                  behavior: HitTestBehavior.opaque,
                  child: Row(
                    children: [
                      Container(
                        width: 22,
                        height: 22,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(
                          color: Palette.accentBadgeBg,
                          borderRadius: BorderRadius.circular(7),
                        ),
                        child: const Text(
                          '₿',
                          style: TextStyle(
                            fontFamily: Fonts.display,
                            fontWeight: FontWeight.w700,
                            fontSize: 12,
                            color: Palette.accent,
                            height: 1,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Text.rich(
                        TextSpan(
                          text: masked ? '•••••' : amount,
                          style: const TextStyle(
                            fontFamily: Fonts.display,
                            fontWeight: FontWeight.w700,
                            fontSize: 20,
                            letterSpacing: -0.4,
                            color: Palette.text,
                            height: 1,
                          ),
                          children: const [
                            TextSpan(text: ' '),
                            TextSpan(
                              text: 'SATS',
                              style: TextStyle(
                                fontFamily: Fonts.display,
                                fontWeight: FontWeight.w500,
                                fontSize: 12,
                                color: Palette.muted,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  @override
  bool shouldRebuild(_HeaderDelegate old) =>
      old.amount != amount ||
      old.fiat != fiat ||
      old.masked != masked;
}

/// Prototype `emptyHistory` (lines 1102-1107).
class _EmptyHistory extends StatelessWidget {
  const _EmptyHistory();

  @override
  Widget build(BuildContext context) {
    return const Padding(
      padding: EdgeInsets.fromLTRB(24, 54, 24, 30),
      child: Column(
        children: [
          Opacity(
            opacity: 0.5,
            child: Icon(
              PyxIcons.swap,
              size: 70,
              color: Palette.faint,
            ),
          ),
          SizedBox(height: 12),
          Text(
            'No transaction history yet',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontFamily: Fonts.display,
              fontWeight: FontWeight.w500,
              fontSize: 32,
              color: Palette.faint,
              height: 1.25,
            ),
          ),
        ],
      ),
    );
  }
}

/// Overlaid scan FAB — mirrors `.scan-fab` (lines 442-453).
class _ScanFab extends StatelessWidget {
  final VoidCallback onTap;

  const _ScanFab({super.key, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Palette.accent,
      shape: const CircleBorder(),
      clipBehavior: Clip.antiAlias,
      elevation: 0,
      child: InkWell(
        onTap: onTap,
        child: SizedBox(
          width: 58,
          height: 58,
          child: Icon(
            PyxIcons.scan,
            size: 25,
            color: Palette.onAccent,
          ),
        ),
      ),
    );
  }
}

/// Jump-to-top pill — mirrors `.jump-top` (lines 432-439).
class _JumpTopPill extends StatelessWidget {
  final VoidCallback onTap;

  const _JumpTopPill({super.key, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Palette.surface2,
      borderRadius: BorderRadius.circular(999),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(999),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 11),
          decoration: BoxDecoration(
            border: Border.all(color: Palette.borderStrong),
            borderRadius: BorderRadius.circular(999),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                PyxIcons.arrowUp,
                size: 14,
                color: Palette.text,
              ),
              const SizedBox(width: 7),
              Text('Top', style: Type.button.copyWith(fontSize: 13)),
            ],
          ),
        ),
      ),
    );
  }
}
