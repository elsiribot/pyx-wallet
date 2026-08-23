import 'dart:async';
import 'package:app_links/app_links.dart';
import 'package:balanced_text/balanced_text.dart';
import 'package:conduit/theme/icons.dart';
import 'package:flutter/material.dart';
import 'package:conduit/bridge_generated.dart/lib.dart';
import 'package:conduit/bridge_generated.dart/client.dart';
import 'package:conduit/bridge_generated.dart/currency.dart';
import 'package:conduit/bridge_generated.dart/factory.dart';
import 'package:conduit/screens/federation_screen.dart';
import 'package:conduit/screens/display_recovery_phrase_screen.dart';
import 'package:conduit/screens/select_currency_screen.dart';
import 'package:conduit/utils/notification_utils.dart';
import 'package:conduit/theme/tokens.dart';
import 'package:conduit/theme/components/buttons.dart';
import 'package:conduit/theme/components/cards.dart';
import 'package:conduit/utils/auth_utils.dart';
import 'package:conduit/drawers/invite_scanner_drawer.dart';
import 'package:conduit/drawers/leave_federation_drawer.dart';
import 'package:conduit/drawers/recovery_drawer.dart';

class BaseScreen extends StatefulWidget {
  final ConduitClientFactory clientFactory;

  const BaseScreen({super.key, required this.clientFactory});

  @override
  State<BaseScreen> createState() => _BaseScreenState();
}

class _BaseScreenState extends State<BaseScreen> {
  List<FederationInfo> _federations = [];
  String? _currencyName;
  StreamSubscription<Uri>? _linkSubscription;

  @override
  void initState() {
    super.initState();

    _refreshFederations(autoNavigate: true);
    _loadCurrency();
    _initInviteLinks();
  }

  /// The manifest declares the `fedimint:` scheme; accept federation invite
  /// codes arriving as deep links (`fedimint:{invite}` or a raw fed1… code).
  void _initInviteLinks() {
    final appLinks = AppLinks();
    _linkSubscription = appLinks.uriLinkStream.listen(_handleInviteLink);
    appLinks.getInitialLink().then((uri) {
      if (uri != null) _handleInviteLink(uri);
    });
  }

  void _handleInviteLink(Uri uri) {
    final raw = uri.toString();
    final code = raw.startsWith('fedimint:')
        ? raw.substring('fedimint:'.length)
        : raw;
    final invite = parseInviteCode(invite: code);
    if (invite == null) return;
    _handleJoinFederation(invite);
  }

  @override
  void dispose() {
    _linkSubscription?.cancel();
    super.dispose();
  }

  Future<void> _loadCurrency() async {
    final code = await widget.clientFactory.getCurrency();

    if (!mounted) return;

    setState(() {
      _currencyName = findFiatCurrency(code: code)?.name;
    });
  }

  Future<void> _refreshFederations({bool autoNavigate = false}) async {
    final federations = await widget.clientFactory.listFederations();

    setState(() {
      _federations = federations;
    });

    if (autoNavigate && federations.length == 1) {
      _handleFederationTap(federations.first);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Topbar: title + add-wallet scan (prototype `wallets`)
          Padding(
            padding:
                const EdgeInsets.fromLTRB(Gaps.screenH, 6, Gaps.screenH, 14),
            child: Row(
              children: [
                const Text('Wallets', style: Type.screenTitle),
                const Spacer(),
                IconBtn(
                  onTap: _showScannerDrawer,
                  child: const Icon(PyxIcons.plus),
                ),
              ],
            ),
          ),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(
                  Gaps.screenH, 0, Gaps.screenH, 26),
              children: [
                if (_federations.isEmpty)
                  _buildOnboarding()
                else ...[
                  Padding(
                    padding: const EdgeInsets.only(top: 6, bottom: 14),
                    child: Text(
                      'Switch between your wallets. Each wallet is held '
                      'by a federation of independent guardians.',
                      style: Type.cardSub.copyWith(fontSize: 13, height: 1.55),
                    ),
                  ),
                  for (final federation in _federations) ...[
                    _buildFederationCard(federation),
                    const SizedBox(height: 10),
                  ],
                ],
                const SectionLabel('Settings',
                    margin: EdgeInsets.only(top: 18, bottom: 10)),
                PyxCard(
                  padding: const EdgeInsets.symmetric(
                      horizontal: Gaps.cardPad, vertical: 2),
                  child: Column(
                    children: [
                      _settingsRow(
                        'Recovery Phrase',
                        'Backup your wallet',
                        _handleSeedPhraseTap,
                      ),
                      _settingsRow(
                        'Default Currency',
                        _currencyName ?? '',
                        _handleCurrencyTap,
                        last: true,
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    ),
  );

  Widget _settingsRow(
    String title,
    String value,
    VoidCallback onTap, {
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
            Text(title,
                style: Type.rowTitle.copyWith(fontWeight: FontWeight.w500)),
            Row(
              children: [
                Text(value, style: Type.rowSub.copyWith(fontSize: 13)),
                const SizedBox(width: 8),
                const Icon(PyxIcons.caretRight,
                    size: 16, color: Palette.faint),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildOnboarding() {
    return Padding(
      padding: const EdgeInsets.only(top: 40, bottom: 8),
      child: Column(
        children: [
          const Opacity(
            opacity: 0.5,
            child:
                Icon(PyxIcons.wallet, size: 64, color: Palette.faint),
          ),
          const SizedBox(height: 20),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: BalancedText(
              'The federation cannot link payments to you or deduce '
              'your balance.',
              textAlign: TextAlign.center,
              style: Type.cardSub.copyWith(fontSize: 13, height: 1.55),
            ),
          ),
          const SizedBox(height: 24),
          PyxButton.primary(
            label: 'Join Federation',
            onTap: _showScannerDrawer,
          ),
        ],
      ),
    );
  }

  void _showScannerDrawer() {
    InviteScannerDrawer.show(
      context,
      clientFactory: widget.clientFactory,
      onJoin: _handleJoinFederation,
      onRecover: _handleRecoverFederation,
    );
  }

  void _navigateToClientScreen(ConduitClient client) {
    if (!mounted) return;

    if (client.hasPendingRecoveries()) {
      RecoveryDrawer.show(
        context,
        client: client,
        clientFactory: widget.clientFactory,
      );
    } else {
      Navigator.of(context).push(
        MaterialPageRoute(
          builder:
              (_) => FederationScreen(
                client: client,
                clientFactory: widget.clientFactory,
              ),
        ),
      );
    }
  }

  Future<void> _handleJoinFederation(InviteCodeWrapper invite) async {
    final client = await widget.clientFactory.join(invite: invite);

    _refreshFederations();

    if (!mounted) return;

    Navigator.of(context).popUntil((route) => route.isFirst);

    _navigateToClientScreen(client);
  }

  Future<void> _handleRecoverFederation(InviteCodeWrapper invite) async {
    final client = await widget.clientFactory.recover(invite: invite);

    _refreshFederations();

    if (!mounted) return;

    Navigator.of(context).popUntil((route) => route.isFirst);

    _navigateToClientScreen(client);
  }

  /// Wallet card (prototype `wallets` card rows, lines 1173-1187).
  Widget _buildFederationCard(FederationInfo federation) {
    final guardians = federation.guardians;

    return GestureDetector(
      onLongPress: () => _showLeaveFederationDrawer(federation),
      child: PyxCard(
        onTap: () => _handleFederationTap(federation),
        child: Row(
          children: [
            Container(
              width: 42,
              height: 42,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: Palette.surface2,
                borderRadius: BorderRadius.circular(12),
              ),
              child: const Icon(PyxIcons.wallet,
                  size: 21, color: Palette.accent),
            ),
            const SizedBox(width: Gaps.rowGap),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(federation.name, style: Type.cardName),
                  const SizedBox(height: 3),
                  Text(
                    '$guardians ${guardians == 1 ? 'Guardian' : 'Guardians'}',
                    style: Type.cardSub,
                  ),
                ],
              ),
            ),
            const Icon(PyxIcons.caretRight,
                size: 16, color: Palette.faint),
          ],
        ),
      ),
    );
  }

  Future<void> _handleFederationTap(FederationInfo federation) async {
    try {
      final client = await widget.clientFactory.load(
        federationId: federation.id,
      );

      if (client == null) {
        if (mounted) {
          NotificationUtils.showError(context, 'Failed to load federation');
        }
        return;
      }

      _navigateToClientScreen(client);
    } catch (e) {
      if (mounted) {
        NotificationUtils.showError(context, e.toString());
      }
    }
  }

  void _showLeaveFederationDrawer(FederationInfo federation) {
    LeaveFederationDrawer.show(
      context,
      federation: federation,
      clientFactory: widget.clientFactory,
      onSuccess: _refreshFederations,
    );
  }

  Future<void> _handleCurrencyTap() async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder:
            (_) => SelectCurrencyScreen(clientFactory: widget.clientFactory),
      ),
    );

    _loadCurrency();
  }

  Future<void> _handleSeedPhraseTap() async {
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
      if (mounted) {
        NotificationUtils.showError(context, e.toString());
      }
    }
  }
}
