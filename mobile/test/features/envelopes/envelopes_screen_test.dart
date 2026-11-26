import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/errors/error_text.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/features/envelopes/data/envelopes.dart';
import 'package:budget_owl/features/envelopes/domain/envelope.dart';
import 'package:budget_owl/features/envelopes/domain/requests.dart';
import 'package:budget_owl/l10n/gen/app_localizations_en.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fixture_server.dart';
import '../../support/harness.dart';

/// Envelopes (More → Envelopes) against `envelopes.generated.json`.
void main() {
  final l = AppLocalizationsEn();
  final household =
      ((fixtureBody('envelopes', state: 'envelopes', path: '/api/auth/me')!
                  as Map<String, Object?>)['household']!
              as Map<String, Object?>)['householdId']!
          as String;
  final coffee = EntryForm(amount: '12.50', date: CalendarDate.parse('2026-08-20'));

  Future<(FixtureServer, ProviderContainer)> open(
    WidgetTester tester, {
    String state = 'envelopes',
    String who = 'owner',
    DateTime? now,
  }) async {
    final server = FixtureServer.load('envelopes', state: state);
    final phone = TestPhone(server: server)..signedIn(who);
    if (now != null) phone.now = now;
    final container = await pumpApp(tester, phone);
    await tapVisible(tester, find.text('More').last);
    await tapVisible(tester, find.text('Envelopes').last);
    return (server, container);
  }

  Future<void> tap(WidgetTester tester, String text) => tapVisible(tester, find.text(text).last);

  Future<void> actionsFor(WidgetTester tester, String envelope) =>
      tapVisible(tester, find.byTooltip('Actions for $envelope'));

  Finder field(String label) => find.widgetWithText(TextFormField, label);

  List<String> writes(FixtureServer server) =>
      server.served.where((line) => !line.startsWith('GET ')).toList();

  Envelope groceries(ProviderContainer container) => container
      .read(envelopesProvider('2026-08'))
      .data!
      .envelopes
      .firstWhere((e) => e.name == 'Groceries');

  testWidgets("opens on this month, with the server's totals and an overspend shown as one", (
    tester,
  ) async {
    final (server, _) = await open(tester);

    expect(find.text('August 2026'), findsOneWidget);
    expect(server.served, contains('GET /api/envelopes/periods'));
    expect(find.text(r'$1,070.00'), findsOneWidget);
    expect(find.text(r'$478.89'), findsOneWidget);
    expect(find.text(r'$591.11'), findsOneWidget);
    expect(find.textContaining(r'$11.20'), findsOneWidget);
    expect(find.text(l.envelopes_over), findsOneWidget);
    // An envelope with nothing logged offers no log to open.
    expect(find.text(l.envelopes_showLogOne), findsOneWidget);
    expect(find.text(l.envelopes_showLog('2')), findsOneWidget);
    expect(find.text(l.envelopes_showLog('0')), findsNothing);
  });

  testWidgets('a month with none lands on the latest month that has some', (tester) async {
    await open(tester, now: DateTime.utc(2026, 10, 5, 12));

    expect(find.text('August 2026'), findsOneWidget);
  });

  testWidgets('the arrows move between months', (tester) async {
    await open(tester);

    await tester.tap(find.byTooltip('Previous month'));
    await settle(tester);
    expect(find.text('July 2026'), findsOneWidget);
    expect(find.text(r'$51.70'), findsWidgets);

    await tester.tap(find.byTooltip('Next month'));
    await settle(tester);
    await tester.tap(find.byTooltip('Next month'));
    await settle(tester);
    expect(find.text('September 2026'), findsOneWidget);
    expect(find.text(l.envelopes_emptyBody), findsOneWidget);
  });

  testWidgets('the log says where every figure came from', (tester) async {
    await open(tester);

    await tap(tester, l.envelopes_showLog('3'));
    expect(find.text(l.envelopes_source_SYNCED), findsOneWidget);
    expect(find.text(l.envelopes_source_MANUAL), findsNWidgets(2));
    expect(find.text('big shop'), findsOneWidget);
  });

  testWidgets('quick add opens on the amount and logs an amount alone', (tester) async {
    final (server, _) = await open(tester);

    await tap(tester, l.envelopes_logSpend);
    expect(find.text(l.envelopes_logTitle('Groceries')), findsOneWidget);
    await tester.enterText(field('Amount'), '12.50');
    await tapVisible(tester, find.widgetWithText(FilledButton, l.envelopes_logIt));

    expect(writes(server), ['POST /api/envelopes/env-2026-08-01-groceries/entries']);
    expect(server.state, 'entry-logged');
    expect(find.text(l.envelopes_logged), findsOneWidget);
    expect(server.served.where((line) => line.contains(household)), isEmpty);
  });

  testWidgets('correcting an entry sends only the note', (tester) async {
    final (server, _) = await open(tester, state: 'entry-logged');

    await tap(tester, l.envelopes_showLog('4'));
    await tap(tester, r'$12.50');
    await tap(tester, 'Edit');
    await tester.enterText(field('Note'), 'coffee beans');
    await tapVisible(tester, find.widgetWithText(FilledButton, l.envelopes_logIt));

    expect(writes(server), [
      'PATCH /api/envelopes/env-2026-08-01-groceries/entries/ent-2026-08-20-12-5000',
    ]);
    expect(server.state, 'entry-edited');
    expect(find.text(l.envelopes_entryUpdated), findsOneWidget);
  });

  testWidgets('removing an entry asks, and says the amount goes back', (tester) async {
    final (server, _) = await open(tester, state: 'entry-edited');

    await tap(tester, l.envelopes_showLog('4'));
    await tap(tester, r'$12.50');
    await tap(tester, 'Delete');
    expect(find.text(l.envelopes_deleteEntryMessage('Groceries')), findsOneWidget);
    await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Delete'));

    expect(writes(server), [
      'DELETE /api/envelopes/env-2026-08-01-groceries/entries/ent-2026-08-20-12-5000',
    ]);
    expect(server.state, 'entry-deleted');
  });

  testWidgets('adding an envelope for this month', (tester) async {
    final (server, _) = await open(tester, state: 'entry-deleted');

    await tap(tester, l.envelopes_add);
    await tester.enterText(field('Name'), 'Coffee');
    await tester.enterText(field('Amount'), '40.00');
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

    expect(writes(server), ['POST /api/envelopes']);
    expect(server.state, 'envelope-added');
    expect(find.text('Coffee'), findsOneWidget);
  });

  testWidgets('re-amounting sends the amount and the version, never the month', (tester) async {
    final (server, _) = await open(tester, state: 'envelope-added');

    await actionsFor(tester, 'Groceries');
    await tap(tester, 'Edit');
    expect(find.text(l.envelopes_monthFixedHint), findsOneWidget);
    await tester.enterText(field('Amount'), '650.00');
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

    expect(writes(server), ['PATCH /api/envelopes/env-2026-08-01-groceries']);
    expect(server.state, 'envelope-edited');
    expect(find.text(l.envelopes_updated), findsOneWidget);
  });

  testWidgets('archive, restore and delete, each confirmed where it should be', (tester) async {
    final (server, _) = await open(tester, state: 'envelope-edited');

    await actionsFor(tester, 'Fuel');
    await tap(tester, 'Archive');
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Archive'));
    expect(server.state, 'envelope-archived');

    await tap(tester, l.envelopes_showArchived('2'));
    await tap(tester, 'Holiday');
    await tap(tester, l.envelopes_unarchive);
    expect(server.state, 'envelope-unarchived');

    await actionsFor(tester, 'Adhoc');
    await tap(tester, 'Delete');
    expect(find.text(l.envelopes_deleteMessage('1')), findsOneWidget);
    await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Delete'));
    expect(server.state, 'envelope-deleted');

    expect(writes(server), [
      'POST /api/envelopes/env-2026-08-01-fuel/archive',
      'POST /api/envelopes/env-2026-08-01-holiday/unarchive',
      'DELETE /api/envelopes/env-2026-08-01-adhoc',
    ]);
  });

  group('roles', () {
    testWidgets('a VIEWER reads every figure and log, and is offered nothing', (tester) async {
      await open(tester, state: 'envelopes-viewer', who: 'viewer');

      expect(find.text(l.envelopes_add), findsNothing);
      expect(find.text(l.envelopes_logSpend), findsNothing);
      expect(find.byTooltip('Actions for Groceries'), findsNothing);
      await tap(tester, l.envelopes_showLog('3'));
      await tap(tester, 'big shop');
      expect(find.text('Edit'), findsNothing);
    });

    testWidgets("a VIEWER's forced write gets the translated 403", (tester) async {
      final (server, container) = await open(tester, state: 'envelopes-viewer', who: 'viewer');

      final refusal = await tester.runAsync(
        () => container
            .read(envelopesWriterProvider)
            .logEntry(groceries(container), coffee)
            .then<AppError?>((_) => null, onError: (Object e) => e as AppError),
      );

      expect(refusal?.status, 403);
      expect(errorText(l, refusal!, 'en'), 'Your role in this household is read-only.');
      expect(server.state, 'envelopes-viewer');
    });
  });

  group('writes are refused before anything is sent', () {
    testWidgets('offline: Log spend says so, and the writer sends nothing', (tester) async {
      final (server, container) = await open(tester);
      container.read(reachabilityProvider.notifier).record(Reachability.offline);
      await tester.pump();

      await tap(tester, l.envelopes_logSpend);
      expect(find.text(l.envelopes_logTitle('Groceries')), findsNothing);
      expect(find.text(l.offline_write), findsOneWidget);

      expect(
        await attempt(
          tester,
          () => container.read(envelopesWriterProvider).logEntry(groceries(container), coffee),
        ),
        isA<AppError>().having((e) => e.kind, 'kind', AppErrorKind.offline),
      );
      expect(writes(server), isEmpty);
      expect(server.state, 'envelopes');
    });

    testWidgets('version unconfirmed: it says why, and nothing is sent', (tester) async {
      final (server, container) = await open(tester);
      container.read(reachabilityProvider.notifier).unconfirmVersion();
      await tester.pump();

      await tap(tester, l.envelopes_logSpend);
      expect(find.text(l.offline_versionUnconfirmed), findsOneWidget);
      expect(
        await attempt(
          tester,
          () => container.read(envelopesWriterProvider).logEntry(groceries(container), coffee),
        ),
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
