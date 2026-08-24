import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import 'package:conduit/bridge_generated.dart/client.dart';
import 'package:conduit/bridge_generated.dart/currency.dart';
import 'package:conduit/bridge_generated.dart/factory.dart';
import 'package:conduit/bridge_generated.dart/lib.dart';
import 'package:conduit/drawers/ecash_drawer.dart';
import 'package:conduit/drawers/scanner_drawer.dart';
import 'package:conduit/theme/components/buttons.dart';
import 'package:conduit/theme/components/code_field.dart';
import 'package:conduit/theme/components/controls.dart';
import 'package:conduit/theme/components/inputs.dart';
import 'package:conduit/theme/components/qr_surface.dart';
import 'package:conduit/theme/components/sheet.dart';
import 'package:conduit/theme/components/toast.dart';
import 'package:conduit/theme/icons.dart';
import 'package:conduit/theme/tokens.dart';
import 'package:conduit/utils/currency_utils.dart';

/// Unified receive screen — mirrors prototype.html `receive` (lines
/// 1246-1533): Bitcoin asset pill, Lightning/On-Chain/Ecash segments,
/// centered amount entry with unit selector, QR state machine, code field.
class ReceiveScreen extends StatefulWidget {
  final ConduitClient client;
  final ConduitClientFactory clientFactory;

  const ReceiveScreen({
    super.key,
    required this.client,
    required this.clientFactory,
  });

  @override
  State<ReceiveScreen> createState() => _ReceiveScreenState();
}

enum _Method { lightning, onchain, ecash }

class _ReceiveScreenState extends State<ReceiveScreen> {
  _Method _method = _Method.lightning;
  final _amountCtl = TextEditingController();
  String _unit = 'SATS';

  // Generated codes
  String? _lnurl;
  bool _lnurlUnavailable = false; // e.g. no gateway registered
  String? _invoice; // amount-derived
  String? _address;
  bool _generating = false;

  FiatCurrency get _fiat => findFiatCurrency(code: widget.client.currencyCode())!;

  @override
  void initState() {
    super.initState();
    widget.client.lnurl().then((v) {
      if (mounted) setState(() => _lnurl = v);
    }).catchError((e) {
      // Mirrors the prototype's CONFIG.lnurl=false state: fall back to
      // amount-derived invoices only.
      debugPrint('lnurl unavailable: $e');
      if (mounted) setState(() => _lnurlUnavailable = true);
    });
    _loadAddress();
  }

  Future<void> _loadAddress() async {
    final v2 = await widget.client.walletV2Receive();
    final addr = v2 ?? await widget.client.onchainReceiveAddress();
    if (mounted) setState(() => _address = addr);
  }

  @override
  void dispose() {
    _amountCtl.dispose();
    super.dispose();
  }

  double? get _amount {
    final v = double.tryParse(_amountCtl.text.replaceAll(',', '.'));
    return (v != null && v > 0) ? v : null;
  }

  Future<int> _amountSats() async {
    final a = _amount!;
    return switch (_unit) {
      'SATS' => a.round(),
      'BTC' => (a * 100000000).round(),
      _ => await widget.client.fiatToSats(amountFiat: a),
    };
  }

  /// Prototype `commitRecv`: on Enter/blur with an amount, mint the
  /// amount-derived invoice (Lightning only; on-chain is instant BIP21).
  Future<void> _commitAmount() async {
    if (_method != _Method.lightning) {
      setState(() {});
      return;
    }
    if (_amount == null) {
      setState(() => _invoice = null);
      return;
    }
    setState(() {
      _generating = true;
      _invoice = null;
    });
    try {
      final sats = await _amountSats();
      final receive = await widget.client.lnReceive(amountSat: sats);
      if (!mounted) return;
      setState(() {
        _invoice = receive.invoice;
        _generating = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _generating = false);
      pyxToast('Failed to create invoice');
    }
  }

  /// The displayed code per prototype `recvCode()` (lines 1377-1391).
  (String kind, String? value) _code() {
    final has = _amount != null;
    switch (_method) {
      case _Method.onchain:
        if (_address == null) return ('none', null);
        if (!has) return ('address', _address);
        final btc = _btcAmountForUri();
        return (
          'bip21',
          btc == null ? _address : 'bitcoin:$_address?amount=$btc',
        );
      case _Method.lightning:
        if (has) return ('invoice', _invoice);
        if (_lnurlUnavailable) return ('none', null);
        return ('lnurl', _lnurl);
      case _Method.ecash:
        return ('scan', null);
    }
  }

  String? _btcAmountForUri() {
    final a = _amount;
    if (a == null) return null;
    final sats = switch (_unit) {
      'SATS' => a.round(),
      'BTC' => (a * 100000000).round(),
      // fiat → sats needs the async rate; BIP21 amount falls back to bare
      // address until the invoice-style commit resolves it. Keep simple:
      _ => null,
    };
    if (sats == null) return null;
    return (sats / 100000000).toStringAsFixed(8);
  }

  String _subLine() {
    final a = _amount;
    if (a == null) return '';
    if (_unit == 'SATS') {
      final fiat = cachedFiatAmount(widget.client, a.round());
      return fiat == null ? '' : '≈ ${fiat.amount.replaceFirst(' ', '')}';
    }
    if (_unit == 'BTC') {
      return '${NumberFormat('#,###').format((a * 100000000).round()).replaceAll(',', ' ')} sats';
    }
    return '';
  }

  void _openUnitPicker() {
    final options = ['SATS', 'BTC', _fiat.code];
    showPyxSheet(
      context,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (final code in options)
            _CurrencyRow(
              code: code,
              name: switch (code) {
                'SATS' => 'Satoshis',
                'BTC' => 'Bitcoin',
                _ => _fiat.name,
              },
              selected: _unit == code,
              onTap: () {
                Navigator.of(context).pop();
                setState(() => _unit = code);
                _commitAmount();
              },
            ),
        ],
      ),
    );
  }

  void _openScanner() {
    ScannerDrawer.show(
      context,
      client: widget.client,
      clientFactory: widget.clientFactory,
    );
  }

  Future<void> _pasteEcash() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    final text = data?.text;
    if (text == null || !mounted) return;
    final notes = parseEcash(notes: text);
    if (notes == null) {
      pyxToast('Clipboard does not contain ecash');
      return;
    }
    EcashDrawer.show(context, client: widget.client, notes: notes);
  }

  @override
  Widget build(BuildContext context) {
    final (kind, value) = _code();
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(
                Gaps.screenH, 6, Gaps.screenH, 14,
              ),
              child: Row(
                children: [
                  IconBtn(
                    bare: true,
                    onTap: () => Navigator.of(context).pop(),
                    child: const Icon(PyxIcons.caretLeft),
                  ),
                  const SizedBox(width: 12),
                  const Text('Receive Bitcoin', style: Type.screenTitle),
                ],
              ),
            ),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.fromLTRB(
                  Gaps.screenH, 0, Gaps.screenH, 26,
                ),
                children: [
                  const AssetPill(label: 'Bitcoin'),
                  const SizedBox(height: 14),
                  Seg(
                    items: const ['Lightning', 'On-Chain', 'Ecash'],
                    index: _method.index,
                    onChanged: (i) => setState(() {
                      _method = _Method.values[i];
                      if (_method == _Method.lightning) _commitAmount();
                    }),
                  ),
                  if (_method == _Method.ecash)
                    _ecashBody()
                  else ...[
                    // Amount + unit selector (prototype `.recv-amt-row`)
                    Padding(
                      padding: const EdgeInsets.only(top: 32, bottom: 6),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          IntrinsicWidth(
                            child: TextField(
                              controller: _amountCtl,
                              keyboardType:
                                  const TextInputType.numberWithOptions(
                                      decimal: true),
                              textAlign: TextAlign.right,
                              style: Type.amountEntry,
                              cursorColor: Palette.accent,
                              decoration: InputDecoration(
                                isDense: true,
                                border: InputBorder.none,
                                hintText: '0',
                                hintStyle: Type.amountEntry
                                    .copyWith(color: Palette.faint),
                                constraints:
                                    const BoxConstraints(minWidth: 30),
                              ),
                              onChanged: (_) => setState(() {}),
                              onEditingComplete: () {
                                FocusScope.of(context).unfocus();
                                _commitAmount();
                              },
                            ),
                          ),
                          const SizedBox(width: 12),
                          GestureDetector(
                            onTap: _openUnitPicker,
                            child: Container(
                              padding: const EdgeInsets.symmetric(
                                  horizontal: 12, vertical: 8),
                              decoration: BoxDecoration(
                                color: Palette.surface2,
                                border: Border.all(color: Palette.border),
                                borderRadius: BorderRadius.circular(10),
                              ),
                              child: Row(
                                children: [
                                  Text(
                                    _unit,
                                    style: const TextStyle(
                                      fontFamily: Fonts.display,
                                      fontWeight: FontWeight.w600,
                                      fontSize: 14,
                                      color: Palette.muted,
                                    ),
                                  ),
                                  const SizedBox(width: 6),
                                  const Icon(PyxIcons.caretDown,
                                      size: 14, color: Palette.muted),
                                ],
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                    // Reserved-height sub-line so the QR never shifts
                    SizedBox(
                      height: 20,
                      child: Center(
                        child: Text(_subLine(), style: Type.fiat),
                      ),
                    ),
                    const SizedBox(height: 26),
                    _qrZone(kind, value),
                    if (value != null) CodeField(value: value),
                    Padding(
                      padding: const EdgeInsets.only(top: 18),
                      child: Center(
                        child: Text(
                          _note(kind),
                          textAlign: TextAlign.center,
                          style: Type.helper,
                        ),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _qrZone(String kind, String? value) {
    if (_generating) {
      return const QrSurface(loading: true);
    }
    if (value == null) {
      return QrSurface(
        placeholder: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(PyxIcons.qrCode),
            const SizedBox(height: 13),
            Text(
              _method == _Method.lightning
                  ? 'Enter an amount to generate an invoice'
                  : 'Loading address…',
              style: Type.helper.copyWith(fontSize: 13.5),
            ),
          ],
        ),
      );
    }
    // Only Lightning codes carry the bolt badge (prototype recvQrZone)
    return QrSurface(
      data: value,
      centerChip: _method == _Method.lightning
          ? const Icon(PyxIcons.lightning,
              size: 24, color: Palette.onAccent)
          : null,
    );
  }

  String _note(String kind) => switch (kind) {
        'lnurl' => 'Reusable code — the sender chooses the amount',
        'invoice' => 'One-time invoice for the entered amount',
        'address' => '',
        'bip21' => 'On-chain payment request for the entered amount',
        'none' =>
          'LNURL is unavailable — enter an amount for a one-time invoice',
        _ => '',
      };

  /// Ecash arrives as an animated QR from the sender: scanning UI
  /// (prototype `ecashScanHtml`, lines 1307-1358).
  Widget _ecashBody() {
    return Padding(
      padding: const EdgeInsets.only(top: 48),
      child: Column(
        children: [
          GestureDetector(
            onTap: _openScanner,
            child: AspectRatio(
              aspectRatio: 1,
              child: Container(
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(24),
                  color: Palette.surface,
                  border: Border.all(color: Palette.border),
                ),
                child: Stack(
                  alignment: Alignment.center,
                  children: [
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
                          child: _corner(a),
                        ),
                      ),
                    Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(PyxIcons.scan,
                            size: 40, color: Palette.faint),
                        const SizedBox(height: 12),
                        Text(
                          'Tap to scan the sender\'s code',
                          style: Type.helper.copyWith(fontSize: 13.5),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
          const SizedBox(height: 20),
          PyxButton.ghost(
            label: 'Paste from clipboard',
            icon: const Icon(PyxIcons.copy),
            onTap: _pasteEcash,
          ),
        ],
      ),
    );
  }

  Widget _corner(Alignment a) {
    final top = a.y < 0;
    final left = a.x < 0;
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
          bottomLeft: !top && left ? const Radius.circular(11) : Radius.zero,
          bottomRight:
              !top && !left ? const Radius.circular(11) : Radius.zero,
        ),
      ),
    );
  }
}

/// Currency picker row (prototype `.cur-row`, Task 3.4 minimal form).
class _CurrencyRow extends StatelessWidget {
  final String code;
  final String name;
  final bool selected;
  final VoidCallback onTap;

  const _CurrencyRow({
    required this.code,
    required this.name,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(Radii.icon),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 11),
        decoration: selected
            ? BoxDecoration(
                color: Palette.surface2,
                borderRadius: BorderRadius.circular(Radii.icon),
              )
            : null,
        child: Row(
          children: [
            SizedBox(
              width: 56,
              child: Text(
                code,
                style: const TextStyle(
                  fontFamily: Fonts.display,
                  fontWeight: FontWeight.w600,
                  fontSize: 14.5,
                  color: Palette.text,
                ),
              ),
            ),
            Expanded(
              child: Text(
                name,
                style: Type.rowSub.copyWith(
                    fontSize: 13.5, color: Palette.muted),
              ),
            ),
            if (selected)
              const Icon(PyxIcons.check, size: 18, color: Palette.accent),
          ],
        ),
      ),
    );
  }
}
