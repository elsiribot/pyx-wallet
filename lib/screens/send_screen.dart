import 'dart:async';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:conduit/bridge_generated.dart/client.dart';
import 'package:conduit/bridge_generated.dart/factory.dart';
import 'package:conduit/bridge_generated.dart/lib.dart';
import 'package:conduit/bridge_generated.dart/lnurl.dart';
import 'package:conduit/drawers/scanner_drawer.dart';
import 'package:conduit/theme/components/buttons.dart';
import 'package:conduit/theme/components/inputs.dart';
import 'package:conduit/theme/components/slide_to_send.dart';
import 'package:conduit/theme/components/toast.dart';
import 'package:conduit/theme/icons.dart';
import 'package:conduit/theme/tokens.dart';

/// Send screen — mirrors prototype.html `send` (lines 1600-1946):
/// destination-gated flow with contacts dropdown, locked amounts for fixed
/// invoices, fee row, slide-to-send confirm.
class SendScreen extends StatefulWidget {
  final ConduitClient client;
  final ConduitClientFactory clientFactory;

  const SendScreen({
    super.key,
    required this.client,
    required this.clientFactory,
  });

  @override
  State<SendScreen> createState() => _SendScreenState();
}

sealed class _Dest {}

class _DestInvoice extends _Dest {
  final Bolt11InvoiceWrapper invoice;
  _DestInvoice(this.invoice);
}

class _DestLnurl extends _Dest {
  final LnurlWrapper lnurl;
  PayResponseWrapper? limits;
  _DestLnurl(this.lnurl);
}

class _DestOnchain extends _Dest {
  final BitcoinAddressWrapper address;
  _DestOnchain(this.address);
}

class _SendScreenState extends State<SendScreen> {
  final _toCtl = TextEditingController();
  final _toFocus = FocusNode();
  final _amountCtl = TextEditingController();
  final _noteCtl = TextEditingController();

  _Dest? _dest;
  bool _amountLocked = false;
  int? _feeSats;
  List<ConduitContact> _contacts = [];
  bool _showDrop = false;

  @override
  void initState() {
    super.initState();
    widget.clientFactory.listContacts().then((c) {
      if (mounted) setState(() => _contacts = c);
    });
    _toFocus.addListener(() {
      if (!_toFocus.hasFocus) {
        // let a dropdown tap land before hiding (prototype: 150ms blur delay)
        Future.delayed(const Duration(milliseconds: 150), () {
          if (mounted) setState(() => _showDrop = false);
        });
      } else {
        setState(() => _showDrop = true);
      }
    });
  }

  @override
  void dispose() {
    _toCtl.dispose();
    _toFocus.dispose();
    _amountCtl.dispose();
    _noteCtl.dispose();
    super.dispose();
  }

  /// Prototype `parseSendDest`/`applySendDest` (lines 1746-1781).
  Future<void> _applyDest(String raw) async {
    final input = raw.trim();
    _feeSats = null;
    _amountLocked = false;

    final invoice = parseBolt11Invoice(invoice: input);
    if (invoice != null) {
      final sats = invoice.amountSats();
      setState(() {
        _dest = _DestInvoice(invoice);
        if (sats > 0) {
          _amountCtl.text = _fmt(sats);
          _amountLocked = true;
        }
      });
      try {
        final fees = await widget.client.lnCalculateFees(invoice: invoice);
        if (mounted) setState(() => _feeSats = fees.feeSats);
      } catch (_) {}
      return;
    }

    final lnurl = parseLnurl(request: input);
    if (lnurl != null) {
      final dest = _DestLnurl(lnurl);
      setState(() => _dest = dest);
      try {
        final limits = await lnurlFetchLimits(lnurl: lnurl);
        if (!mounted || _dest != dest) return;
        setState(() {
          dest.limits = limits;
          if (limits.isFixedAmount()) {
            _amountCtl.text = _fmt(limits.maxSats);
            _amountLocked = true;
          }
        });
      } catch (_) {
        if (mounted) pyxToast('Could not reach recipient');
      }
      return;
    }

    final address = parseBitcoinAddress(address: input);
    if (address != null) {
      setState(() => _dest = _DestOnchain(address));
      return;
    }

    setState(() => _dest = null);
  }

  String _fmt(int sats) =>
      NumberFormat('#,###').format(sats).replaceAll(',', ' ');

  int? get _amountSats {
    final v =
        int.tryParse(_amountCtl.text.replaceAll(RegExp(r'[^\d]'), ''));
    return (v != null && v > 0) ? v : null;
  }

  bool get _ready => _dest != null && _amountSats != null;

  Future<void> _confirmSend() async {
    final sats = _amountSats!;
    try {
      switch (_dest!) {
        case _DestInvoice(:final invoice):
          await widget.client.lnSend(invoice: invoice);
        case _DestLnurl(:final limits):
          final resolved = await lnurlResolve(
            payResponse: limits!,
            amountSats: sats,
          );
          await widget.client.lnSend(invoice: resolved);
        case _DestOnchain(:final address):
          await widget.client.onchainSend(
            address: address,
            amountSats: sats,
          );
      }
      if (!mounted) return;
      pyxToast('Payment sent');
      Navigator.of(context).pop();
    } catch (e) {
      if (!mounted) return;
      pyxToast('Send failed: $e');
    }
  }

  void _pickContact(ConduitContact c) {
    _toCtl.text = c.name;
    setState(() => _showDrop = false);
    FocusScope.of(context).unfocus();
    _applyDest(c.lnurl.encode());
  }

  List<ConduitContact> get _filteredContacts {
    final q = _toCtl.text.trim();
    if (q.isEmpty) return _contacts;
    return _contacts.where((c) => c.matchQuery(query: q)).toList();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(
                  Gaps.screenH, 6, Gaps.screenH, 14),
              child: Row(
                children: [
                  IconBtn(
                    bare: true,
                    onTap: () => Navigator.of(context).pop(),
                    child: const Icon(PyxIcons.caretLeft),
                  ),
                  const SizedBox(width: 12),
                  const Text('Send Bitcoin', style: Type.screenTitle),
                ],
              ),
            ),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.fromLTRB(
                    Gaps.screenH, 0, Gaps.screenH, 26),
                children: [
                  const AssetPill(label: 'Bitcoin'),
                  Field(
                    label: 'To',
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        PyxInput(
                          trailing: GestureDetector(
                            onTap: () => ScannerDrawer.show(
                              context,
                              client: widget.client,
                              clientFactory: widget.clientFactory,
                            ),
                            child: const Icon(PyxIcons.scan,
                                size: 20, color: Palette.muted),
                          ),
                          child: PyxTextField(
                            controller: _toCtl,
                            focusNode: _toFocus,
                            hint: 'LN address, LNURL, invoice or address',
                            mono: true,
                            onChanged: (v) {
                              setState(() {});
                              _applyDest(v);
                            },
                          ),
                        ),
                        if (_showDrop && _filteredContacts.isNotEmpty)
                          Container(
                            margin: const EdgeInsets.only(top: 6),
                            constraints:
                                const BoxConstraints(maxHeight: 300),
                            decoration: BoxDecoration(
                              color: Palette.surface2,
                              border:
                                  Border.all(color: Palette.borderStrong),
                              borderRadius:
                                  BorderRadius.circular(Radii.card),
                            ),
                            child: ListView(
                              shrinkWrap: true,
                              padding: EdgeInsets.zero,
                              children: [
                                for (final c in _filteredContacts)
                                  InkWell(
                                    onTap: () => _pickContact(c),
                                    child: Padding(
                                      padding: const EdgeInsets.symmetric(
                                          horizontal: 14, vertical: 12),
                                      child: Row(
                                        children: [
                                          Container(
                                            width: 34,
                                            height: 34,
                                            alignment: Alignment.center,
                                            decoration:
                                                const BoxDecoration(
                                              color: Palette.surface3,
                                              shape: BoxShape.circle,
                                            ),
                                            child: Text(
                                              _initials(c.name),
                                              style: const TextStyle(
                                                fontFamily: Fonts.display,
                                                fontWeight: FontWeight.w700,
                                                fontSize: 13,
                                                color: Palette.muted,
                                              ),
                                            ),
                                          ),
                                          const SizedBox(width: 12),
                                          Expanded(
                                            child: Text(c.name,
                                                style: Type.rowTitle
                                                    .copyWith(
                                                        fontSize: 14)),
                                          ),
                                        ],
                                      ),
                                    ),
                                  ),
                              ],
                            ),
                          ),
                      ],
                    ),
                  ),
                  Field(
                    label: 'Amount',
                    child: PyxInput(
                      disabled: _dest == null,
                      suffix: 'SATS',
                      trailing: _amountLocked
                          ? const Icon(PyxIcons.lock,
                              size: 16, color: Palette.faint)
                          : null,
                      child: PyxTextField(
                        controller: _amountCtl,
                        enabled: _dest != null && !_amountLocked,
                        keyboardType: TextInputType.number,
                        hint: _dest == null
                            ? 'Enter a destination first'
                            : '0',
                        onChanged: (_) => setState(() {}),
                      ),
                    ),
                  ),
                  Field(
                    label: 'Note (optional)',
                    child: PyxInput(
                      child: PyxTextField(
                        controller: _noteCtl,
                        fontSize: 15,
                      ),
                    ),
                  ),
                  Field(
                    label: 'Fee',
                    child: PyxInput(
                      suffix: 'SATS',
                      child: Padding(
                        padding:
                            const EdgeInsets.symmetric(vertical: 17),
                        child: Text(
                          _feeSats != null ? _fmt(_feeSats!) : '—',
                          style: Type.input.copyWith(fontSize: 15),
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 26),
                  SlideToSend(
                    enabled: _ready,
                    onConfirm: _confirmSend,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  static String _initials(String name) => name
      .split(RegExp(r'\s+'))
      .where((w) => w.isNotEmpty)
      .map((w) => w[0])
      .take(2)
      .join()
      .toUpperCase();
}
