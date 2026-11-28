import 'package:flutter_test/flutter_test.dart';

import '../support/harness.dart';
import 'golden_harness.dart';
import 'plans_golden_helpers.dart';

/// The debt plan and debt detail, rendered from `debt.generated.json` (MobilePlansFixtureIT): every
/// variant of both screens, the plan's states, and the three sheets. **Read them.**
void main() => runGoldens(scenarios);

Future<void> _openPlan(WidgetTester tester) async {
  await tapText(tester, 'More');
  await tapText(tester, 'Debt plan');
}

Future<TestPhone> planPhone(
  WidgetTester tester,
  Variant v, {
  String state = 'plans',
  String who = 'owner',
}) => plansPhone(tester, v, file: 'debt', open: _openPlan, state: state, who: who);

/// The detail of the debt on rung 1 (the Visa in `plans`).
Future<TestPhone> detailPhone(
  WidgetTester tester,
  Variant v, {
  String state = 'plans',
  String who = 'owner',
}) async {
  final phone = await planPhone(tester, v, state: state, who: who);
  await tapText(tester, 'Your debts');
  await tapVisible(tester, below(tester, find.text('Open'), 'RUNG 1'));
  return phone;
}

final List<Scenario> scenarios = [
  const Scenario('debt-plan', everyWay, planPhone),

  Scenario('debt-plan-chart', everyWay, (tester, v) async {
    final phone = await planPhone(tester, v);
    await scrollTo(tester, 'Balance to zero');
    return phone;
  }),

  Scenario('debt-plan-compare', bothThemes, (tester, v) async {
    final phone = await planPhone(tester, v);
    await scrollTo(tester, 'Avalanche or snowball');
    return phone;
  }),

  Scenario('debt-plan-ladder', everyWay, (tester, v) async {
    final phone = await planPhone(tester, v);
    await scrollTo(tester, 'Your debts');
    return phone;
  }),

  Scenario('debt-plan-custom', bothThemes, (tester, v) async {
    final phone = await planPhone(tester, v, state: 'custom');
    await scrollTo(tester, 'Your debts');
    return phone;
  }),

  Scenario('debt-plan-custom-chart', bothThemes, (tester, v) async {
    final phone = await planPhone(tester, v, state: 'custom');
    await scrollTo(tester, 'Balance to zero');
    return phone;
  }),

  Scenario('debt-plan-extra-exceeds-left', bothThemes, (tester, v) {
    return planPhone(tester, v, state: 'amount');
  }),

  Scenario('debt-plan-below-minimums', bothThemes, (tester, v) async {
    final phone = await planPhone(tester, v, state: 'below');
    await scrollTo(tester, 'Balance to zero');
    return phone;
  }),

  Scenario('debt-plan-unranked-stale', bothThemes, (tester, v) async {
    final phone = await planPhone(tester, v, state: 'stale-added');
    await scrollTo(tester, 'Your debts');
    return phone;
  }),

  Scenario('debt-plan-empty', bothThemes, (tester, v) => planPhone(tester, v, state: 'empty')),

  Scenario('debt-plan-viewer', bothThemes, (tester, v) async {
    final phone = await planPhone(tester, v, state: 'viewer', who: 'viewer');
    await scrollTo(tester, 'Order of attack');
    return phone;
  }),

  Scenario('debt-plan-offline-stale', bothThemes, (tester, v) async {
    final phone = await planPhone(tester, v);
    await goStale(tester, phone);
    return phone;
  }),

  Scenario('debt-plan-error', bothThemes, (tester, v) {
    return errorPhone(
      tester,
      v,
      file: 'debt',
      beforeOpen: (tester) => tapText(tester, 'More'),
      open: (tester) => tapText(tester, 'Debt plan'),
    );
  }),

  Scenario('debt-plan-amount', bothThemes, (tester, v) async {
    final phone = await planPhone(tester, v);
    await tapText(tester, 'Change the monthly amount');
    return phone;
  }),

  Scenario('debt-add', bothThemes, (tester, v) async {
    final phone = await planPhone(tester, v);
    await tapText(tester, 'Add a debt');
    return phone;
  }),

  const Scenario('debt-detail', everyWay, detailPhone),

  Scenario('debt-detail-chart', everyWay, (tester, v) async {
    final phone = await detailPhone(tester, v);
    await scrollTo(tester, 'Balance over time');
    return phone;
  }),

  Scenario('debt-detail-history', bothThemes, (tester, v) async {
    final phone = await detailPhone(tester, v);
    await scrollTo(tester, 'Payment history');
    return phone;
  }),

  Scenario('debt-detail-estimated', bothThemes, (tester, v) {
    return detailPhone(tester, v, state: 'debt-added');
  }),

  Scenario('debt-detail-stale', bothThemes, (tester, v) async {
    final phone = await detailPhone(tester, v, state: 'stale-added');
    await scrollTo(tester, 'Balance now');
    return phone;
  }),

  Scenario('debt-detail-viewer', bothThemes, (tester, v) {
    return detailPhone(tester, v, state: 'viewer', who: 'viewer');
  }),

  Scenario('debt-edit-terms', bothThemes, (tester, v) async {
    final phone = await detailPhone(tester, v);
    await tapText(tester, 'Edit terms');
    return phone;
  }),

  Scenario('log-payment', everyWay, (tester, v) async {
    final phone = await detailPhone(tester, v);
    await tapText(tester, 'Log a payment');
    await settlePreview(tester);
    return phone;
  }),

  Scenario('confirm-estimate', bothThemes, (tester, v) async {
    final phone = await detailPhone(tester, v, state: 'debt-added');
    await tapText(tester, 'Confirm the balance');
    return phone;
  }),

  Scenario('confirm-stale', bothThemes, (tester, v) async {
    final phone = await detailPhone(tester, v, state: 'stale-added');
    await tapText(tester, 'Confirm the balance');
    return phone;
  }),
];
