import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/errors/error_text.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';
import 'package:budget_owl/features/accounts/domain/requests.dart';
import 'package:budget_owl/l10n/gen/app_localizations_en.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fixture_server.dart';
import '../../support/harness.dart';

/// The Accounts tab against `accounts.generated.json`: what is shown, what is sent, and what is
/// refused before anything is sent.
void main() {
  final l = AppLocalizationsEn();
  final household =
      ((fixtureBody('accounts', state: 'accounts', path: '/api/auth/me')!
                  as Map<String, Object?>)['household']!
              as Map<String, Object?>)['householdId']!
          as String;

  Future<(FixtureServer, ProviderContainer)> open(
    WidgetTester tester, {
    String state = 'accounts',
    String who = 'owner',
  }) async {
    final server = FixtureServer.load('accounts', state: state);
    final container = await pumpApp(tester, TestPhone(server: server)..signedIn(who));
    await tapVisible(tester, find.text('Accounts').last);
    return (server, container);
  }

  Future<void> tap(WidgetTester tester, String text) => tapVisible(tester, find.text(text).last);

  Future<void> dismissSheet(WidgetTester tester) async {
    await tester.tapAt(const Offset(20, 20));
    await settle(tester);
  }

  Finder field(String label) => find.widgetWithText(TextFormField, label);

  List<String> writes(FixtureServer server) =>
      server.served.where((line) => !line.startsWith('GET ')).toList();

  testWidgets("groups by currency, each balance the server's, and totals nothing", (tester) async {
    await open(tester);

    expect(find.text('EUR'), findsOneWidget);
    expect(find.text('USD'), findsOneWidget);
    expect(find.text('€1,200.00'), findsOneWidget);
    expect(find.text(r'$4,755.35'), findsOneWidget);
    expect(find.text('Travel Card •8820'), findsOneWidget);
    expect(find.textContaining('Subtotal'), findsNothing);
    // The archived ones are behind the toggle, not mixed in.
    expect(find.text('Gift Card'), findsNothing);
  });

  testWidgets('Delete is offered only where the server said the account is empty', (tester) async {
    await open(tester);

    await tap(tester, 'Everyday Checking •4417');
    expect(find.text('Edit'), findsOneWidget);
    expect(find.text('Delete'), findsNothing, reason: 'it has history');
    await dismissSheet(tester);

    await tap(tester, 'Emergency Fund');
    expect(find.text('Delete'), findsOneWidget);
    await dismissSheet(tester);

    await tap(tester, 'Show 2 archived');
    await tap(tester, 'Old Joint Account');
    // Its history nets to $0.00 and it is still not empty: no Delete, and no Edit while archived.
    expect(find.text('Bring back'), findsOneWidget);
    expect(find.text('Delete'), findsNothing);
    expect(find.text('Edit'), findsNothing);
  });

  testWidgets("adding sends the web's body, and the list re-reads", (tester) async {
    final (server, _) = await open(tester);

    await tap(tester, 'Add an account');
    // The household's base currency, in the UI only (accounts.md).
    expect(find.widgetWithText(TextFormField, 'USD'), findsOneWidget);
    await tester.enterText(field('Name'), 'Cash Tin');
    await tap(tester, 'Current account');
    await tap(tester, 'Cash');
    await tester.enterText(field('Opening balance'), '40.00');
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save account'));

    expect(writes(server), ['POST /api/accounts']);
    expect(server.state, 'account-added');
    expect(find.text('Account added.'), findsOneWidget);
    expect(find.text('Cash Tin'), findsOneWidget);
    expect(server.served.where((line) => line.contains(household)), isEmpty);
  });

  testWidgets('a field the server refused is marked until it is edited', (tester) async {
    final (server, _) = await open(tester);

    await tap(tester, 'Add an account');
    await tester.enterText(field('Name'), 'Cash Tin');
    await tap(tester, 'Current account');
    await tap(tester, 'Cash');
    await tester.enterText(field('Currency'), 'zzz');
    await tester.enterText(field('Opening balance'), '40.00');
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save account'));

    expect(server.state, 'accounts');
    expect(find.text(l.error_validation_failed), findsOneWidget);
    expect(find.text(l.validation_server), findsOneWidget);
    await tester.enterText(field('Currency'), 'ZZY');
    await tester.pumpAndSettle();
    expect(find.text(l.validation_server), findsNothing);
  });

  testWidgets('an edit sends only what changed, and an emptied digits box clears them', (
    tester,
  ) async {
    final (server, _) = await open(tester, state: 'account-added');

    await tap(tester, 'Travel Card •8820');
    await tap(tester, 'Edit');
    await tester.enterText(field('Name'), 'Travel Rewards');
    await tester.enterText(field('Last four digits'), '');
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save account'));

    expect(writes(server), ['PATCH /api/accounts/acc-travel-card']);
    expect(server.state, 'account-edited');
    expect(find.text('Account saved.'), findsOneWidget);
    expect(find.text('Travel Rewards'), findsOneWidget);
    expect(find.textContaining('8820'), findsNothing);
  });

  testWidgets('an untouched edit sends nothing', (tester) async {
    final (server, _) = await open(tester);

    await tap(tester, 'Everyday Checking •4417');
    await tap(tester, 'Edit');
    // The currency is locked, with the reason beside it rather than the field vanishing.
    expect(find.text(l.accounts_currencyLocked), findsOneWidget);
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save account'));

    expect(writes(server), isEmpty);
    expect(find.text('Edit account'), findsNothing);
  });

  testWidgets('archiving asks first, says what it does, then archives', (tester) async {
    final (server, _) = await open(tester, state: 'account-edited');

    await tap(tester, 'Emergency Fund');
    await tap(tester, 'Archive');
    expect(find.text('Archive Emergency Fund?'), findsOneWidget);
    expect(find.text(l.accounts_archiveMessagePhone), findsOneWidget);
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Archive'));

    expect(writes(server), ['POST /api/accounts/acc-emergency-fund/archive']);
    expect(server.state, 'account-archived');
    expect(find.text(l.accounts_archivedNotice), findsOneWidget);
  });

  testWidgets('bringing one back does not ask', (tester) async {
    final (server, _) = await open(tester, state: 'account-archived');

    await tap(tester, 'Show 3 archived');
    await tap(tester, 'Old Joint Account');
    await tap(tester, 'Bring back');

    expect(writes(server), ['POST /api/accounts/acc-old-joint-account/unarchive']);
    expect(server.state, 'account-unarchived');
    expect(find.text(l.accounts_unarchivedNotice), findsOneWidget);
  });

  testWidgets('deleting says "permanently", then deletes', (tester) async {
    final (server, _) = await open(tester, state: 'account-unarchived');

    await tap(tester, 'Show 2 archived');
    await tap(tester, 'Gift Card');
    await tap(tester, 'Delete');
    expect(find.textContaining('permanently'), findsOneWidget);
    await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Delete'));

    expect(writes(server), ['DELETE /api/accounts/acc-gift-card']);
    expect(server.state, 'account-deleted');
    expect(find.text(l.accounts_deletedNotice), findsOneWidget);
  });

  group('somebody else saved first', () {
    Future<FixtureServer> conflict(WidgetTester tester) async {
      final (server, _) = await open(tester, state: 'account-deleted');
      await tap(tester, 'Everyday Checking •4417');
      await tap(tester, 'Edit');
      await tester.enterText(field('Opening balance'), '1700.00');
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Save account'));
      return server;
    }

    Future<void> toTop(WidgetTester tester) async {
      await tester.drag(find.byType(Scrollable).last, const Offset(0, 3000));
      await settle(tester);
    }

    testWidgets('says what moved, re-reads, and stops with Save off', (tester) async {
      final server = await conflict(tester);

      expect(server.state, 'account-conflict');
      expect(server.served, contains('GET /api/accounts/acc-everyday-checking'));
      // To the bottom of the sheet, where Save is.
      await tester.drag(find.byType(Scrollable).last, const Offset(0, -3000));
      await settle(tester);
      final save = tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Save account'));
      expect(save.onPressed, isNull);
      await toTop(tester);
      expect(find.text(l.accounts_conflictTitle), findsOneWidget);
      expect(find.text('Name: Everyday Joint'), findsOneWidget);
    });

    testWidgets('"keep mine" sends the same form at the version just shown', (tester) async {
      final server = await conflict(tester);
      await toTop(tester);

      await tap(tester, l.accounts_conflictOverwrite);

      expect(writes(server), [
        'PATCH /api/accounts/acc-everyday-checking',
        'PATCH /api/accounts/acc-everyday-checking',
      ]);
      expect(server.state, 'account-overwritten');
      expect(find.text('Account saved.'), findsOneWidget);
    });

    testWidgets('"use theirs" reloads the form and sends nothing', (tester) async {
      final server = await conflict(tester);
      await toTop(tester);

      await tap(tester, l.accounts_conflictUseLatest);

      expect(writes(server), ['PATCH /api/accounts/acc-everyday-checking']);
      expect(find.text(l.accounts_conflictTitle), findsNothing);
      expect(find.widgetWithText(TextFormField, 'Everyday Joint'), findsOneWidget);
    });
  });

  group('roles', () {
    testWidgets('a VIEWER sees every account and no write control', (tester) async {
      await open(tester, state: 'accounts-viewer', who: 'viewer');

      expect(find.text(r'$4,755.35'), findsOneWidget);
      expect(find.text('Add an account'), findsNothing);
      await tap(tester, 'Everyday Checking •4417');
      expect(find.text('Edit'), findsNothing);
      expect(find.text('Archive'), findsNothing);
      await tap(tester, 'Show 2 archived');
      expect(find.text(l.accounts_archivedNote), findsOneWidget);
      await tap(tester, 'Gift Card');
      expect(find.text('Delete'), findsNothing);
      expect(find.text('Bring back'), findsNothing);
    });

    testWidgets("a VIEWER's forced write gets the translated 403, not a crash", (tester) async {
      final (server, container) = await open(tester, state: 'accounts-viewer', who: 'viewer');

      // A real round trip through the shared transport: run outside the test's fake clock.
      final refusal = await tester.runAsync(
        () => container
            .read(accountsWriterProvider)
            .create(
              const AccountForm(
                name: 'Cash Tin',
                type: 'CASH',
                currency: 'USD',
                openingBalance: '40.00',
              ),
            )
            .then<AppError?>((_) => null, onError: (Object e) => e as AppError),
      );

      expect(refusal?.status, 403);
      expect(errorText(l, refusal!, 'en'), 'Your role in this household is read-only.');
      expect(server.state, 'accounts-viewer');
    });
  });

  group('writes are refused before anything is sent', () {
    const cashTin = AccountForm(
      name: 'Cash Tin',
      type: 'CASH',
      currency: 'USD',
      openingBalance: '40.00',
    );

    testWidgets('offline: the control says so, and the writer sends nothing', (tester) async {
      final (server, container) = await open(tester);
      // The server is still answering: only the phone's own gate can stop the request.
      container.read(reachabilityProvider.notifier).record(Reachability.offline);
      await tester.pump();

      await tap(tester, 'Add an account');
      expect(find.text('Add an account'), findsOneWidget, reason: 'no sheet opened');
      expect(find.text(l.offline_write), findsOneWidget);

      expect(
        await attempt(tester, () => container.read(accountsWriterProvider).create(cashTin)),
        isA<AppError>().having((e) => e.kind, 'kind', AppErrorKind.offline),
      );
      expect(writes(server), isEmpty);
      expect(server.state, 'accounts');
    });

    testWidgets('version unconfirmed: it says why, and nothing is sent', (tester) async {
      final (server, container) = await open(tester);
      container.read(reachabilityProvider.notifier).unconfirmVersion();
      await tester.pump();

      await tap(tester, 'Add an account');
      expect(find.text(l.offline_versionUnconfirmed), findsOneWidget);

      await tap(tester, 'Emergency Fund');
      await tap(tester, 'Archive');
      expect(find.text('Archive Emergency Fund?'), findsNothing, reason: 'the action is off');
      expect(find.text(l.offline_versionUnconfirmed), findsWidgets);

      expect(
        await attempt(tester, () => container.read(accountsWriterProvider).create(cashTin)),
        isA<AppError>(),
      );
      expect(writes(server), isEmpty);
    });
  });
}

/// A write attempted directly, under real time: if the gate failed and the request went out, it
/// completes (or fails) for real and the test says so, instead of hanging on the fake clock.
Future<Object?> attempt(WidgetTester tester, Future<Object?> Function() write) => tester
    .runAsync<Object?>(() => write().then<Object?>((_) => null, onError: (Object error) => error));
