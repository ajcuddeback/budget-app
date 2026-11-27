import 'dart:io';

import 'package:budget_owl/features/insights/data/csv_export.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fixture_server.dart';
import '../../support/harness.dart';

/// The share sheet, stood in for: what it was handed, and what it answers.
final class FakeSharer implements FileSharer {
  FakeSharer(this.outcome, {this.fails = false});

  final ShareOutcome outcome;
  final bool fails;
  final List<({String path, String text, List<int> bytes})> shared = [];
  final List<String> forgotten = [];
  int sweeps = 0;

  @override
  Future<ShareOutcome> share(File file, {required String mimeType, Rect? origin}) async {
    expect(mimeType, 'text/csv');
    shared.add((path: file.path, text: file.readAsStringSync(), bytes: file.readAsBytesSync()));
    if (fails) throw StateError('the share sheet could not open');
    return outcome;
  }

  @override
  Future<void> forget(String name, Directory temporary) async => forgotten.add(name);

  @override
  Future<void> sweep(Directory temporary) async => sweeps++;
}

void main() {
  Future<FixtureServer> openInsights(
    WidgetTester tester, {
    String file = 'insights',
    String state = 'demo',
    String who = 'owner',
    FakeSharer? sharer,
    Directory? temporary,
  }) async {
    final server = FixtureServer.load(file, state: state);
    await pumpApp(
      tester,
      TestPhone(server: server)..signedIn(who),
      overrides: [
        if (sharer != null) fileSharerProvider.overrideWithValue(sharer),
        if (temporary != null) exportDirectoryProvider.overrideWith((ref) async => temporary),
      ],
    );
    await tester.tap(find.text('More'));
    await settle(tester);
    await tapVisible(tester, find.text('Insights'));
    return server;
  }

  Directory scratch() {
    final dir = Directory.systemTemp.createTempSync('budget-owl-export-test');
    addTearDown(() => dir.deleteSync(recursive: true));
    return dir;
  }

  group('the reports', () {
    testWidgets("twelve months by the phone's date, and July as the last complete month", (
      tester,
    ) async {
      final server = await openInsights(tester);

      expect(
        server.served,
        containsAll([
          'GET /api/reports/income-and-spending?from=2025-09&to=2026-08',
          'GET /api/reports/spending-by-category?from=2026-07&to=2026-07',
        ]),
      );
      expect(find.text('INCOME VS SPENDING'), findsOneWidget);
      expect(find.textContaining('Avg kept'), findsOneWidget);
      expect(find.text('WHERE JULY 2026 WENT'), findsOneWidget);
      expect(find.text('Groceries'), findsWidgets);
    });

    testWidgets('a range chip asks report 1 again and saves nothing', (tester) async {
      final server = await openInsights(tester);

      await tapVisible(tester, find.text('6 mo'));

      expect(server.served.last, 'GET /api/reports/income-and-spending?from=2026-03&to=2026-08');
      expect(server.served.where((s) => !s.startsWith('GET')), isEmpty);
    });

    testWidgets('steps back a month, and never past the current one', (tester) async {
      final server = await openInsights(tester);

      await tapVisible(tester, find.byTooltip('Next month'));
      expect(find.text('WHERE AUGUST 2026 HAS GONE SO FAR'), findsOneWidget);
      final forward = tester.widget<IconButton>(
        find.ancestor(of: find.byTooltip('Next month'), matching: find.byType(IconButton)),
      );
      expect(forward.onPressed, isNull);

      await tapVisible(tester, find.byTooltip('Previous month'));
      await tapVisible(tester, find.byTooltip('Previous month'));
      expect(server.served.last, 'GET /api/reports/spending-by-category?from=2026-06&to=2026-06');
      expect(find.text('WHERE JUNE 2026 WENT'), findsOneWidget);
    });

    testWidgets('a VIEWER sees exactly what an owner sees, export included', (tester) async {
      final server = await openInsights(tester, state: 'viewer', who: 'viewer');

      expect(find.textContaining('Avg kept'), findsOneWidget);
      expect(find.text('Download CSV'), findsWidgets);
      expect(server.served.where((s) => !s.startsWith('GET')), isEmpty);
    });

    testWidgets('nothing recorded is a sentence, not a flat line and a zero average', (
      tester,
    ) async {
      await openInsights(tester, file: 'insights-states', state: 'empty');

      expect(
        find.text('Nothing recorded between September 2025 and August 2026 yet.'),
        findsOneWidget,
      );
      expect(find.textContaining('Avg kept'), findsNothing);
      expect(find.text('Nothing was spent in July 2026.'), findsOneWidget);
    });

    testWidgets('two currencies are two complete reports, never one figure', (tester) async {
      await openInsights(tester, file: 'insights-states', state: 'two-currencies');

      expect(find.text('USD'), findsOneWidget);
      expect(find.text('EUR'), findsOneWidget);
      await tapVisible(tester, find.text('EUR'));
      expect(find.textContaining('€'), findsWidgets);
    });
  });

  group('CSV export', () {
    Future<void> download(WidgetTester tester) async {
      // The income card's, the first of the two.
      await tapVisible(tester, find.text('Download CSV').first);
      await settle(tester);
    }

    testWidgets("shares the server's own file by Accept, then deletes it", (tester) async {
      final sharer = FakeSharer(ShareOutcome.shared);
      final temporary = scratch();
      final server = await openInsights(tester, sharer: sharer, temporary: temporary);

      await download(tester);

      expect(sharer.shared, hasLength(1));
      final shared = sharer.shared.single;
      expect(
        shared.path,
        '${temporary.path}/budget-owl-export/budget-owl-income-and-spending-2025-09-2026-08.csv',
      );
      // The bytes are the server's: its byte-order mark and its formula-safe quoting, untouched.
      expect(shared.bytes.take(3), [0xEF, 0xBB, 0xBF], reason: 'the byte-order mark');
      expect(shared.text, startsWith('"currency","period"'));
      expect(server.served.last, 'GET /api/reports/income-and-spending?from=2025-09&to=2026-08');
      expect(File(shared.path).existsSync(), isFalse, reason: 'deleted after sharing');
      expect(Directory('${temporary.path}/budget-owl-export').existsSync(), isFalse);
      expect(sharer.forgotten, isEmpty, reason: 'a shared copy is left for the app receiving it');
      expect(sharer.sweeps, greaterThanOrEqualTo(1));
    });

    testWidgets('a dismissed sheet deletes the file, and the platform copy too', (tester) async {
      final sharer = FakeSharer(ShareOutcome.dismissed);
      final temporary = scratch();
      await openInsights(tester, sharer: sharer, temporary: temporary);

      await download(tester);

      expect(File(sharer.shared.single.path).existsSync(), isFalse);
      expect(sharer.forgotten, ['budget-owl-income-and-spending-2025-09-2026-08.csv']);
      expect(temporary.listSync(recursive: true), isEmpty);
    });

    testWidgets('a failed share deletes the file and says so', (tester) async {
      final sharer = FakeSharer(ShareOutcome.shared, fails: true);
      final temporary = scratch();
      await openInsights(tester, sharer: sharer, temporary: temporary);

      await download(tester);

      expect(File(sharer.shared.single.path).existsSync(), isFalse);
      expect(temporary.listSync(recursive: true), isEmpty);
      expect(find.textContaining('share sheet could not open'), findsOneWidget);
    });

    testWidgets('offline, nothing is written and nothing is shared', (tester) async {
      final sharer = FakeSharer(ShareOutcome.shared);
      final temporary = scratch();
      final server = await openInsights(tester, sharer: sharer, temporary: temporary);
      server.offline = true;

      await download(tester);

      expect(sharer.shared, isEmpty);
      expect(temporary.listSync(recursive: true), isEmpty);
    });
  });

  group('platform copies', () {
    test('only an export of ours is ever swept from the share folder', () async {
      final temporary = Directory.systemTemp.createTempSync('budget-owl-sweep');
      addTearDown(() => temporary.deleteSync(recursive: true));
      final folder = Directory('${temporary.path}/share_plus')..createSync();
      final ours = File('${folder.path}/budget-owl-spending-by-category-2026-07-2026-07.csv')
        ..writeAsStringSync('x');
      final theirs = File('${folder.path}/holiday.jpg')..writeAsStringSync('x');

      // flutter_test's default platform is Android, where share_plus keeps its copies.
      await const SharePlusSharer().sweep(temporary);

      expect(ours.existsSync(), isFalse);
      expect(theirs.existsSync(), isTrue);
    });
  });
}
