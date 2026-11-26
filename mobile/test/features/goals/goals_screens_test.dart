import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/features/goals/data/goals_repository.dart';
import 'package:budget_owl/features/goals/domain/requests.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../goldens/plans_golden_helpers.dart' show settlePreview;
import '../../support/harness.dart';
import '../plans_harness.dart';

/// Goals and a goal's page through the widget tree, against `goals.generated.json`. Each write
/// starts in the state the fixture chain recorded it from and must leave the server where the real
/// API was left.
void main() {
  Future<Plans> goals(WidgetTester tester, {String state = 'plans', String who = 'owner'}) =>
      Plans.open(
        tester,
        file: 'goals',
        state: state,
        who: who,
        on: (p) async {
          await p.tap('More');
          await p.tap('Goals');
        },
      );

  Finder openGoal(String name) => find.byWidgetPredicate(
    (w) => w is Semantics && w.properties.label == 'Open $name',
  );

  Future<Plans> goalPage(
    WidgetTester tester,
    String name, {
    String state = 'plans',
    String who = 'owner',
  }) async {
    final p = await goals(tester, state: state, who: who);
    await tapVisible(tester, openGoal(name));
    return p;
  }

  Future<void> type(WidgetTester tester, String label, String text) async {
    final field = find.widgetWithText(TextFormField, label);
    await revealIn(tester, field);
    await tester.enterText(field, text);
    await tester.pump();
  }

  group('reading', () {
    testWidgets('the list, by id and never by name, and no household in any path', (tester) async {
      final p = await goalPage(tester, 'Japan');

      expect(p.server.served, contains('GET /api/goals?includeArchived=true&page=0&size=200'));
      expect(p.server.served, contains('GET /api/goals/gol-0001'));
      expect(p.server.served.where((s) => s.contains('Japan')), isEmpty, reason: 'ids, not names');
      expect(p.server.served.where((s) => s.contains('household')), isEmpty);
    });

    testWidgets('a long history pages with Show more, as the web does', (tester) async {
      final p = await goalPage(tester, 'Emergency fund');

      expect(find.text('Jun 1, 2025', skipOffstage: false), findsNothing);
      await p.tap('Show more');

      expect(p.server.served.last, 'GET /api/goals/gol-0002/contributions?page=1&size=50');
      expect(find.text('Jun 1, 2025', skipOffstage: false), findsOneWidget);
      expect(find.text('Show more', skipOffstage: false), findsNothing, reason: 'all 56 shown');
      expect(p.writes, isEmpty);
    });

    testWidgets('archived goals sit behind a toggle', (tester) async {
      await goals(tester);

      expect(find.text('Wedding gift', skipOffstage: false), findsNothing);
      await tapVisible(tester, find.text('Show archived goals'));
      expect(find.text('Archived goals'), findsOneWidget);
    });
  });

  group('a VIEWER', () {
    testWidgets('sees every goal and figure and no write control', (tester) async {
      final p = await goals(tester, state: 'viewer', who: 'viewer');

      expect(find.text('Add a goal', skipOffstage: false), findsNothing);
      await tapVisible(tester, openGoal('Japan'));
      for (final control in [
        'Set money aside',
        'Take money out',
        'Edit',
        'Change',
        'Archive',
        'Delete',
      ]) {
        expect(find.text(control, skipOffstage: false), findsNothing, reason: control);
      }
      expect(find.byIcon(Icons.delete_outline), findsNothing);
      expect(find.text('History', skipOffstage: false), findsOneWidget);
      expect(p.writes, isEmpty);
    });

    testWidgets("a contribution, if one is ever sent, is the API's translated 403", (
      tester,
    ) async {
      final p = await goals(tester, state: 'viewer', who: 'viewer');
      final japan = p.container
          .read(goalsProvider)
          .data!
          .content
          .firstWhere((g) => g.id == 'gol-0001');

      final error = await p.forced(
        (c) => c
            .read(goalsWriterProvider)
            .contribute(
              japan,
              contributionBody(
                amount: '110',
                date: CalendarDate.parse('2026-08-20'),
                direction: ContributionDirection.into,
              ),
            ),
      );

      expect(error?.status, 403);
      expect(readOnlyText(error!), 'Your role in this household is read-only.');
    });
  });

  group('writes', () {
    testWidgets('adding a goal previews it on the server, then adds it', (tester) async {
      final p = await goals(tester);

      await p.tap('Add a goal');
      await type(tester, 'What it is for', 'Bike');
      await type(tester, 'How much it needs', '900');
      await settlePreview(tester);
      expect(p.writes, ['POST /api/goals/projection-preview']);
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Add a goal').last);

      expect(p.writes.last, 'POST /api/goals');
      expect(p.server.state, 'goal-added');
      expect(find.text('Bike is added.'), findsOneWidget);
    });

    testWidgets('setting money aside opens with what is still to come', (tester) async {
      final p = await goalPage(tester, 'Japan', state: 'goal-added');

      await p.tap('Set money aside');
      expect(
        tester.widget<TextFormField>(find.widgetWithText(TextFormField, 'Amount')).controller?.text,
        '110',
      );
      await p.tap('Set aside');

      expect(p.writes, ['POST /api/goals/gol-0001/contributions']);
      expect(p.server.state, 'set-aside');
      expect(find.text('Money set aside.'), findsOneWidget);
    });

    testWidgets('taking money out is its own direction, with a note', (tester) async {
      final p = await goalPage(tester, 'Emergency fund', state: 'set-aside');

      await p.tap('Take money out');
      await type(tester, 'Amount', '50');
      await type(tester, 'Note (optional)', 'Vet');
      await p.tap('Take out');

      expect(p.writes, ['POST /api/goals/gol-0002/contributions']);
      expect(p.server.state, 'taken-out');
      expect(find.text('Money taken out.'), findsOneWidget);
    });

    testWidgets('an edit previews, then sends only the target', (tester) async {
      final p = await goalPage(tester, 'Japan', state: 'taken-out');

      await p.tap('Edit');
      await settlePreview(tester);
      await type(tester, 'How much it needs', '4000');
      await settlePreview(tester);
      await p.tap('Save');

      expect(p.writes.where((w) => !w.contains('preview')), ['PATCH /api/goals/gol-0001']);
      expect(p.server.state, 'goal-edited');
      expect(find.text('The goal is updated.'), findsOneWidget);
    });

    testWidgets('the plan changes from this month', (tester) async {
      final p = await goalPage(tester, 'Japan', state: 'goal-edited');

      await p.tap('Change');
      await settlePreview(tester);
      await type(tester, 'Each month', '165');
      await settlePreview(tester);
      await p.tap('Save the plan');

      expect(p.writes.last, 'PUT /api/goals/gol-0001/contribution-plan/2026-08');
      expect(p.server.state, 'plan-changed');
      expect(find.text('The plan is changed from August 2026.'), findsOneWidget);
    });

    testWidgets('removing a contribution asks first', (tester) async {
      final p = await goalPage(tester, 'Japan', state: 'plan-changed');

      await tapVisible(tester, find.byTooltip('Remove Aug 20, 2026'));
      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Remove'));

      expect(p.writes, ['DELETE /api/goals/gol-0001/contributions/gen-0074']);
      expect(p.server.state, 'contribution-removed');
    });

    testWidgets('archive asks first; restore does not', (tester) async {
      final p = await goalPage(tester, 'New laptop', state: 'contribution-removed');

      await p.tap('Archive');
      expect(find.text('Archive New laptop?'), findsOneWidget);
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Archive'));
      expect(p.server.state, 'archived');
      expect(find.text('The goal is archived.'), findsOneWidget);
      expect(find.text('Set money aside'), findsNothing, reason: 'archived: no contributions');

      await p.tap('Restore');
      expect(p.server.state, 'unarchived');
      expect(p.writes, ['POST /api/goals/gol-0003/archive', 'POST /api/goals/gol-0003/unarchive']);
    });

    testWidgets('deleting asks first, says it is permanent, and goes back to the list', (
      tester,
    ) async {
      final p = await goalPage(tester, 'Bike', state: 'unarchived');

      await p.tap('Delete');
      expect(find.text('Delete Bike permanently?'), findsOneWidget);
      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Delete permanently'));

      expect(p.writes, ['DELETE /api/goals/gol-0005']);
      expect(p.server.state, 'deleted');
      expect(find.text('Your goals'), findsOneWidget);
      expect(openGoal('Bike'), findsNothing);
      expect(
        p.server.served.skipWhile((s) => !s.startsWith('DELETE')).where((s) => s.contains('gol-0005')),
        ['DELETE /api/goals/gol-0005'],
        reason: 'nothing asks after the deleted goal',
      );
    });
  });

  group('with writes off', () {
    testWidgets('offline, no sheet opens and nothing is sent', (tester) async {
      final p = await goalPage(tester, 'Japan');
      await p.goOffline();
      final before = p.server.served.length;

      await p.tap('Set money aside');
      expect(find.text('Set aside'), findsNothing, reason: 'no sheet opened');
      await p.tap('Archive');
      expect(find.text('Archive Japan?'), findsNothing, reason: 'no confirmation either');

      expect(find.text(offlineSentence, skipOffstage: false), findsOneWidget);
      expect(p.server.served.length, before);
    });

    testWidgets('offline, adding a goal is refused before the sheet opens', (tester) async {
      final p = await goals(tester);
      await p.goOffline();
      final before = p.server.served.length;

      await p.tap('Add a goal');

      expect(find.text('What it is for'), findsNothing);
      expect(find.text(offlineSentence, skipOffstage: false), findsOneWidget);
      expect(p.server.served.length, before);
    });

    testWidgets('with the version unconfirmed, the refusal says so, not "offline"', (
      tester,
    ) async {
      final p = await goalPage(tester, 'Japan');
      await p.unconfirm();
      final before = p.server.served.length;

      await p.tap('Take money out');

      expect(find.textContaining(unconfirmedSentence, skipOffstage: false), findsOneWidget);
      expect(find.text(offlineSentence, skipOffstage: false), findsNothing);
      expect(p.server.served.length, before);
    });
  });
}
