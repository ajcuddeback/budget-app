import 'package:budget_owl/features/debt/data/debt_repository.dart';
import 'package:budget_owl/features/debt/domain/debt.dart';
import 'package:budget_owl/features/debt/domain/requests.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../goldens/plans_golden_helpers.dart' show below, settlePreview;
import '../../support/harness.dart';
import '../plans_harness.dart';

/// The debt plan and a debt's page through the widget tree, against `debt.generated.json`. Each
/// write starts in the state the fixture chain recorded it from and must leave the server where the
/// real API was left.
void main() {
  Future<Plans> plan(WidgetTester tester, {String state = 'plans', String who = 'owner'}) =>
      Plans.open(
        tester,
        file: 'debt',
        state: state,
        who: who,
        on: (p) async {
          await p.tap('More');
          await p.tap('Debt plan');
        },
      );

  /// The page of the debt on rung 1.
  Future<Plans> detail(WidgetTester tester, {String state = 'plans', String who = 'owner'}) async {
    final p = await plan(tester, state: state, who: who);
    await tapVisible(tester, below(tester, find.text('Open'), 'RUNG 1'));
    return p;
  }

  Future<void> type(WidgetTester tester, String label, String text) async {
    final field = find.widgetWithText(TextFormField, label);
    await revealIn(tester, field);
    await tester.enterText(field, text);
    await tester.pump();
  }

  group('reading', () {
    testWidgets('the plan, its debts and the what-if, none naming a household', (tester) async {
      final p = await plan(tester);

      expect(
        p.server.served,
        containsAll([
          'GET /api/debt-plan',
          'GET /api/debts?includeArchived=false',
          'GET /api/debt-plan/projection?monthlyAmount=955.0000',
        ]),
      );
      expect(p.server.served.where((s) => s.contains('household')), isEmpty);
      expect(find.text('Debt plan'), findsWidgets);
    });

    testWidgets('a debt opens by id, with its payments', (tester) async {
      final p = await detail(tester);

      expect(p.server.served, contains('GET /api/debts/deb-0001'));
      expect(p.server.served, contains('GET /api/debts/deb-0001/payments?page=0&size=50'));
      expect(find.text('Visa'), findsWidgets);
    });
  });

  group('a VIEWER', () {
    testWidgets('sees the plan and no write control', (tester) async {
      final p = await plan(tester, state: 'viewer', who: 'viewer');

      for (final control in [
        'Add a debt',
        'Change the monthly amount',
        'Edit terms',
        'Move down',
        'Your own order',
      ]) {
        expect(find.text(control, skipOffstage: false), findsNothing, reason: control);
      }
      expect(find.text('Your plan uses Avalanche.', skipOffstage: false), findsOneWidget);

      await tapVisible(tester, below(tester, find.text('Open'), 'RUNG 1'));
      for (final control in ['Log a payment', 'Edit terms', 'Confirm the balance']) {
        expect(find.text(control, skipOffstage: false), findsNothing, reason: control);
      }
      expect(p.writes, isEmpty);
    });

    testWidgets("a write, if one is ever sent, is the API's translated 403", (tester) async {
      final p = await plan(tester, state: 'viewer', who: 'viewer');

      final error = await p.forced(
        (c) => c.read(debtWriterProvider).updatePlan(strategyBody(0, Strategy.snowball)),
      );

      expect(error?.status, 403);
      expect(readOnlyText(error!), 'Your role in this household is read-only.');
    });
  });

  group('a debt', () {
    testWidgets('logging a payment previews it on the server, then records it', (tester) async {
      final p = await detail(tester);

      await p.tap('Log a payment');
      await settlePreview(tester);
      expect(p.writes, ['POST /api/debts/deb-0001/payments/preview'], reason: 'the scheduled 445');
      await p.tap(r'$495.00');
      await settlePreview(tester);
      await tapVisible(tester, find.text('Somewhere I do not track'));
      await tapVisible(tester, find.textContaining('Everyday Checking').last);
      await p.tap('Record it');

      expect(p.writes, [
        'POST /api/debts/deb-0001/payments/preview',
        'POST /api/debts/deb-0001/payments/preview',
        'POST /api/debts/deb-0001/payments',
      ]);
      expect(p.server.state, 'payment-logged');
      expect(find.textContaining('Payment recorded'), findsOneWidget);
    });

    testWidgets('editing the terms sends only what changed', (tester) async {
      final p = await detail(tester, state: 'payment-logged');

      await p.tap('Edit terms');
      await type(tester, 'Who you owe it to', 'Chase Bank');
      await p.tap('Save');

      expect(p.writes, ['PATCH /api/debts/deb-0001']);
      expect(p.server.state, 'terms-edited');
      expect(find.text('Terms saved.'), findsOneWidget);
    });

    testWidgets('adding a debt with the date its balance was true', (tester) async {
      final p = await plan(tester, state: 'terms-edited');

      await p.tap('Add a debt');
      await type(tester, 'What you call it', 'Store card');
      await type(tester, 'Who you owe it to', 'Argos');
      await type(tester, 'Balance now', '1240');
      await tapVisible(tester, find.text('The date that balance was true'));
      await tapVisible(tester, find.byTooltip('Switch to input'));
      await tester.enterText(find.byType(TextField).last, '05/20/2026');
      await tapVisible(tester, find.text('OK'));
      await type(tester, 'Annual rate', '29.9');
      await type(tester, 'Minimum payment', '45');
      await p.tap('Save');

      expect(p.writes, ['POST /api/debts']);
      expect(p.server.state, 'debt-added');
      expect(find.text('Debt added.'), findsOneWidget);
    });

    testWidgets('an estimate is corrected from a statement', (tester) async {
      final p = await detail(tester, state: 'debt-added');

      await p.tap('Confirm the balance');
      await p.tap('Correct it');
      await type(tester, 'What the statement says', '1180');
      await p.tap('Save the balance');

      expect(p.writes, ['POST /api/debts/deb-0004/balance']);
      expect(p.server.state, 'estimate-corrected');
    });

    testWidgets('a stale balance is confirmed as it stands', (tester) async {
      final p = await detail(tester, state: 'stale-added');

      await p.tap('Confirm the balance');
      await p.tap(r'Confirm $860.00');

      expect(p.writes, ['POST /api/debts/deb-0005/balance']);
      expect(p.server.state, 'stale-confirmed');
    });
  });

  group('the plan', () {
    testWidgets('snowball, avalanche, your own order, then a move', (tester) async {
      final p = await plan(tester, state: 'stale-confirmed');

      await p.press('Snowball');
      expect(p.server.state, 'snowball');
      await p.press('Avalanche');
      expect(p.server.state, 'avalanche');
      await p.press('Your own order');
      expect(p.server.state, 'custom');
      await tapVisible(tester, below(tester, find.text('Move down'), 'RUNG 1'));
      expect(p.server.state, 'custom-moved');
      expect(p.writes, List.filled(4, 'PUT /api/debt-plan'));
    });

    testWidgets('the monthly amount', (tester) async {
      final p = await plan(tester, state: 'custom-moved');

      await p.tap('Change the monthly amount');
      await type(tester, 'Towards debt each month', '2000');
      await p.tap('Save');

      expect(p.writes, ['PUT /api/debt-plan']);
      expect(p.server.state, 'amount');
      expect(find.text('Monthly amount saved.', skipOffstage: false), findsOneWidget);
    });
  });

  group('with writes off', () {
    testWidgets('the writer itself refuses, offline or unconfirmed, and sends nothing', (
      tester,
    ) async {
      final p = await plan(tester);
      final before = p.server.served.length;
      Future<void> write(ProviderContainer c) =>
          c.read(debtWriterProvider).updatePlan(strategyBody(0, Strategy.snowball));

      await p.believeOffline();
      expect((await p.forced(write))?.isConnectivity, isTrue);
      await p.reachable();
      await p.unconfirm();
      expect((await p.forced(write))?.isConnectivity, isTrue);
      expect(p.server.served.length, before);
    });

    testWidgets('offline, logging a payment is refused before a sheet opens', (tester) async {
      final p = await detail(tester);
      await p.goOffline();
      final before = p.server.served.length;

      await p.tap('Log a payment');

      expect(find.text('Record it'), findsNothing);
      expect(find.text(offlineSentence, skipOffstage: false), findsOneWidget);
      expect(p.server.served.length, before);
    });

    testWidgets('offline, a strategy is not changed and nothing is sent', (tester) async {
      final p = await plan(tester);
      await p.goOffline();
      final before = p.server.served.length;

      await p.press('Snowball');
      await p.tap('Add a debt');

      expect(find.text('Save'), findsNothing);
      expect(find.text(offlineSentence, skipOffstage: false), findsOneWidget);
      expect(p.server.served.length, before);
    });

    testWidgets('with the version unconfirmed, the refusal says so, not "offline"', (tester) async {
      final p = await plan(tester);
      await p.unconfirm();
      final before = p.server.served.length;

      await p.press('Snowball');

      expect(find.textContaining(unconfirmedSentence, skipOffstage: false), findsOneWidget);
      expect(find.text(offlineSentence, skipOffstage: false), findsNothing);
      expect(p.server.served.length, before);
    });
  });
}
