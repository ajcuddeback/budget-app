import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/harness.dart';
import 'golden_harness.dart';
import 'plans_golden_helpers.dart';

/// Goals and a goal's page, rendered from `goals.generated.json` (MobilePlansFixtureIT): every
/// variant of both screens, their states, and the sheets. **Read them.**
void main() => runGoldens(scenarios);

Future<void> _openGoals(WidgetTester tester) async {
  await tapText(tester, 'More');
  await tapText(tester, 'Goals');
}

Future<TestPhone> goalsPhone(
  WidgetTester tester,
  Variant v, {
  String state = 'plans',
  String who = 'owner',
}) => plansPhone(tester, v, file: 'goals', open: _openGoals, state: state, who: who);

/// The page of the goal called [name].
Future<TestPhone> goalPhone(
  WidgetTester tester,
  Variant v, {
  String name = 'Japan',
  String state = 'plans',
  String who = 'owner',
}) async {
  final phone = await goalsPhone(tester, v, state: state, who: who);
  await tapVisible(tester, openGoal(name));
  return phone;
}

/// The list's *Open ›* for the goal called [name]: its accessible name carries the goal's.
Finder openGoal(String name) =>
    find.byWidgetPredicate((w) => w is Semantics && w.properties.label == 'Open $name');

final List<Scenario> scenarios = [
  const Scenario('goals', everyWay, goalsPhone),

  Scenario('goals-list', bothThemes, (tester, v) async {
    final phone = await goalsPhone(tester, v);
    await scrollTo(tester, 'Emergency fund');
    return phone;
  }),

  Scenario('goals-archived', bothThemes, (tester, v) async {
    final phone = await goalsPhone(tester, v);
    await tapText(tester, 'Show archived goals');
    await scrollTo(tester, 'Archived goals');
    return phone;
  }),

  Scenario('goals-empty', bothThemes, (tester, v) => goalsPhone(tester, v, state: 'empty')),

  Scenario('goals-viewer', bothThemes, (tester, v) {
    return goalsPhone(tester, v, state: 'viewer', who: 'viewer');
  }),

  Scenario('goals-offline-stale', bothThemes, (tester, v) async {
    final phone = await goalsPhone(tester, v);
    await goStale(tester, phone);
    return phone;
  }),

  Scenario('goals-error', bothThemes, (tester, v) {
    return errorPhone(
      tester,
      v,
      file: 'goals',
      beforeOpen: (tester) => tapText(tester, 'More'),
      open: (tester) => tapText(tester, 'Goals'),
    );
  }),

  Scenario('goal-add', bothThemes, (tester, v) async {
    final phone = await goalsPhone(tester, v);
    await tapText(tester, 'Add a goal');
    await tester.enterText(find.widgetWithText(TextFormField, 'How much it needs'), '900');
    await settlePreview(tester);
    return phone;
  }),

  const Scenario('goal-detail', everyWay, goalPhone),

  Scenario('goal-detail-plan', bothThemes, (tester, v) async {
    final phone = await goalPhone(tester, v);
    await scrollTo(tester, 'The plan');
    return phone;
  }),

  Scenario('goal-detail-history', bothThemes, (tester, v) async {
    final phone = await goalPhone(tester, v, name: 'Emergency fund');
    await scrollTo(tester, 'History');
    return phone;
  }),

  Scenario('goal-detail-more', bothThemes, (tester, v) async {
    final phone = await goalPhone(tester, v, name: 'Emergency fund');
    await tapText(tester, 'Show more');
    await scrollTo(tester, 'Jun 1, 2025');
    return phone;
  }),

  Scenario('goal-detail-reached', bothThemes, (tester, v) {
    return goalPhone(tester, v, name: 'New laptop');
  }),

  Scenario('goal-detail-viewer', bothThemes, (tester, v) {
    return goalPhone(tester, v, state: 'viewer', who: 'viewer');
  }),

  Scenario('goal-edit', bothThemes, (tester, v) async {
    final phone = await goalPhone(tester, v);
    await tapText(tester, 'Edit');
    await settlePreview(tester);
    return phone;
  }),

  Scenario('goal-plan', bothThemes, (tester, v) async {
    final phone = await goalPhone(tester, v, state: 'goal-edited');
    await tapText(tester, 'Change');
    return phone;
  }),

  Scenario('set-aside', bothThemes, (tester, v) async {
    final phone = await goalPhone(tester, v);
    await tapText(tester, 'Set money aside');
    return phone;
  }),

  Scenario('take-out', bothThemes, (tester, v) async {
    final phone = await goalPhone(tester, v, name: 'Emergency fund');
    await tapText(tester, 'Take money out');
    return phone;
  }),
];
