import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/fixture_server.dart';
import '../support/harness.dart';
import 'golden_harness.dart';

/// The Accounts tab, from `accounts.generated.json` (MobileAccountsFixtureIT): light and dark,
/// right-to-left and text 2.0 for the screen, and its `VIEWER`, offline-stale and error states.
/// `tools/mobile-check.sh` renders them; **read them**.
void main() => runGoldens(scenarios);

const List<Variant> _sheet = [light, dark, large];

Future<TestPhone> _accounts(
  WidgetTester tester,
  Variant v, {
  String state = 'accounts',
  String who = 'owner',
}) async {
  final phone = await signedInPhone(tester, v, file: 'accounts', state: state, who: who);
  await tapText(tester, 'Accounts');
  return phone;
}

final List<Scenario> scenarios = [
  const Scenario('accounts', everyWay, _accounts),

  Scenario('accounts-archived', bothThemes, (tester, v) async {
    final phone = await _accounts(tester, v);
    await tapText(tester, 'Show 2 archived');
    await revealIn(tester, find.text('Old Joint Account'));
    return phone;
  }),

  Scenario('accounts-viewer', bothThemes, (tester, v) {
    return _accounts(tester, v, state: 'accounts-viewer', who: 'viewer');
  }),

  Scenario('accounts-offline-stale', bothThemes, (tester, v) async {
    final phone = await _accounts(tester, v);
    (phone.server as FixtureServer).offline = true;
    phone.now = demoNow.add(const Duration(hours: 2));
    await tester.fling(find.byType(ListView).last, const Offset(0, 400), 1000);
    await settle(tester);
    return phone;
  }),

  Scenario('accounts-error', bothThemes, (tester, v) async {
    final server = FixtureServer.load('accounts', state: 'accounts')
      ..offlineAfter = 'GET /api/auth/me';
    final phone = TestPhone(server: server)..signedIn('owner');
    await pumpPhone(tester, v, phone);
    await tapText(tester, 'Accounts');
    return phone;
  }),

  Scenario('accounts-row-actions', bothThemes, (tester, v) async {
    final phone = await _accounts(tester, v);
    await tapText(tester, 'Everyday Checking •4417');
    return phone;
  }),

  Scenario('account-add', _sheet, (tester, v) async {
    final phone = await _accounts(tester, v);
    await tapText(tester, 'Add an account');
    return phone;
  }),

  Scenario('account-add-refused', bothThemes, (tester, v) async {
    final phone = await _accounts(tester, v);
    await tapText(tester, 'Add an account');
    await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'Cash Tin');
    await _chooseType(tester, 'Cash');
    await tester.enterText(find.widgetWithText(TextFormField, 'Currency'), 'ZZZ');
    await tester.enterText(find.widgetWithText(TextFormField, 'Opening balance'), '40.00');
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save account'));
    await tester.drag(find.byType(Scrollable).last, const Offset(0, 3000));
    await settle(tester);
    return phone;
  }),

  Scenario('account-edit-locked', bothThemes, (tester, v) async {
    final phone = await _accounts(tester, v);
    await tapText(tester, 'Everyday Checking •4417');
    await tapText(tester, 'Edit');
    await revealIn(tester, find.widgetWithText(TextFormField, 'Opening balance'));
    return phone;
  }),

  Scenario('account-conflict', bothThemes, (tester, v) async {
    final phone = await _accounts(tester, v, state: 'account-deleted');
    await tapText(tester, 'Everyday Checking •4417');
    await tapText(tester, 'Edit');
    await revealIn(tester, find.widgetWithText(TextFormField, 'Opening balance'));
    await tester.enterText(find.widgetWithText(TextFormField, 'Opening balance'), '1700.00');
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save account'));
    await tester.drag(find.byType(Scrollable).last, const Offset(0, 3000));
    await settle(tester);
    return phone;
  }),
];

Future<void> _chooseType(WidgetTester tester, String type) async {
  await tapVisible(tester, find.text('Current account').last);
  await tester.tap(find.text(type).last);
  await settle(tester);
}
