import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/errors/error_text.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/features/categories/data/categories.dart';
import 'package:budget_owl/features/categories/domain/requests.dart';
import 'package:budget_owl/l10n/gen/app_localizations_en.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fixture_server.dart';
import '../../support/harness.dart';

/// Categories and payee rules (More → Categories) against `categories.generated.json`.
void main() {
  final l = AppLocalizationsEn();
  final household =
      ((fixtureBody('categories', state: 'categories', path: '/api/auth/me')!
                  as Map<String, Object?>)['household']!
              as Map<String, Object?>)['householdId']!
          as String;
  const blueBottle = PayeeRule(
    id: 'rule-sq-blue-bottle',
    payeeName: 'SQ *BLUE BOTTLE',
    categoryName: 'Eating Out',
    categoryArchived: false,
  );

  Future<(FixtureServer, ProviderContainer)> open(
    WidgetTester tester, {
    String state = 'categories',
    String who = 'owner',
  }) async {
    final server = FixtureServer.load('categories', state: state);
    final container = await pumpApp(tester, TestPhone(server: server)..signedIn(who));
    await tapVisible(tester, find.text('More').last);
    await tapVisible(tester, find.text('Categories').last);
    return (server, container);
  }

  Future<void> tap(WidgetTester tester, String text) => tapVisible(tester, find.text(text).last);

  Finder field(String label) => find.widgetWithText(TextFormField, label);

  List<String> writes(FixtureServer server) =>
      server.served.where((line) => !line.startsWith('GET ')).toList();

  testWidgets('income then spending, children under their parent, archived behind a toggle', (
    tester,
  ) async {
    final (server, _) = await open(tester);

    expect(find.text('Income'), findsNWidgets(2), reason: 'the group and the category');
    expect(find.text('Spending'), findsOneWidget);
    expect(find.text('Salary'), findsOneWidget);
    expect(find.text('Wedding'), findsNothing);
    await tap(tester, 'Show 1 archived');
    expect(find.text('Wedding'), findsOneWidget);
    expect(server.served, contains('GET /api/payee-rules?page=0&size=200'));
  });

  testWidgets('adding a top-level category states its kind', (tester) async {
    final (server, _) = await open(tester);

    await tap(tester, 'Add a category');
    await tester.enterText(field('Name'), 'Pets');
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

    expect(writes(server), ['POST /api/categories']);
    expect(server.state, 'category-added');
    expect(find.text(l.categories_created), findsOneWidget);
    expect(find.text('Pets'), findsOneWidget);
    expect(server.served.where((line) => line.contains(household)), isEmpty);
  });

  testWidgets("a sub-category takes its parent's kind, and says so", (tester) async {
    final (server, _) = await open(tester, state: 'category-added');

    await tap(tester, 'Pets');
    await tap(tester, l.categories_addChild);
    expect(find.text(l.categories_kindFromParentHint), findsOneWidget);
    await tester.enterText(field('Name'), 'Vet');
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

    expect(writes(server), ['POST /api/categories']);
    expect(server.state, 'subcategory-added');
    expect(find.text('Vet'), findsOneWidget);
  });

  testWidgets('a rename sends the name and the version, and nothing else', (tester) async {
    final (server, _) = await open(tester, state: 'subcategory-added');

    await tap(tester, 'Eating Out');
    await tap(tester, 'Edit');
    expect(find.text(l.categories_kindFixedHint), findsOneWidget);
    await tester.enterText(field('Name'), 'Dining');
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

    expect(writes(server), ['PATCH /api/categories/cat-eating-out']);
    expect(server.state, 'category-edited');
    expect(find.text('Dining'), findsOneWidget);
  });

  testWidgets('an untouched edit sends nothing', (tester) async {
    final (server, _) = await open(tester);

    await tap(tester, 'Fuel');
    await tap(tester, 'Edit');
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

    expect(writes(server), isEmpty);
    expect(find.text(l.categories_editTitle), findsNothing);
  });

  testWidgets('a duplicate is refused with a sentence, and nothing changes', (tester) async {
    final (server, _) = await open(tester);

    await tap(tester, 'Add a category');
    await tester.enterText(field('Name'), 'groceries');
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

    expect(server.state, 'categories');
    expect(find.text(l.error_generic), findsOneWidget);
  });

  testWidgets('archiving asks first, then archives', (tester) async {
    final (server, _) = await open(tester, state: 'category-edited');

    await tap(tester, 'Shopping');
    await tap(tester, 'Archive');
    expect(find.text(l.categories_archiveMessage), findsOneWidget);
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Archive'));

    expect(writes(server), ['POST /api/categories/cat-shopping/archive']);
    expect(server.state, 'category-archived');
    expect(find.text(l.categories_archived), findsOneWidget);
  });

  testWidgets('restoring an archived one', (tester) async {
    final (server, _) = await open(tester, state: 'category-archived');

    await tap(tester, 'Show 2 archived');
    await tap(tester, 'Wedding');
    await tap(tester, 'Restore');

    expect(writes(server), ['POST /api/categories/cat-wedding/unarchive']);
    expect(server.state, 'category-unarchived');
  });

  testWidgets('deleting says the transactions are kept and the children go too', (tester) async {
    final (server, _) = await open(tester, state: 'category-unarchived');

    await tap(tester, 'Transport');
    await tap(tester, 'Delete');
    expect(find.text(l.categories_deleteMessageWithChildren('3')), findsOneWidget);
    await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Delete'));

    expect(writes(server), ['DELETE /api/categories/cat-transport']);
    expect(server.state, 'category-deleted');
    expect(find.text('Transport'), findsNothing);
  });

  testWidgets('a payee rule is listed and removed, and nothing else', (tester) async {
    final (server, _) = await open(tester, state: 'category-deleted');

    await tap(tester, 'Show payee rules (2)');
    expect(find.text('SQ *BLUE BOTTLE'), findsOneWidget);
    expect(find.text('files under Dining'), findsOneWidget, reason: 'renamed in this story');
    await tapVisible(tester, find.bySemanticsLabel('Remove SQ *BLUE BOTTLE'));

    expect(writes(server), ['DELETE /api/payee-rules/rule-sq-blue-bottle']);
    expect(server.state, 'rule-removed');
    expect(find.text(l.checkIn_rules_removed), findsOneWidget);
    expect(find.text('SQ *BLUE BOTTLE'), findsNothing);
  });

  group('roles', () {
    testWidgets('a VIEWER reads everything and is offered nothing', (tester) async {
      await open(tester, state: 'categories-viewer', who: 'viewer');

      expect(find.text('Add a category'), findsNothing);
      await tap(tester, 'Transport');
      expect(find.text('Edit'), findsNothing);
      await tap(tester, 'Show payee rules (3)');
      expect(find.text('SQ *BLUE BOTTLE'), findsOneWidget);
      expect(find.text('Remove'), findsNothing);
      expect(find.text(l.checkIn_rules_archived), findsOneWidget);
    });

    testWidgets("a VIEWER's forced write gets the translated 403", (tester) async {
      final (server, container) = await open(tester, state: 'categories-viewer', who: 'viewer');

      final refusals = <AppError>[];
      for (final write in [
        () => container
            .read(categoriesWriterProvider)
            .create(const CategoryForm(name: 'Pets', kind: 'EXPENSE')),
        () => container.read(categoriesWriterProvider).removeRule(blueBottle),
      ]) {
        // A real round trip through the shared transport: run outside the test's fake clock.
        await tester.runAsync(() async {
          try {
            await write();
          } on AppError catch (error) {
            refusals.add(error);
          }
        });
      }

      expect(refusals.map((e) => e.status), [403, 403]);
      expect(errorText(l, refusals.first, 'en'), 'Your role in this household is read-only.');
      expect(server.state, 'categories-viewer');
    });
  });

  group('writes are refused before anything is sent', () {
    testWidgets('offline: the control says so, and the writer sends nothing', (tester) async {
      final (server, container) = await open(tester);
      container.read(reachabilityProvider.notifier).record(Reachability.offline);
      await tester.pump();

      await tap(tester, 'Add a category');
      expect(find.widgetWithText(AppBar, l.categories_addTitle), findsNothing, reason: 'no sheet');
      expect(find.text(l.offline_write), findsOneWidget);
      await tap(tester, 'Show payee rules (3)');
      await tapVisible(tester, find.bySemanticsLabel('Remove SQ *BLUE BOTTLE'));
      expect(find.text(l.offline_write), findsWidgets);

      expect(
        await attempt(
          tester,
          () => container.read(categoriesWriterProvider).removeRule(blueBottle),
        ),
        isA<AppError>().having((e) => e.kind, 'kind', AppErrorKind.offline),
      );
      expect(writes(server), isEmpty);
      expect(server.state, 'categories');
    });

    testWidgets('version unconfirmed: it says why, and nothing is sent', (tester) async {
      final (server, container) = await open(tester);
      container.read(reachabilityProvider.notifier).unconfirmVersion();
      await tester.pump();

      await tap(tester, 'Add a category');
      expect(find.text(l.offline_versionUnconfirmed), findsOneWidget);
      expect(
        await attempt(
          tester,
          () => container
              .read(categoriesWriterProvider)
              .create(const CategoryForm(name: 'Pets', kind: 'EXPENSE')),
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
