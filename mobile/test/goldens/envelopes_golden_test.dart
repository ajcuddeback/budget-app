import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/fixture_server.dart';
import '../support/harness.dart';
import 'golden_harness.dart';

/// Envelopes (More → Envelopes), from `envelopes.generated.json` (MobileAccountsFixtureIT): light
/// and dark, right-to-left and text 2.0 for the screen, and its `VIEWER`, offline-stale and error
/// states. `tools/mobile-check.sh` renders them; **read them**.
void main() => runGoldens(scenarios);

const List<Variant> _sheet = [light, dark, large];

Future<TestPhone> _envelopes(
  WidgetTester tester,
  Variant v, {
  String state = 'envelopes',
  String who = 'owner',
}) async {
  final phone = await signedInPhone(tester, v, file: 'envelopes', state: state, who: who);
  await tapText(tester, 'More');
  await tapText(tester, 'Envelopes');
  return phone;
}

final List<Scenario> scenarios = [
  const Scenario('envelopes', everyWay, _envelopes),

  Scenario('envelopes-log', bothThemes, (tester, v) async {
    final phone = await _envelopes(tester, v);
    await tapText(tester, 'Show 3 entries');
    await revealIn(tester, find.text('big shop'));
    return phone;
  }),

  Scenario('envelopes-viewer', bothThemes, (tester, v) {
    return _envelopes(tester, v, state: 'envelopes-viewer', who: 'viewer');
  }),

  Scenario('envelopes-empty-month', bothThemes, (tester, v) async {
    final phone = await _envelopes(tester, v);
    await tester.tap(find.byTooltip('Next month'));
    await settle(tester);
    return phone;
  }),

  Scenario('envelopes-archived', bothThemes, (tester, v) async {
    final phone = await _envelopes(tester, v);
    await tapText(tester, 'Show 1 archived');
    await revealIn(tester, find.text('Holiday'));
    return phone;
  }),

  Scenario('envelopes-offline-stale', bothThemes, (tester, v) async {
    final phone = await _envelopes(tester, v);
    (phone.server as FixtureServer).offline = true;
    phone.now = demoNow.add(const Duration(hours: 2));
    await tester.fling(find.byType(ListView).last, const Offset(0, 400), 1000);
    await settle(tester);
    return phone;
  }),

  Scenario('envelopes-error', bothThemes, (tester, v) async {
    final phone = await signedInPhone(tester, v, file: 'envelopes', state: 'envelopes');
    (phone.server as FixtureServer).offlineAfter = 'GET /api/envelopes/periods';
    await tapText(tester, 'More');
    await tapText(tester, 'Envelopes');
    return phone;
  }),

  Scenario('envelope-quick-add', _sheet, (tester, v) async {
    final phone = await _envelopes(tester, v);
    await tapText(tester, 'Log spend');
    return phone;
  }),

  Scenario('envelope-add', bothThemes, (tester, v) async {
    final phone = await _envelopes(tester, v);
    await tapText(tester, 'Add an envelope');
    return phone;
  }),
];
