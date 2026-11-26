import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/features/goals/domain/goal.dart';
import 'package:budget_owl/features/goals/domain/requests.dart';
import 'package:budget_owl/shared/said.dart';
import 'package:flutter_test/flutter_test.dart';

import '../recorded.dart';

/// Every body the goal screens send, against the body the real API accepted in
/// `goals.generated.json`, and the projection's sentences for each reason code.
void main() {
  final recorded = Recorded('goals');
  const goalsPath = '/api/goals?includeArchived=true&page=0&size=200';

  Goal goal(String state, String id) =>
      GoalPage.fromJson(recorded.answer(state, goalsPath)).content.firstWhere((g) => g.id == id);

  GoalForm formOf(Goal g, {String? targetAmount, String? Function()? targetMonth}) => GoalForm(
    name: g.name,
    targetAmount: targetAmount ?? g.targetAmount,
    targetMonth: targetMonth == null ? g.targetMonth : targetMonth(),
    startingAmount: g.startingAmount,
    accountId: g.accountId,
  );

  List<Said> say(ProjectionFacts facts, {PlanShape plan = PlanShape.oneAmount}) => projectionSaid(
    facts,
    money: (wire, currency) => '$currency $wire',
    month: (wire) => wire ?? '-',
    count: (n) => '$n',
    plan: plan,
  );

  group('bodies', () {
    test('adding sends only what was entered', () {
      expect(
        createGoalBody(const GoalForm(name: ' Bike ', targetAmount: '900')),
        recorded.sent('plans', 'POST', '/api/goals'),
      );
    });

    test('an account and a currency are never sent together', () {
      final body = createGoalBody(
        const GoalForm(name: 'Fund', targetAmount: '900', currency: 'eur', accountId: 'acc-0002'),
      );
      expect(body['accountId'], 'acc-0002');
      expect(body.containsKey('currency'), isFalse);
      expect(
        createGoalBody(
          const GoalForm(name: 'Fund', targetAmount: '900', currency: 'eur'),
        )['currency'],
        'EUR',
      );
    });

    test('an edit sends the version and only the target', () {
      final japan = goal('taken-out', 'gol-0001');
      expect(
        goalPatch(japan, formOf(japan, targetAmount: '4000')),
        recorded.sent('taken-out', 'PATCH', '/api/goals/gol-0001'),
      );
    });

    test('the form as it opens changes nothing, whatever the figures look like', () {
      for (final g in GoalPage.fromJson(recorded.answer('plans', goalsPath)).content) {
        expect(patchIsEmpty(goalPatch(g, formOf(g))), isTrue, reason: g.name);
      }
    });

    test('clearing the month sends null; leaving it sends nothing', () {
      final japan = goal('plans', 'gol-0001');
      final cleared = goalPatch(japan, formOf(japan, targetMonth: () => null));
      expect(cleared, {'version': japan.version, 'targetMonth': null});
    });

    test('setting money aside, and taking it out as its own direction', () {
      expect(
        contributionBody(
          amount: '110',
          date: CalendarDate.parse('2026-08-20'),
          direction: ContributionDirection.into,
        ),
        recorded.sent('goal-added', 'POST', '/api/goals/gol-0001/contributions'),
      );
      expect(
        contributionBody(
          amount: ' 50 ',
          date: CalendarDate.parse('2026-08-20'),
          direction: ContributionDirection.out,
          note: ' Vet ',
        ),
        recorded.sent('set-aside', 'POST', '/api/goals/gol-0002/contributions'),
      );
    });

    test("the plan sends its amount with the goal's version", () {
      expect(
        planBody(monthlyAmount: '165', version: goal('goal-edited', 'gol-0001').version),
        recorded.sent('goal-edited', 'PUT', '/api/goals/gol-0001/contribution-plan/2026-08'),
      );
    });

    test('previews ask with only what was changed', () {
      final japan = goal('plans', 'gol-0001');
      expect(editPreviewBody(japan, formOf(japan)), <String, Object?>{});
      expect(editPreviewBody(japan, formOf(japan, targetAmount: '4000')), {'targetAmount': '4000'});
      expect(
        editPreviewBody(japan, formOf(japan, targetAmount: '40.')),
        isNull,
        reason: 'half-typed',
      );
      expect(
        draftPreviewBody(const GoalForm(name: '', targetAmount: '900')),
        recorded.sent('plans', 'POST', '/api/goals/projection-preview'),
      );
      expect(draftPreviewBody(const GoalForm(name: 'Bike', targetAmount: '')), isNull);
    });

    test('archiving and restoring carry an empty body', () {
      expect(
        recorded.sent('contribution-removed', 'POST', '/api/goals/gol-0003/archive'),
        <String, Object?>{},
      );
      expect(
        recorded.sent('archived', 'POST', '/api/goals/gol-0003/unarchive'),
        <String, Object?>{},
      );
    });
  });

  group('the projection in words', () {
    test('late by a month, and what it would take', () {
      final said = say(goal('plans', 'gol-0001').facts);
      expect(said.map((s) => s.key), ['goals.projection.lateOne', 'goals.projection.required']);
      expect(said.first.params['count'], '1');
    });

    test('a plan with a later step says so, not "at this amount"', () {
      final said = say(goal('plans', 'gol-0001').facts, plan: PlanShape.mayChange);
      expect(said.first.key, 'goals.projection.planLateOne');
    });

    test('a reached goal says only that', () {
      expect(say(goal('plans', 'gol-0003').facts).map((s) => s.key), ['goals.reason.reached']);
    });

    test('a goal with no month says when it gets there and asks nothing more', () {
      final said = say(goal('plans', 'gol-0002').facts);
      expect(said.map((s) => s.key), ['goals.projection.reaches']);
    });

    test('every required-figure reason has its own sentence, never a blank', () {
      for (final reason in [
        'no-contribution',
        'beyond-horizon',
        'reached',
        'no-target-month',
        'target-month-passed',
        'something-new',
      ]) {
        expect(requiredReasonKey(reason), isNotEmpty, reason: reason);
      }
      expect(requiredReasonKey('something-new'), 'goals.reason.notReachable');
    });

    test('how far over a reached goal is drops the sign — string surgery, no arithmetic', () {
      final laptop = goal('plans', 'gol-0003');
      expect(laptop.remaining, startsWith('-'));
      expect(unsigned(laptop.remaining), laptop.remaining.substring(1));
      expect(nextMonth('2026-12'), '2027-01');
    });
  });
}
