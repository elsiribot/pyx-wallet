import 'package:conduit/theme/icons.dart';
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:conduit/theme/tokens.dart';
import 'package:conduit/theme/components/buttons.dart';
import 'package:conduit/theme/components/tx_row.dart';
import 'package:conduit/screens/home_screen_body.dart';
import 'package:conduit/screens/settings_screen.dart';
import 'package:conduit/utils/currency_utils.dart';
import 'package:app_links/app_links.dart';
import 'package:conduit/bridge_generated.dart/client.dart';
import 'package:conduit/bridge_generated.dart/events.dart';
import 'package:conduit/bridge_generated.dart/factory.dart';
import 'package:conduit/bridge_generated.dart/lib.dart';
import 'package:conduit/widgets/settings_card_widget.dart';
import 'package:conduit/screens/receive_screen.dart';
import 'package:conduit/screens/send_screen.dart';
import 'package:conduit/screens/ecash_amount_screen.dart';
import 'package:conduit/screens/onchain_address_screen.dart';
import 'package:conduit/screens/wallet_v2_receive_screen.dart';
import 'package:conduit/drawers/scanner_drawer.dart';
import 'package:conduit/drawers/payment_details_drawer.dart';
import 'package:conduit/screens/connection_status_screen.dart';
import 'package:conduit/bridge_generated.dart/lnurl.dart';
import 'package:conduit/drawers/ecash_drawer.dart';
import 'package:conduit/drawers/lightning_invoice_drawer.dart';
import 'package:conduit/drawers/lnurl_drawer.dart';
import 'package:conduit/screens/onchain_amount_screen.dart';
import 'package:conduit/utils/notification_utils.dart';
import 'package:conduit/screens/display_contacts_screen.dart';
import 'package:conduit/screens/lightning_address_entry_screen.dart';
import 'package:conduit/drawers/invite_drawer.dart';
import 'package:conduit/drawers/recovery_drawer.dart';
import 'package:flutter/services.dart';

class FederationScreen extends StatefulWidget {
  final ConduitClient client;
  final ConduitClientFactory clientFactory;

  const FederationScreen({
    super.key,
    required this.client,
    required this.clientFactory,
  });

  @override
  State<FederationScreen> createState() => _FederationScreenState();
}

class _FederationScreenState extends State<FederationScreen> {
  late final Stream<RecentPaymentsUpdate> _eventStream;
  late final Stream<int> _balanceStream;
  late final Stream<List<(String, bool)>> _connectionStream;
  late final AppLinks _appLinks;
  StreamSubscription<Uri>? _linkSubscription;
  int? _expirationDate;
  InviteCodeWrapper? _expirationSuccessor;
  // Eye toggle over balance + amounts (prototype `maskNum`). Starts masked
  // so balances aren't exposed on open.
  bool _masked = true;
  List<ConduitPayment> _payments = [];
  StreamSubscription<RecentPaymentsUpdate>? _paymentsSubscription;

  @override
  void initState() {
    super.initState();
    _eventStream = widget.client.subscribeEventLog();
    _balanceStream = widget.client.subscribeBalance();
    _connectionStream = widget.client.subscribeConnectionStatus();
    _paymentsSubscription = _eventStream.listen(_onPaymentsUpdate);
    _initDeepLinks();
    _fetchExpirationStatus();
    // Warm the exchange-rate cache so the fiat toggle is reachable and the
    // fiat figure renders from cache without blocking. Repaint once it lands.
    widget.client.prefetchExchangeRates().then((_) {
      if (mounted) setState(() {});
    });
  }

  Future<void> _fetchExpirationStatus() async {
    final date = await widget.client.expirationDate();
    if (date == null || !mounted) return;

    final successor = await widget.client.expirationSuccessor();

    if (!mounted) return;

    setState(() {
      _expirationDate = date;
      _expirationSuccessor = successor;
    });
  }

  void _onPaymentsUpdate(RecentPaymentsUpdate update) {
    if (!mounted) return;
    setState(() => _payments = update.payments);
    if (update.notification case final notification?) {
      HapticFeedback.heavyImpact();
      if (!notification.success) {
        NotificationUtils.showError(
          context,
          notification.incoming
              ? 'Failed to receive payment'
              : 'Failed to send payment',
        );
      }
    }
  }

  @override
  void dispose() {
    _paymentsSubscription?.cancel();
    _linkSubscription?.cancel();
    widget.client.shutdown();
    super.dispose();
  }

  void _initDeepLinks() {
    _appLinks = AppLinks();

    _linkSubscription = _appLinks.uriLinkStream.listen(_handleDeepLink);

    _appLinks.getInitialLink().then((uri) {
      if (uri != null) _handleDeepLink(uri);
    });
  }

  void _handleDeepLink(Uri uri) {
    final input = uri.toString();

    final parsers = [
      (
        parseBolt11Invoice(invoice: input),
        (dynamic result) => LightningInvoiceDrawer.show(
          context,
          client: widget.client,
          invoice: result,
        ),
      ),
      (
        parseEcash(notes: input),
        (dynamic result) =>
            EcashDrawer.show(context, client: widget.client, notes: result),
      ),
      (
        parseBitcoinAddress(address: input),
        (dynamic result) => Navigator.of(context).push(
          MaterialPageRoute(
            builder:
                (_) =>
                    OnchainAmountScreen(client: widget.client, address: result),
          ),
        ),
      ),
      (
        parseLnurl(request: input),
        (dynamic result) => LnurlDrawer.show(
          context,
          client: widget.client,
          clientFactory: widget.clientFactory,
          lnurl: result,
        ),
      ),
    ];

    for (final (result, showDrawer) in parsers) {
      if (result != null) {
        showDrawer(result);
        return;
      }
    }
  }

  void _onCreateInvoice() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => ReceiveScreen(
          client: widget.client,
          clientFactory: widget.clientFactory,
        ),
      ),
    );
  }

  void _onSend() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => SendScreen(
          client: widget.client,
          clientFactory: widget.clientFactory,
        ),
      ),
    );
  }

  void _onSendEcash() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => EcashAmountScreen(client: widget.client),
      ),
    );
  }

  void _onReceiveBitcoin() async {
    try {
      final v2Address = await widget.client.walletV2Receive();

      if (!mounted) return;

      if (v2Address != null) {
        Navigator.of(context).push(
          MaterialPageRoute(
            builder:
                (_) => WalletV2ReceiveScreen(
                  address: v2Address,
                  client: widget.client,
                ),
          ),
        );
      } else {
        // Addresses already sorted ascending by Rust (oldest first, newest last)
        final addressesList = await widget.client.onchainListAddresses();

        if (!mounted) return;

        Navigator.of(context).push(
          MaterialPageRoute(
            builder:
                (context) => OnchainAddressScreen(
                  client: widget.client,
                  addressesList: addressesList,
                ),
          ),
        );
      }
    } catch (e) {
      if (!mounted) return;
      NotificationUtils.showError(context, 'Failed to load address');
    }
  }

  void _onLightningAddress() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder:
            (_) => LightningAddressEntryScreen(
              client: widget.client,
              clientFactory: widget.clientFactory,
            ),
      ),
    );
  }

  void _onContacts() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder:
            (_) => DisplayContactsScreen(
              client: widget.client,
              clientFactory: widget.clientFactory,
            ),
      ),
    );
  }

  Widget _buildExpiryCard(int date) {
    final formatted = DateFormat.MMMMd().format(
      DateTime.fromMillisecondsSinceEpoch(date * 1000),
    );
    final successor = _expirationSuccessor;

    return SettingsCard(
      icon: PyxIcons.moon,
      iconColor: Colors.amber,
      title: 'Expires on $formatted',
      subtitle:
          successor != null ? 'Tap to join successor' : 'Migrate your funds',
      onTap:
          successor != null
              ? () => InviteDrawer.show(
                context,
                invite: successor,
                onJoin: _joinSuccessor,
                onRecover: _recoverSuccessor,
              )
              : null,
    );
  }

  Future<void> _joinSuccessor(InviteCodeWrapper invite) async {
    final client = await widget.clientFactory.join(invite: invite);

    if (!mounted) return;

    _openSuccessor(client);
  }

  Future<void> _recoverSuccessor(InviteCodeWrapper invite) async {
    final client = await widget.clientFactory.recover(invite: invite);

    if (!mounted) return;

    _openSuccessor(client);
  }

  /// Closes the invite drawer and swaps the current federation screen for the
  /// successor's, routing through the recovery drawer when it has recoveries.
  void _openSuccessor(ConduitClient client) {
    Navigator.of(context).pop();

    if (client.hasPendingRecoveries()) {
      RecoveryDrawer.show(
        context,
        client: client,
        clientFactory: widget.clientFactory,
      );
    } else {
      Navigator.of(context).pushReplacement(
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

  void _onScan() {
    ScannerDrawer.show(
      context,
      client: widget.client,
      clientFactory: widget.clientFactory,
    );
  }

  void _showEventDetails(ConduitPayment event) {
    PaymentDetailsDrawer.show(context, event: event);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            // Topbar: settings gear left; eye toggle + federation status
            // ring right (prototype `home` topbar, lines 989-994).
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
                    onTap: _onSettings,
                    child: Icon(PyxIcons.gearSix),
                  ),
                  const Spacer(),
                  IconBtn(
                    child: Icon(
                      _masked ? PyxIcons.eyeSlash : PyxIcons.eye,
                    ),
                    onTap: () => setState(() => _masked = !_masked),
                  ),
                  const SizedBox(width: 10),
                  StreamBuilder<List<(String, bool)>>(
                    stream: _connectionStream,
                    builder: (context, snapshot) {
                      final statuses = snapshot.data ?? const [];
                      final connected =
                          statuses.where((s) => s.$2).length;
                      return Stack(
                        clipBehavior: Clip.none,
                        children: [
                          IconBtn(
                            child: Icon(PyxIcons.usersThree),
                            onTap: () => Navigator.of(context).push(
                              MaterialPageRoute(
                                builder: (_) => ConnectionStatusScreen(
                                  client: widget.client,
                                ),
                              ),
                            ),
                          ),
                          if (statuses.isNotEmpty)
                            Positioned.fill(
                              child: GuardianRing(
                                online: connected,
                                total: statuses.length,
                              ),
                            ),
                        ],
                      );
                    },
                  ),
                ],
              ),
            ),
            if (_expirationDate case final date?)
              Padding(
                padding:
                    const EdgeInsets.symmetric(horizontal: Gaps.screenH),
                child: _buildExpiryCard(date),
              ),
            Expanded(
              child: StreamBuilder<int>(
                stream: _balanceStream,
                builder: (context, snapshot) {
                  final sats = snapshot.data ?? 0;
                  final fiat = cachedFiatAmount(widget.client, sats);
                  return HomeBody(
                    amount: NumberFormat('#,###')
                        .format(sats)
                        .replaceAll(',', ' '),
                    // Prototype writes "≈ €3.45" — symbol tight to the number
                    fiat: fiat == null
                        ? null
                        : '≈ ${fiat.amount.replaceFirst(' ', '')}',
                    masked: _masked,
                    payments: _payments,
                    onReceive: _onCreateInvoice,
                    onSend: _onSend,
                    onScan: _onScan,
                    onPaymentTap: _showEventDetails,
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _onSettings() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => SettingsScreen(
          client: widget.client,
          clientFactory: widget.clientFactory,
          onLightningAddress: _onLightningAddress,
          onContacts: _onContacts,
        ),
      ),
    );
  }
}

