import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/fixture_server.dart';
import '../support/harness.dart';
import 'golden_harness.dart';

/// Categories and payee rules (More → Categories), from `categories.generated.json`
/// (MobileAccountsFixtureIT): light and dark, right-to-left and text 2.0 for the screen, and its
/// `VIEWER`, offline-stale and error states. `tools/mobile-check.sh` renders them; **read them**.
void main() => runGoldens(scenarios);

const List<Variant> _sheet = [light, dark, large];

Future<TestPhone> _categories(
  WidgetTester tester,
  Variant v, {
  String state = 'categories',
  String who = 'owner',
}) async {
  final phone = await signedInPhone(tester, v, file: 'categories', state: state, who: who);
  await tapText(tester, 'More');
  await tapText(tester, 'Categories');
  return phone;
}

final List<Scenario> scenarios = [
  const Scenario('categories', everyWay, _categories),

  Scenario('categories-viewer', bothThemes, (tester, v) {
    return _categories(tester, v, state: 'categories-viewer', who: 'viewer');
  }),

  Scenario('categories-rules', bothThemes, (tester, v) async {
    final phone = await _categories(tester, v);
    await tapText(tester, 'Show payee rules (3)');
    await revealIn(tester, find.text('SQ *BLUE BOTTLE'));
    return phone;
  }),

  Scenario('categories-viewer-rules', bothThemes, (tester, v) async {
    final phone = await _categories(tester, v, state: 'categories-viewer', who: 'viewer');
    await tapText(tester, 'Show payee rules (3)');
    await revealIn(tester, find.text('SQ *BLUE BOTTLE'));
    return phone;
  }),

  Scenario('categories-archived', bothThemes, (tester, v) async {
    final phone = await _categories(tester, v);
    await tapText(tester, 'Show 1 archived');
    await revealIn(tester, find.text('Wedding'));
    return phone;
  }),

  Scenario('categories-offline-stale', bothThemes, (tester, v) async {
    final phone = await _categories(tester, v);
    (phone.server as FixtureServer).offline = true;
    phone.now = demoNow.add(const Duration(hours: 2));
    await tester.fling(find.byType(ListView).last, const Offset(0, 400), 1000);
    await settle(tester);
    return phone;
  }),

  Scenario('categories-error', bothThemes, (tester, v) async {
    final server = FixtureServer.load('categories', state: 'categories')
      ..offlineAfter = 'GET /api/auth/me';
    final phone = TestPhone(server: server)..signedIn('owner');
    await pumpPhone(tester, v, phone);
    await tapText(tester, 'More');
    await tapText(tester, 'Categories');
    return phone;
  }),

  Scenario('categories-row-actions', bothThemes, (tester, v) async {
    final phone = await _categories(tester, v);
    await tapText(tester, 'Transport');
    return phone;
  }),

  Scenario('category-delete-children', bothThemes, (tester, v) async {
    final phone = await _categories(tester, v);
    await tapText(tester, 'Transport');
    await tapText(tester, 'Delete');
    return phone;
  }),

  Scenario('category-add', _sheet, (tester, v) async {
    final phone = await _categories(tester, v);
    await tapText(tester, 'Add a category');
    return phone;
  }),

  Scenario('category-edit-child', bothThemes, (tester, v) async {
    final phone = await _categories(tester, v);
    await tapText(tester, 'Fuel');
    await tapText(tester, 'Edit');
    return phone;
  }),
];
