import 'package:budget_owl/features/debt/domain/debt.dart';
import 'package:budget_owl/features/debt/domain/plan_view.dart';
import 'package:budget_owl/features/debt/domain/requests.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/said.dart';

/// The debt detail page's view logic (debt-plan.md, `/debt-plan/{id}`), as plain functions.

/// One cell of the solver panel, and **which of three kinds of figure** it is: entered (no tag),
/// `SOLVED`, or *from your plan*. A cell with no figure has no source and no tag — there is nothing
/// to have been worked out.
final class TermCell {
  const TermCell({required this.labelKey, required this.value, this.source, this.missingKey});

  final String labelKey;
  final String value;
  final TermSource? source;
  final String? missingKey;
}

/// The eight cells, in the web's order. The count above them is derived from these.
List<TermCell> termsOf(Debt debt, Formats f) {
  String money(String? wire) => wire == null ? '' : f.money(wire, debt.currency);
  String noHorizon() =>
      debt.projectionReason == 'never-clears' ? 'debt.term.neverClears' : 'debt.term.noValue';
  return [
    TermCell(
      labelKey: 'debt.term.originalBalance',
      value: money(debt.originalBalance),
      source: debt.originalBalance == null ? null : TermSource.entered,
      missingKey: debt.originalBalance == null ? 'debt.term.noOriginal' : null,
    ),
    // Never solved, ever: the one number a household can read off a statement.
    TermCell(labelKey: 'debt.term.balance', value: money(debt.balance), source: TermSource.entered),
    TermCell(
      labelKey: 'debt.term.apr',
      value: debt.apr == null ? '' : '${f.rate(debt.apr)}%',
      source: debt.apr == null ? null : debt.aprSource,
      missingKey: debt.apr == null ? 'debt.rate.unavailable' : null,
    ),
    // Always entered: the floor the plan allocates, never part of this debt's own projection.
    TermCell(
      labelKey: 'debt.term.minimum',
      value: money(debt.minimumPayment),
      source: TermSource.entered,
    ),
    TermCell(
      labelKey: 'debt.term.payment',
      value: money(debt.payment),
      source: debt.payment == null ? null : debt.paymentSource,
      missingKey: debt.payment == null ? 'debt.term.noPayment' : null,
    ),
    TermCell(
      labelKey: 'debt.term.monthsLeft',
      value: debt.monthsLeft == null ? '' : f.count(debt.monthsLeft!),
      source: debt.monthsLeft == null ? null : debt.horizonSource,
      missingKey: debt.monthsLeft == null ? noHorizon() : null,
    ),
    TermCell(
      labelKey: 'debt.term.payoffMonth',
      value: debt.payoffMonth == null ? '' : f.month(debt.payoffMonth),
      source: debt.payoffMonth == null ? null : debt.horizonSource,
      missingKey: debt.payoffMonth == null ? noHorizon() : null,
    ),
    // Always derived: a sum over the projection. Its own words when there is none — "never, at
    // this payment" answers when, and this cell asks how much.
    TermCell(
      labelKey: 'debt.term.totalInterest',
      value: money(debt.totalInterestLeft),
      source: debt.totalInterestLeft == null ? null : TermSource.solved,
      missingKey: debt.totalInterestLeft == null
          ? (debt.projectionReason == 'never-clears'
                ? 'debt.term.noInterestTotal'
                : 'debt.term.noValue')
          : null,
    ),
  ];
}

/// "4 entered · 3 solved.", counted from the cells rather than asserted.
Said termCountOf(List<TermCell> cells, Formats f) => Said('debt.terms.count', {
  'entered': f.count(cells.where((c) => c.source == TermSource.entered).length),
  'solved': f.count(cells.where((c) => c.source == TermSource.solved).length),
});

/// Where the balance came from, named. Stale is read from the flag first.
Said balanceLineOf(Debt debt, Formats f) {
  final anchor = f.money(debt.anchorBalance, debt.currency);
  final date = f.date(debt.anchorDate);
  if (debt.balanceStale) {
    return Said('debt.balance.staleLine', {'amount': anchor, 'date': date});
  }
  if (debt.balanceSource == 'ESTIMATED') {
    return Said('debt.balance.estimatedLine', {
      'months': f.count(debt.estimatedMonths),
      'amount': anchor,
      'date': date,
    });
  }
  return Said('debt.balance.confirmedLine', {
    'source': f.message(
      Said(balanceSourceKey(debt.balanceSource), {'date': f.date(debt.balanceAsOf)}),
    ),
    'amount': anchor,
    'date': date,
  });
}

/// The short word beside the headline balance. **No two sources read the same.**
String balanceLabelKey(Debt debt) {
  if (debt.balanceStale) return 'debt.balance.staleLabel';
  return debt.balanceSource == 'ESTIMATED' ? 'debt.balance.estimatedLabel' : 'debt.balance.owedNow';
}

/// "Paying $260.00 extra is buying you 5 months: gone by December 2027 instead of May 2028."
///
/// **Months only**: the designs also name a money saving, which would be a subtraction of two
/// interest totals on the phone. Both ends are read off the series the same way, and for the line
/// being paid that reproduces `payoffMonth` — which is what makes it a reading, not a guess.
Said? buyingOf(DebtDetail detail, Formats f) {
  final debt = detail.debt;
  if (detail.atMinimumIsTheSameLine) return null;
  final extra = debt.planExtra;
  final paceEnd = debt.payoffMonth ?? clearedMonth(detail.pace);
  final minimumEnd = clearedMonth(detail.atMinimum);
  final sooner = monthsBetween(paceEnd, minimumEnd);
  if (extra == null || sooner == null || sooner <= 0) return null;
  if ((signOf(extra) ?? 0) <= 0) return null;
  return Said('debt.chart.buying', {
    'extra': f.money(extra, debt.currency),
    'months': f.count(sooner),
    'soon': f.month(paceEnd),
    'late': f.month(minimumEnd),
  });
}

/// "6 you logged · 1 from a statement", counted from the rows on screen.
Said historyCountOf(List<DebtPayment> payments, Formats f) => Said('debt.history.counts', {
  'logged': f.count(payments.where((p) => p.provenance == 'USER_LOGGED').length),
  'corrections': f.count(payments.where((p) => p.provenance == 'STATEMENT_CORRECTION').length),
});
