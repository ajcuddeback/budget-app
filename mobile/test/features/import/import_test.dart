import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/errors/error_text.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/features/import/data/imports_repository.dart';
import 'package:budget_owl/features/import/data/statement_file.dart';
import 'package:budget_owl/l10n/gen/app_localizations_en.dart';
import 'package:file_selector/file_selector.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fixture_server.dart';
import '../../support/harness.dart';

/// The picker, stood in for: hands back the statement a test chose, from memory.
final class FakePicker implements StatementPicker {
  FakePicker(this.statement);

  final PickedStatement? statement;
  int picks = 0;

  @override
  Future<PickedStatement?> pick() async {
    picks++;
    return statement;
  }
}

/// The statement bytes the fixture's preview carried, by format: the file the API was sent.
Uint8List recordedFile(String format) {
  final decoded = jsonDecode(
    File('test/fixtures/import.generated.json').readAsStringSync(),
  ) as Map<String, Object?>;
  for (final e in decoded['exchanges']! as List<Object?>) {
    final exchange = e! as Map<String, Object?>;
    final request = exchange['request'];
    if (exchange['path'] == '/api/imports/preview' &&
        request is Map<String, Object?> &&
        request['format'] == format) {
      return base64Decode(request['file']! as String);
    }
  }
  throw StateError('no recorded $format preview');
}

/// Every file under [root], links not followed.
///
/// A directory below [root] that this process may not list is skipped: whatever is in it was not
/// written by this process, which can list what it creates. The system temp folder is shared, and
/// on a CI runner it holds `systemd-private-*` folders that only root may open; a recursive
/// `listSync` threw on the first of them, so the test failed there and passed as root.
/// [root] itself is never skipped: a root that cannot be listed fails the test.
List<File> filesUnder(Directory root) {
  final files = <File>[];
  void walk(Directory directory, {required bool isRoot}) {
    final List<FileSystemEntity> entries;
    try {
      entries = directory.listSync(followLinks: false);
    } on FileSystemException {
      if (isRoot) rethrow;
      return; // Not listable by us, or removed by another test meanwhile: nothing of ours.
    }
    for (final entity in entries) {
      if (entity is File) {
        files.add(entity);
      } else if (entity is Directory) {
        walk(entity, isRoot: false);
      }
    }
  }

  walk(root, isRoot: true);
  return files;
}

void main() {
  Future<FixtureServer> openImport(
    WidgetTester tester, {
    String state = 'ready',
    String who = 'owner',
    StatementPicker? picker,
  }) async {
    final server = FixtureServer.load('import', state: state);
    await pumpApp(
      tester,
      TestPhone(server: server)..signedIn(who),
      overrides: [if (picker != null) statementPickerProvider.overrideWithValue(picker)],
    );
    await tester.tap(find.text('More'));
    await settle(tester);
    await tapVisible(tester, find.text('Import'));
    return server;
  }

  Future<void> tapText(WidgetTester tester, String text) =>
      tapVisible(tester, find.text(text).last);

  Future<void> chooseAccountAndFile(WidgetTester tester) async {
    await tapVisible(tester, find.text('Choose an account'));
    await tapText(tester, 'Everyday Checking •4417');
    await tapText(tester, 'Choose a file');
  }

  List<String> writes(FixtureServer server) =>
      server.served.where((s) => !s.startsWith('GET')).toList();

  group('roles', () {
    testWidgets('a VIEWER is told plainly, offered no form, and nothing is fetched', (
      tester,
    ) async {
      final server = await openImport(tester, state: 'viewer', who: 'viewer');

      expect(find.text("Viewers can't import statements"), findsOneWidget);
      expect(find.text('Choose a file'), findsNothing);
      expect(server.served.where((s) => s.contains('/api/imports')), isEmpty);
    });

    testWidgets("a VIEWER's preview, if one is ever sent, is the API's translated 403", (
      tester,
    ) async {
      final server = await openImport(tester, state: 'viewer', who: 'viewer');
      final container = ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));
      final request = fixtureRequest('import', state: 'viewer', path: '/api/imports/preview');

      final error = await tester.runAsync(() async {
        try {
          await container.read(importsWriterProvider).preview(request);
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
      expect(writes(server), ['POST /api/imports/preview']);
    });
  });

  group('an OFX statement', () {
    testWidgets('previews, includes the row that looks typed when ticked, imports, undoes', (
      tester,
    ) async {
      final picker = FakePicker(
        PickedStatement(bytes: recordedFile('OFX'), name: 'August statement.QFX'),
      );
      final server = await openImport(tester, picker: picker);

      expect(find.text('Nothing imported yet.'), findsOneWidget);
      await chooseAccountAndFile(tester);
      expect(find.text('OFX or QFX'), findsOneWidget, reason: 'the format follows the name');
      await tapText(tester, 'Preview the import');

      // Byte for byte the recorded request: account, format, the file as base64, no name.
      expect(writes(server), ['POST /api/imports/preview']);
      expect(
        find.text(
          r'Your bank says the balance is $4,622.55. After this import, '
          r'Budget Owl will say $4,622.55.'
          '\nThey match.',
        ),
        findsOneWidget,
      );
      expect(find.text('Import 3 transactions into Everyday Checking •4417'), findsOneWidget);

      await tapVisible(tester, find.byType(CheckboxListTile).first);
      await tapText(tester, 'Import 4 transactions into Everyday Checking •4417');

      expect(server.state, 'imported');
      expect(find.textContaining('Imported 4 transactions into Everyday Checking'), findsOneWidget);

      await tapText(tester, 'Undo this import');
      expect(find.text('Undo this import?'), findsOneWidget);
      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Undo this import').last);

      expect(server.state, 'undone');
      expect(find.text('Import undone. 4 transactions removed.'), findsOneWidget);
    });

    testWidgets('somebody else importing first refuses the commit and previews again', (
      tester,
    ) async {
      final picker = FakePicker(PickedStatement(bytes: recordedFile('OFX'), name: 'aug.ofx'));
      final server = await openImport(tester, state: 'undone', picker: picker);

      await chooseAccountAndFile(tester);
      await tapText(tester, 'Preview the import');
      await tapText(tester, 'Import 3 transactions into Everyday Checking •4417');

      expect(server.state, 'stale');
      expect(writes(server), [
        'POST /api/imports/preview',
        'POST /api/imports',
        'POST /api/imports/preview',
      ]);
      expect(
        find.textContaining('2 of these were added by somebody else a moment ago.'),
        findsOneWidget,
      );
      // The row only this file carries is still new; the two the other import carried are not.
      expect(find.text('Import 1 transactions into Everyday Checking •4417'), findsOneWidget);
    });
  });

  testWidgets('a CSV is read with the suggested columns only once Update preview is pressed', (
    tester,
  ) async {
    final picker = FakePicker(PickedStatement(bytes: recordedFile('CSV'), name: 'export.csv'));
    final server = await openImport(tester, picker: picker);

    await chooseAccountAndFile(tester);
    await tapText(tester, 'Preview the import');
    expect(
      find.text(
        'Budget Owl guessed these settings from the file. Check them, then update the '
        'preview.',
      ),
      findsOneWidget,
    );
    expect(find.text('Import 3 transactions into Everyday Checking •4417'), findsNothing);

    await tapText(tester, 'Update preview');

    expect(writes(server), ['POST /api/imports/preview', 'POST /api/imports/preview']);
    expect(server.served, contains('GET /api/accounts/acc-everyday-checking/import-mapping'));
    expect(find.text('Import 3 transactions into Everyday Checking •4417'), findsOneWidget);
  });

  // The network stays up throughout: only the phone's own knowledge says no, so what refuses is
  // the control and the writer, never the server.
  for (final (why, said) in [
    ('offline', "You're offline — this needs a connection"),
    (
      "the server's version is not confirmed",
      'Changes are off until the server confirms it runs a version this app works with. '
          'Refresh to check again.',
    ),
  ]) {
    testWidgets('$why, nothing is previewed and the reason is said', (tester) async {
      final picker = FakePicker(PickedStatement(bytes: recordedFile('OFX'), name: 'aug.ofx'));
      final server = await openImport(tester, picker: picker);
      await chooseAccountAndFile(tester);
      final container = ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));
      final reachability = container.read(reachabilityProvider.notifier);
      if (why == 'offline') {
        reachability.record(Reachability.offline);
      } else {
        reachability.unconfirmVersion();
      }
      await settle(tester);

      final preview = tester.widget<FilledButton>(
        find.widgetWithText(FilledButton, 'Preview the import'),
      );
      expect(preview.onPressed, isNull);
      expect(find.text(said), findsWidgets);

      // And the writer itself refuses, behind the disabled control.
      final error = await tester.runAsync(() async {
        try {
          await container.read(importsWriterProvider).preview({
            'accountId': 'acc-everyday-checking',
            'format': 'OFX',
            'file': 'T0ZY',
          });
          return null;
        } on AppError catch (e) {
          return e;
        }
      });
      expect(error?.isConnectivity, isTrue);
      expect(writes(server), isEmpty);
    });
  }

  testWidgets('a file over 5 MB is refused on the phone, as a courtesy, before anything is sent', (
    tester,
  ) async {
    final picker = FakePicker(
      PickedStatement(bytes: Uint8List(5 * 1024 * 1024 + 1), name: 'huge.csv'),
    );
    final server = await openImport(tester, picker: picker);

    await chooseAccountAndFile(tester);

    expect(find.textContaining('larger than 5 MB'), findsOneWidget);
    expect(writes(server), isEmpty);
  });

  testWidgets('the statement is never written to the phone: not a file, not the cache', (
    tester,
  ) async {
    final started = DateTime.now();
    final bytes = recordedFile('OFX');
    final picker = FakePicker(PickedStatement(bytes: bytes, name: 'aug.ofx'));
    final server = FixtureServer.load('import', state: 'ready');
    final phone = TestPhone(server: server)..signedIn('owner');
    await pumpApp(tester, phone, overrides: [statementPickerProvider.overrideWithValue(picker)]);
    await tester.tap(find.text('More'));
    await settle(tester);
    await tapVisible(tester, find.text('Import'));
    await chooseAccountAndFile(tester);
    await tapText(tester, 'Preview the import');
    await tapVisible(tester, find.byType(CheckboxListTile).first);
    await tapText(tester, 'Import 4 transactions into Everyday Checking •4417');
    expect(server.state, 'imported');

    // Distinctive text from the statement, as it is and as it travels.
    final markers = ['EC-0004', base64Encode(bytes).substring(0, 48)];
    final everywhere = [phone.cacheDirectory, phone.supportDirectory, Directory.systemTemp];
    final holding = await tester.runAsync(() async {
      final found = <String>[];
      for (final root in everywhere) {
        for (final entity in filesUnder(root)) {
          try {
            if (entity.lastModifiedSync().isBefore(started)) continue;
            final text = latin1.decode(entity.readAsBytesSync());
            if (markers.any(text.contains)) found.add(entity.path);
          } on FileSystemException {
            // Another test's file, gone or unreadable: not one this test wrote.
          }
        }
      }
      return found;
    });
    expect(holding, isEmpty);
  });

  group('the platform picker', () {
    late Directory temporary;

    setUp(() => temporary = Directory.systemTemp.createTempSync('budget-owl-picker'));
    tearDown(() => temporary.deleteSync(recursive: true));

    test("keeps the bytes in memory and deletes the platform's copy at once", () async {
      // Android's file_selector: the bytes beside a copy in cache/<uuid>/<name>.
      final folder = Directory('${temporary.path}/0b1c')..createSync();
      final copy = File('${folder.path}/aug.ofx')..writeAsStringSync('OFX');
      final picker = SystemStatementPicker(
        open: () async => XFile.fromData(utf8.encode('OFX'), path: copy.path, name: 'aug.ofx'),
        temporary: () async => temporary,
      );

      final picked = await picker.pick();

      expect(utf8.decode(picked!.bytes), 'OFX');
      expect(copy.existsSync(), isFalse);
      expect(folder.existsSync(), isFalse, reason: "the plugin's folder, left empty, goes too");
    });

    test('deletes the copy even when reading it fails', () async {
      final copy = File('${temporary.path}/aug.ofx')..writeAsStringSync('OFX');
      final picker = SystemStatementPicker(
        open: () async => XFile('${temporary.path}/missing.ofx'),
        temporary: () async => temporary,
      );
      // iOS: the document copied into tmp/, then read from there.
      final reading = SystemStatementPicker(
        open: () async => XFile(copy.path),
        temporary: () async => temporary,
      );

      await expectLater(picker.pick(), throwsA(isA<FileSystemException>()));
      expect((await reading.pick())!.bytes, utf8.encode('OFX'));
      expect(copy.existsSync(), isFalse);
      expect(temporary.existsSync(), isTrue, reason: 'never the temporary directory itself');
    });

    test('never deletes a file outside the temporary directory', () async {
      final elsewhere = Directory.systemTemp.createTempSync('budget-owl-elsewhere');
      addTearDown(() => elsewhere.deleteSync(recursive: true));
      final original = File('${elsewhere.path}/statement.ofx')..writeAsStringSync('OFX');
      final picker = SystemStatementPicker(
        open: () async => XFile(original.path),
        temporary: () async => temporary,
      );

      await picker.pick();
      await forgetPlatformCopy('${temporary.path}/../${original.uri.pathSegments.last}', temporary);

      expect(original.existsSync(), isTrue);
    });

    test('a cancelled pick is nothing, and touches nothing', () async {
      final picker = SystemStatementPicker(
        open: () async => null,
        temporary: () async => temporary,
      );

      expect(await picker.pick(), isNull);
    });
  });
}
