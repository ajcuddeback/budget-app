import 'package:budget_owl/features/insights/data/reports_repository.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/fixture_server.dart';
import '../support/harness.dart';
import 'golden_harness.dart';

/// Insights, from the demo ledger the real API summed (MobileWeeklyFixtureIT), and its two other
/// states from households of their own. `tools/mobile-check.sh` renders them; **read them**.
void main() => runGoldens(scenarios);

Future<TestPhone> _insights(
  WidgetTester tester,
  Variant v, {
  String file = 'insights',
  String state = 'demo',
  String who = 'owner',
}) async {
  final phone = await signedInPhone(tester, v, state: state, who: who, file: file);
  await tester.tap(find.byIcon(Icons.more_horiz));
  await settle(tester);
  await tapVisible(tester, find.byIcon(Icons.insights_outlined));
  return phone;
}

Future<void> _down(WidgetTester tester, Finder from, double by) async {
  await tester.drag(from, Offset(0, -by));
  await settle(tester);
}

final List<Scenario> scenarios = [
  const Scenario('insights', everyWay, _insights),

  Scenario('insights-where', bothThemes, (tester, v) async {
    final phone = await _insights(tester, v);
    await revealIn(tester, find.textContaining('WENT'));
    await _down(tester, find.textContaining('WENT'), 120);
    return phone;
  }),

  Scenario('insights-figures', bothThemes, (tester, v) async {
    final phone = await _insights(tester, v);
    await tapVisible(tester, find.text('Show the figures'));
    await _down(tester, find.text('Show the figures'), 300);
    return phone;
  }),

  Scenario('insights-viewer', bothThemes, (tester, v) {
    return _insights(tester, v, state: 'viewer', who: 'viewer');
  }),

  Scenario('insights-empty', bothThemes, (tester, v) {
    return _insights(tester, v, file: 'insights-states', state: 'empty');
  }),

  Scenario('insights-two-currencies', bothThemes, (tester, v) async {
    final phone = await _insights(tester, v, file: 'insights-states', state: 'two-currencies');
    await tapVisible(tester, find.text('EUR'));
    return phone;
  }),

  Scenario('insights-offline-stale', bothThemes, (tester, v) async {
    final phone = await _insights(tester, v);
    (phone.server as FixtureServer).offline = true;
    phone.now = demoNow.add(const Duration(hours: 2));
    final container = ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));
    await tester.runAsync(
      () => container
          .read(incomeAndSpendingProvider((from: '2025-09', to: '2026-08')).notifier)
          .refresh(),
    );
    await settle(tester);
    return phone;
  }),

  Scenario('insights-error', bothThemes, (tester, v) async {
    final server = FixtureServer.load('insights', state: 'demo')
      ..offlineAfter = 'GET /api/transactions';
    final phone = TestPhone(server: server)..signedIn('owner');
    await pumpPhone(tester, v, phone);
    await tester.tap(find.byIcon(Icons.more_horiz));
    await settle(tester);
    await tapVisible(tester, find.byIcon(Icons.insights_outlined));
    return phone;
  }),
];
