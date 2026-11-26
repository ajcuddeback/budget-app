import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/errors/error_text.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/features/check_in/data/check_in_repository.dart';
import 'package:budget_owl/features/check_in/domain/check_in.dart';
import 'package:budget_owl/features/check_in/domain/requests.dart';
import 'package:budget_owl/l10n/gen/app_localizations_en.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fixture_server.dart';
import '../../support/harness.dart';

/// The weekly check-in, through the widget tree, against the canvas week the real API recorded
/// (MobileWeeklyFixtureIT): what is shown, what is sent, the state the server moved to, and what
/// is refused before anything is sent.
void main() {
  Future<FixtureServer> openCheckIn(
    WidgetTester tester, {
    required String state,
    String who = 'owner',
  }) async {
    final server = FixtureServer.load('check-in', state: state);
    await pumpApp(tester, TestPhone(server: server)..signedIn(who));
    await tester.tap(find.text('Check-in'));
    await settle(tester);
    return server;
  }

  Future<void> tapText(WidgetTester tester, String text) =>
      tapVisible(tester, find.text(text).last);

  List<String> writes(FixtureServer server) =>
      server.served.where((s) => !s.startsWith('GET')).toList();

  group('not started', () {
    testWidgets('shows the computed week and starts a draft with no body', (tester) async {
      final server = await openCheckIn(tester, state: 'not-started');

      expect(find.text('Check-in for week 34'), findsOneWidget);
      expect(find.text('Not started yet'), findsOneWidget);
      expect(find.text('Mountain Strength Co'), findsOneWidget);
      // No answer control before a draft exists.
      expect(find.text('Yes, prepaid'), findsNothing);

      await tapText(tester, 'Start check-in');

      expect(writes(server), ['PUT /api/check-ins/2026-W34']);
      expect(server.state, 'draft');
      expect(find.text('Yes, prepaid'), findsOneWidget);
      expect(find.text('In progress'), findsOneWidget);
    });

    testWidgets('a VIEWER reads the week, is told so, and is offered no start', (tester) async {
      final server = await openCheckIn(tester, state: 'viewer-not-started', who: 'viewer');

      expect(find.textContaining('only an owner or a member can answer it'), findsOneWidget);
      expect(find.text('Start check-in'), findsNothing);
      expect(find.text('Mountain Strength Co'), findsOneWidget);
      expect(writes(server), isEmpty);
    });

    testWidgets("a VIEWER's start, if one is ever sent, is the API's translated 403", (
      tester,
    ) async {
      await openCheckIn(tester, state: 'viewer-not-started', who: 'viewer');
      final container = ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));

      final error = await tester.runAsync(() async {
        try {
          await container.read(checkInWriterProvider).start('2026-W34');
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
  });

  group('one step per screen', () {
    testWidgets('each screen names the next, and back and forward keep every answer', (
      tester,
    ) async {
      await openCheckIn(tester, state: 'filed-ride');

      expect(find.text("1 · What's new"), findsOneWidget);
      expect(find.text('Next: categorize'), findsOneWidget);
      await tapText(tester, 'Next: categorize');
      expect(find.text('3 charges need a category'), findsOneWidget);
      expect(find.text('Eating Out'), findsOneWidget);
      await tapText(tester, 'Next: bills');
      expect(find.text('One to map'.toUpperCase()), findsOneWidget);
      await tapText(tester, 'Next: next week');
      expect(find.text(r'You came in $63.00 under'), findsOneWidget);
      expect(find.text('Done for the week'), findsOneWidget);
      await tapText(tester, 'Back');
      await tapText(tester, 'Back');
      expect(find.text('Eating Out'), findsOneWidget, reason: 'the answer is on the server');
    });
  });

  group('answering', () {
    testWidgets('"Yes, prepaid" sends the choice alone and the week is read again', (tester) async {
      final server = await openCheckIn(tester, state: 'started');

      await tapText(tester, 'Yes, prepaid');

      expect(writes(server), [
        'PUT /api/check-ins/2026-W34/answers/odd.row-2026-08-18-chase-out-126',
      ]);
      expect(server.state, 'prepaid');
      expect(server.served.last, 'GET /api/check-ins/current');
      expect(find.text('All handled'), findsOneWidget);
    });

    testWidgets('a category from the sheet, then "always file" replaces it at its version', (
      tester,
    ) async {
      final server = await openCheckIn(tester, state: 'prepaid');
      await tapText(tester, 'Next: categorize');

      // SQ *BLUE BOTTLE is the third row; its "Choose a category" is the third.
      await tapVisible(tester, find.text('Choose a category').at(2));
      await tapVisible(tester, find.widgetWithText(ListTile, 'Eating Out'));
      expect(server.state, 'filed-blue');

      await tapVisible(tester, find.text('Always file this payee here').at(2));
      expect(server.state, 'remembered', reason: 'the replacement carried version 0');
      expect(writes(server).toSet(), {
        'PUT /api/check-ins/2026-W34/answers/cat.row-2026-08-19-chase-out-18.5',
      });
    });

    testWidgets('"Yes, map it" attaches to the bill the server suggested and remembers it', (
      tester,
    ) async {
      final server = await openCheckIn(tester, state: 'filed-ride');
      for (final next in ['Next: categorize', 'Next: bills']) {
        await tapText(tester, next);
      }

      await tapText(tester, 'Yes, map it');

      expect(server.state, 'mapped');
      expect(find.text('Attached to Internet, due Aug 25, 2026'), findsOneWidget);
    });

    testWidgets("another member's answer first is a 409, said, and theirs is shown", (
      tester,
    ) async {
      final server = await openCheckIn(tester, state: 'mapped');
      for (final next in ['Next: categorize', 'Next: bills', 'Next: next week']) {
        await tapText(tester, next);
      }

      await tapText(tester, 'Roll it into next week');

      expect(server.state, 'rolled');
      expect(
        find.text('Someone else answered this first. Their answer is shown below.'),
        findsOneWidget,
      );
      await revealIn(tester, find.text(r'$543.00'));
      expect(find.text(r'$543.00'), findsOneWidget, reason: "the server's rolled-over figure");
    });

    testWidgets('finishing against a week that moved is refused whole, the item marked', (
      tester,
    ) async {
      final server = await openCheckIn(tester, state: 'allocated');
      for (final next in ['Next: categorize', 'Next: bills', 'Next: next week']) {
        await tapText(tester, next);
      }

      await tapText(tester, 'Done for the week');

      expect(server.state, 'stale');
      expect(find.textContaining('Something changed while you were answering'), findsOneWidget);
      expect(find.textContaining('new rows, a rule, a bill or a goal'), findsOneWidget);
      // The first marked item's step is opened.
      expect(find.text('3 charges need a category'), findsOneWidget);
      expect(
        find.text('This changed in the ledger after you answered. Check it and answer again.'),
        findsOneWidget,
      );

      // Answered again — at the answer's version — the mark goes, and the week finishes.
      await tapVisible(tester, find.text('Groceries').first);
      await tapVisible(tester, find.widgetWithText(ListTile, 'Groceries'));
      expect(server.state, 'reanswered');
      expect(find.textContaining('This changed in the ledger'), findsNothing);
      await tapText(tester, 'Next: bills');
      await tapText(tester, 'Next: next week');
      await tapText(tester, 'Done for the week');

      expect(server.state, 'completed');
      expect(find.text('Check-in done'), findsOneWidget);
      expect(find.text(r'Recorded $63.00 to Emergency fund.'), findsOneWidget);
      expect(find.text(r'Next week: $480.00.'), findsOneWidget);
    });

    testWidgets('discarding asks first, then deletes the draft', (tester) async {
      final server = await openCheckIn(tester, state: 'draft');
      for (final next in ['Next: categorize', 'Next: bills', 'Next: next week']) {
        await tapText(tester, next);
      }

      await tapText(tester, 'Discard this draft');
      expect(find.text('Discard this check-in?'), findsOneWidget);
      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Discard this draft'));

      expect(writes(server), ['DELETE /api/check-ins/2026-W34']);
      expect(server.state, 'discarded');
      expect(find.text('The draft was discarded.'), findsOneWidget);
      expect(find.text('Start check-in'), findsOneWidget);
    });

    testWidgets("another week's open draft is offered, and opened", (tester) async {
      final server = await openCheckIn(tester, state: 'next-week');
      expect(find.text('Check-in for week 35'), findsOneWidget);

      await tapText(tester, 'Start check-in');
      expect(
        find.text(
          "There's an unfinished check-in for 2026-W34. Finish it or discard it before "
          'starting this one.',
        ),
        findsOneWidget,
      );
      await tapText(tester, 'Open it');

      expect(server.served.last, 'GET /api/check-ins/2026-W34');
      expect(find.text('Check-in for week 34'), findsOneWidget);
      expect(find.text('Back to this week'), findsOneWidget);
    });
  });

  group('roles and connection', () {
    testWidgets('a VIEWER sees every answer and figure, and no control', (tester) async {
      final server = await openCheckIn(tester, state: 'viewer-draft', who: 'viewer');
      for (final next in ['Next: categorize', 'Next: bills', 'Next: next week']) {
        await tapText(tester, next);
      }

      expect(find.text(r'$480.00'), findsOneWidget);
      expect(find.text('Done for the week'), findsNothing);
      expect(find.text('Discard this draft'), findsNothing);
      final radios = tester.widgetList<RadioListTile<Object?>>(find.byType(RadioListTile));
      expect(radios.every((r) => r.enabled == false), isTrue);
      expect(writes(server), isEmpty);
    });

    testWidgets("a VIEWER's answer, if one is ever sent, is the API's translated 403", (
      tester,
    ) async {
      await openCheckIn(tester, state: 'viewer-draft', who: 'viewer');
      final container = ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));
      final week = container.read(checkInProvider(null)).data!;
      // A first answer, as a viewer's phone would have to send it: no version to replace.
      const item = Item(itemKey: 'alloc', kind: ItemKind.allocation, question: true);
      final choice = week.stepNamed(StepName.nextWeek)!.choices!.last;

      final error = await tester.runAsync(() async {
        try {
          await container
              .read(checkInWriterProvider)
              .answer(week.week, item, allocation(choice, '63.0000')!);
          return null;
        } on AppError catch (e) {
          return e;
        }
      });

      expect(error?.status, 403);
      expect(error?.code, 'read-only-role');
    });

    // The network stays up: only the phone's own knowledge says no, so what refuses is the control
    // and the writer, never the server.
    for (final (why, said) in [
      ('offline', "You're offline — this needs a connection"),
      (
        "the server's version is not confirmed",
        'Changes are off until the server confirms it runs a version this app works with. '
            'Refresh to check again.',
      ),
    ]) {
      testWidgets('$why, every answer is disabled, said, and nothing is sent', (tester) async {
        final server = await openCheckIn(tester, state: 'started');
        final container = ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));
        final reachability = container.read(reachabilityProvider.notifier);
        if (why == 'offline') {
          reachability.record(Reachability.offline);
        } else {
          reachability.unconfirmVersion();
        }
        await settle(tester);

        expect(find.text(said), findsOneWidget);
        final prepaid = tester.widget<OutlinedButton>(
          find.widgetWithText(OutlinedButton, 'Yes, prepaid'),
        );
        expect(prepaid.onPressed, isNull);

        // And the writer itself refuses, behind the disabled control.
        final week = container.read(checkInProvider(null)).data!;
        final item = week.steps.first.items.first;
        final error = await tester.runAsync(() async {
          try {
            await container
                .read(checkInWriterProvider)
                .answer(week.week, item, choiceOnly(AnswerChoice.prepaid));
            return null;
          } on AppError catch (e) {
            return e;
          }
        });
        expect(error?.isConnectivity, isTrue);
        expect(writes(server), isEmpty);
      });
    }
  });
}
