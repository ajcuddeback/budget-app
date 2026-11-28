import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/errors/error_text.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/core/privacy/privacy_cover.dart';
import 'package:budget_owl/features/transactions/data/transactions_repository.dart';
import 'package:budget_owl/features/transactions/domain/requests.dart';
import 'package:budget_owl/l10n/gen/app_localizations_en.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/fixture_server.dart';
import '../support/harness.dart';

/// Every built screen's behaviour, through the widget tree, against the generated fixtures: what is
/// shown, what is sent, and what is refused before anything is sent.
void main() {
  Future<(FixtureServer, TestPhone)> signedIn(
    WidgetTester tester, {
    String state = 'demo',
    String who = 'owner',
    String file = 'demo',
  }) async {
    final server = FixtureServer.load(file, state: state);
    final phone = TestPhone(server: server)..signedIn(who);
    await pumpApp(tester, phone);
    return (server, phone);
  }

  Future<void> tapText(WidgetTester tester, String text) =>
      tapVisible(tester, find.text(text).last);

  group('connect', () {
    testWidgets('refuses a path before anything is sent', (tester) async {
      final server = FixtureServer.load('demo', state: 'connect');
      await pumpApp(tester, TestPhone(server: server));

      await tester.enterText(find.byType(TextField), 'https://owl.example.test/api');
      await tapText(tester, 'Connect');

      expect(find.textContaining('no /api and no other path'), findsOneWidget);
      expect(server.served, isEmpty);
    });

    testWidgets('a fresh server offers setup, and only a fresh one', (tester) async {
      final server = FixtureServer.load('first-run', state: 'fresh');
      await pumpApp(tester, TestPhone(server: server)..connected());

      expect(find.text('Set up a new server'), findsOneWidget);
      expect(find.text('Sign in with my account'), findsNothing);
    });

    testWidgets('a server in use offers sign-in, not setup', (tester) async {
      await pumpApp(
        tester,
        TestPhone(server: FixtureServer.load('demo', state: 'connect'))..connected(),
      );

      expect(find.text('Set up a new server'), findsNothing);
      expect(find.text('Sign in with my account'), findsOneWidget);
    });
  });

  group('sign in', () {
    testWidgets('a wrong password gets one sentence, and the field is emptied', (tester) async {
      final phone = TestPhone(server: FixtureServer.load('demo', state: 'connect'))..connected();
      await pumpApp(tester, phone);
      await tapText(tester, 'Sign in with my account');

      await tester.enterText(find.widgetWithText(TextField, 'Email address'), 'ada@example.com');
      await tester.enterText(find.widgetWithText(TextField, 'Password'), 'wrong-guess-1');
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Sign in'));

      expect(find.text('The email address or password is not right.'), findsOneWidget);
      expect(find.text('wrong-guess-1'), findsNothing);
      expect(phone.store.values.keys, ['budget_owl.server']);
    });
  });

  testWidgets('setup creates the owner with the CSRF pair, then signs in', (tester) async {
    final server = FixtureServer.load('first-run', state: 'fresh');
    final phone = TestPhone(server: server)..connected();
    await pumpApp(tester, phone);
    await tapText(tester, 'Set up a new server');
    final body = fixtureRequest('first-run', state: 'fresh', path: '/api/setup/first-user');

    await tester.enterText(
      find.widgetWithText(TextFormField, 'Your name'),
      body['displayName']! as String,
    );
    await tester.enterText(
      find.widgetWithText(TextFormField, 'Email address'),
      body['email']! as String,
    );
    await tester.enterText(
      find.widgetWithText(TextFormField, 'Password'),
      body['password']! as String,
    );
    await tester.enterText(
      find.widgetWithText(TextFormField, 'Household name'),
      body['householdName']! as String,
    );
    await revealIn(tester, find.widgetWithText(TextFormField, 'Base currency'));
    await tester.enterText(find.widgetWithText(TextFormField, 'Base currency'), 'usd');
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Create account'));

    expect(server.served, contains('POST /api/setup/first-user'));
    expect(
      find.text('A transaction has to go somewhere, so add an account first.'),
      findsOneWidget,
    );
    expect(phone.store.values['budget_owl.bearer_token'], 'fixture-token-owner');
  });

  group('join', () {
    testWidgets('a link for another host is refused and nothing is sent', (tester) async {
      final server = FixtureServer.load('first-run', state: 'invited');
      await pumpApp(tester, TestPhone(server: server)..connected());
      await tapText(tester, 'I have an invitation link');
      final before = server.served.length;

      await tester.enterText(
        find.byType(TextField),
        'https://evil.example.test/join/fixture-invitation',
      );
      await tapText(tester, 'Connect');

      expect(find.textContaining('That invitation is for evil.example.test'), findsOneWidget);
      expect(server.served.length, before);
    });

    testWidgets('the disclosure is above the button, and joining leads to sign-in', (tester) async {
      final server = FixtureServer.load('first-run', state: 'invited');
      await pumpApp(tester, TestPhone(server: server)..connected());
      await tapText(tester, 'I have an invitation link');
      await tester.enterText(find.byType(TextField), '$testServerOrigin/join/fixture-invitation');
      await tapText(tester, 'Connect');

      final disclosure = find.textContaining('can see everything you record');
      final button = find.widgetWithText(FilledButton, 'Accept and join');
      expect(tester.getTopLeft(disclosure).dy, lessThan(tester.getTopLeft(button).dy));
      expect(
        find.text('fixture-invitation'),
        findsNothing,
        reason: 'the link is not kept on screen',
      );

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
      await tapVisible(tester, button);

      expect(find.text('Welcome to Rivera Household'), findsOneWidget);
      expect(find.text('You joined as Member.'), findsOneWidget);
      await tapText(tester, 'Sign in');
      expect(find.text('You have joined the household. Sign in to continue.'), findsOneWidget);
      expect(find.text('grace@example.com'), findsOneWidget);
    });

    testWidgets("an unusable invitation says so, in the web's words", (tester) async {
      final server = FixtureServer.load('first-run', state: 'invited');
      await pumpApp(tester, TestPhone(server: server)..connected());
      await tapText(tester, 'I have an invitation link');
      await tester.enterText(find.byType(TextField), '$testServerOrigin/join/not-an-invitation');
      await tapText(tester, 'Connect');
      final body = fixtureRequest(
        'first-run',
        state: 'invited',
        path: '/api/invitations/not-an-invitation/accept',
      );
      await tester.enterText(find.widgetWithText(TextFormField, 'Your name'), 'Grace');
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Choose a password'),
        body['password']! as String,
      );
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Accept and join'));

      expect(find.textContaining('This invitation link cannot be used'), findsOneWidget);
    });
  });

  testWidgets('a member of no household can only sign out', (tester) async {
    final (server, phone) = await signedIn(tester, state: 'no-household', who: 'member');

    expect(find.text('You are not part of a household yet'), findsOneWidget);
    await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Sign out'));
    await untilSignedOut(tester);

    expect(server.served, contains('POST /api/auth/logout'));
    expect(phone.store.values.keys, ['budget_owl.server']);
  });

  testWidgets('the running app is covered in the task switcher, over every route', (tester) async {
    await signedIn(tester);
    expect(find.text('Budget Owl'), findsNothing);

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    await tester.pump();

    expect(find.text('Budget Owl'), findsOneWidget);
    expect(
      find.ancestor(of: find.text('Greenfield Market').first, matching: find.byType(PrivacyCover)),
      findsOneWidget,
    );
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
  });

  group('transactions', () {
    testWidgets('a VIEWER sees every row and no write control', (tester) async {
      final (server, _) = await signedIn(tester, state: 'viewer', who: 'viewer');

      expect(find.text('Greenfield Market'), findsWidgets);
      expect(find.text('Record a transaction'), findsNothing);
      expect(find.text('Move money'), findsNothing);
      await tester.tap(find.text('Metro Power').first);
      await settle(tester);
      expect(find.text('Edit'), findsNothing);
      expect(server.served.where((s) => !s.startsWith('GET')), isEmpty);
    });

    testWidgets("a VIEWER's write, if one is ever sent, is the API's translated 403", (
      tester,
    ) async {
      final (_, _) = await signedIn(tester, state: 'viewer', who: 'viewer');
      final container = ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));
      final form = TransactionForm(
        accountId: 'acc-0001',
        date: CalendarDate.parse('2026-08-20'),
        direction: Direction.out,
        amount: const ValidAmount('4.50'),
        payeeName: 'Corner Kitchen',
        categoryId: 'cat-0008',
      );

      final error = await tester.runAsync(() async {
        try {
          await container.read(transactionsWriterProvider).create(form);
          return null;
        } on AppError catch (e) {
          return e;
        }
      });

      expect(error?.status, 403);
      expect(
        errorText(AppLocalizationsEn(), error!, 'en_US'),
        'Your role in this household is read-only.',
      );
    });

    testWidgets('"Needs a category" is a filter you can stand in', (tester) async {
      final (server, _) = await signedIn(tester);

      await tapText(tester, 'Needs a category');

      expect(server.served.last, contains('uncategorised=true'));
      expect(find.text('Metro Power'), findsNothing);
      expect(find.text('Greenfield Market'), findsOneWidget);
    });

    testWidgets('filing the pending shop sends only its category and version', (tester) async {
      final (server, _) = await signedIn(tester, state: 'deleted');

      await tapVisible(tester, find.text('Greenfield Market').first);
      await tapText(tester, 'Add a category');
      await tapVisible(tester, find.byType(DropdownButtonFormField<String?>).first);
      await tapText(tester, 'Groceries');
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

      expect(server.state, 'categorised');
      expect(find.text('Transaction saved.'), findsOneWidget);
    });

    testWidgets('a change made elsewhere first is a 409, said plainly, and the ledger re-reads', (
      tester,
    ) async {
      final (server, _) = await signedIn(tester);
      final reads = server.served.where((s) => s.startsWith('GET /api/transactions?')).length;

      await tapVisible(tester, find.text('Greenfield Market').first);
      await tapText(tester, 'Edit');
      await revealIn(tester, find.widgetWithText(TextFormField, 'Note'));
      await tester.enterText(find.widgetWithText(TextFormField, 'Note'), 'weekly shop');
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

      expect(
        find.textContaining('Somebody else changed this', skipOffstage: false),
        findsOneWidget,
      );
      expect(
        server.served.where((s) => s.startsWith('GET /api/transactions?')).length,
        greaterThan(reads),
      );
    });

    testWidgets('deleting asks first, then deletes', (tester) async {
      final (server, _) = await signedIn(tester, state: 'added');

      await tapVisible(tester, find.text('Corner Kitchen').first);
      await tapText(tester, 'Delete');
      expect(find.text('Delete this transaction?'), findsOneWidget);
      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Delete'));

      expect(server.state, 'deleted');
      expect(find.text('Transaction deleted.'), findsOneWidget);
    });

    testWidgets('offline, recording says it needs a connection and sends nothing', (tester) async {
      final (server, _) = await signedIn(tester);
      server.offline = true;
      final container = ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));
      container.read(reachabilityProvider.notifier).record(Reachability.offline);
      await settle(tester);
      final before = server.served.length;
      // Nothing was attempted yet: no standing banner (only the stale label, once it is stale).
      expect(find.text("You're offline — this needs a connection"), findsNothing);
      // The button stays readable over the rows: opaque, not the translucent divider colour.
      final fab = tester.widget<FloatingActionButton>(find.byType(FloatingActionButton));
      expect(fab.backgroundColor?.a, 1.0);
      expect(fab.onPressed, isNotNull, reason: 'a tap explains, rather than doing nothing');

      await tapVisible(tester, find.text('Record a transaction'));

      expect(find.text("You're offline — this needs a connection"), findsOneWidget);
      expect(find.text('Record a transaction'), findsOneWidget, reason: 'no sheet opened');
      expect(server.served.length, before);
    });
  });

  group('transfers', () {
    testWidgets('moving money sends one figure within one currency', (tester) async {
      final (server, _) = await signedIn(tester, state: 'categorised');

      await tapText(tester, 'Move money');
      expect(find.textContaining("isn't spending and isn't income"), findsOneWidget);
      expect(find.text('Category'), findsNothing);
      await tapVisible(tester, find.byType(DropdownButtonFormField<String>).first);
      await tapText(tester, 'Everyday Checking •4417 (USD)');
      await tapVisible(tester, find.byType(DropdownButtonFormField<String>).at(1));
      await tapText(tester, 'Emergency Fund (USD)');
      expect(find.text('Amount that arrived'), findsNothing);
      await tester.enterText(find.widgetWithText(TextFormField, 'Amount that left'), '200.00');
      await revealIn(tester, find.widgetWithText(TextFormField, 'Note'));
      await tester.enterText(find.widgetWithText(TextFormField, 'Note'), 'Top up');
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

      expect(server.state, 'moved');
      expect(find.text('Money moved.'), findsOneWidget);
    });

    testWidgets('the same account twice is refused as it is chosen', (tester) async {
      final (server, _) = await signedIn(tester, state: 'categorised');
      final before = server.served.length;

      await tapText(tester, 'Move money');
      await tapVisible(tester, find.byType(DropdownButtonFormField<String>).first);
      await tapText(tester, 'Travel Card •8820 (USD)');
      await tapVisible(tester, find.byType(DropdownButtonFormField<String>).at(1));
      await tapText(tester, 'Travel Card •8820 (USD)');

      expect(find.textContaining("Money can't move from an account to itself"), findsOneWidget);
      expect(server.served.length, before);
    });

    testWidgets('across currencies, what arrived is asked for and sent', (tester) async {
      final (server, _) = await signedIn(tester, state: 'transfer-deleted');

      await tapText(tester, 'Move money');
      await tapVisible(tester, find.byType(DropdownButtonFormField<String>).first);
      await tapText(tester, 'Everyday Checking •4417 (USD)');
      await tapVisible(tester, find.byType(DropdownButtonFormField<String>).at(1));
      await tapText(tester, 'Berlin Savings (EUR)');
      await tester.enterText(find.widgetWithText(TextFormField, 'Amount that left'), '100.00');
      await revealIn(tester, find.widgetWithText(TextFormField, 'Amount that arrived'));
      await tester.enterText(find.widgetWithText(TextFormField, 'Amount that arrived'), '92.50');
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

      expect(server.state, 'crossed');
    });

    testWidgets('an edit has no account pickers and sends only what changed', (tester) async {
      final (server, _) = await signedIn(tester, state: 'moved');

      await tapVisible(tester, find.text('Transfer out').first);
      await tapText(tester, 'Edit');
      expect(find.byType(DropdownButtonFormField<String>), findsNothing);
      expect(find.textContaining('delete it and make it again'), findsOneWidget);
      await tester.enterText(find.widgetWithText(TextFormField, 'Amount that left'), '250.00');
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

      expect(server.state, 'transfer-edited');
      expect(find.text('Transfer saved.'), findsOneWidget);
    });

    testWidgets("a cross-currency transfer shows the rate as derived, never as the bank's", (
      tester,
    ) async {
      await signedIn(tester, state: 'crossed');

      await tapVisible(tester, find.text('Transfer in').first);
      await tapText(tester, 'Edit');

      expect(find.text('That works out at 1 USD = 0.925 EUR.'), findsOneWidget);
      expect(find.textContaining("It isn't the rate your bank gave you"), findsOneWidget);
    });

    testWidgets('deleting a transfer says both halves go, and deletes the pair', (tester) async {
      final (server, _) = await signedIn(tester, state: 'transfer-edited');

      await tapVisible(tester, find.text('Transfer out').first);
      await tapText(tester, 'Delete');
      expect(find.textContaining('Both halves go'), findsOneWidget);
      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Delete'));

      expect(server.served, contains('DELETE /api/transfers/tgr-0002'));
      expect(server.state, 'transfer-deleted');
    });

    testWidgets("split asks first, with the pair's own version", (tester) async {
      final (server, _) = await signedIn(tester, state: 'crossed');

      await tapVisible(tester, find.text('Transfer out').at(1));
      await tapText(tester, 'Split into two rows');
      expect(find.textContaining('come back with no category'), findsOneWidget);
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Split'));

      expect(server.state, 'split');
      expect(find.text('Split into two rows. Both need a category.'), findsOneWidget);
    });

    testWidgets('pairing preselects nothing, warns first, and pairs', (tester) async {
      final (server, _) = await signedIn(tester, state: 'split');

      await revealIn(tester, find.text(r'−⁠$500.00'));
      await tester.tap(find.text(r'−⁠$500.00'));
      await settle(tester);
      await tapText(tester, 'This is a transfer…');
      expect(find.widgetWithText(FilledButton, 'Pair these two rows'), findsOneWidget);
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, 'Pair these two rows'))
            .onPressed,
        isNull,
        reason: 'nothing is chosen for the person',
      );
      expect(find.textContaining('Both rows will lose their category and payee'), findsOneWidget);
      await tapVisible(tester, find.textContaining(r'Emergency Fund · $500.00'));
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Pair these two rows'));

      expect(server.state, 'paired');
      expect(find.text('Paired into a transfer.'), findsOneWidget);
    });

    testWidgets('two rows going the same way are refused with the reason', (tester) async {
      await signedIn(tester);

      await tapVisible(tester, find.text('Greenfield Market').at(2));
      await tapText(tester, 'This is a transfer…');
      await tapVisible(tester, find.textContaining('Aug 9, 2026 · Travel Card'));
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Pair these two rows'));

      expect(find.textContaining('Both rows went the same way'), findsOneWidget);
    });
  });

  group('devices', () {
    testWidgets('marks this phone, and signs another device out', (tester) async {
      final (server, _) = await signedIn(tester);
      await tapText(tester, 'More');
      await tapText(tester, 'Devices');

      expect(find.text('This phone'), findsOneWidget);
      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Sign out').first);

      expect(server.served, contains('DELETE /api/auth/devices/s-session-0001'));
      expect(find.text('That device has been signed out.'), findsOneWidget);
    });

    testWidgets('signing this phone out revokes it and wipes it', (tester) async {
      final (server, phone) = await signedIn(tester);
      await tapText(tester, 'More');
      await tapText(tester, 'Devices');

      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Sign out here'));
      expect(find.text('Sign out of this phone?'), findsOneWidget);
      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Sign out here').last);
      await untilSignedOut(tester);

      expect(server.served, contains('POST /api/auth/logout'));
      expect(phone.store.values.keys, ['budget_owl.server']);
      expect(phone.cacheFile.existsSync(), isFalse);
    });
  });

  group('more', () {
    testWidgets("lists the rest in the web's order, with Admin for the administrator", (
      tester,
    ) async {
      await signedIn(tester);
      await tapText(tester, 'More');

      final labels = [
        'Debt plan',
        'Goals',
        'Envelopes',
        'Categories',
        'Insights',
        'Import',
        'Household',
        'Devices',
        'Preferences',
        'Admin',
      ];
      final ys = [for (final l in labels) tester.getTopLeft(find.text(l)).dy];
      expect(ys, [...ys]..sort());
    });

    testWidgets('hides Admin from anyone else', (tester) async {
      await signedIn(tester, state: 'viewer', who: 'viewer');
      await tapText(tester, 'More');

      expect(find.text('Admin'), findsNothing);
      expect(find.text('Devices'), findsOneWidget);
    });
  });
}
