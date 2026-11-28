import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/features/debt/domain/debt.dart';
import 'package:budget_owl/features/debt/domain/requests.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/said.dart';

/// Which sentence each figure on the debt screens gets — the web pages' view logic, as plain
/// functions so every wording rule has a unit test.
///
/// Four backend facts get their own words rather than an ordinary rendering: a **negative extra**
/// (*"all that is left to clear it"*), a rate that is **not derivable** (*"Rate not available"*,
/// never 0%), a **stale** balance (asks for a statement), and **`never-clears`** (a reason, never a
/// blank). Nothing here computes a figure: the server's strings are formatted and placed.

/// How a figure is put into a sentence: the screen passes its locale's formatters in.
final class Formats {
  const Formats({
    required this.money,
    required this.month,
    required this.rate,
    required this.date,
    required this.count,
    required this.message,
  });

  final String Function(String wire, String currency) money;
  final String Function(String? wire) month;
  final String Function(String? wire) rate;
  final String Function(CalendarDate date) date;
  final String Function(int count) count;

  /// A message by its web key, for a sentence that nests another (a reason inside a headline).
  final String Function(Said said) message;
}

/// "{kind} · {servicer}", or the kind alone.
Said namingOf(Debt debt, Formats f) => debt.servicer == null || debt.servicer!.isEmpty
    ? Said(debtKindKey(debt.kind))
    : Said('debt.naming', {
        'kind': f.message(Said(debtKindKey(debt.kind))),
        'servicer': debt.servicer!,
      });

/// A rate that was neither entered nor derived says so. **Never 0%.**
Said rateOf(String? apr, {required bool derivable, required Formats f}) {
  if (apr != null) return Said('debt.rate.value', {'rate': f.rate(apr)});
  return Said(derivable ? 'debt.rate.notEntered' : 'debt.rate.unavailable');
}

/// When a debt clears, or why it does not — a fact about the terms, not an error.
Said clearsOf(String? payoffMonth, String? reason, Formats f) {
  if (payoffMonth != null) return Said('debt.clears.on', {'month': f.month(payoffMonth)});
  return switch (reason) {
    'never-clears' => const Said('debt.clears.never'),
    'plan-below-minimums' => const Said('debt.clears.noPlan'),
    _ => const Said('debt.clears.unknown'),
  };
}

/// What a rung is paying, in the allocation rule's own words. A negative extra is a debt that owes
/// less than its minimum: `minimum + extra` is still exactly what is allocated, so that figure is
/// said, and "extra −$65" never is.
Said payingOf(Rung rung, String currency, Formats f) {
  final sign = signOf(rung.extra);
  if (sign != null && sign < 0) {
    return Said('debt.paying.clearsIt', {'amount': f.money(rung.monthlyAmount, currency)});
  }
  if (sign == 0) {
    return Said(rung.rung == 1 ? 'debt.paying.minimumOnly' : 'debt.paying.minimumUntil', {
      'amount': f.money(rung.minimum, currency),
    });
  }
  return Said('debt.paying.minimumPlusExtra', {
    'minimum': f.money(rung.minimum, currency),
    'extra': f.money(rung.extra, currency),
  });
}

/// "$4,470.00 paid off · 42% of $10,650.00" — **absent** without an original balance.
Said? progressOf(Debt debt, Formats f) {
  if (debt.progressPercent == null || debt.paidOffSoFar == null || debt.originalBalance == null) {
    return null;
  }
  return Said('debt.progress', {
    'paid': f.money(debt.paidOffSoFar!, debt.currency),
    'percent': f.rate(debt.progressPercent),
    'original': f.money(debt.originalBalance!, debt.currency),
  });
}

/// What kind of figure the balance is. **No two sources read the same**; a stale estimate is read
/// from the flag first, because past the cap the source comes back `CONFIRMED`.
Said balanceNoteOf(Debt debt, Formats f) {
  if (debt.balanceStale) {
    return Said('debt.balance.stale', {'date': f.date(debt.anchorDate)});
  }
  if (debt.balanceSource == 'ESTIMATED') {
    return Said('debt.balance.estimated', {'months': f.count(debt.estimatedMonths)});
  }
  return Said(balanceSourceKey(debt.balanceSource), {'date': f.date(debt.balanceAsOf)});
}

/// "$26,480.00 to clear · debt-free April 2029 on this plan", or why there is no date.
Said headlineOf(CurrencyGroup group, Formats f) {
  final owed = f.money(group.owed, group.currency);
  final month = group.selected.debtFreeMonth;
  if (month == null) {
    return Said('debt.headline.noDate', {
      'owed': owed,
      'reason': f.message(Said(projectionReasonKey(group.selected.reason))),
    });
  }
  return Said('debt.headline', {'owed': owed, 'month': f.month(month)});
}

/// "$905.00 a month committed · $260.00 of it strategic".
Said headerOf(Plan plan, CurrencyGroup group, Formats f) => Said('debt.header', {
  'committed': f.money(plan.monthlyAmount, plan.currency),
  'strategic': f.money(group.surplus, group.currency),
});

/// One comparison card's figures.
final class StrategyCard {
  const StrategyCard({
    required this.strategy,
    required this.rationaleKey,
    required this.selected,
    required this.debtFree,
    this.firstWin,
    this.totalInterest,
  });

  final Strategy strategy;
  final String rationaleKey;
  final bool selected;
  final Said debtFree;
  final Said? firstWin;
  final String? totalInterest;
}

/// The two cards — **always avalanche and snowball**, whichever is selected.
List<StrategyCard> cardsOf(Plan plan, CurrencyGroup group, Formats f) => [
  for (final (strategy, projection) in [
    (Strategy.avalanche, group.avalanche),
    (Strategy.snowball, group.snowball),
  ])
    StrategyCard(
      strategy: strategy,
      rationaleKey: 'debt.rationale.${strategy.wire}',
      selected: plan.strategy == strategy.wire,
      debtFree: projection.debtFreeMonth == null
          ? Said('debt.card.noDate', {
              'reason': f.message(Said(projectionReasonKey(projection.reason))),
            })
          : Said('debt.card.debtFree', {'month': f.month(projection.debtFreeMonth)}),
      firstWin: projection.firstWinMonth == null
          ? null
          : Said('debt.card.firstWin', {'month': f.month(projection.firstWinMonth)}),
      totalInterest: projection.totalInterest,
    ),
];

/// What the household's **own** order costs — the two cards never say it. Omitted under the named
/// strategies, where the matching card already does.
Said? selectedSaid(Plan plan, CurrencyGroup group, Formats f) {
  if (plan.strategy != Strategy.custom.wire) return null;
  final month = group.selected.debtFreeMonth;
  final interest = group.selected.totalInterest;
  if (month == null || interest == null) {
    return Said('debt.yourOrder.noDate', {
      'reason': f.message(Said(projectionReasonKey(group.selected.reason))),
    });
  }
  return Said('debt.yourOrder', {
    'month': f.month(month),
    'amount': f.money(interest, group.currency),
  });
}

/// "Avalanche costs less in interest. Snowball clears your first debt 3 months sooner. Either is a
/// good answer." It states which is cheaper and invents **no difference figure**: comparing two
/// totals is exact, subtracting them would be arithmetic on money.
Said? comparisonOf(CurrencyGroup group, Formats f) {
  final avalanche = group.avalanche;
  final snowball = group.snowball;
  if (avalanche.totalInterest == null || snowball.totalInterest == null) return null;
  final cheaper = compareWire(avalanche.totalInterest!, snowball.totalInterest!);
  if (cheaper == null) return null;
  final sooner = monthsBetween(snowball.firstWinMonth, avalanche.firstWinMonth);
  if (cheaper == 0 && (sooner == null || sooner == 0)) return const Said('debt.comparison.same');
  final params = {
    'cheaper': f.message(Said((cheaper <= 0 ? Strategy.avalanche : Strategy.snowball).messageKey)),
    'sooner': f.message(Said(Strategy.snowball.messageKey)),
    'months': f.count(sooner == null ? 0 : sooner.abs()),
  };
  if (sooner == null || sooner <= 0) return Said('debt.comparison.cheaperOnly', params);
  return Said('debt.comparison.both', params);
}

/// The what-if's step, a round figure in the household's own money, never read back.
const String whatIfStep = '50';

/// "Adding $50.00 a month would make you debt-free February 2029 — 2 months sooner." Every figure
/// is the server's: the date from a projection that stored nothing.
Said? whatIfSaid(Plan plan, Plan? alternative, Formats f) {
  if (alternative == null) return null;
  final now = plan.planGroup?.selected.debtFreeMonth;
  final then = alternative.planGroup?.selected.debtFreeMonth;
  final sooner = monthsBetween(then, now);
  if (then == null || sooner == null) return null;
  final params = {
    'step': f.money(whatIfStep, plan.currency),
    'month': f.month(then),
    'months': f.count(sooner),
  };
  // Past a point the ladder is bounded by the smallest debt's own arithmetic, and "0 months sooner"
  // claimed as a win would be a sales pitch.
  if (sooner <= 0) return Said('debt.whatIf.noChange', params);
  return Said(sooner == 1 ? 'debt.whatIf.oneMonth' : 'debt.whatIf.months', params);
}

/// The query amount the what-if asks about, or null when the page asks nothing: no debt, a plan
/// below its minimums, or a figure that is not an amount.
String? whatIfAmount(Plan plan) {
  final group = plan.planGroup;
  if (group == null || plan.debtCount == 0 || group.belowMinimums) return null;
  return addForQuery(plan.monthlyAmount, whatIfStep);
}

/// One debt as the ladder shows it. **The dates come from the rung, not the debt**: a debt's own
/// `payoffMonth` is its projection alone at its minimum — the comparison, which belongs on its own
/// page under a label that says so.
final class LadderRow {
  const LadderRow({
    required this.debt,
    required this.naming,
    required this.rate,
    required this.rateSolved,
    required this.clears,
    required this.paying,
    required this.balanceNote,
    required this.canMoveUp,
    required this.canMoveDown,
    this.progress,
  });

  final Debt debt;
  final Said naming;
  final Said rate;

  /// Only beside a rate there is: "Rate not available — Solved" would be a contradiction.
  final bool rateSolved;
  final Said clears;
  final Said paying;
  final Said? progress;
  final Said balanceNote;
  final bool canMoveUp;
  final bool canMoveDown;
}

/// The ranked debts in [group]'s currency, in rung order, each joined to its rung.
List<LadderRow> ladderOf(CurrencyGroup group, DebtList debts, Formats f) {
  final rungs = {for (final r in group.selected.rungs) r.debtId: r};
  final ranked = debts.debts.where((d) => d.currency == group.currency && d.rung != null).toList()
    ..sort((a, b) => a.rung!.compareTo(b.rung!));
  return [
    for (var i = 0; i < ranked.length; i++)
      rowOf(
        ranked[i],
        rungs[ranked[i].id],
        group.selected.reason,
        f,
        canMoveUp: i > 0,
        canMoveDown: i < ranked.length - 1,
      ),
  ];
}

/// Debts outside every ladder — settled, or at zero. Listed, never silently dropped.
List<LadderRow> unrankedOf(DebtList debts, Formats f) => [
  for (final d in debts.debts)
    if (d.rung == null) rowOf(d, null, null, f),
];

LadderRow rowOf(
  Debt debt,
  Rung? rung,
  String? planReason,
  Formats f, {
  bool canMoveUp = false,
  bool canMoveDown = false,
}) => LadderRow(
  debt: debt,
  naming: namingOf(debt, f),
  rate: rateOf(debt.apr, derivable: debt.aprDerivable, f: f),
  rateSolved: debt.aprSource == TermSource.solved && debt.apr != null,
  clears: rung == null
      ? const Said('debt.clears.unknown')
      : clearsOf(rung.payoffMonth, planReason ?? debt.projectionReason, f),
  paying: rung == null
      ? Said('debt.paying.none', {'amount': f.money(debt.minimumPayment, debt.currency)})
      : payingOf(rung, debt.currency, f),
  progress: progressOf(debt, f),
  balanceNote: balanceNoteOf(debt, f),
  canMoveUp: canMoveUp,
  canMoveDown: canMoveDown,
);

/// The whole order across every currency, with [currency]'s ladder having [id] moved [by] one
/// place, or null when it has nowhere to go. **The whole order**, because the server clears the
/// position of any debt the list leaves out.
List<String>? movedOrder(Plan plan, DebtList debts, String currency, String id, int by, Formats f) {
  final groups = [for (final g in plan.currencies) (g.currency, ladderOf(g, debts, f))];
  final within = [
    for (final (c, rows) in groups)
      if (c == currency) ...rows.map((r) => r.debt.id),
  ];
  final at = within.indexOf(id);
  final to = at + by;
  if (at == -1 || to < 0 || to >= within.length) return null;
  final moved = [...within];
  moved[at] = within[to];
  moved[to] = within[at];
  return [
    for (final (c, rows) in groups)
      if (c == currency) ...moved else ...rows.map((r) => r.debt.id),
  ];
}

/// Every ranked debt in the order on screen — what *Your own order* starts from, so choosing it
/// changes nothing until something is moved.
List<String> rankedOrder(Plan plan, DebtList debts, Formats f) => [
  for (final g in plan.currencies) ...ladderOf(g, debts, f).map((r) => r.debt.id),
];
