import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:conduit/bridge_generated.dart/client.dart';
import 'package:conduit/theme/components/buttons.dart';
import 'package:conduit/theme/components/cards.dart';
import 'package:conduit/theme/components/controls.dart';
import 'package:conduit/theme/components/sheet.dart';
import 'package:conduit/theme/icons.dart';
import 'package:conduit/theme/tokens.dart';

/// Federation details — mirrors prototype.html `fed-details`
/// (lines 2101-2160): centered provider avatar, name/guardians card,
/// module pills, guardian list with per-guardian sheet.
class ConnectionStatusScreen extends StatelessWidget {
  final ConduitClient client;

  const ConnectionStatusScreen({super.key, required this.client});

  /// BFT quorum: n guardians tolerate f = (n-1) ~/ 3 faults.
  static int _quorum(int n) => n - (n - 1) ~/ 3;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: FutureBuilder<String?>(
          future: client.federationName(),
          builder: (context, nameSnapshot) {
            final name = nameSnapshot.data ?? 'Federation';
            return StreamBuilder<List<(String, bool)>>(
              stream: client.subscribeConnectionStatus(),
              builder: (context, snapshot) {
                final statuses = snapshot.data ?? const <(String, bool)>[];
                final online = statuses.where((s) => s.$2).length;
                final total = statuses.length;
                final statusColor = total == 0
                    ? Palette.muted
                    : online >= total
                        ? Palette.green
                        : online >= _quorum(total)
                            ? Palette.amber
                            : Palette.red;
                return Column(
                  children: [
                    // Centered topbar (prototype fed-details is centered)
                    Padding(
                      padding: const EdgeInsets.fromLTRB(
                          Gaps.screenH, 6, Gaps.screenH, 14),
                      child: Stack(
                        alignment: Alignment.center,
                        children: [
                          const Center(
                            child: Text('Wallet Provider',
                                style: Type.titleCentered),
                          ),
                          Align(
                            alignment: Alignment.centerLeft,
                            child: IconBtn(
                              bare: true,
                              onTap: () => Navigator.of(context).pop(),
                              child: const Icon(PyxIcons.caretLeft),
                            ),
                          ),
                        ],
                      ),
                    ),
                    Expanded(
                      child: ListView(
                        padding: const EdgeInsets.fromLTRB(
                            Gaps.screenH, 0, Gaps.screenH, 26),
                        children: [
                          // Big provider avatar
                          Padding(
                            padding:
                                const EdgeInsets.only(top: 6, bottom: 24),
                            child: Center(
                              child: Container(
                                width: 122,
                                height: 122,
                                alignment: Alignment.center,
                                decoration: BoxDecoration(
                                  shape: BoxShape.circle,
                                  gradient: RadialGradient(
                                    center: const Alignment(-0.3, -0.4),
                                    colors: [
                                      Color.lerp(Palette.teal,
                                          Colors.white, 0.2)!,
                                      Color.lerp(
                                          Palette.teal, Palette.bg, 0.5)!,
                                    ],
                                  ),
                                  border:
                                      Border.all(color: Palette.border),
                                ),
                                child: Text(
                                  name.isEmpty
                                      ? '?'
                                      : name[0].toUpperCase(),
                                  style: const TextStyle(
                                    fontFamily: Fonts.display,
                                    fontWeight: FontWeight.w700,
                                    fontSize: 54,
                                    color: Colors.white,
                                  ),
                                ),
                              ),
                            ),
                          ),
                          PyxCard(
                            padding: const EdgeInsets.symmetric(
                                horizontal: Gaps.cardPad, vertical: 4),
                            child: Column(
                              children: [
                                DRow.text(k: 'Name', value: name),
                                DRow(
                                  k: 'Guardians',
                                  showDivider: false,
                                  v: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.end,
                                    children: [
                                      Text(
                                        '$online of $total online',
                                        style: Type.drowValue.copyWith(
                                            color: statusColor),
                                      ),
                                      Text(
                                        'Quorum requires ${_quorum(total)} of $total',
                                        style: Type.rowSub
                                            .copyWith(fontSize: 12),
                                      ),
                                    ],
                                  ),
                                ),
                              ],
                            ),
                          ),
                          const SectionLabel('Modules',
                              margin:
                                  EdgeInsets.only(top: 24, bottom: 12)),
                          Wrap(
                            spacing: 9,
                            runSpacing: 9,
                            children: const [
                              _ModPill(
                                  icon: PyxIcons.lightning,
                                  label: 'Lightning'),
                              _ModPill(
                                  icon: PyxIcons.coinVertical,
                                  label: 'Ecash'),
                              _ModPill(
                                  icon: PyxIcons.link, label: 'On-chain'),
                            ],
                          ),
                          SectionLabel(
                            'Guardians',
                            margin:
                                const EdgeInsets.only(top: 26, bottom: 12),
                            trailing: Text(
                              '$online / $total online',
                              style:
                                  Type.rowSub.copyWith(fontSize: 12),
                            ),
                          ),
                          for (var i = 0; i < statuses.length; i++) ...[
                            _GuardianCard(
                              name: statuses[i].$1,
                              online: statuses[i].$2,
                              colorSeed: i,
                              onTap: () => _openGuardian(
                                  context, statuses[i].$1, statuses[i].$2),
                            ),
                            const SizedBox(height: 9),
                          ],
                          FutureBuilder<FederationStats?>(
                            future: client.federationStats(),
                            builder: (context, stats) {
                              final s = stats.data;
                              if (s == null) {
                                return const SizedBox.shrink();
                              }
                              return Column(
                                crossAxisAlignment:
                                    CrossAxisAlignment.stretch,
                                children: [
                                  const SectionLabel('Federation',
                                      margin: EdgeInsets.only(
                                          top: 24, bottom: 12)),
                                  PyxCard(
                                    padding: const EdgeInsets.symmetric(
                                        horizontal: Gaps.cardPad,
                                        vertical: 4),
                                    child: Column(
                                      children: [
                                        DRow.text(
                                          k: 'Total value',
                                          value:
                                              '${NumberFormat('#,###').format(s.totalValueSat).replaceAll(',', ' ')} SATS',
                                        ),
                                        DRow.text(
                                          k: 'Block height',
                                          value: NumberFormat('#,###')
                                              .format(s.blockCount),
                                          showDivider: s.feerate != null,
                                        ),
                                        if (s.feerate case final f?)
                                          DRow.text(
                                            k: 'Feerate',
                                            value: '$f sat/vB',
                                            showDivider: false,
                                          ),
                                      ],
                                    ),
                                  ),
                                ],
                              );
                            },
                          ),
                        ],
                      ),
                    ),
                  ],
                );
              },
            );
          },
        ),
      ),
    );
  }

  /// Per-guardian bottom sheet (prototype `openGuardian`, lines 2159-2184).
  void _openGuardian(BuildContext context, String name, bool online) {
    showPyxSheet(
      context,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: Text(name, style: Type.sheetTitle),
          ),
          const SizedBox(height: 6),
          Center(
            child: PyxBadge(
              online ? 'Online' : 'Offline',
              color: online ? Palette.green : Palette.burnt,
              leading:
                  StatusDot(online ? StatusKind.on : StatusKind.off),
            ),
          ),
          const SizedBox(height: 12),
        ],
      ),
    );
  }
}

/// Module pill (prototype `.mod-pill`, lines 486-487).
class _ModPill extends StatelessWidget {
  final IconData icon;
  final String label;

  const _ModPill({required this.icon, required this.label});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.only(left: 11, right: 13, top: 9, bottom: 9),
      decoration: BoxDecoration(
        color: Palette.surface,
        border: Border.all(color: Palette.border),
        borderRadius: BorderRadius.circular(Radii.modPill),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 16, color: Palette.accent),
          const SizedBox(width: 8),
          Text(label, style: Type.body.copyWith(fontSize: 13.5, height: 1)),
        ],
      ),
    );
  }
}

class _GuardianCard extends StatelessWidget {
  final String name;
  final bool online;
  final int colorSeed;
  final VoidCallback onTap;

  const _GuardianCard({
    required this.name,
    required this.online,
    required this.colorSeed,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final color = Palette
        .guardianPalette[colorSeed % Palette.guardianPalette.length];
    return PyxCard(
      onTap: onTap,
      padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 13),
      child: Row(
        children: [
          AvatarCircle(size: 38, label: name.isEmpty ? '?' : name[0], color: color),
          const SizedBox(width: Gaps.rowGap),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(name, style: Type.cardName),
                const SizedBox(height: 3),
                Text(
                  online ? 'Online' : 'Offline',
                  style: Type.cardSub.copyWith(
                    color: online ? Palette.green : Palette.burnt,
                  ),
                ),
              ],
            ),
          ),
          Container(
            width: 9,
            height: 9,
            decoration: BoxDecoration(
              color: online ? Palette.green : Palette.burnt,
              shape: BoxShape.circle,
            ),
          ),
          const SizedBox(width: 4),
          const Icon(PyxIcons.caretRight, size: 16, color: Palette.faint),
        ],
      ),
    );
  }
}
