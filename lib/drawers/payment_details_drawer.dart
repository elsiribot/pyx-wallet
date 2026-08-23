import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import 'package:conduit/bridge_generated.dart/events.dart';
import 'package:conduit/theme/components/buttons.dart';
import 'package:conduit/theme/components/controls.dart';
import 'package:conduit/theme/components/sheet.dart';
import 'package:conduit/theme/components/toast.dart';
import 'package:conduit/theme/icons.dart';
import 'package:conduit/theme/tokens.dart';
import 'package:conduit/utils/payment_utils.dart';
import 'package:conduit/utils/currency_utils.dart';

/// Transaction detail sheet — mirrors prototype.html tx detail
/// (lines 2189-2255): centered icon + type + signed amount + state badge,
/// user rows, wrench toggling the technical section with tap-to-copy rows.
class PaymentDetailsDrawer extends StatefulWidget {
  final ConduitPayment event;

  const PaymentDetailsDrawer({super.key, required this.event});

  static Future<void> show(
    BuildContext context, {
    required ConduitPayment event,
  }) {
    return showPyxSheet(context, child: PaymentDetailsDrawer(event: event));
  }

  @override
  State<PaymentDetailsDrawer> createState() => _PaymentDetailsDrawerState();
}

class _PaymentDetailsDrawerState extends State<PaymentDetailsDrawer> {
  bool _showTech = false;

  ConduitPayment get event => widget.event;

  String _sats(int amount) =>
      NumberFormat('#,###').format(amount).replaceAll(',', ' ');

  @override
  Widget build(BuildContext context) {
    final incoming = event.incoming;
    final failed = event.success == false;
    final pending = event.success == null;
    final fiat = historicalFiat(event);
    final date = DateFormat('MMM d, y · HH:mm')
        .format(DateTime.fromMillisecondsSinceEpoch(event.timestamp));
    final status = PaymentTypeUtils.getStatus(
      incoming: incoming,
      success: event.success,
    );

    return Stack(
      children: [
        Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Header (prototype .tx-detail-head)
            Container(
              width: 54,
              height: 54,
              alignment: Alignment.center,
              margin: const EdgeInsets.only(top: 2),
              decoration: BoxDecoration(
                color: Palette.surface2,
                borderRadius: BorderRadius.circular(16),
              ),
              child: Icon(
                incoming ? PyxIcons.arrowDown : PyxIcons.arrowUp,
                size: 26,
                color: failed
                    ? Palette.amber
                    : (incoming ? Palette.green : Palette.muted),
              ),
            ),
            const SizedBox(height: 12),
            Center(
              child: Text(
                PaymentTypeUtils.getLabel(event.paymentType),
                style: Type.sheetTitle.copyWith(fontSize: 19),
              ),
            ),
            const SizedBox(height: 8),
            Center(
              child: Text.rich(
                TextSpan(
                  text:
                      '${incoming ? '+' : '-'}${_sats(event.amountSats)}',
                  style: Type.bigAmount.copyWith(
                    color: incoming ? Palette.green : Palette.red,
                  ),
                  children: const [
                    TextSpan(text: ' '),
                    TextSpan(text: 'SATS', style: Type.bigAmountUnit),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            Center(
              child: PyxBadge(
                status,
                color: failed
                    ? Palette.red
                    : (pending ? Palette.amber : Palette.green),
                leading: StatusDot(
                  failed
                      ? StatusKind.off
                      : (pending ? StatusKind.warn : StatusKind.on),
                ),
              ),
            ),
            const SizedBox(height: 18),
            _row('Date', Text(date, style: Type.drowValue)),
            if (fiat != null)
              _row(
                'Value then',
                Text(
                  fiat.amount.replaceFirst(' ', ''),
                  style: Type.drowValue,
                ),
              ),
            if (event.feeSats case final fee?)
              _row('Fee', Text('${_sats(fee)} sats', style: Type.drowValue)),
            if (_showTech) ...[
              Padding(
                padding: const EdgeInsets.only(top: 13, bottom: 4),
                child: Text('TECHNICAL', style: Type.sectionLabel),
              ),
              _row('Operation ID', _copyable(event.operationId)),
              if (event.txid case final v?) _row('Txid', _copyable(v)),
              if (event.preimage case final v?)
                _row('Preimage', _copyable(v)),
              if (event.address case final v?)
                _row('Address', _copyable(v)),
              if (event.ecash case final v?) _row('Ecash', _copyable(v)),
            ],
            const SizedBox(height: 8),
          ],
        ),
        // Wrench toggle (prototype .tx-wrench)
        Positioned(
          top: 0,
          right: 0,
          child: IconBtn(
            activeRing: _showTech,
            onTap: () => setState(() => _showTech = !_showTech),
            child: Icon(
              PyxIcons.wrench,
              size: 18,
              color: _showTech ? Palette.accent : Palette.text,
            ),
          ),
        ),
      ],
    );
  }

  Widget _row(String k, Widget v) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 15),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: Palette.border)),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(k.toUpperCase(), style: Type.drowKey),
          Flexible(child: v),
        ],
      ),
    );
  }

  /// Truncated value with copy icon; tap copies (prototype .tx-copy).
  Widget _copyable(String value) {
    return GestureDetector(
      onTap: () {
        Clipboard.setData(ClipboardData(text: value));
        pyxToast('Copied to clipboard');
      },
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 215),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Flexible(
              child: Text(
                value,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: Type.drowValue.copyWith(fontSize: 13.5),
              ),
            ),
            const SizedBox(width: 6),
            const Icon(PyxIcons.copy, size: 14, color: Palette.faint),
          ],
        ),
      ),
    );
  }
}
