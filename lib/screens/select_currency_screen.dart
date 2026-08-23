import 'package:flutter/material.dart';
import 'package:conduit/bridge_generated.dart/factory.dart';
import 'package:conduit/bridge_generated.dart/currency.dart';
import 'package:conduit/theme/components/buttons.dart';
import 'package:conduit/theme/icons.dart';
import 'package:conduit/theme/tokens.dart';

/// Display-currency picker — mirrors prototype.html currency picker
/// (lines 1533-1597): search field, code + name rows, accent check on the
/// active currency.
class SelectCurrencyScreen extends StatefulWidget {
  final ConduitClientFactory clientFactory;

  const SelectCurrencyScreen({super.key, required this.clientFactory});

  @override
  State<SelectCurrencyScreen> createState() => _SelectCurrencyScreenState();
}

class _SelectCurrencyScreenState extends State<SelectCurrencyScreen> {
  String _query = '';
  String? _selected;

  @override
  void initState() {
    super.initState();
    widget.clientFactory.getCurrency().then((code) {
      if (mounted) setState(() => _selected = code);
    });
  }

  List<FiatCurrency> get _filtered => listFiatCurrencies()
      .where(
        (c) =>
            c.code.toLowerCase().contains(_query.toLowerCase()) ||
            c.name.toLowerCase().contains(_query.toLowerCase()),
      )
      .toList();

  @override
  Widget build(BuildContext context) {
    final currencies = _filtered;
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
                  const Text('Currency', style: Type.screenTitle),
                ],
              ),
            ),
            // Search (prototype .cur-search-wrap)
            Padding(
              padding: const EdgeInsets.fromLTRB(
                  Gaps.screenH, 0, Gaps.screenH, 10),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14),
                decoration: BoxDecoration(
                  color: Palette.surface2,
                  border: Border.all(color: Palette.border),
                  borderRadius: BorderRadius.circular(Radii.input),
                ),
                child: Row(
                  children: [
                    const Icon(PyxIcons.search,
                        size: 18, color: Palette.faint),
                    const SizedBox(width: 10),
                    Expanded(
                      child: TextField(
                        onChanged: (v) => setState(() => _query = v),
                        style: Type.body.copyWith(fontSize: 15),
                        cursorColor: Palette.accent,
                        decoration: InputDecoration(
                          isDense: true,
                          border: InputBorder.none,
                          hintText: 'Search currencies',
                          hintStyle: Type.body.copyWith(
                              fontSize: 15, color: Palette.faint),
                          contentPadding:
                              const EdgeInsets.symmetric(vertical: 13),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            Expanded(
              child: currencies.isEmpty
                  ? Center(
                      child: Text('No matches',
                          style: Type.helper.copyWith(fontSize: 13.5)),
                    )
                  : ListView.builder(
                      padding: const EdgeInsets.fromLTRB(14, 0, 14, 26),
                      itemCount: currencies.length,
                      itemBuilder: (context, i) {
                        final c = currencies[i];
                        final on = c.code == _selected;
                        return InkWell(
                          onTap: () async {
                            await widget.clientFactory
                                .setCurrency(currencyCode: c.code);
                            if (context.mounted) {
                              Navigator.of(context).pop();
                            }
                          },
                          borderRadius:
                              BorderRadius.circular(Radii.icon),
                          child: Container(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 8, vertical: 11),
                            decoration: on
                                ? BoxDecoration(
                                    color: Palette.surface2,
                                    borderRadius: BorderRadius.circular(
                                        Radii.icon),
                                  )
                                : null,
                            child: Row(
                              children: [
                                SizedBox(
                                  width: 34,
                                  child: Text(
                                    c.symbol,
                                    textAlign: TextAlign.center,
                                    style: const TextStyle(
                                      fontFamily: Fonts.display,
                                      fontWeight: FontWeight.w700,
                                      fontSize: 16,
                                      color: Palette.muted,
                                    ),
                                  ),
                                ),
                                const SizedBox(width: 13),
                                SizedBox(
                                  width: 50,
                                  child: Text(
                                    c.code,
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
                                    c.name,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: Type.rowSub.copyWith(
                                        fontSize: 13.5,
                                        color: Palette.muted),
                                  ),
                                ),
                                if (on)
                                  const Icon(PyxIcons.check,
                                      size: 18, color: Palette.accent),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
    );
  }
}
