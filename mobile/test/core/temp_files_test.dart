import 'dart:io';

import 'package:budget_owl/app.dart';
import 'package:budget_owl/core/auth/token_store.dart';
import 'package:budget_owl/core/cache/cache_holder.dart';
import 'package:budget_owl/core/cache/cache_key_store.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/session/local_state.dart';
import 'package:budget_owl/core/session/server_store.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/storage/temp_files.dart';
import 'package:budget_owl/features/import/data/statement_file.dart';
import 'package:file_selector/file_selector.dart';
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

  group("U2: iOS's picker Inbox", () {
    const bundleInbox = 'app.budgetowl.budgetOwl-Inbox';

    PickerInbox inboxIn(Directory tmp, [String name = bundleInbox]) =>
        PickerInbox(temporary: tmp, inbox: Directory('${tmp.path}/$name'));

    test('everything in tmp/<bundle id>-Inbox/ goes, and nothing beside it', () async {
      final tmp = scratch('budget-owl-ios-tmp');
      write(tmp, '$bundleInbox/Statement 2026-07.ofx');
      write(tmp, '$bundleInbox/statement-2.csv');
      write(tmp, '$bundleInbox/nested/statement.qif');
      write(tmp, 'WebKit/cache.db');
      write(tmp, 'beside.txt');

      await sweepPickerInbox(inboxIn(tmp));

      expect(Directory('${tmp.path}/$bundleInbox').listSync(), isEmpty);
      expect(there(tmp, 'WebKit/cache.db'), isTrue);
      expect(there(tmp, 'beside.txt'), isTrue);
    });

    test('never outside tmp: links in it go as links, an Inbox that is a link is left', () async {
      final tmp = scratch('budget-owl-ios-tmp');
      final outside = scratch('budget-owl-outside');
      final precious = write(outside, 'kept.ofx');
      Directory('${tmp.path}/$bundleInbox').createSync();
      Link('${tmp.path}/$bundleInbox/to-file').createSync(precious.path);
      Link('${tmp.path}/$bundleInbox/to-folder').createSync(outside.path);

      await sweepPickerInbox(inboxIn(tmp));

      expect(precious.existsSync(), isTrue);
      expect(there(tmp, '$bundleInbox/to-file'), isFalse, reason: 'the link itself went');

      final linked = scratch('budget-owl-ios-tmp');
      Link('${linked.path}/$bundleInbox').createSync(outside.path);
      await sweepPickerInbox(inboxIn(linked));
      expect(precious.existsSync(), isTrue);
    });

    test('only a folder named *-Inbox directly in tmp', () async {
      final tmp = scratch('budget-owl-ios-tmp');
      write(tmp, 'Documents/statement.ofx');
      write(tmp, 'deeper/$bundleInbox/statement.ofx');
      // A real Inbox too: a request naming any other place sweeps nothing, not this one either.
      write(tmp, '$bundleInbox/statement.ofx');

      await sweepPickerInbox(inboxIn(tmp, 'Documents'));
      await sweepPickerInbox(
        PickerInbox(temporary: tmp, inbox: Directory('${tmp.path}/deeper/$bundleInbox')),
      );

      expect(there(tmp, 'Documents/statement.ofx'), isTrue);
      expect(there(tmp, 'deeper/$bundleInbox/statement.ofx'), isTrue);
      expect(there(tmp, '$bundleInbox/statement.ofx'), isTrue);
    });

    test('a tmp/ reached through a link, as /var is on iOS, is still swept', () async {
      final real = scratch('budget-owl-private-var');
      write(real, '$bundleInbox/statement.ofx');
      final holder = scratch('budget-owl-var');
      final viaLink = Link('${holder.path}/tmp')..createSync(real.path);

      await sweepPickerInbox(inboxIn(Directory(viaLink.path)));

      expect(there(real, '$bundleInbox/statement.ofx'), isFalse);
    });

    test('the start-up and wipe sweep: the Inbox on iOS only', () async {
      final caches = scratch('budget-owl-caches');
      final tmp = scratch('budget-owl-ios-tmp');
      write(tmp, '$bundleInbox/statement.ofx');

      await sweepTemporaryFiles(caches, platform: TargetPlatform.android, inbox: inboxIn(tmp));
      expect(there(tmp, '$bundleInbox/statement.ofx'), isTrue);

      await sweepTemporaryFiles(caches, platform: TargetPlatform.iOS, inbox: inboxIn(tmp));
      expect(there(tmp, '$bundleInbox/statement.ofx'), isFalse);
    });

    test('AppDelegate says where it is; on Android nothing is asked', () async {
      final calls = <String>[];
      final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        ..setMockMethodCallHandler(filesChannel, (call) async {
          calls.add(call.method);
          return {'temporary': '/private/var/tmp', 'inbox': '/private/var/tmp/$bundleInbox'};
        });
      addTearDown(() => messenger.setMockMethodCallHandler(filesChannel, null));

      ProviderContainer on(TargetPlatform platform) {
        final c = ProviderContainer(
          overrides: [targetPlatformProvider.overrideWithValue(platform)],
          retry: noRetry,
        );
        addTearDown(c.dispose);
        return c;
      }

      final ios = await on(TargetPlatform.iOS).read(pickerInboxProvider.future);
      expect(ios?.inbox.path, '/private/var/tmp/$bundleInbox');
      expect(ios?.temporary.path, '/private/var/tmp');
      expect(await on(TargetPlatform.android).read(pickerInboxProvider.future), isNull);
      expect(calls, ['pickerInbox']);

      messenger.setMockMethodCallHandler(
        filesChannel,
        (call) async => throw PlatformException(code: 'unavailable'),
      );
      expect(await on(TargetPlatform.iOS).read(pickerInboxProvider.future), isNull);
    });

    test("an iOS pick deletes its own copy at once, in tmp/'s Inbox, not Caches", () async {
      final caches = scratch('budget-owl-caches');
      final tmp = scratch('budget-owl-ios-tmp');
      ProviderContainer on(TargetPlatform platform) {
        final c = ProviderContainer(
          overrides: [
            targetPlatformProvider.overrideWithValue(platform),
            temporaryDirectoryProvider.overrideWith((ref) async => caches),
            pickerInboxProvider.overrideWith(
              (ref) async => platform == TargetPlatform.iOS ? inboxIn(tmp) : null,
            ),
          ],
          retry: noRetry,
        );
        addTearDown(c.dispose);
        return c;
      }

      final ios = on(TargetPlatform.iOS);
      expect((await ios.read(pickerCopyRootProvider.future)).path, tmp.path);
      expect(
        (await on(TargetPlatform.android).read(pickerCopyRootProvider.future)).path,
        caches.path,
      );

      final copy = write(tmp, '$bundleInbox/Statement 2026-07.ofx');
      final picked = await SystemStatementPicker(
        open: () async => XFile(copy.path),
        temporary: () => ios.read(pickerCopyRootProvider.future),
      ).pick();

      expect(picked?.name, 'Statement 2026-07.ofx');
      expect(copy.existsSync(), isFalse);
    });

    test("an iOS phone's wipe sweeps the Inbox", () async {
      final tmp = scratch('budget-owl-ios-tmp');
      write(tmp, '$bundleInbox/statement.ofx');
      final phone = TestPhone(server: FixtureServer.load('demo', state: 'demo'))..signedIn('owner');
      final c = ProviderContainer(
        overrides: [
          for (final o in phone.overrides)
            if (!identical(o.origin, targetPlatformProvider) &&
                !identical(o.origin, pickerInboxProvider))
              o,
          targetPlatformProvider.overrideWithValue(TargetPlatform.iOS),
          pickerInboxProvider.overrideWith((ref) async => inboxIn(tmp)),
        ],
        retry: noRetry,
      );
      addTearDown(c.dispose);

      await c.read(localStateProvider).wipe();

      expect(there(tmp, '$bundleInbox/statement.ofx'), isFalse);
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
