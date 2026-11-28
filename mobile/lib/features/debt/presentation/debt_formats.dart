import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/features/debt/domain/plan_view.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/said.dart';
import 'package:budget_owl/shared/year_month.dart';
import 'package:flutter/widgets.dart';
import 'package:intl/intl.dart' show NumberFormat;

/// The reader's formatters, for the debt screens' sentences: every amount through [formatMoney] in
/// its own currency, every month and count through intl.
Formats formatsOf(BuildContext context) {
  final locale = context.localeName;
  final numbers = NumberFormat.decimalPattern(locale);
  return Formats(
    money: (wire, currency) => context.money(Money.fromWire(wire, currency)),
    month: (wire) => monthText(wire, locale),
    rate: (wire) => formatRate(wire, locale),
    date: context.date,
    count: numbers.format,
    message: context.said,
  );
}
