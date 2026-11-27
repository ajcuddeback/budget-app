import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/shared/figures.dart';

/// How often something recurs: a small closed set, not a recurrence rule (ADR-0032). Bills and
/// income schedule the same way.
enum ScheduleKind {
  monthly,
  yearly,
  weekly,
  fortnightly,
  irregular,
  unknown;

  static ScheduleKind parse(String wire) => switch (wire) {
    'MONTHLY' => monthly,
    'YEARLY' => yearly,
    'WEEKLY' => weekly,
    'FORTNIGHTLY' => fortnightly,
    'IRREGULAR' => irregular,
    _ => unknown,
  };

  String get wire => name.toUpperCase();

  /// The kinds a person may choose, in the web's order.
  static const List<ScheduleKind> choosable = [monthly, yearly, weekly, fortnightly, irregular];

  /// The web's name for the kind (`bills.kind.*`). A kind a newer server invents gets a neutral word.
  String get messageKey => 'bills.kind.${this == unknown ? 'unknown' : wire}';

  /// The parameters this kind uses — **and no others**: the server refuses a parameter a kind has no
  /// use for, so the form shows these and the request carries only these.
  Set<ScheduleParameter> get parameters => switch (this) {
    monthly => const {ScheduleParameter.dayOfMonth},
    yearly => const {ScheduleParameter.month, ScheduleParameter.dayOfMonth},
    weekly => const {ScheduleParameter.dayOfWeek},
    fortnightly => const {ScheduleParameter.dayOfWeek, ScheduleParameter.anchorDate},
    irregular || unknown => const {},
  };
}

enum ScheduleParameter { dayOfMonth, month, dayOfWeek, anchorDate }

/// ISO order, Monday first.
enum DayOfWeek {
  monday,
  tuesday,
  wednesday,
  thursday,
  friday,
  saturday,
  sunday;

  static DayOfWeek? parse(String? wire) {
    for (final day in values) {
      if (day.wire == wire) return day;
    }
    return null;
  }

  String get wire => name.toUpperCase();

  /// 1 for Monday … 7 for Sunday.
  int get iso => index + 1;
}

/// A schedule as the server reports one: the kind, and only the parameters that kind uses.
final class Schedule {
  const Schedule({
    required this.kind,
    this.dayOfMonth,
    this.month,
    this.dayOfWeek,
    this.anchorDate,
  });

  factory Schedule.fromJson(JsonMap json) {
    final anchor = json.stringOrNull('anchorDate');
    return Schedule(
      kind: ScheduleKind.parse(json.string('kind')),
      dayOfMonth: json.integerOrNull('dayOfMonth'),
      month: json.integerOrNull('month'),
      dayOfWeek: DayOfWeek.parse(json.stringOrNull('dayOfWeek')),
      anchorDate: anchor == null ? null : CalendarDate.parse(anchor),
    );
  }

  final ScheduleKind kind;
  final int? dayOfMonth;
  final int? month;
  final DayOfWeek? dayOfWeek;
  final CalendarDate? anchorDate;
}

/// What a household has recorded about one occurrence. **There is no `OVERDUE`**: overdue is `DUE`
/// with a date in the past, derived per request (ADR-0025).
enum OccurrenceStatus {
  due,
  paid,
  skipped,
  other;

  static OccurrenceStatus parse(String wire) => switch (wire) {
    'DUE' => due,
    'PAID' => paid,
    'SKIPPED' => skipped,
    _ => other,
  };

  String get wire => switch (this) {
    due => 'DUE',
    paid => 'PAID',
    skipped => 'SKIPPED',
    other => 'OTHER',
  };

  String get messageKey => 'bills.status.${this == other ? 'unknown' : wire}';
}

/// One occurrence of a bill, on one date. Addressed by its **due date**: for most of its life it
/// has no row at all (ADR-0032). Every amount is the server's string.
final class BillOccurrence {
  const BillOccurrence({
    required this.billId,
    required this.dueDate,
    required this.expectedAmount,
    required this.status,
    required this.statusSource,
    required this.recorded,
    required this.overdue,
    required this.surprising,
    this.actualAmount,
    this.paidDate,
  });

  factory BillOccurrence.fromJson(JsonMap json) {
    final paid = json.stringOrNull('paidDate');
    return BillOccurrence(
      billId: json.string('billId'),
      dueDate: CalendarDate.parse(json.string('dueDate')),
      expectedAmount: json.amount('expectedAmount'),
      status: OccurrenceStatus.parse(json.string('status')),
      actualAmount: json.amountOrNull('actualAmount'),
      paidDate: paid == null ? null : CalendarDate.parse(paid),
      statusSource: json.string('statusSource'),
      recorded: json.boolean('recorded'),
      overdue: json.boolean('overdue'),
      surprising: json.boolean('surprising'),
    );
  }

  final String billId;
  final CalendarDate dueDate;
  final String expectedAmount;
  final OccurrenceStatus status;

  /// What was really paid; null until somebody records a payment.
  final String? actualAmount;
  final CalendarDate? paidDate;

  /// `MANUAL` or `AUTO_MATCHED`: a figure somebody asserted is never shown as one the app inferred.
  final String statusSource;

  /// Whether anything is stored. False is the common case, and is not an error.
  final bool recorded;
  final bool overdue;
  final bool surprising;

  String get sourceKey => switch (statusSource) {
    'MANUAL' => 'bills.source.MANUAL',
    'AUTO_MATCHED' => 'bills.source.AUTO_MATCHED',
    _ => 'bills.source.unknown',
  };

  /// Both figures only when a payment differed from the budget: the difference is the useful
  /// number, and repeating an equal one on every row says nothing (bills-and-income.md, UI).
  bool get showsBothFigures =>
      status == OccurrenceStatus.paid &&
      actualAmount != null &&
      !sameAmount(actualAmount!, expectedAmount);

  /// The one figure a row shows otherwise: what was paid when that is known.
  String get figure =>
      status == OccurrenceStatus.paid && actualAmount != null ? actualAmount! : expectedAmount;
}

/// A payee and account a weekly check-in remembered as paying this bill.
final class BillMapping {
  const BillMapping({
    required this.ruleId,
    required this.payeeName,
    required this.accountId,
    required this.accountName,
    this.accountLastFour,
  });

  factory BillMapping.fromJson(JsonMap json) => BillMapping(
    ruleId: json.string('ruleId'),
    payeeName: json.string('payeeName'),
    accountId: json.string('accountId'),
    accountName: json.string('accountName'),
    accountLastFour: json.stringOrNull('accountLastFour'),
  );

  final String ruleId;

  /// From a bank file: drawn in its own direction-neutral text.
  final String payeeName;
  final String accountId;
  final String accountName;
  final String? accountLastFour;
}

/// One bill, as `BillResponse` sends it.
final class Bill {
  const Bill({
    required this.id,
    required this.name,
    required this.expectedAmount,
    required this.currency,
    required this.schedule,
    required this.amountVaries,
    required this.active,
    required this.occurrences,
    required this.mappings,
    required this.version,
    this.categoryId,
    this.categoryName,
    this.categoryParentName,
    this.tolerance,
  });

  factory Bill.fromJson(JsonMap json) {
    final currency = json.string('currency');
    return Bill(
      id: json.string('id'),
      name: json.string('name'),
      categoryId: json.stringOrNull('categoryId'),
      categoryName: json.stringOrNull('categoryName'),
      categoryParentName: json.stringOrNull('categoryParentName'),
      expectedAmount: json.amount('expectedAmount'),
      currency: currency,
      schedule: Schedule.fromJson(json.object('schedule')),
      amountVaries: json.boolean('amountVaries'),
      tolerance: json.amountOrNull('tolerance'),
      active: json.boolean('active'),
      occurrences: [for (final o in json.objects('occurrences')) BillOccurrence.fromJson(o)],
      // Absent on a server from before slice 14: read as none.
      mappings: json['mappings'] == null
          ? const []
          : [for (final m in json.objects('mappings')) BillMapping.fromJson(m)],
      version: json.integer('version'),
    );
  }

  final String id;
  final String name;
  final String? categoryId;
  final String? categoryName;
  final String? categoryParentName;
  final String expectedAmount;

  /// The household's base currency: every bill shares it (ADR-0022).
  final String currency;
  final Schedule schedule;
  final bool amountVaries;

  /// Null unless the amount varies; the two move together.
  final String? tolerance;

  /// A retired bill stops projecting and keeps everything already recorded.
  final bool active;

  /// This month's, in date order. Empty is ordinary.
  final List<BillOccurrence> occurrences;
  final List<BillMapping> mappings;
  final int version;

  Money money(String wire) => Money.fromWire(wire, currency);
}

/// One month's bills and what they come to.
final class BillsPage {
  const BillsPage({
    required this.content,
    required this.total,
    required this.outstanding,
    required this.overdue,
    required this.currency,
  });

  factory BillsPage.fromJson(JsonMap json) => BillsPage(
    content: [for (final b in json.objects('content')) Bill.fromJson(b)],
    total: json.amount('total'),
    outstanding: json.amount('outstanding'),
    overdue: json.amount('overdue'),
    currency: json.string('currency'),
  );

  final List<Bill> content;
  final String total;
  final String outstanding;
  final String overdue;
  final String currency;

  List<Bill> get active => content.where((b) => b.active).toList();

  List<Bill> get retired => content.where((b) => !b.active).toList();
}

/// One income source, with what it is worth in the month asked about. Income projects no
/// occurrences: what a month has is `dates`, and a fortnightly wage lands two or three times.
final class IncomeSource {
  const IncomeSource({
    required this.id,
    required this.name,
    required this.amount,
    required this.currency,
    required this.schedule,
    required this.sourceOfTruth,
    required this.active,
    required this.dates,
    required this.expectedForPeriod,
    required this.version,
    this.accountId,
    this.accountName,
  });

  factory IncomeSource.fromJson(JsonMap json) => IncomeSource(
    id: json.string('id'),
    name: json.string('name'),
    amount: json.amount('amount'),
    currency: json.string('currency'),
    schedule: Schedule.fromJson(json.object('schedule')),
    accountId: json.stringOrNull('accountId'),
    accountName: json.stringOrNull('accountName'),
    sourceOfTruth: json.string('sourceOfTruth'),
    active: json.boolean('active'),
    dates: [for (final d in json.strings('dates')) CalendarDate.parse(d)],
    expectedForPeriod: json.amount('expectedForPeriod'),
    version: json.integer('version'),
  );

  final String id;
  final String name;

  /// What arrives each time — not the month's total, which is [expectedForPeriod].
  final String amount;
  final String currency;
  final Schedule schedule;
  final String? accountId;
  final String? accountName;
  final String sourceOfTruth;
  final bool active;
  final List<CalendarDate> dates;
  final String expectedForPeriod;
  final int version;

  String get truthKey => switch (sourceOfTruth) {
    'MANUAL_ESTIMATE' => 'bills.truth.MANUAL_ESTIMATE',
    'AUTO' => 'bills.truth.AUTO',
    _ => 'bills.truth.unknown',
  };
}

/// One month's income and what it comes to: an expectation, not money that arrived.
final class IncomeList {
  const IncomeList({required this.content, required this.total, required this.currency});

  factory IncomeList.fromJson(JsonMap json) => IncomeList(
    content: [for (final s in json.objects('content')) IncomeSource.fromJson(s)],
    total: json.amount('total'),
    currency: json.string('currency'),
  );

  final List<IncomeSource> content;
  final String total;
  final String currency;

  List<IncomeSource> get active => content.where((s) => s.active).toList();

  List<IncomeSource> get retired => content.where((s) => !s.active).toList();
}

/// The **five** terms and the subtraction — the figure the page exists for, from
/// `GET /api/periods/{period}/summary`. `left` goes negative and is never clamped; `owed` is never
/// part of the subtraction.
final class PeriodSummary {
  const PeriodSummary({
    required this.period,
    required this.income,
    required this.bills,
    required this.billsOutstanding,
    required this.billsOverdue,
    required this.envelopesAllocated,
    required this.owed,
    required this.debtCount,
    required this.debtMinimums,
    required this.debtMinimumsNotInBills,
    required this.goalsSetAside,
    required this.goalsCounted,
    required this.debtPlanExtraExceedsLeft,
    required this.left,
    required this.currency,
    this.debtPlanExtra,
  });

  factory PeriodSummary.fromJson(JsonMap json) => PeriodSummary(
    period: json.string('period'),
    income: json.amount('income'),
    bills: json.amount('bills'),
    billsOutstanding: json.amount('billsOutstanding'),
    billsOverdue: json.amount('billsOverdue'),
    envelopesAllocated: json.amount('envelopesAllocated'),
    owed: json.amount('owed'),
    debtCount: json.integer('debtCount'),
    debtMinimums: json.amount('debtMinimums'),
    debtMinimumsNotInBills: json.amount('debtMinimumsNotInBills'),
    goalsSetAside: json.amount('goalsSetAside'),
    goalsCounted: json.integer('goalsCounted'),
    debtPlanExtra: json.amountOrNull('debtPlanExtra'),
    debtPlanExtraExceedsLeft: json.boolean('debtPlanExtraExceedsLeft'),
    left: json.amount('left'),
    currency: json.string('currency'),
  );

  final String period;
  final String income;
  final String bills;
  final String billsOutstanding;
  final String billsOverdue;
  final String envelopesAllocated;
  final String owed;

  /// In every currency, while [owed] and the minimums are the base currency only.
  final int debtCount;
  final String debtMinimums;
  final String debtMinimumsNotInBills;
  final String goalsSetAside;
  final int goalsCounted;
  final String? debtPlanExtra;

  /// Compared on the server, so this client compares no amounts to say it.
  final bool debtPlanExtraExceedsLeft;
  final String left;
  final String currency;

  Money money(String wire) => Money.fromWire(wire, currency);
}
