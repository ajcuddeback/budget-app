import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/money/money.dart';

/// The shapes `CheckInController` sends (weekly-check-in.md, API), as the web's
/// `check-in.models.ts` reads them.
///
/// **Every amount is [Money] from its wire string**, and the phone does no arithmetic on one:
/// every figure, step 4's previews included, is the server's. The one amount the phone ever sends
/// back is the surplus, as the string the server sent ([Figures.surplusWire]).
///
/// **No sentence is pre-composed.** Names arrive beside their ids, and the screen builds each
/// sentence from a message with parameters (ADR-0023).

enum CheckInStatus {
  notStarted('NOT_STARTED'),
  draft('DRAFT'),
  completed('COMPLETED');

  CheckInStatus(this.wire);
  final String wire;
}

/// The four steps, in the canvas's order: screens follow it whatever order the server lists.
enum StepName {
  whatsNew('WHATS_NEW'),
  categorize('CATEGORIZE'),
  bills('BILLS'),
  nextWeek('NEXT_WEEK');

  StepName(this.wire);
  final String wire;
}

enum ItemKind {
  overdueBill('OVERDUE_BILL'),
  unusualCharge('UNUSUAL_CHARGE'),
  categorize('CATEGORIZE'),
  billCharge('BILL_CHARGE'),
  allocation('ALLOCATION');

  ItemKind(this.wire);
  final String wire;
}

enum AnswerChoice {
  markPaid('MARK_PAID'),
  skip('SKIP'),
  prepaid('PREPAID'),
  category('CATEGORY'),
  attach('ATTACH'),
  notABill('NOT_A_BILL'),
  leave('LEAVE'),
  debt('DEBT'),
  goal('GOAL'),
  rollOver('ROLL_OVER');

  AnswerChoice(this.wire);
  final String wire;
}

/// Why step 3 asks about a charge, or how a rule pre-answered it.
enum ChargeReason {
  ruleMatch('RULE_MATCH'),
  ruleOutsideTolerance('RULE_OUTSIDE_TOLERANCE'),
  recurring('RECURRING');

  ChargeReason(this.wire);
  final String wire;
}

enum OccurrenceStatus {
  due('DUE'),
  paid('PAID'),
  skipped('SKIPPED');

  OccurrenceStatus(this.wire);
  final String wire;
}

T _enum<T extends Enum>(List<T> values, String Function(T) wire, String text, String key) {
  for (final value in values) {
    if (wire(value) == text) return value;
  }
  throw UnexpectedJson('$key is not a known value');
}

T? _enumOrNull<T extends Enum>(List<T> values, String Function(T) wire, String? text, String key) =>
    text == null ? null : _enum(values, wire, text, key);

int? _intOrNull(JsonMap json, String key) {
  final value = json[key];
  if (value == null || value is int) return value as int?;
  throw UnexpectedJson('$key is not an integer or null');
}

CalendarDate _parse(String text, String key) {
  try {
    return CalendarDate.parse(text);
  } on FormatException {
    throw UnexpectedJson('$key is not a date');
  }
}

CalendarDate? _dateOrNull(JsonMap json, String key) {
  final text = json.stringOrNull(key);
  return text == null ? null : _parse(text, key);
}

CalendarDate _date(JsonMap json, String key) =>
    _dateOrNull(json, key) ?? (throw UnexpectedJson('$key is not a date'));

/// `{"amount": "63.0000", "currency": "USD"}`.
Money _money(JsonMap json) {
  try {
    return Money.fromWire(json.string('amount'), json.string('currency'));
  } on FormatException {
    throw const UnexpectedJson('amount is not an amount');
  }
}

Money? _moneyOrNull(JsonMap json, String key) {
  final value = json.objectOrNull(key);
  return value == null ? null : _money(value);
}

List<JsonMap>? _objectsOrNull(JsonMap json, String key) =>
    json[key] == null ? null : json.objects(key);

/// A member by id and display name. The name is null for a member who has been removed.
final class Person {
  const Person({required this.id, this.name});

  factory Person.fromJson(JsonMap json) =>
      Person(id: json.string('id'), name: json.stringOrNull('name'));

  final String id;
  final String? name;
}

/// What a rule would answer. Derived on every read; a read never writes one.
final class PreAnswer {
  const PreAnswer({required this.choice, this.categoryId, this.categoryName});

  factory PreAnswer.fromJson(JsonMap json) => PreAnswer(
    choice: _enum(AnswerChoice.values, (c) => c.wire, json.string('choice'), 'choice'),
    categoryId: json.stringOrNull('categoryId'),
    categoryName: json.stringOrNull('categoryName'),
  );

  final AnswerChoice choice;
  final String? categoryId;
  final String? categoryName;
}

final class Answer {
  const Answer({
    required this.choice,
    required this.remember,
    required this.recordDebtPayment,
    required this.byRule,
    required this.version,
    this.categoryId,
    this.categoryName,
    this.billId,
    this.billName,
    this.dueDate,
    this.goalId,
    this.debtId,
    this.amount,
    this.answeredBy,
  });

  factory Answer.fromJson(JsonMap json) => Answer(
    choice: _enum(AnswerChoice.values, (c) => c.wire, json.string('choice'), 'choice'),
    categoryId: json.stringOrNull('categoryId'),
    categoryName: json.stringOrNull('categoryName'),
    billId: json.stringOrNull('billId'),
    billName: json.stringOrNull('billName'),
    dueDate: _dateOrNull(json, 'dueDate'),
    goalId: json.stringOrNull('goalId'),
    debtId: json.stringOrNull('debtId'),
    amount: _moneyOrNull(json, 'amount'),
    remember: json.boolean('remember'),
    recordDebtPayment: json.boolean('recordDebtPayment'),
    byRule: json.boolean('byRule'),
    answeredBy: json.objectOrNull('answeredBy') == null
        ? null
        : Person.fromJson(json.object('answeredBy')),
    version: json.integer('version'),
  );

  final AnswerChoice choice;
  final String? categoryId;
  final String? categoryName;
  final String? billId;
  final String? billName;
  final CalendarDate? dueDate;
  final String? goalId;
  final String? debtId;
  final Money? amount;
  final bool remember;
  final bool recordDebtPayment;
  final bool byRule;
  final Person? answeredBy;

  /// Sent back when this answer is replaced, so a race with another member is a `409`.
  final int version;
}

/// One question, or one row a rule pre-answered. Which fields are set depends on its kind.
final class Item {
  const Item({
    required this.itemKey,
    required this.kind,
    required this.question,
    this.date,
    this.amount,
    this.payeeName,
    this.accountName,
    this.accountLastFour,
    this.billId,
    this.billName,
    this.dueDate,
    this.expected,
    this.usual,
    this.multiple,
    this.skips,
    this.reason,
    this.suggestedBillId,
    this.suggestedDueDate,
    this.debtPromptName,
    this.ruleCategoryId,
    this.ruleCategoryName,
    this.preAnswer,
    this.answer,
  });

  factory Item.fromJson(JsonMap json) => Item(
    itemKey: json.string('itemKey'),
    kind: _enum(ItemKind.values, (k) => k.wire, json.string('kind'), 'kind'),
    question: json.boolean('question'),
    date: _dateOrNull(json, 'date'),
    amount: _moneyOrNull(json, 'amount'),
    payeeName: json.stringOrNull('payeeName'),
    accountName: json.stringOrNull('accountName'),
    accountLastFour: json.stringOrNull('accountLastFour'),
    billId: json.stringOrNull('billId'),
    billName: json.stringOrNull('billName'),
    dueDate: _dateOrNull(json, 'dueDate'),
    expected: _moneyOrNull(json, 'expected'),
    usual: _moneyOrNull(json, 'usual'),
    multiple: _intOrNull(json, 'multiple'),
    skips: _intOrNull(json, 'skips'),
    reason: _enumOrNull(ChargeReason.values, (r) => r.wire, json.stringOrNull('reason'), 'reason'),
    suggestedBillId: json.stringOrNull('suggestedBillId'),
    suggestedDueDate: _dateOrNull(json, 'suggestedDueDate'),
    debtPromptName: json.objectOrNull('debtPrompt')?.string('debtName'),
    ruleCategoryId: json.stringOrNull('ruleCategoryId'),
    ruleCategoryName: json.stringOrNull('ruleCategoryName'),
    preAnswer: json.objectOrNull('preAnswer') == null
        ? null
        : PreAnswer.fromJson(json.object('preAnswer')),
    answer: json.objectOrNull('answer') == null ? null : Answer.fromJson(json.object('answer')),
  );

  /// Issued by the server, sent back in a path, never parsed or built here.
  final String itemKey;
  final ItemKind kind;

  /// False for an item a rule pre-answered: shown and changeable, but not asked.
  final bool question;
  final CalendarDate? date;
  final Money? amount;

  /// From a bank file: shown in its own text, never inside a sentence.
  final String? payeeName;
  final String? accountName;
  final String? accountLastFour;
  final String? billId;
  final String? billName;
  final CalendarDate? dueDate;
  final Money? expected;
  final Money? usual;

  /// *k* for a prepaid offer, or null when only "Leave it" is offered.
  final int? multiple;
  final int? skips;
  final ChargeReason? reason;
  final String? suggestedBillId;
  final CalendarDate? suggestedDueDate;

  /// The debt-plan prompt's debt, when this bill is a debt's bill row.
  final String? debtPromptName;
  final String? ruleCategoryId;
  final String? ruleCategoryName;
  final PreAnswer? preAnswer;
  final Answer? answer;

  /// The answer's choice, else a rule's.
  AnswerChoice? get chosen => answer?.choice ?? preAnswer?.choice;

  /// The category the item reads as filed under: the answer's, else a rule's pre-answer.
  String? get chosenCategoryId {
    final given = answer;
    if (given != null) return given.choice == AnswerChoice.category ? given.categoryId : null;
    return preAnswer?.choice == AnswerChoice.category ? preAnswer?.categoryId : null;
  }

  String? get chosenCategoryName {
    final given = answer;
    if (given != null) return given.choice == AnswerChoice.category ? given.categoryName : null;
    return preAnswer?.categoryName;
  }

  bool get byRule => answer?.byRule ?? preAnswer != null;
}

/// Step 1's income line: information, never a question.
final class IncomeLine {
  const IncomeLine({
    required this.incomeSourceId,
    required this.name,
    required this.expectedDate,
    required this.expected,
    this.landed = false,
    this.date,
    this.actual,
    this.actualWire,
    this.expectedWire,
  });

  factory IncomeLine.fromJson(JsonMap json) {
    final actual = json.objectOrNull('actual');
    final expected = json.object('expected');
    return IncomeLine(
      incomeSourceId: json.string('incomeSourceId'),
      name: json.string('name'),
      expectedDate: _date(json, 'expectedDate'),
      expected: _money(expected),
      expectedWire: expected.string('amount'),
      landed: json.stringOrNull('transactionId') != null,
      date: _dateOrNull(json, 'date'),
      actual: actual == null ? null : _money(actual),
      actualWire: actual?.string('amount'),
    );
  }

  final String incomeSourceId;
  final String name;
  final CalendarDate expectedDate;
  final Money expected;
  final String? expectedWire;
  final bool landed;
  final CalendarDate? date;
  final Money? actual;
  final String? actualWire;

  /// "As expected": an exact equality of the two figures, never a difference worked out here.
  bool get asExpected =>
      actual != null && actual!.currency == expected.currency && actual!.amount == expected.amount;
}

/// One row of step 3's table, from the set Bills & Income reads.
final class OccurrenceLine {
  const OccurrenceLine({
    required this.billId,
    required this.billName,
    required this.dueDate,
    required this.status,
    required this.amount,
    required this.overdue,
  });

  factory OccurrenceLine.fromJson(JsonMap json) => OccurrenceLine(
    billId: json.string('billId'),
    billName: json.string('billName'),
    dueDate: _date(json, 'dueDate'),
    status: _enum(OccurrenceStatus.values, (s) => s.wire, json.string('status'), 'status'),
    amount: _money(json.object('amount')),
    overdue: json.boolean('overdue'),
  );

  final String billId;
  final String billName;
  final CalendarDate dueDate;
  final OccurrenceStatus status;
  final Money amount;
  final bool overdue;
}

/// A bill "Different bill" offers, with the dates a charge may attach to.
final class BillChoice {
  const BillChoice({
    required this.billId,
    required this.name,
    required this.expected,
    required this.dueDates,
  });

  factory BillChoice.fromJson(JsonMap json) => BillChoice(
    billId: json.string('billId'),
    name: json.string('name'),
    expected: _money(json.object('expected')),
    dueDates: [for (final text in json.strings('dueDates')) _parse(text, 'dueDates[]')],
  );

  final String billId;
  final String name;
  final Money expected;
  final List<CalendarDate> dueDates;
}

/// Step 4's figures, every one the server's.
final class Figures {
  const Figures({
    required this.weekSpending,
    required this.base,
    required this.basePerDay,
    required this.surplusAllocatable,
    required this.soFar,
    this.weekTarget,
    this.surplus,
    this.surplusWire,
    this.rolledOver,
    this.rolledPerDay,
  });

  factory Figures.fromJson(JsonMap json) => Figures(
    weekTarget: _moneyOrNull(json, 'weekTarget'),
    weekSpending: _money(json.object('weekSpending')),
    surplus: _moneyOrNull(json, 'surplus'),
    surplusWire: json.objectOrNull('surplus')?.string('amount'),
    base: _money(json.object('base')),
    basePerDay: _money(json.object('basePerDay')),
    rolledOver: _moneyOrNull(json, 'rolledOver'),
    rolledPerDay: _moneyOrNull(json, 'rolledPerDay'),
    surplusAllocatable: json.boolean('surplusAllocatable'),
    soFar: json.boolean('soFar'),
  );

  final Money? weekTarget;
  final Money weekSpending;
  final Money? surplus;

  /// The surplus exactly as the server wrote it: the only amount an answer ever carries, so the
  /// server can tell the person saw the same figure (weekly-check-in.md, Money rules).
  final String? surplusWire;
  final Money base;
  final Money basePerDay;
  final Money? rolledOver;
  final Money? rolledPerDay;

  /// False when the month is already short, so only rolling over is offered.
  final bool surplusAllocatable;

  /// The week is not over yet, so its spending is "so far".
  final bool soFar;

  bool get hasSurplus => surplus != null && surplus!.amount.sign > 0;
}

/// One place the surplus can go, with figures from the real write paths' previews.
final class Choice {
  const Choice({
    required this.choice,
    this.debtId,
    this.debtName,
    this.payoffMonthNow,
    this.payoffMonthWith,
    this.goalId,
    this.goalName,
    this.savedNow,
    this.savedWith,
    this.target,
  });

  factory Choice.fromJson(JsonMap json) => Choice(
    choice: _enum(AnswerChoice.values, (c) => c.wire, json.string('choice'), 'choice'),
    debtId: json.stringOrNull('debtId'),
    debtName: json.stringOrNull('debtName'),
    payoffMonthNow: json.stringOrNull('payoffMonthNow'),
    payoffMonthWith: json.stringOrNull('payoffMonthWith'),
    goalId: json.stringOrNull('goalId'),
    goalName: json.stringOrNull('goalName'),
    savedNow: _moneyOrNull(json, 'savedNow'),
    savedWith: _moneyOrNull(json, 'savedWith'),
    target: _moneyOrNull(json, 'target'),
  );

  final AnswerChoice choice;
  final String? debtId;
  final String? debtName;

  /// `YYYY-MM`: months, never days.
  final String? payoffMonthNow;
  final String? payoffMonthWith;
  final String? goalId;
  final String? goalName;
  final Money? savedNow;
  final Money? savedWith;
  final Money? target;
}

final class DueLine {
  const DueLine({required this.billName, required this.dueDate});

  factory DueLine.fromJson(JsonMap json) =>
      DueLine(billName: json.string('billName'), dueDate: _date(json, 'dueDate'));

  final String billName;
  final CalendarDate dueDate;
}

final class Streak {
  const Streak({required this.weeks, this.debtFreeFrom, this.debtFreeTo});

  factory Streak.fromJson(JsonMap json) => Streak(
    weeks: json.integer('weeks'),
    debtFreeFrom: json.stringOrNull('debtFreeFrom'),
    debtFreeTo: json.stringOrNull('debtFreeTo'),
  );

  final int weeks;
  final String? debtFreeFrom;
  final String? debtFreeTo;
}

/// One shown step. The fields a step does not use are null.
final class CheckInStep {
  const CheckInStep({
    required this.step,
    required this.answered,
    required this.questions,
    required this.answeredCount,
    required this.items,
    this.income,
    this.more,
    this.weekSpending,
    this.bills,
    this.stillToPay,
    this.billChoices,
    this.figures,
    this.choices,
    this.dueNextWeek,
    this.streak,
  });

  factory CheckInStep.fromJson(JsonMap json) => CheckInStep(
    step: _enum(StepName.values, (s) => s.wire, json.string('step'), 'step'),
    answered: json.boolean('answered'),
    questions: json.integer('questions'),
    answeredCount: json.integer('answeredCount'),
    items: [for (final item in json.objects('items')) Item.fromJson(item)],
    income: _objectsOrNull(json, 'income')?.map(IncomeLine.fromJson).toList(),
    more: _intOrNull(json, 'more'),
    weekSpending: _moneyOrNull(json, 'weekSpending'),
    bills: _objectsOrNull(json, 'bills')?.map(OccurrenceLine.fromJson).toList(),
    stillToPay: _moneyOrNull(json, 'stillToPay'),
    billChoices: _objectsOrNull(json, 'billChoices')?.map(BillChoice.fromJson).toList(),
    figures: json.objectOrNull('figures') == null ? null : Figures.fromJson(json.object('figures')),
    choices: _objectsOrNull(json, 'choices')?.map(Choice.fromJson).toList(),
    dueNextWeek: _objectsOrNull(json, 'dueNextWeek')?.map(DueLine.fromJson).toList(),
    streak: json.objectOrNull('streak') == null ? null : Streak.fromJson(json.object('streak')),
  );

  final StepName step;
  final bool answered;
  final int questions;
  final int answeredCount;
  final List<Item> items;
  final List<IncomeLine>? income;

  /// Uncategorised rows beyond the 200 shown (step 2).
  final int? more;
  final Money? weekSpending;
  final List<OccurrenceLine>? bills;
  final Money? stillToPay;
  final List<BillChoice>? billChoices;
  final Figures? figures;
  final List<Choice>? choices;
  final List<DueLine>? dueNextWeek;
  final Streak? streak;

  Item? get allocation {
    for (final item in items) {
      if (item.kind == ItemKind.allocation) return item;
    }
    return null;
  }
}

final class WeekSnapshot {
  const WeekSnapshot({required this.nextWeekTarget});

  factory WeekSnapshot.fromJson(JsonMap json) =>
      WeekSnapshot(nextWeekTarget: _money(json.object('nextWeekTarget')));

  final Money nextWeekTarget;
}

/// One record a completed check-in wrote, with the names its summary needs.
final class Outcome {
  const Outcome({
    required this.kind,
    required this.choice,
    required this.byRule,
    required this.remember,
    this.billName,
    this.goalId,
    this.goalName,
    this.debtId,
    this.debtName,
    this.amount,
  });

  factory Outcome.fromJson(JsonMap json) => Outcome(
    kind: _enum(ItemKind.values, (k) => k.wire, json.string('kind'), 'kind'),
    choice: _enum(AnswerChoice.values, (c) => c.wire, json.string('choice'), 'choice'),
    byRule: json.boolean('byRule'),
    remember: json.boolean('remember'),
    billName: json.stringOrNull('billName'),
    goalId: json.stringOrNull('goalId'),
    goalName: json.stringOrNull('goalName'),
    debtId: json.stringOrNull('debtId'),
    debtName: json.stringOrNull('debtName'),
    amount: _moneyOrNull(json, 'amount'),
  );

  final ItemKind kind;
  final AnswerChoice choice;
  final bool byRule;
  final bool remember;
  final String? billName;
  final String? goalId;
  final String? goalName;
  final String? debtId;
  final String? debtName;
  final Money? amount;
}

/// One week, as every caller reads it.
final class Week {
  const Week({
    required this.week,
    required this.weekStart,
    required this.weekEnd,
    required this.status,
    required this.otherCurrencyRows,
    required this.steps,
    required this.outcome,
    this.version,
    this.basis,
    this.completedAt,
    this.completedBy,
    this.snapshot,
  });

  factory Week.fromJson(JsonMap json) {
    final steps = [for (final step in json.objects('steps')) CheckInStep.fromJson(step)]
      ..sort((a, b) => a.step.index.compareTo(b.step.index));
    final completedAt = json.stringOrNull('completedAt');
    return Week(
      week: json.string('week'),
      weekStart: _date(json, 'weekStart'),
      weekEnd: _date(json, 'weekEnd'),
      status: _enum(CheckInStatus.values, (s) => s.wire, json.string('status'), 'status'),
      version: _intOrNull(json, 'version'),
      basis: json.stringOrNull('basis'),
      completedAt: completedAt == null ? null : DateTime.tryParse(completedAt),
      completedBy: json.objectOrNull('completedBy') == null
          ? null
          : Person.fromJson(json.object('completedBy')),
      otherCurrencyRows: json.integer('otherCurrencyRows'),
      steps: steps,
      snapshot: json.objectOrNull('snapshot') == null
          ? null
          : WeekSnapshot.fromJson(json.object('snapshot')),
      outcome: [for (final o in json.objects('outcome')) Outcome.fromJson(o)],
    );
  }

  /// `2026-W34`.
  final String week;
  final CalendarDate weekStart;
  final CalendarDate weekEnd;
  final CheckInStatus status;

  /// The draft's; null before a draft exists. Completion sends it back.
  final int? version;

  /// What completion sends back, so a draft is never finished against a week that moved.
  final String? basis;
  final DateTime? completedAt;
  final Person? completedBy;
  final int otherCurrencyRows;

  /// The shown steps only, in the canvas's order. A skipped step is not here.
  final List<CheckInStep> steps;
  final WeekSnapshot? snapshot;
  final List<Outcome> outcome;

  /// `2026-W34` → 34.
  int get number {
    final match = RegExp(r'^\d{4}-W(\d{2})$').firstMatch(week);
    return match == null ? 0 : int.parse(match.group(1)!);
  }

  int get answeredSteps => steps.where((s) => s.answered).length;

  CheckInStep? stepNamed(StepName name) {
    for (final step in steps) {
      if (step.step == name) return step;
    }
    return null;
  }
}

/// `2026-W34`, and nothing else reaches a path. The server parses it strictly as well.
final RegExp weekPattern = RegExp(r'^\d{4}-W\d{2}$');

/// One item a `409 check-in-stale` names, with why. An item-less reason (`basis-changed`) has
/// a null key.
final class StaleItem {
  const StaleItem({required this.reason, this.itemKey});

  final String? itemKey;
  final String reason;
}

/// The items a stale refusal names, read defensively: an unknown reason still marks its item.
List<StaleItem> staleItems(JsonMap params) {
  final listed = params['items'];
  if (listed is! List<Object?>) return const [];
  return [
    for (final entry in listed.take(1000))
      if (entry is Map<String, Object?>)
        StaleItem(
          itemKey: entry['itemKey'] is String ? entry['itemKey']! as String : null,
          reason: entry['reason'] is String ? entry['reason']! as String : 'unknown',
        ),
  ];
}
