import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/features/goals/domain/goal.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/said.dart';
import 'package:budget_owl/shared/year_month.dart';

/// Goal request bodies and the projection in words, built as the web's `goal-patch.ts` builds them.
///
/// The two decisions most likely to go wrong silently are made here, once, and tested: **what a
/// `PATCH` omits versus what it sends as `null`**, and **which sentence a reason code gets**.
/// Nothing does arithmetic on money: amounts are compared exactly and passed on as typed.

/// The add and edit sheets' fields, as typed. Empty means "nothing entered".
final class GoalForm {
  const GoalForm({
    required this.name,
    required this.targetAmount,
    this.targetMonth,
    this.startingAmount = '',
    this.monthlyContribution = '',
    this.currency = '',
    this.accountId,
  });

  final String name;
  final String targetAmount;

  /// `YYYY-MM`, or null.
  final String? targetMonth;
  final String startingAmount;
  final String monthlyContribution;
  final String currency;
  final String? accountId;
}

/// `POST /api/goals`, with nothing sent that was not entered. The account owns its currency, and
/// the server refuses the two together, so exactly one of them is ever sent.
Map<String, Object?> createGoalBody(GoalForm form) => {
  'name': form.name.trim(),
  'targetAmount': form.targetAmount.trim(),
  if (form.targetMonth != null) 'targetMonth': form.targetMonth,
  if (form.startingAmount.trim().isNotEmpty) 'startingAmount': form.startingAmount.trim(),
  if (form.monthlyContribution.trim().isNotEmpty)
    'monthlyContribution': form.monthlyContribution.trim(),
  if (form.accountId != null)
    'accountId': form.accountId
  else if (form.currency.trim().isNotEmpty)
    'currency': form.currency.trim().toUpperCase(),
};

/// `PATCH /api/goals/{id}` with **only what changed**: three-way on the month and the account (an
/// untouched field is absent, a cleared one `null`). Sending every field would quietly put back
/// another member's month the moment two people edit one goal.
Map<String, Object?> goalPatch(Goal goal, GoalForm form) {
  final patch = <String, Object?>{'version': goal.version};
  final name = form.name.trim();
  if (name != goal.name) patch['name'] = name;
  if (!sameAmount(form.targetAmount.trim(), goal.targetAmount)) {
    patch['targetAmount'] = form.targetAmount.trim();
  }
  final start = form.startingAmount.trim().isEmpty ? '0' : form.startingAmount.trim();
  if (!sameAmount(start, goal.startingAmount)) patch['startingAmount'] = start;
  if (form.targetMonth != goal.targetMonth) patch['targetMonth'] = form.targetMonth;
  if (form.accountId != goal.accountId) patch['accountId'] = form.accountId;
  return patch;
}

bool patchIsEmpty(Map<String, Object?> patch) => patch.keys.every((k) => k == 'version');

/// What the edit sheet asks the server to project: the goal with the typed target, month and
/// start substituted. Null while an amount is half-typed — a question the server would refuse.
Map<String, Object?>? editPreviewBody(Goal goal, GoalForm form) {
  final target = form.targetAmount.trim();
  final start = form.startingAmount.trim().isEmpty ? '0' : form.startingAmount.trim();
  if (!isTypedAmount(target) || !isTypedAmount(start)) return null;
  return {
    if (!sameAmount(target, goal.targetAmount)) 'targetAmount': target,
    if (!sameAmount(start, goal.startingAmount)) 'startingAmount': start,
    if (form.targetMonth != goal.targetMonth) 'targetMonth': form.targetMonth,
  };
}

/// What the add sheet asks before the goal exists, or null until there is a target.
Map<String, Object?>? draftPreviewBody(GoalForm form) {
  final target = form.targetAmount.trim();
  final start = form.startingAmount.trim();
  final monthly = form.monthlyContribution.trim();
  if (!isTypedAmount(target)) return null;
  if (start.isNotEmpty && !isTypedAmount(start)) return null;
  if (monthly.isNotEmpty && !isTypedAmount(monthly)) return null;
  return {
    'targetAmount': target,
    if (form.currency.trim().isNotEmpty && form.accountId == null)
      'currency': form.currency.trim().toUpperCase(),
    if (start.isNotEmpty) 'startingAmount': start,
    if (form.targetMonth != null) 'targetMonth': form.targetMonth,
    if (monthly.isNotEmpty) 'monthlyAmount': monthly,
  };
}

/// Which way a contribution goes. **Taking money out is its own sheet**, not a minus sign typed
/// into the first one.
enum ContributionDirection { into, out }

/// `POST /api/goals/{id}/contributions`, signed by the direction chosen — string surgery on
/// already-checked digits, no arithmetic. The currency is the goal's and is not sent.
Map<String, Object?> contributionBody({
  required String amount,
  required CalendarDate date,
  required ContributionDirection direction,
  String note = '',
}) => {
  'amount': direction == ContributionDirection.out ? '-${amount.trim()}' : amount.trim(),
  'date': date.iso,
  if (note.trim().isNotEmpty) 'note': note.trim(),
};

/// `PUT /api/goals/{id}/contribution-plan/{fromPeriod}`: from that month on, this amount. Uses the
/// goal's version.
Map<String, Object?> planBody({required String monthlyAmount, required int version}) => {
  'monthlyAmount': monthlyAmount.trim(),
  'version': version,
};

/// Whether the projected month comes from one amount throughout (`oneAmount`), or from a plan that
/// has — or, on a list row that cannot see the steps, may have — a later step.
enum PlanShape { oneAmount, mayChange }

String _projectedReasonKey(String reason) => switch (reason) {
  'no-contribution' => 'goals.reason.noContribution',
  'beyond-horizon' => 'goals.reason.beyondHorizon',
  'reached' => 'goals.reason.reached',
  'no-target-month' => 'goals.reason.noTargetMonth',
  'target-month-passed' => 'goals.reason.targetMonthPassed',
  _ => 'goals.reason.notReachable',
};

/// The sentence for a reason the required figure is absent. Never a blank.
String requiredReasonKey(String reason) => switch (reason) {
  'no-contribution' => 'goals.required.noContribution',
  'beyond-horizon' => 'goals.required.beyondHorizon',
  'reached' => 'goals.reason.reached',
  'no-target-month' => 'goals.required.noTargetMonth',
  'target-month-passed' => 'goals.required.targetMonthPassed',
  _ => 'goals.reason.notReachable',
};

/// The projection in words — *"At $110.00 a month you reach it in December 2027 — 1 month after
/// November 2027. $163.13 a month gets you there by November 2027."* Every reason code has its own
/// sentence. The one thing worked out here is how many months late, which is date arithmetic.
List<Said> projectionSaid(
  ProjectionFacts goal, {
  required String Function(String wire, String currency) money,
  required String Function(String? wire) month,
  required String Function(int count) count,
  required PlanShape plan,
}) {
  if (goal.reached) return const [Said('goals.reason.reached')];
  final projection = goal.projection;
  final monthly = money(goal.monthlyContribution, goal.currency);
  final steady = plan == PlanShape.oneAmount;
  final said = <Said>[];
  final target = goal.targetMonth;
  final projected = projection.projectedMonth;
  if (projected != null) {
    final reaches = month(projected);
    if (goal.overdue && target != null) {
      said.add(
        Said(steady ? 'goals.projection.overdueReaches' : 'goals.projection.planOverdueReaches', {
          'target': month(target),
          'monthly': monthly,
          'month': reaches,
        }),
      );
    } else if (target != null) {
      final late = YearMonth.parse(target).monthsUntil(YearMonth.parse(projected));
      if (late > 0) {
        final key = steady
            ? (late == 1 ? 'goals.projection.lateOne' : 'goals.projection.late')
            : (late == 1 ? 'goals.projection.planLateOne' : 'goals.projection.planLate');
        said.add(
          Said(key, {
            'monthly': monthly,
            'month': reaches,
            'count': count(late),
            'target': month(target),
          }),
        );
      } else {
        said.add(
          Said(steady ? 'goals.projection.onTime' : 'goals.projection.planOnTime', {
            'monthly': monthly,
            'month': reaches,
            'target': month(target),
          }),
        );
      }
    } else {
      said.add(
        Said(steady ? 'goals.projection.reaches' : 'goals.projection.planReaches', {
          'monthly': monthly,
          'month': reaches,
        }),
      );
    }
  } else if (projection.projectedReason != null) {
    if (goal.overdue && target != null) {
      said.add(Said('goals.projection.overdue', {'target': month(target)}));
    }
    said.add(Said(_projectedReasonKey(projection.projectedReason!), {'monthly': monthly}));
  }
  final required = projection.requiredMonthly;
  final requiredReason = projection.requiredReason;
  if (required != null && target != null) {
    said.add(
      Said('goals.projection.required', {
        'amount': money(required, goal.currency),
        'target': month(target),
      }),
    );
  } else if (requiredReason != null &&
      requiredReason != 'no-target-month' &&
      requiredReason != 'target-month-passed') {
    // No month and a passed month are already said above, or need no saying on a list row.
    said.add(Said(requiredReasonKey(requiredReason), {'target': month(target)}));
  }
  return said;
}

/// The month after [period], for "from next month".
String nextMonth(String period) => YearMonth.parse(period).shift(1).wire;

/// The magnitude of a negative wire amount, by dropping its sign — string surgery, as the web
/// does it to say how far over a reached goal is.
String unsigned(String wire) => wire.startsWith('-') ? wire.substring(1) : wire;

/// Whether a wire amount is above zero.
bool isPositiveWire(String wire) => (signOf(wire) ?? 0) > 0;

/// The money helper the screens pass in, kept here so tests can build sentences without a widget.
Money moneyOf(String wire, String currency) => Money.fromWire(wire, currency);
