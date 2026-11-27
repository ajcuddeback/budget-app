import 'dart:io';

import 'package:budget_owl/app.dart';
import 'package:budget_owl/core/auth/token_store.dart';
import 'package:budget_owl/core/cache/cache_holder.dart';
import 'package:budget_owl/core/cache/cache_key_store.dart';
import 'package:budget_owl/core/session/local_state.dart';
import 'package:budget_owl/core/session/server_store.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/storage/temp_files.dart';
import 'package:budget_owl/features/import/data/statement_file.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fixture_server.dart';
import '../support/harness.dart';

/// M1 and L1: what the app or a plugin leaves in the temporary directory — an exported CSV, the
/// share sheet's copy of it, the picker's copy of a statement — is swept at every wipe and once at
/// start-up, and nothing outside the temporary directory is ever touched.
void main() {
  const export = 'budget-owl-income-and-spending-2025-09-2026-08.csv';
  const uuid = '3f2c9a1e-7b4d-4e8a-9c0f-1d2e3f4a5b6c';

  Directory scratch(String name) {
    final dir = Directory.systemTemp.createTempSync(name);
    addTearDown(() {
      if (dir.existsSync()) dir.deleteSync(recursive: true);
    });
    return dir;
  }

  File write(Directory root, String path) => File('${root.path}/$path')
    ..createSync(recursive: true)
    ..writeAsStringSync('2026-07-01,Rent,-1200.00');

  bool there(Directory root, String path) =>
      FileSystemEntity.typeSync('${root.path}/$path', followLinks: false) !=
      FileSystemEntityType.notFound;

  group('the sweep', () {
    test('every export: all of budget-owl-export/, and only our names in share_plus/', () async {
      final temp = scratch('budget-owl-temp');
      write(temp, 'budget-owl-export/$export');
      write(temp, 'budget-owl-export/half-written.tmp');
      write(temp, 'share_plus/$export');
      write(temp, 'share_plus/holiday.jpg');
      write(temp, 'share_plus/budget-owl-notes.csv');
      write(temp, 'other-app-cache.bin');

      await sweepExports(temp);

      expect(there(temp, 'budget-owl-export'), isFalse);
      expect(there(temp, 'share_plus/$export'), isFalse);
      expect(there(temp, 'share_plus/holiday.jpg'), isTrue, reason: 'not ours');
      expect(there(temp, 'share_plus/budget-owl-notes.csv'), isTrue, reason: 'not an export name');
      expect(there(temp, 'other-app-cache.bin'), isTrue);
    });

    test("the picker's copies: a UUID folder holding exactly one file, and nothing else", () async {
      final temp = scratch('budget-owl-temp');
      write(temp, '$uuid/Statement 2026-07.ofx');
      write(temp, '${uuid.toUpperCase().replaceFirst('3F', '4F')}/statement.csv');
      write(temp, '5a2c9a1e-7b4d-4e8a-9c0f-1d2e3f4a5b6c/one.txt');
      write(temp, '5a2c9a1e-7b4d-4e8a-9c0f-1d2e3f4a5b6c/two.txt');
      write(temp, '6b2c9a1e-7b4d-4e8a-9c0f-1d2e3f4a5b6c/nested/one.txt');
      Directory('${temp.path}/7c2c9a1e-7b4d-4e8a-9c0f-1d2e3f4a5b6c').createSync();
      write(temp, 'not-a-uuid/statement.ofx');
      write(temp, '8d2c9a1e-7b4d-4e8a-9c0f-1d2e3f4a5b6c.ofx');

      await sweepPickerCopies(temp);

      expect(there(temp, uuid), isFalse);
      expect(there(temp, uuid.toUpperCase().replaceFirst('3F', '4F')), isFalse);
      expect(there(temp, '5a2c9a1e-7b4d-4e8a-9c0f-1d2e3f4a5b6c/one.txt'), isTrue, reason: 'two');
      expect(there(temp, '6b2c9a1e-7b4d-4e8a-9c0f-1d2e3f4a5b6c/nested/one.txt'), isTrue);
      expect(there(temp, '7c2c9a1e-7b4d-4e8a-9c0f-1d2e3f4a5b6c'), isTrue, reason: 'empty');
      expect(there(temp, 'not-a-uuid/statement.ofx'), isTrue);
      expect(there(temp, '8d2c9a1e-7b4d-4e8a-9c0f-1d2e3f4a5b6c.ofx'), isTrue, reason: 'a file');
    });

    test('never outside the temporary directory: a link is never followed', () async {
      final temp = scratch('budget-owl-temp');
      final outside = scratch('budget-owl-outside');
      final precious = write(outside, 'only-file.txt');
      final preciousExport = write(outside, export);
      Link('${temp.path}/$uuid').createSync(outside.path);
      Link('${temp.path}/budget-owl-export').createSync(outside.path);
      Directory('${temp.path}/share_plus').createSync();
      Link('${temp.path}/share_plus/$export').createSync(preciousExport.path);
      // Two files outside: a UUID link would need exactly one, so give it a folder that has one.
      final lone = Directory('${outside.path}/lone')..createSync();
      write(lone, 'only.txt');
      Link('${temp.path}/9e2c9a1e-7b4d-4e8a-9c0f-1d2e3f4a5b6c').createSync(lone.path);

      await sweepTemporaryFiles(temp, platform: TargetPlatform.android);

      expect(precious.existsSync(), isTrue);
      expect(preciousExport.existsSync(), isTrue);
      expect(File('${lone.path}/only.txt').existsSync(), isTrue);
    });

    test("on iOS the picker's folders are left alone; exports go everywhere", () async {
      final temp = scratch('budget-owl-temp');
      write(temp, '$uuid/statement.ofx');
      write(temp, 'budget-owl-export/$export');

      await sweepTemporaryFiles(temp, platform: TargetPlatform.iOS);

      expect(there(temp, '$uuid/statement.ofx'), isTrue);
      expect(there(temp, 'budget-owl-export'), isFalse);
    });

    test('a temporary directory that does not exist is nothing to sweep', () async {
      final temp = scratch('budget-owl-temp')..deleteSync();
      await sweepTemporaryFiles(temp, platform: TargetPlatform.android);
    });
  });

  group('when the sweep runs', () {
    ProviderContainer containerFor(TestPhone phone) {
      final container = ProviderContainer(overrides: phone.overrides, retry: noRetry);
      addTearDown(container.dispose);
      return container;
    }

    Future<void> until<T extends SessionState>(ProviderContainer c) async {
      for (var i = 0; i < 400 && c.read(sessionProvider) is! T; i++) {
        await Future<void>.delayed(const Duration(milliseconds: 5));
      }
      expect(c.read(sessionProvider), isA<T>());
    }

    test('M1, L1: a start after the app was killed mid-share sweeps, with no wipe', () async {
      final phone = TestPhone(server: FixtureServer.load('demo', state: 'demo'))..signedIn('owner');
      final temp = phone.temporaryDirectory;
      write(temp, 'budget-owl-export/$export');
      write(temp, 'share_plus/$export');
      write(temp, 'share_plus/holiday.jpg');
      write(temp, '$uuid/statement.ofx');

      final c = containerFor(phone);
      await until<SessionSignedIn>(c);

      expect(c.read(localStateProvider).wipes, 0, reason: 'a signed-in start: no wipe ran');
      expect(there(temp, 'budget-owl-export'), isFalse);
      expect(there(temp, 'share_plus/$export'), isFalse);
      expect(there(temp, uuid), isFalse);
      expect(there(temp, 'share_plus/holiday.jpg'), isTrue);
    });

    test(
      'L1: a pick that failed after the plugin wrote its copy: the next wipe removes it',
      () async {
        final phone = TestPhone(server: FixtureServer.load('demo', state: 'demo'))
          ..signedIn('owner');
        final c = containerFor(phone);
        await until<SessionSignedIn>(c);
        final picker = SystemStatementPicker(
          open: () async {
            // file_selector_android writes its copy, then the pick fails before returning its path.
            write(phone.temporaryDirectory, '$uuid/Statement 2026-07.ofx');
            throw PlatformException(code: 'read_failed');
          },
          temporary: () async => phone.temporaryDirectory,
        );
        await expectLater(picker.pick(), throwsA(isA<PlatformException>()));
        expect(there(phone.temporaryDirectory, uuid), isTrue, reason: 'its path never came back');

        await c.read(sessionProvider.notifier).signOut();

        expect(there(phone.temporaryDirectory, uuid), isFalse);
      },
    );

    test(
      'U1: a sweep that fails is logged, not a failed wipe; every other step still runs',
      () async {
        var swept = 0;
        final phone = TestPhone(server: FixtureServer.load('demo', state: 'demo'))
          ..signedIn('owner');
        final c = containerFor(phone);
        await until<SessionSignedIn>(c);
        final failing = LocalState(
          c.read(tokenStoreProvider),
          c.read(cacheHolderProvider),
          c.read(cacheKeyStoreProvider),
          c.read(serverStoreProvider),
          sweepTemporary: () async {
            swept++;
            throw const FileSystemException('no temporary directory');
          },
        );

        await failing.wipe();
        expect(swept, 1);
        expect(phone.store.values.keys, ['budget_owl.server'], reason: 'the token and key went');
      },
    );
  });

  test('a picked file that is not in the temporary directory is never deleted', () async {
    final temp = scratch('budget-owl-temp');
    final elsewhere = scratch('budget-owl-documents');
    final document = write(elsewhere, 'statement.ofx');

    await forgetPlatformCopy(document.path, temp);
    final name = elsewhere.uri.pathSegments.where((s) => s.isNotEmpty).last;
    await forgetPlatformCopy('${temp.path}/../$name/statement.ofx', temp);

    expect(document.existsSync(), isTrue);
  });
}
