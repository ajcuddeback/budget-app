import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/features/bills/data/bills_repository.dart';
import 'package:budget_owl/features/bills/domain/requests.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../goldens/plans_golden_helpers.dart' show below;
import '../../support/harness.dart';
import '../plans_harness.dart';

/// Bills & income through the widget tree, against `bills.generated.json`: what is shown, what is
/// sent, and what is refused before anything is sent. Each write starts in the state the fixture
/// chain recorded it from and must leave the server in the state that write produced.
void main() {
  Future<Plans> bills(WidgetTester tester, {String state = 'plans', String who = 'owner'}) =>
      Plans.open(tester, file: 'bills', state: state, who: who, on: (p) => p.tap('Bills'));

  /// Chooses [item] from the open drop-down menu, which builds its items lazily.
  Future<void> pick(WidgetTester tester, String item) async {
    await revealIn(tester, find.text(item));
    await tester.tap(find.text(item).last);
    await settle(tester);
  }

  Future<void> correct(WidgetTester tester, String anchor) =>
      tapVisible(tester, below(tester, find.text('Correct this'), anchor));

  group('reading', () {
    testWidgets('the month comes from three reads, none naming a household', (tester) async {
      final p = await bills(tester);

      expect(
        p.server.served,
        containsAll([
          'GET /api/bills?period=2026-08&includeInactive=true',
          'GET /api/income?period=2026-08&includeInactive=true',
          'GET /api/periods/2026-08/summary',
        ]),
      );
      expect(p.server.served.where((s) => s.contains('household')), isEmpty);
      expect(find.text('August 2026'), findsOneWidget);
      expect(find.text('Rent'), findsWidgets);
    });

    testWidgets('the month moves both ways, and reads that month', (tester) async {
      final p = await bills(tester);

      await tapVisible(tester, find.byTooltip('Next month'));
      expect(p.server.served, contains('GET /api/bills?period=2026-09&includeInactive=true'));
      expect(find.text('September 2026'), findsOneWidget);

      await tapVisible(tester, find.byTooltip('Previous month'));
      await tapVisible(tester, find.byTooltip('Previous month'));
      expect(p.server.served, contains('GET /api/periods/2026-07/summary'));
      expect(find.text('July 2026'), findsOneWidget);
    });
  });

  group('a VIEWER', () {
    testWidgets('sees every figure and no write control', (tester) async {
      final p = await bills(tester, state: 'viewer', who: 'viewer');

      expect(find.text('Electricity', skipOffstage: false), findsWidgets);
      for (final control in ['Add a bill', 'Add an income source', 'Mark paid', 'Correct this']) {
        expect(find.text(control, skipOffstage: false), findsNothing, reason: control);
      }
      expect(find.byTooltip('Actions for Rent'), findsNothing);
      expect(find.text('Unmap', skipOffstage: false), findsNothing);
      expect(p.writes, isEmpty);
    });

    testWidgets("a write, if one is ever sent, is the API's translated 403", (tester) async {
      final p = await bills(tester, state: 'viewer', who: 'viewer');

      final error = await p.forced(
        (c) => c
            .read(billsWriterProvider)
            .record(
              'bil-0003',
              CalendarDate.parse('2026-08-18'),
              markPaidBody(CalendarDate.parse('2026-08-20')),
            ),
      );

      expect(error?.status, 403);
      expect(readOnlyText(error!), 'Your role in this household is read-only.');
    });
  });

  group('occurrences', () {
    testWidgets("Mark paid sends the status and the phone's date, and the month re-reads", (
      tester,
    ) async {
      final p = await bills(tester);
      final reads = p.server.served.where((s) => s.startsWith('GET /api/bills?')).length;

      await tapVisible(tester, below(tester, find.text('Mark paid'), 'Electricity'));

      expect(p.writes, ['PUT /api/bills/bil-0003/occurrences/2026-08-18']);
      expect(p.server.state, 'paid');
      expect(find.text('Marked paid.'), findsOneWidget);
      expect(
        p.server.served.where((s) => s.startsWith('GET /api/bills?')).length,
        greaterThan(reads),
      );
    });

    testWidgets("the paid date's picker stops at the phone's own today", (tester) async {
      final p = await bills(tester, state: 'paid');

      await correct(tester, 'Aug 17, 2026');
      await p.tap('Paid a different amount…');
      await tapVisible(tester, find.text('Date paid'));

      // The server refuses a date later than today anywhere on Earth, so none is offered.
      final picker = tester.widget<DatePickerDialog>(find.byType(DatePickerDialog));
      expect(DateUtils.dateOnly(picker.lastDate), DateTime(2026, 8, 20));
      expect(p.writes, isEmpty);
    });

    testWidgets('a different amount sends the figure and the date', (tester) async {
      final p = await bills(tester, state: 'paid');

      await correct(tester, 'Aug 17, 2026');
      await p.tap('Paid a different amount…');
      await tester.enterText(find.widgetWithText(TextFormField, 'Amount paid'), '14.50');
      await p.tap('Record it');

      expect(p.writes, ['PUT /api/bills/bil-0005/occurrences/2026-08-17']);
      expect(p.server.state, 'paid-other');
    });

    testWidgets('Skip, then Mark as due, each one tap behind Correct this', (tester) async {
      final p = await bills(tester, state: 'paid-other');

      await correct(tester, 'Internet');
      await p.tap('Skip');
      expect(p.server.state, 'skipped');
      expect(find.text('Skipped for this month.'), findsOneWidget);

      await correct(tester, 'Rent');
      await p.tap('Mark as due');
      expect(p.server.state, 'marked-due');
      expect(find.text('Marked as due.'), findsOneWidget);
    });

    testWidgets('Forget asks first, then deletes the record', (tester) async {
      final p = await bills(tester, state: 'marked-due');

      await correct(tester, 'Phone');
      await p.tap('Forget');
      expect(find.text('Forget what was recorded?'), findsOneWidget);
      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Forget'));

      expect(p.writes, ['DELETE /api/bills/bil-0002/occurrences/2026-08-12']);
      expect(p.server.state, 'forgotten');
    });

    testWidgets('an irregular bill records a payment on the day it was paid', (tester) async {
      final p = await bills(tester, state: 'forgotten');

      await p.tapTooltip('Actions for Window cleaner');
      await p.tap('Record a payment');
      await tester.enterText(find.widgetWithText(TextFormField, 'Amount paid'), '25');
      await p.tap('Record it');

      expect(p.writes, ['PUT /api/bills/bil-0008/occurrences/2026-08-20']);
      expect(p.server.state, 'recorded');
    });
  });

  group('bills', () {
    testWidgets('adding a monthly bill sends its day and nothing else', (tester) async {
      final p = await bills(tester, state: 'recorded');

      await p.tap('Add a bill');
      await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'Water');
      await tester.enterText(find.widgetWithText(TextFormField, 'Amount'), '38.50');
      await tapVisible(tester, find.text('Day of the month'));
      await pick(tester, '22');
      await p.tap('Save');

      expect(p.writes, ['POST /api/bills']);
      expect(p.server.state, 'bill-added');
      expect(find.text('Bill added.'), findsOneWidget);
    });

    testWidgets('an edit sends only the amount, with the version', (tester) async {
      final p = await bills(tester, state: 'bill-added');

      await p.tapTooltip('Actions for Phone');
      await p.tap('Edit');
      await tester.enterText(find.widgetWithText(TextFormField, 'Amount'), '48');
      await p.tap('Save');

      expect(p.writes, ['PATCH /api/bills/bil-0002']);
      expect(p.server.state, 'bill-edited');
      expect(find.text('Bill saved.'), findsOneWidget);
    });

    testWidgets('an edit somebody beat is a 409, said plainly, and the month re-reads', (
      tester,
    ) async {
      final p = await bills(tester);
      final reads = p.server.served.where((s) => s.startsWith('GET /api/bills?')).length;

      await p.tapTooltip('Actions for Phone');
      await p.tap('Edit');
      await tester.enterText(find.widgetWithText(TextFormField, 'Amount'), '48');
      await p.tap('Save');

      expect(
        find.textContaining('Somebody else changed this', skipOffstage: false),
        findsOneWidget,
      );
      expect(p.server.state, 'plans');
      expect(
        p.server.served.where((s) => s.startsWith('GET /api/bills?')).length,
        greaterThan(reads),
      );
    });

    testWidgets('retiring asks first; restoring and deleting follow', (tester) async {
      final p = await bills(tester, state: 'bill-edited');

      await p.tapTooltip('Actions for Gym');
      await p.tap('Retire');
      expect(find.text('Retire Gym?'), findsOneWidget);
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Retire'));
      expect(p.server.state, 'bill-retired');

      await tapVisible(tester, below(tester, find.text('Restore'), 'Streaming'));
      expect(p.server.state, 'bill-restored');

      await p.tapTooltip('Actions for Car insurance');
      await p.tap('Delete');
      expect(find.text('Delete Car insurance?'), findsOneWidget);
      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Delete').last);
      expect(p.server.state, 'bill-deleted');
      expect(p.writes, [
        'PATCH /api/bills/bil-0005',
        'PATCH /api/bills/bil-0009',
        'DELETE /api/bills/bil-0007',
      ]);
    });

    testWidgets("unmapping removes the charge rule, not the bill's history", (tester) async {
      final p = await bills(tester, state: 'income-deleted');

      await tapVisible(tester, find.text('Unmap').first);

      expect(p.writes, ['DELETE /api/charge-rules/rul-0001']);
      expect(p.server.state, 'unmapped');
    });
  });

  group('income', () {
    testWidgets('add, edit, retire, restore and delete', (tester) async {
      final p = await bills(tester, state: 'bill-deleted');

      await p.tap('Add an income source');
      await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'Tutoring');
      await tester.enterText(find.widgetWithText(TextFormField, 'Amount each time'), '60');
      await tapVisible(tester, find.text('Every month').last);
      await tapVisible(tester, find.text('Every week').last);
      await tapVisible(tester, find.text('Day of the week'));
      await pick(tester, 'Saturday');
      await p.tap('Save');
      expect(p.server.state, 'income-added');
      expect(find.text('Income source added.'), findsOneWidget);

      await p.tapTooltip('Actions for Tutoring');
      await p.tap('Edit');
      await tester.enterText(find.widgetWithText(TextFormField, 'Amount each time'), '65');
      await p.tap('Save');
      expect(p.server.state, 'income-edited');

      await p.tapTooltip('Actions for Child benefit');
      await p.tap('Retire');
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Retire'));
      expect(p.server.state, 'income-retired');

      await tapVisible(tester, below(tester, find.text('Restore'), 'Freelance'));
      expect(p.server.state, 'income-restored');

      await p.tapTooltip('Actions for Tutoring');
      await p.tap('Delete');
      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Delete').last);
      expect(p.server.state, 'income-deleted');
      expect(p.writes, [
        'POST /api/income',
        'PATCH /api/income/inc-0004',
        'PATCH /api/income/inc-0002',
        'PATCH /api/income/inc-0003',
        'DELETE /api/income/inc-0004',
      ]);
    });
  });

  group('with writes off', () {
    testWidgets('the writer itself refuses, offline or unconfirmed, and sends nothing', (
      tester,
    ) async {
      final p = await bills(tester);
      final before = p.server.served.length;
      Future<void> write(ProviderContainer c) => c
          .read(billsWriterProvider)
          .record(
            'bil-0003',
            CalendarDate.parse('2026-08-18'),
            markPaidBody(CalendarDate.parse('2026-08-20')),
          );

      await p.believeOffline();
      expect((await p.forced(write))?.isConnectivity, isTrue);
      await p.reachable();
      await p.unconfirm();
      expect((await p.forced(write))?.isConnectivity, isTrue);
      expect(p.server.served.length, before);
    });

    testWidgets('offline, Mark paid says it needs a connection and sends nothing', (tester) async {
      final p = await bills(tester);
      await p.goOffline();
      final before = p.server.served.length;

      await tapVisible(tester, below(tester, find.text('Mark paid'), 'Electricity'));

      expect(find.text(offlineSentence), findsOneWidget);
      expect(p.server.served.length, before);
    });

    testWidgets('offline, no sheet opens and nothing is sent', (tester) async {
      final p = await bills(tester);
      await p.goOffline();
      final before = p.server.served.length;

      await p.tap('Add a bill');
      expect(find.text('Save'), findsNothing, reason: 'no sheet opened');
      await p.tapTooltip('Actions for Gym');
      await p.tap('Retire');
      expect(find.text('Retire Gym?'), findsNothing, reason: 'no confirmation either');
      await p.tap('Add an income source');
      expect(find.text(offlineSentence, skipOffstage: false), findsOneWidget);
      expect(p.server.served.length, before);
    });

    testWidgets('with the version unconfirmed, the refusal says so, not "offline"', (tester) async {
      final p = await bills(tester);
      await p.unconfirm();
      final before = p.server.served.length;

      await tapVisible(tester, below(tester, find.text('Mark paid'), 'Electricity'));

      expect(find.textContaining(unconfirmedSentence), findsOneWidget);
      expect(find.text(offlineSentence), findsNothing);
      expect(p.server.served.length, before);
    });
  });
}
