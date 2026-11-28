import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/shared/figures.dart';

/// The wire shapes of the debt API, as `DebtResponses.java` sends them (debt-plan.md).
///
/// **Every amount and the rate are strings and stay strings** (ADR-0006): the server computes and
/// the phone displays. **Every solver figure travels with its source**, so a figure the app worked
/// out is never mistaken for one the household asserted.

/// How a term got its value. `FROM_PLAN` is the payment the ladder allocated.
enum TermSource {
  entered,
  solved,
  fromPlan,
  unknown;

  static TermSource parse(String wire) => switch (wire) {
    'ENTERED' => entered,
    'SOLVED' => solved,
    'FROM_PLAN' => fromPlan,
    _ => unknown,
  };
}

enum DebtKind {
  creditCard,
  installment;

  static DebtKind? parse(String wire) => switch (wire) {
    'CREDIT_CARD' => creditCard,
    'INSTALLMENT' => installment,
    _ => null,
  };

  String get wire => this == creditCard ? 'CREDIT_CARD' : 'INSTALLMENT';

  String get messageKey => 'debt.kind.$wire';
}

/// The web's key for a kind, a newer server's unknown one included.
String debtKindKey(String wire) => switch (DebtKind.parse(wire)) {
  final DebtKind kind => kind.messageKey,
  null => 'debt.kind.unknown',
};

enum Strategy {
  avalanche,
  snowball,
  custom;

  static Strategy? parse(String wire) => switch (wire) {
    'AVALANCHE' => avalanche,
    'SNOWBALL' => snowball,
    'CUSTOM' => custom,
    _ => null,
  };

  String get wire => name.toUpperCase();

  String get messageKey => 'debt.strategy.$wire';
}

/// The web's key for a strategy, a newer server's unknown one included.
String strategyKey(String wire) => switch (Strategy.parse(wire)) {
  final Strategy s => s.messageKey,
  null => 'debt.strategy.unknown',
};

/// Why there is no projection. **Neither is an error**.
String projectionReasonKey(String? reason) => switch (reason) {
  'never-clears' => 'debt.reason.neverClears',
  'plan-below-minimums' => 'debt.reason.belowMinimums',
  _ => 'debt.reason.unknown',
};

String balanceSourceKey(String source) => switch (source) {
  'CONFIRMED' => 'debt.balanceSource.CONFIRMED',
  'ESTIMATED' => 'debt.balanceSource.ESTIMATED',
  'LINKED_STATEMENT' => 'debt.balanceSource.LINKED_STATEMENT',
  _ => 'debt.balanceSource.unknown',
};

String provenanceKey(String provenance) => switch (provenance) {
  'USER_LOGGED' => 'debt.provenance.USER_LOGGED',
  'AUTO_MATCHED' => 'debt.provenance.AUTO_MATCHED',
  'STATEMENT_CORRECTION' => 'debt.provenance.STATEMENT_CORRECTION',
  _ => 'debt.provenance.unknown',
};

/// One debt, as `DebtResponse` sends it. Four fields are the ones a screen gets wrong by rendering
/// them as ordinary: a **negative** `planExtra`, a **null** rate that is not derivable, a **stale**
/// balance, and a **null** progress.
final class Debt {
  const Debt({
    required this.id,
    required this.name,
    required this.kind,
    required this.currency,
    required this.balance,
    required this.balanceSource,
    required this.balanceAsOf,
    required this.estimatedMonths,
    required this.balanceStale,
    required this.anchorBalance,
    required this.anchorDate,
    required this.aprSource,
    required this.aprDerivable,
    required this.minimumPayment,
    required this.paymentSource,
    required this.horizonSource,
    required this.archived,
    required this.paymentCount,
    required this.version,
    this.servicer,
    this.apr,
    this.originalBalance,
    this.paidOffSoFar,
    this.progressPercent,
    this.payment,
    this.monthsLeft,
    this.payoffMonth,
    this.totalInterestLeft,
    this.projectionReason,
    this.rung,
    this.planMinimum,
    this.planExtra,
    this.accountId,
    this.billId,
  });

  factory Debt.fromJson(JsonMap json) => Debt(
    id: json.string('id'),
    name: json.string('name'),
    kind: json.string('kind'),
    servicer: json.stringOrNull('servicer'),
    currency: json.string('currency'),
    balance: json.amount('balance'),
    balanceSource: json.string('balanceSource'),
    balanceAsOf: CalendarDate.parse(json.string('balanceAsOf')),
    estimatedMonths: json.integer('estimatedMonths'),
    balanceStale: json.boolean('balanceStale'),
    anchorBalance: json.amount('anchorBalance'),
    anchorDate: CalendarDate.parse(json.string('anchorDate')),
    apr: _rateOrNull(json, 'apr'),
    aprSource: TermSource.parse(json.string('aprSource')),
    aprDerivable: json.boolean('aprDerivable'),
    minimumPayment: json.amount('minimumPayment'),
    originalBalance: json.amountOrNull('originalBalance'),
    paidOffSoFar: json.amountOrNull('paidOffSoFar'),
    progressPercent: _rateOrNull(json, 'progressPercent'),
    payment: json.amountOrNull('payment'),
    paymentSource: TermSource.parse(json.string('paymentSource')),
    monthsLeft: json.integerOrNull('monthsLeft'),
    payoffMonth: json.monthOrNull('payoffMonth'),
    horizonSource: TermSource.parse(json.string('horizonSource')),
    totalInterestLeft: json.amountOrNull('totalInterestLeft'),
    projectionReason: json.stringOrNull('projectionReason'),
    rung: json.integerOrNull('rung'),
    planMinimum: json.amountOrNull('planMinimum'),
    planExtra: json.amountOrNull('planExtra'),
    accountId: json.stringOrNull('accountId'),
    billId: json.stringOrNull('billId'),
    archived: json.boolean('archived'),
    paymentCount: json.integer('paymentCount'),
    version: json.integer('version'),
  );

  final String id;
  final String name;

  /// The wire kind: a newer server's kind is still a debt.
  final String kind;
  final String? servicer;
  final String currency;

  /// Derived: the anchor rolled forward by the payments after it. **Never solved.**
  final String balance;
  final String balanceSource;
  final CalendarDate balanceAsOf;
  final int estimatedMonths;

  /// Past the estimation cap: the figure is the last confirmed one, and a statement is asked for.
  final bool balanceStale;
  final String anchorBalance;
  final CalendarDate anchorDate;

  /// A percentage, or null — and then [aprDerivable] says whether it is "not entered" or "not
  /// available". **Never rendered as 0%.**
  final String? apr;
  final TermSource aprSource;
  final bool aprDerivable;
  final String minimumPayment;
  final String? originalBalance;
  final String? paidOffSoFar;

  /// Null without an original balance: progress is **absent**, never 0%.
  final String? progressPercent;
  final String? payment;
  final TermSource paymentSource;
  final int? monthsLeft;

  /// `YYYY-MM`: months, not days.
  final String? payoffMonth;
  final TermSource horizonSource;
  final String? totalInterestLeft;
  final String? projectionReason;

  /// Null for a debt outside the ladder: archived, settled, or at zero.
  final int? rung;
  final String? planMinimum;

  /// **Can be negative** (ADR-0036): a debt owing less than its own minimum.
  final String? planExtra;
  final String? accountId;
  final String? billId;
  final bool archived;
  final int paymentCount;
  final int version;

  Money money(String wire) => Money.fromWire(wire, currency);

  /// An estimate, or past the cap — either way the confirm sheet is offered.
  bool get estimated => balanceSource == 'ESTIMATED' || balanceStale;
}

/// Every debt, with the totals Bills & income shares. Not a page: the plan ranks every debt, and
/// the per-household cap is what makes that safe. `owed` and the minimums are the base currency
/// only while `debtCount` spans every currency (ADR-0022).
final class DebtList {
  const DebtList({
    required this.debts,
    required this.strategy,
    required this.currency,
    required this.owed,
    required this.debtMinimums,
    required this.debtCount,
  });

  factory DebtList.fromJson(JsonMap json) => DebtList(
    debts: [for (final d in json.objects('debts')) Debt.fromJson(d)],
    strategy: json.string('strategy'),
    currency: json.string('currency'),
    owed: json.amount('owed'),
    debtMinimums: json.amount('debtMinimums'),
    debtCount: json.integer('debtCount'),
  );

  final List<Debt> debts;
  final String strategy;
  final String currency;
  final String owed;
  final String debtMinimums;
  final int debtCount;
}

/// What is still owed at the start of [month].
final class SeriesPoint {
  const SeriesPoint(this.month, this.balance);

  factory SeriesPoint.fromJson(JsonMap json) =>
      SeriesPoint(json.month('month'), json.amount('balance'));

  final String month;
  final String balance;
}

List<SeriesPoint> _series(JsonMap json, String key) => [
  for (final p in json.objects(key)) SeriesPoint.fromJson(p),
];

/// One debt and the two lines its chart draws. `seriesMonthStride` is how many months one point
/// stands for: the server thins a long line rather than truncating it.
final class DebtDetail {
  const DebtDetail({
    required this.debt,
    required this.pace,
    required this.atMinimum,
    required this.seriesMonthStride,
    required this.atMinimumIsTheSameLine,
  });

  factory DebtDetail.fromJson(JsonMap json) => DebtDetail(
    debt: Debt.fromJson(json.object('debt')),
    pace: _series(json, 'pace'),
    atMinimum: _series(json, 'atMinimum'),
    seriesMonthStride: json.integer('seriesMonthStride'),
    atMinimumIsTheSameLine: json.boolean('atMinimumIsTheSameLine'),
  );

  final Debt debt;
  final List<SeriesPoint> pace;
  final List<SeriesPoint> atMinimum;
  final int seriesMonthStride;
  final bool atMinimumIsTheSameLine;
}

/// One payment, with the split as it was recorded and how the row got here.
final class DebtPayment {
  const DebtPayment({
    required this.id,
    required this.paidDate,
    required this.currency,
    required this.amount,
    required this.interest,
    required this.principal,
    required this.balanceAfter,
    required this.provenance,
    this.paidFromAccountId,
    this.note,
  });

  factory DebtPayment.fromJson(JsonMap json) => DebtPayment(
    id: json.string('id'),
    paidDate: CalendarDate.parse(json.string('paidDate')),
    currency: json.string('currency'),
    amount: json.amount('amount'),
    interest: json.amount('interest'),
    principal: json.amount('principal'),
    balanceAfter: json.amount('balanceAfter'),
    provenance: json.string('provenance'),
    paidFromAccountId: json.stringOrNull('paidFromAccountId'),
    note: json.stringOrNull('note'),
  );

  final String id;
  final CalendarDate paidDate;
  final String currency;
  final String amount;
  final String interest;
  final String principal;
  final String balanceAfter;
  final String provenance;

  /// Where the money left — **not** where the debt is held. Nothing is written to it.
  final String? paidFromAccountId;
  final String? note;

  Money money(String wire) => Money.fromWire(wire, currency);
}

/// One page of a debt's history, in **the server's order**: two rows can share a date and only the
/// server knows which came first.
final class DebtPaymentPage {
  const DebtPaymentPage({required this.content, required this.totalElements});

  factory DebtPaymentPage.fromJson(JsonMap json) => DebtPaymentPage(
    content: [for (final p in json.objects('content')) DebtPayment.fromJson(p)],
    totalElements: json.integer('totalElements'),
  );

  final List<DebtPayment> content;
  final int totalElements;
}

/// What logging an amount **would** record — the server's split, through the same code as logging.
/// Read strictly: a body merely shaped like JSON is no preview at all.
final class PaymentPreview {
  const PaymentPreview({
    required this.currency,
    required this.interest,
    required this.principal,
    required this.balanceAfter,
    this.monthInterest,
    this.interestMonth,
  });

  factory PaymentPreview.fromJson(JsonMap json) => PaymentPreview(
    currency: json.string('currency'),
    interest: json.amount('interest'),
    principal: json.amount('principal'),
    balanceAfter: json.amount('balanceAfter'),
    monthInterest: json.stringOrNull('monthInterest'),
    interestMonth: json.monthOrNull('interestMonth'),
  );

  final String currency;
  final String interest;
  final String principal;
  final String balanceAfter;

  /// Why the interest is what it is — interest is charged once per debt per month (ADR-0043).
  /// `CARRIED`: the first payment dated in [interestMonth], so it carries that month's interest.
  /// `ALREADY_CARRIED`: an earlier payment did, so this one is all principal. `NONE`, or absent
  /// from an older server: nothing to say. The server decides; the phone only words it.
  final String? monthInterest;

  /// `YYYY-MM`: the month that decision was taken for.
  final String? interestMonth;

  Money money(String wire) => Money.fromWire(wire, currency);
}

/// One debt's place in the ladder, and what the plan allocates it.
final class Rung {
  const Rung({
    required this.rung,
    required this.debtId,
    required this.minimum,
    required this.extra,
    required this.monthlyAmount,
    this.payoffMonth,
  });

  factory Rung.fromJson(JsonMap json) => Rung(
    rung: json.integer('rung'),
    debtId: json.string('debtId'),
    minimum: json.amount('minimum'),
    extra: json.amount('extra'),
    monthlyAmount: json.amount('monthlyAmount'),
    payoffMonth: json.monthOrNull('payoffMonth'),
  );

  final int rung;
  final String debtId;
  final String minimum;

  /// **Negative** where a rung owes less than its minimum and clears at once (ADR-0036).
  final String extra;

  /// `minimum + extra`, by the server: the figure the household acts on.
  final String monthlyAmount;
  final String? payoffMonth;
}

/// One strategy's answer. Both named strategies are projected whichever is selected.
final class StrategyProjection {
  const StrategyProjection({
    required this.strategy,
    required this.rungs,
    required this.series,
    required this.seriesMonthStride,
    this.debtFreeMonth,
    this.firstWinMonth,
    this.totalInterest,
    this.reason,
  });

  factory StrategyProjection.fromJson(JsonMap json) => StrategyProjection(
    strategy: json.string('strategy'),
    debtFreeMonth: json.monthOrNull('debtFreeMonth'),
    firstWinMonth: json.monthOrNull('firstWinMonth'),
    totalInterest: json.amountOrNull('totalInterest'),
    reason: json.stringOrNull('reason'),
    rungs: [for (final r in json.objects('rungs')) Rung.fromJson(r)],
    series: _series(json, 'series'),
    seriesMonthStride: json.integer('seriesMonthStride'),
  );

  final String strategy;
  final String? debtFreeMonth;
  final String? firstWinMonth;
  final String? totalInterest;
  final String? reason;
  final List<Rung> rungs;
  final List<SeriesPoint> series;
  final int seriesMonthStride;
}

/// One currency's ladder: a surplus exists in one currency and cannot be allocated in another
/// without a rate the instance may not have (ADR-0022).
final class CurrencyGroup {
  const CurrencyGroup({
    required this.currency,
    required this.planCurrency,
    required this.monthlyAmount,
    required this.minimums,
    required this.surplus,
    required this.owed,
    required this.belowMinimums,
    required this.selected,
    required this.avalanche,
    required this.snowball,
  });

  factory CurrencyGroup.fromJson(JsonMap json) => CurrencyGroup(
    currency: json.string('currency'),
    planCurrency: json.boolean('planCurrency'),
    monthlyAmount: json.amount('monthlyAmount'),
    minimums: json.amount('minimums'),
    surplus: json.amount('surplus'),
    owed: json.amount('owed'),
    belowMinimums: json.boolean('belowMinimums'),
    selected: StrategyProjection.fromJson(json.object('selected')),
    avalanche: StrategyProjection.fromJson(json.object('avalanche')),
    snowball: StrategyProjection.fromJson(json.object('snowball')),
  );

  final String currency;
  final bool planCurrency;
  final String monthlyAmount;
  final String minimums;
  final String surplus;
  final String owed;

  /// Accepted, not refused — and no projection, because every figure from it would be a fiction.
  final bool belowMinimums;
  final StrategyProjection selected;
  final StrategyProjection avalanche;
  final StrategyProjection snowball;

  Money money(String wire) => Money.fromWire(wire, currency);
}

/// The plan: the selection, the order, both projections and the comparison. Singular: there is
/// exactly one per household (ADR-0026).
final class Plan {
  const Plan({
    required this.strategy,
    required this.currency,
    required this.monthlyAmount,
    required this.minimums,
    required this.debtCount,
    required this.mixedCurrency,
    required this.currencies,
    required this.version,
  });

  factory Plan.fromJson(JsonMap json) => Plan(
    strategy: json.string('strategy'),
    currency: json.string('currency'),
    monthlyAmount: json.amount('monthlyAmount'),
    minimums: json.amount('minimums'),
    debtCount: json.integer('debtCount'),
    mixedCurrency: json.boolean('mixedCurrency'),
    currencies: [for (final g in json.objects('currencies')) CurrencyGroup.fromJson(g)],
    version: json.integer('version'),
  );

  final String strategy;
  final String currency;
  final String monthlyAmount;
  final String minimums;
  final int debtCount;
  final bool mixedCurrency;

  /// One per currency, the household's base currency first.
  final List<CurrencyGroup> currencies;
  final int version;

  /// The base currency's group, or the first.
  CurrencyGroup? get planGroup {
    for (final g in currencies) {
      if (g.planCurrency) return g;
    }
    return currencies.isEmpty ? null : currencies.first;
  }
}

/// A percentage from the wire (`22.900`), checked at the boundary and kept as the string.
String? _rateOrNull(JsonMap json, String key) {
  final value = json.stringOrNull(key);
  if (value != null && !RegExp(r'^-?[0-9]{1,15}(\.[0-9]{1,6})?$').hasMatch(value)) {
    throw UnexpectedJson('$key is not a rate');
  }
  return value;
}
