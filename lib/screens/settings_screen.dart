import 'package:flutter/material.dart';
import 'package:conduit/theme/icons.dart';
import 'package:conduit/bridge_generated.dart/client.dart';
import 'package:conduit/bridge_generated.dart/currency.dart';
import 'package:conduit/bridge_generated.dart/factory.dart';
import 'package:conduit/screens/display_recovery_phrase_screen.dart';
import 'package:conduit/screens/select_currency_screen.dart';
import 'package:conduit/theme/tokens.dart';
import 'package:conduit/theme/components/buttons.dart';
import 'package:conduit/theme/components/cards.dart';
import 'package:conduit/utils/auth_utils.dart';
import 'package:conduit/utils/notification_utils.dart';

/// Settings — mirrors prototype.html `settings` (lines 2334-2365): grouped
/// key/value cards (Wallet / Services / About) with chevron rows.
class SettingsScreen extends StatefulWidget {
  final ConduitClient client;
  final ConduitClientFactory clientFactory;
  final VoidCallback onLightningAddress;
  final VoidCallback onContacts;

  const SettingsScreen({
    super.key,
    required this.client,
    required this.clientFactory,
    required this.onLightningAddress,
    required this.onContacts,
  });

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  String? _currencyName;
  int _federationCount = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final code = await widget.clientFactory.getCurrency();
    final federations = await widget.clientFactory.listFederations();
    if (!mounted) return;
    setState(() {
      _currencyName = findFiatCurrency(code: code)?.name;
      _federationCount = federations.length;
    });
  }

  Future<void> _onBackup() async {
    try {
      await requireBiometricAuth(context);
      if (!mounted) return;
      final seedPhrase = await widget.clientFactory.seedPhrase();
      if (!mounted) return;
      Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => DisplayRecoveryPhraseScreen(seedPhrase: seedPhrase),
        ),
      );
    } catch (e) {
      if (mounted) NotificationUtils.showError(context, e.toString());
    }
  }

  Future<void> _onCurrency() async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) =>
            SelectCurrencyScreen(clientFactory: widget.clientFactory),
      ),
    );
    _load();
  }

  void _onWallets() {
    // Federation switcher lives on the root screen for now.
    Navigator.of(context).popUntil((route) => route.isFirst);
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
                Gaps.screenH,
                6,
                Gaps.screenH,
                14,
              ),
              child: Row(
                children: [
                  IconBtn(
                    bare: true,
                    child: Icon(PyxIcons.caretLeft),
                    onTap: () => Navigator.of(context).pop(),
                  ),
                  const SizedBox(width: 12),
                  Text('Settings', style: Type.screenTitle),
                ],
              ),
            ),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.fromLTRB(
                  Gaps.screenH,
                  0,
                  Gaps.screenH,
                  26,
                ),
                children: [
                  const SectionLabel('Wallet',
                      margin: EdgeInsets.only(top: 18, bottom: 10)),
                  _group([
                    _row(
                      'Wallets',
                      '$_federationCount active',
                      _onWallets,
                    ),
                    _row(
                      'Default Currency',
                      _currencyName ?? '',
                      _onCurrency,
                    ),
                    _row(
                      'Backup',
                      'Seed phrase',
                      _onBackup,
                      last: true,
                    ),
                  ]),
                  const SectionLabel('Services',
                      margin: EdgeInsets.only(top: 18, bottom: 10)),
                  _group([
                    _row(
                      'Lightning Address',
                      '',
                      widget.onLightningAddress,
                    ),
                    _row('Contacts', '', widget.onContacts, last: true),
                  ]),
                  const SectionLabel('About',
                      margin: EdgeInsets.only(top: 18, bottom: 10)),
                  _group([
                    _row('Network', 'Bitcoin', null),
                    _row('Version', 'Pyx Wallet 0.6.0', null, last: true),
                  ]),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _group(List<Widget> rows) {
    return PyxCard(
      padding: const EdgeInsets.symmetric(horizontal: Gaps.cardPad, vertical: 2),
      child: Column(children: rows),
    );
  }

  /// Settings row: plain-cased key, faint value, chevron
  /// (prototype settings drow variant, lines 2358-2362).
  Widget _row(
    String title,
    String value,
    VoidCallback? onTap, {
    bool last = false,
  }) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 15),
        decoration: last
            ? null
            : const BoxDecoration(
                border: Border(bottom: BorderSide(color: Palette.border)),
              ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(
              title,
              style: Type.rowTitle.copyWith(fontWeight: FontWeight.w500),
            ),
            Row(
              children: [
                Text(
                  value,
                  style: Type.rowSub.copyWith(fontSize: 13),
                ),
                const SizedBox(width: 8),
                Icon(
                  PyxIcons.caretRight,
                  size: 16,
                  color: Palette.faint,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
