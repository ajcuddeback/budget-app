import 'package:budget_owl/core/cache/cache_holder.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/fixture_server.dart';
import '../support/harness.dart';

/// The critical journey, end to end against the generated fixtures (mobile-app.md, Testing
/// notes): connect, sign in, add a transaction, see the balance change, go offline, see the stale
/// label, sign out — and nothing is left on the phone.
void main() {
  testWidgets('connect, sign in, record a coffee, go offline, sign out, cache gone', (
    tester,
  ) async {
    final server = FixtureServer.load('demo', state: 'connect');
    final phone = TestPhone(server: server);
    final container = await pumpApp(tester, phone);

    // Connect: the address, checked with /api/meta before anything else is sent.
    expect(find.text('Connect to your server'), findsOneWidget);
    await tester.enterText(find.byType(TextField), 'owl.example.test');
    await tester.tap(find.text('Connect'));
    await settle(tester);
    expect(server.served.first, 'GET /api/meta');
    expect(find.text('Connected to owl.example.test'), findsOneWidget);

    // Sign in, with the label the app derives for an Android phone.
    await tester.tap(find.text('Sign in with my account'));
    await settle(tester);
    final signIn = fixtureRequest('demo', state: 'connect', path: '/api/auth/token', then: 'demo');
    await tester.enterText(find.widgetWithText(TextField, 'Email address'), signIn['email']! as String);
    await tester.enterText(find.widgetWithText(TextField, 'Password'), signIn['password']! as String);
    expect(find.text('Phone · Android'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Sign in'));
    await settle(tester);

    expect(find.text('Transactions'), findsWidgets);
    expect(phone.store.values['budget_owl.bearer_token'], 'fixture-token-owner');
    expect(find.text('Greenfield Market'), findsWidgets);

    // Choose the checking account and read its balance, then record a coffee.
    await tester.ensureVisible(find.text('Everyday Checking •4417'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Everyday Checking •4417'));
    await settle(tester);
    final before = _balance(tester);

    await tester.tap(find.text('Record a transaction'));
    await settle(tester);
    await tester.enterText(find.widgetWithText(TextFormField, 'Amount'), '4.50');
    await tester.enterText(find.widgetWithText(TextFormField, 'Who it was with'), 'Corner Kitchen');
    await tester.pump(const Duration(milliseconds: 400));
    await settle(tester);
    await tapVisible(tester, find.byType(DropdownButtonFormField<String?>).first);
    await settle(tester);
    await tester.tap(find.text('Eating Out').last);
    await settle(tester);
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));
    await settle(tester);

    expect(find.text('Transaction recorded.'), findsOneWidget);
    final after = _balance(tester);
    expect(after, isNot(before), reason: 'the balance is the server\'s, refreshed after the write');

    // Offline: the copy stays, labelled, and writes say why they cannot happen.
    server.offline = true;
    phone.now = demoNow.add(const Duration(hours: 2));
    await tester.fling(find.byType(ListView).first, const Offset(0, 400), 1000);
    await settle(tester);
    expect(find.textContaining("Last updated 2 hours ago — you're offline"), findsWidgets);

    // Sign out: revoked server-side when reachable, wiped regardless.
    server.offline = false;
    await tester.tap(find.text('More'));
    await settle(tester);
    await tapVisible(tester, find.text('Sign out'));
    await settle(tester);
    await tester.tap(find.widgetWithText(OutlinedButton, 'Sign out'));
    await settle(tester);

    expect(find.text('Sign in'), findsWidgets);
    expect(phone.store.values.containsKey('budget_owl.bearer_token'), isFalse);
    expect(phone.store.values.containsKey('budget_owl.cache_key'), isFalse);
    expect(phone.cacheFile.existsSync(), isFalse);
    expect(container.read(cacheHolderProvider).isSealed, isTrue);
    expect(phone.store.values['budget_owl.server'], testServerOrigin);
  });
}

String _balance(WidgetTester tester) {
  final label = find.textContaining('balance');
  final row = find.ancestor(of: label, matching: find.byType(Row)).first;
  final texts = tester.widgetList<Text>(find.descendant(of: row, matching: find.byType(Text)));
  return texts.map((t) => t.data).join(' ');
}
