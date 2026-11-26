import 'package:budget_owl/features/check_in/data/check_in_repository.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/fixture_server.dart';
import '../support/harness.dart';
import 'golden_harness.dart';

/// The weekly check-in, one step per screen, from the canvas week the real API recorded
/// (MobileWeeklyFixtureIT). `tools/mobile-check.sh` renders them; **read them**.
void main() => runGoldens(scenarios);

Future<TestPhone> _checkIn(
  WidgetTester tester,
  Variant v, {
  required String state,
  String who = 'owner',
  int step = 0,
}) async {
  final phone = await signedInPhone(tester, v, state: state, who: who, file: 'check-in');
  await tester.tap(find.byIcon(Icons.task_alt_outlined));
  await settle(tester);
  for (var i = 0; i < step; i++) {
    await tapVisible(tester, find.textContaining('Next:').last);
  }
  return phone;
}

Future<void> _scrollToEnd(WidgetTester tester) async {
  await tester.drag(find.byType(Scrollable).first, const Offset(0, -3000));
  await settle(tester);
}

final List<Scenario> scenarios = [
  Scenario(
    'check-in-not-started',
    everyWay,
    (tester, v) => _checkIn(tester, v, state: 'not-started'),
  ),

  Scenario('check-in-whats-new', bothThemes, (tester, v) => _checkIn(tester, v, state: 'prepaid')),

  Scenario(
    'check-in-categorize',
    everyWay,
    (tester, v) => _checkIn(tester, v, state: 'remembered', step: 1),
  ),

  Scenario(
    'check-in-bills',
    bothThemes,
    (tester, v) => _checkIn(tester, v, state: 'filed-ride', step: 2),
  ),

  Scenario(
    'check-in-next-week',
    everyWay,
    (tester, v) => _checkIn(tester, v, state: 'mapped', step: 3),
  ),

  Scenario('check-in-next-week-chosen', bothThemes, (tester, v) async {
    final phone = await _checkIn(tester, v, state: 'allocated', step: 3);
    await _scrollToEnd(tester);
    return phone;
  }),

  Scenario(
    'check-in-viewer',
    bothThemes,
    (tester, v) => _checkIn(tester, v, state: 'viewer-draft', who: 'viewer', step: 1),
  ),

  Scenario('check-in-stale', bothThemes, (tester, v) async {
    final phone = await _checkIn(tester, v, state: 'allocated', step: 3);
    await tapVisible(tester, find.text('Done for the week'));
    return phone;
  }),

  Scenario('check-in-answer-conflict', bothThemes, (tester, v) async {
    final phone = await _checkIn(tester, v, state: 'mapped', step: 3);
    await tapVisible(tester, find.text('Roll it into next week'));
    await tester.drag(find.byType(Scrollable).first, const Offset(0, 3000));
    await settle(tester);
    return phone;
  }),

  Scenario('check-in-draft-open', bothThemes, (tester, v) async {
    final phone = await _checkIn(tester, v, state: 'next-week');
    await tapVisible(tester, find.text('Start check-in'));
    await tester.drag(find.byType(Scrollable).first, const Offset(0, 3000));
    await settle(tester);
    return phone;
  }),

  Scenario('check-in-completed', everyWay, (tester, v) => _checkIn(tester, v, state: 'completed')),

  Scenario('check-in-offline-stale', bothThemes, (tester, v) async {
    final phone = await _checkIn(tester, v, state: 'filed-ride', step: 1);
    (phone.server as FixtureServer).offline = true;
    phone.now = demoNow.add(const Duration(hours: 2));
    // What pull-to-refresh calls, asked directly: the shell keeps the ledger's list beneath this
    // one, and a gesture's timing is not what this picture is about.
    final container = ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));
    await tester.runAsync(() => container.read(checkInProvider(null).notifier).refresh());
    await settle(tester);
    await tester.drag(find.text('3 charges need a category'), const Offset(0, 3000));
    await settle(tester);
    return phone;
  }),

  Scenario('check-in-error', bothThemes, (tester, v) async {
    final server = FixtureServer.load('check-in', state: 'not-started')
      ..offlineAfter = 'GET /api/transactions';
    final phone = TestPhone(server: server)..signedIn('owner');
    await pumpPhone(tester, v, phone);
    await tester.tap(find.byIcon(Icons.task_alt_outlined));
    await settle(tester);
    return phone;
  }),
];
