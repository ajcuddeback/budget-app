import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/features/transactions/data/transactions_repository.dart';
import 'package:budget_owl/features/transactions/domain/requests.dart';
import 'package:budget_owl/l10n/gen/app_localizations_en.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fixture_server.dart';
import '../../support/harness.dart';

/// A refused write on the ledger says **why** writes are off — offline, or the server's version not
/// confirmed yet this launch — through `writesOffText`, never a bare "offline" (core 5e0fd95).
void main() {
  final l = AppLocalizationsEn();
  final coffee = TransactionForm(
    accountId: 'acc-0001',
    date: CalendarDate.parse('2026-08-20'),
    direction: Direction.out,
    amount: const ValidAmount('4.50'),
  );

  List<String> writes(FixtureServer server) =>
      server.served.where((line) => !line.startsWith('GET ')).toList();

  for (final (name, cut, sentence) in [
    (
      'version unconfirmed',
      (ReachabilityNotifier r) => r.unconfirmVersion(),
      l.offline_versionUnconfirmed,
    ),
    ('offline', (ReachabilityNotifier r) => r.record(Reachability.offline), l.offline_write),
  ]) {
    testWidgets('$name: the record button and Move money say so, and nothing is sent', (
      tester,
    ) async {
      final server = FixtureServer.load('demo', state: 'demo');
      final container = await pumpApp(tester, TestPhone(server: server)..signedIn('owner'));
      cut(container.read(reachabilityProvider.notifier));
      await tester.pump();

      await tapVisible(tester, find.text('Record a transaction'));
      expect(find.text(sentence), findsOneWidget);
      expect(find.widgetWithText(AppBar, l.transactions_addTitle), findsNothing);

      await tapVisible(tester, find.text('Move money'));
      expect(find.text(sentence), findsOneWidget);

      await expectLater(
        container.read(transactionsWriterProvider).create(coffee),
        throwsA(isA<AppError>()),
      );
      expect(writes(server), isEmpty);
      expect(server.state, 'demo');
    });
  }

  testWidgets('a sheet opened before the check failed explains its disabled Save', (tester) async {
    final server = FixtureServer.load('demo', state: 'demo');
    final container = await pumpApp(tester, TestPhone(server: server)..signedIn('owner'));
    await tapVisible(tester, find.text('Record a transaction'));

    container.read(reachabilityProvider.notifier).unconfirmVersion();
    await tester.pump();
    await revealIn(tester, find.widgetWithText(FilledButton, 'Save'));

    expect(
      tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Save')).onPressed,
      isNull,
    );
    expect(find.text(l.offline_versionUnconfirmed), findsOneWidget);
    expect(writes(server), isEmpty);
  });
}
