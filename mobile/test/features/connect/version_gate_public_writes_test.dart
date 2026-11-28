import 'package:budget_owl/l10n/gen/app_localizations_en.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/adapters.dart';
import '../../support/fixture_server.dart';
import '../../support/harness.dart';

/// P1: the public writes — setup and accepting an invitation — after a launch whose version check
/// failed. Their CSRF read used to start a re-check without waiting for it, so the first tap was
/// refused as "offline" even once the server answered. They now wait for, or run, the check
/// (`SessionController.ensureVersionConfirmed`) before anything is written.
void main() {
  /// `/api/meta` answers busy while [busy] says so, and as Budget Owl after.
  (MetaGate, void Function()) busyThenAnswering(FixtureServer fixture) {
    var busy = true;
    final gate = MetaGate(fixture, (_) async {
      if (busy) return json(429, {'code': 'rate-limited'});
      return json(200, {'app': 'budget-owl', 'apiVersion': 1, 'minimumClientApiVersion': 1});
    });
    return (gate, () => busy = false);
  }

  Future<void> tapText(WidgetTester tester, String text) =>
      tapVisible(tester, find.text(text).last);

  Future<void> fillInvitation(WidgetTester tester) async {
    await tapText(tester, 'I have an invitation link');
    await tester.enterText(find.byType(TextField), '$testServerOrigin/join/fixture-invitation');
    await tapText(tester, 'Connect');
    final body = fixtureRequest(
      'first-run',
      state: 'invited',
      path: '/api/invitations/fixture-invitation/accept',
    );
    await tester.enterText(
      find.widgetWithText(TextFormField, 'Your name'),
      body['displayName']! as String,
    );
    await tester.enterText(
      find.widgetWithText(TextFormField, 'Choose a password'),
      body['password']! as String,
    );
  }

  testWidgets('join works on the first tap once the server answers its check again', (
    tester,
  ) async {
    final (gate, answer) = busyThenAnswering(FixtureServer.load('first-run', state: 'invited'));
    await pumpApp(tester, TestPhone(server: gate)..connected());
    await fillInvitation(tester);
    expect(gate.metaRequests, greaterThanOrEqualTo(1), reason: 'the launch check was busy');

    answer();
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Accept and join'));
    await settleUntil(
      tester,
      () => find.text('Welcome to Rivera Household').evaluate().isNotEmpty,
      what: 'the invitation was accepted',
    );

    expect(gate.writes, ['POST /api/invitations/fixture-invitation/accept']);
    expect(find.text('Welcome to Rivera Household'), findsOneWidget);
  });

  testWidgets('join while the check still fails sends nothing, and says so', (tester) async {
    final (gate, _) = busyThenAnswering(FixtureServer.load('first-run', state: 'invited'));
    await pumpApp(tester, TestPhone(server: gate)..connected());
    await fillInvitation(tester);

    await tapVisible(tester, find.widgetWithText(FilledButton, 'Accept and join'));

    expect(gate.writes, isEmpty);
    expect(find.text(AppLocalizationsEn().error_network), findsOneWidget);
  });

  testWidgets('setup works on the first tap once the server answers its check again', (
    tester,
  ) async {
    final (gate, answer) = busyThenAnswering(FixtureServer.load('first-run', state: 'fresh'));
    final phone = TestPhone(server: gate)..connected();
    await pumpApp(tester, phone);
    await tapText(tester, 'Set up a new server');
    final body = fixtureRequest('first-run', state: 'fresh', path: '/api/setup/first-user');
    for (final (label, key) in [
      ('Your name', 'displayName'),
      ('Email address', 'email'),
      ('Password', 'password'),
      ('Household name', 'householdName'),
    ]) {
      await tester.enterText(find.widgetWithText(TextFormField, label), body[key]! as String);
    }
    await revealIn(tester, find.widgetWithText(TextFormField, 'Base currency'));
    await tester.enterText(find.widgetWithText(TextFormField, 'Base currency'), 'usd');

    answer();
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Create account'));
    await settleUntil(
      tester,
      () => phone.store.values.containsKey('budget_owl.bearer_token'),
      what: 'the new owner was signed in',
    );

    expect(gate.writes, ['POST /api/setup/first-user', 'POST /api/auth/token']);
    expect(phone.store.values['budget_owl.bearer_token'], 'fixture-token-owner');
  });
}
