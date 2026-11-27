import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/harness.dart';
import 'golden_harness.dart';
import 'plans_golden_helpers.dart';

/// Bills & income, rendered from `bills.generated.json` (MobilePlansFixtureIT): the page in every
/// variant, its states, and its sheets. `tools/mobile-check.sh` renders them; **read them**.
void main() => runGoldens(scenarios);

Future<void> _openBills(WidgetTester tester) => tapText(tester, 'Bills');

Future<TestPhone> billsPhone(
  WidgetTester tester,
  Variant v, {
  String state = 'plans',
  String who = 'owner',
}) => plansPhone(tester, v, file: 'bills', open: _openBills, state: state, who: who);

final List<Scenario> scenarios = [
  const Scenario('bills', everyWay, billsPhone),

  Scenario('bills-income', everyWay, (tester, v) async {
    final phone = await billsPhone(tester, v);
    await scrollTo(tester, 'Income');
    return phone;
  }),

  Scenario('bills-list', everyWay, (tester, v) async {
    final phone = await billsPhone(tester, v);
    await scrollTo(tester, 'Your bills');
    return phone;
  }),

  Scenario('bills-weekly', bothThemes, (tester, v) async {
    final phone = await billsPhone(tester, v);
    await scrollTo(tester, 'Gym');
    return phone;
  }),

  Scenario('bills-retired-and-owed', bothThemes, (tester, v) async {
    final phone = await billsPhone(tester, v);
    await scrollTo(tester, 'Retired bills');
    return phone;
  }),

  Scenario('bills-owed', bothThemes, (tester, v) async {
    final phone = await billsPhone(tester, v);
    await scrollTo(tester, 'Owed');
    return phone;
  }),

  Scenario('bills-empty', bothThemes, (tester, v) async {
    final phone = await billsPhone(tester, v, state: 'empty');
    await scrollTo(tester, 'Income');
    return phone;
  }),

  Scenario('bills-viewer', bothThemes, (tester, v) async {
    final phone = await billsPhone(tester, v, state: 'viewer', who: 'viewer');
    await scrollTo(tester, 'Your bills');
    return phone;
  }),

  Scenario('bills-offline-stale', bothThemes, (tester, v) async {
    final phone = await billsPhone(tester, v);
    await goStale(tester, phone);
    return phone;
  }),

  Scenario('bills-error', bothThemes, (tester, v) {
    return errorPhone(tester, v, file: 'bills', beforeOpen: (_) async {}, open: _openBills);
  }),

  Scenario('bills-next-month', bothThemes, (tester, v) async {
    final phone = await billsPhone(tester, v);
    await tapVisible(tester, find.byTooltip('Next month'));
    return phone;
  }),

  Scenario('bills-correct-this', bothThemes, (tester, v) async {
    final phone = await billsPhone(tester, v);
    await scrollTo(tester, 'Phone');
    await tapVisible(tester, below(tester, find.text('Correct this'), 'Phone'));
    return phone;
  }),

  Scenario('bills-paid-other-amount', bothThemes, (tester, v) async {
    final phone = await billsPhone(tester, v);
    await scrollTo(tester, 'Aug 17, 2026');
    await tapVisible(tester, below(tester, find.text('Correct this'), 'Aug 17, 2026'));
    await tapText(tester, 'Paid a different amount…');
    return phone;
  }),

  Scenario('bills-irregular-record', bothThemes, (tester, v) async {
    final phone = await billsPhone(tester, v);
    await scrollTo(tester, 'Window cleaner');
    await tapVisible(tester, find.byTooltip('Actions for Window cleaner'));
    await tapText(tester, 'Record a payment');
    return phone;
  }),

  Scenario('bill-add', everyWay, (tester, v) async {
    final phone = await billsPhone(tester, v);
    await scrollTo(tester, 'Your bills');
    await tapText(tester, 'Add a bill');
    return phone;
  }),

  Scenario('bill-edit-varies', bothThemes, (tester, v) async {
    final phone = await billsPhone(tester, v);
    await scrollTo(tester, 'Electricity');
    await tapVisible(tester, find.byTooltip('Actions for Electricity'));
    await tapText(tester, 'Edit');
    await tester.drag(find.byType(Scrollable).last, const Offset(0, -500));
    await settle(tester);
    return phone;
  }),

  Scenario('income-edit-fortnightly', bothThemes, (tester, v) async {
    final phone = await billsPhone(tester, v);
    await scrollTo(tester, 'Salary');
    await tapVisible(tester, find.byTooltip('Actions for Salary'));
    await tapText(tester, 'Edit');
    return phone;
  }),
];
