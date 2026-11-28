import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/shared/figures.dart';

/// The shapes `GoalController` sends (goals.md, API). **Every amount is a string** beside its
/// currency; `requiredMonthly` alone is at the currency's own scale, because it is an amount
/// somebody will type. The name and the month are separate fields, composed by a message.

const Set<String> _reasons = {
  'no-contribution',
  'beyond-horizon',
  'reached',
  'no-target-month',
  'target-month-passed',
  'not-reachable-by-target-month',
};

/// A reason code, or null. One this client does not know is refused at the boundary, so no
/// sentence is ever a blank.
String? _reasonOrNull(JsonMap json, String key) {
  final value = json.stringOrNull(key);
  if (value != null && !_reasons.contains(value)) throw UnexpectedJson('$key is not a reason');
  return value;
}

/// This month's line: what the plan asks of it, what was recorded, and what is still to come.
final class ThisPeriod {
  const ThisPeriod({
    required this.period,
    required this.planned,
    required this.recorded,
    required this.stillToCome,
  });

  factory ThisPeriod.fromJson(JsonMap json) => ThisPeriod(
    period: json.month('period'),
    planned: json.amount('planned'),
    recorded: json.amount('recorded'),
    stillToCome: json.amount('stillToCome'),
  );

  /// The server's current month: what "this month" means.
  final String period;
  final String planned;

  /// Withdrawals included, so it can be negative.
  final String recorded;

  /// What *Set money aside* opens with.
  final String stillToCome;
}

final class GoalProjection {
  const GoalProjection({
    this.projectedMonth,
    this.projectedReason,
    this.requiredMonthly,
    this.requiredReason,
  });

  factory GoalProjection.fromJson(JsonMap json) => GoalProjection(
    projectedMonth: json.monthOrNull('projectedMonth'),
    projectedReason: _reasonOrNull(json, 'projectedReason'),
    requiredMonthly: json.amountOrNull('requiredMonthly'),
    requiredReason: _reasonOrNull(json, 'requiredReason'),
  );

  final String? projectedMonth;
  final String? projectedReason;
  final String? requiredMonthly;
  final String? requiredReason;
}

/// What a projection sentence needs — a goal, and a preview answer, both have it.
final class ProjectionFacts {
  const ProjectionFacts({
    required this.currency,
    required this.monthlyContribution,
    required this.reached,
    required this.overdue,
    required this.projection,
    this.targetMonth,
  });

  /// A preview's answer, read strictly: anything not shaped like one is no preview.
  factory ProjectionFacts.fromPreview(JsonMap json, {String? targetMonth}) => ProjectionFacts(
    currency: json.string('currency'),
    monthlyContribution: json.amount('monthlyContribution'),
    reached: json.boolean('reached'),
    overdue: json.boolean('overdue'),
    projection: GoalProjection.fromJson(json.object('projection')),
    targetMonth: targetMonth,
  );

  final String currency;
  final String? targetMonth;
  final String monthlyContribution;
  final bool reached;
  final bool overdue;
  final GoalProjection projection;
}

/// One goal, the same shape on every route that returns one.
final class Goal {
  const Goal({
    required this.id,
    required this.name,
    required this.currency,
    required this.targetAmount,
    required this.startingAmount,
    required this.saved,
    required this.remaining,
    required this.reached,
    required this.overdue,
    required this.thisPeriod,
    required this.monthlyContribution,
    required this.projection,
    required this.archived,
    required this.version,
    this.targetMonth,
    this.accountId,
  });

  factory Goal.fromJson(JsonMap json) => Goal(
    id: json.string('id'),
    name: json.string('name'),
    currency: json.string('currency'),
    targetAmount: json.amount('targetAmount'),
    targetMonth: json.monthOrNull('targetMonth'),
    startingAmount: json.amount('startingAmount'),
    saved: json.amount('saved'),
    remaining: json.amount('remaining'),
    reached: json.boolean('reached'),
    overdue: json.boolean('overdue'),
    accountId: json.stringOrNull('accountId'),
    thisPeriod: ThisPeriod.fromJson(json.object('thisPeriod')),
    monthlyContribution: json.amount('monthlyContribution'),
    projection: GoalProjection.fromJson(json.object('projection')),
    archived: json.boolean('archived'),
    version: json.integer('version'),
  );

  final String id;
  final String name;
  final String currency;
  final String targetAmount;

  /// `YYYY-MM`, or null: a goal with no month is normal.
  final String? targetMonth;
  final String startingAmount;

  /// Never clamped: over the target when over-funded, negative after a large withdrawal.
  final String saved;
  final String remaining;
  final bool reached;
  final bool overdue;

  /// Where the money is kept — informational only, never the source of [saved].
  final String? accountId;
  final ThisPeriod thisPeriod;

  /// The plan's step in force this month.
  final String monthlyContribution;
  final GoalProjection projection;
  final bool archived;
  final int version;

  Money money(String wire) => Money.fromWire(wire, currency);

  ProjectionFacts get facts => ProjectionFacts(
    currency: currency,
    targetMonth: targetMonth,
    monthlyContribution: monthlyContribution,
    reached: reached,
    overdue: overdue,
    projection: projection,
  );
}

/// One page of goals.
final class GoalPage {
  const GoalPage({required this.content});

  factory GoalPage.fromJson(JsonMap json) =>
      GoalPage(content: [for (final g in json.objects('content')) Goal.fromJson(g)]);

  final List<Goal> content;
}

/// One step of the monthly plan: from this month on, this much.
final class PlanStep {
  const PlanStep({required this.fromPeriod, required this.monthlyAmount, required this.currency});

  factory PlanStep.fromJson(JsonMap json) => PlanStep(
    fromPeriod: json.month('fromPeriod'),
    monthlyAmount: json.amount('monthlyAmount'),
    currency: json.string('currency'),
  );

  final String fromPeriod;
  final String monthlyAmount;
  final String currency;
}

final class PlanStepPage {
  const PlanStepPage({required this.content});

  factory PlanStepPage.fromJson(JsonMap json) =>
      PlanStepPage(content: [for (final s in json.objects('content')) PlanStep.fromJson(s)]);

  final List<PlanStep> content;
}

/// One recorded contribution. A negative amount is a withdrawal, and is said as one.
final class Contribution {
  const Contribution({
    required this.id,
    required this.date,
    required this.amount,
    required this.currency,
    this.note,
  });

  factory Contribution.fromJson(JsonMap json) => Contribution(
    id: json.string('id'),
    date: CalendarDate.parse(json.string('date')),
    amount: json.amount('amount'),
    currency: json.string('currency'),
    note: json.stringOrNull('note'),
  );

  final String id;
  final CalendarDate date;
  final String amount;
  final String currency;
  final String? note;

  bool get withdrawal => isNegativeWire(amount);

  Money money() => Money.fromWire(amount, currency);
}

/// One page of contributions, newest first, in **the server's order**.
final class ContributionPage {
  const ContributionPage({required this.content, required this.totalElements});

  factory ContributionPage.fromJson(JsonMap json) => ContributionPage(
    content: [for (final c in json.objects('content')) Contribution.fromJson(c)],
    totalElements: json.integer('totalElements'),
  );

  final List<Contribution> content;
  final int totalElements;
}
