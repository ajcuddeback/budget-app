import 'dart:async';
import 'dart:io';

import 'package:budget_owl/app.dart';
import 'package:budget_owl/core/auth/token_store.dart';
import 'package:budget_owl/core/cache/cache_holder.dart';
import 'package:budget_owl/core/session/local_state.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/l10n/gen/app_localizations_en.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/adapters.dart';
import '../support/fixture_server.dart';
import '../support/harness.dart';

/// Start-up's own security fixes (slice 15, phase 2 core):
///
/// - **R1**: a phone with no token keeps no cache. A sign-out killed after it took the token
///   (audit N1) but before its wipe left the cache file and its key; the next sign-in reopened them.
/// - **R3**: a malformed stored origin is another origin's token (a full wipe), and **any** failure
///   while resuming wipes and shows the signed-out state — never a spinner that turns forever.
/// - **P3**: that wipe first ends a loaded token on the server too, and a cache that cannot be
///   written after the member is shown never reaches it.
void main() {
  const tokenKey = 'budget_owl.bearer_token';
  const originKey = 'budget_owl.token_origin';
  const cacheKey = 'budget_owl.cache_key';

  ProviderContainer containerFor(TestPhone phone) {
    final container = ProviderContainer(overrides: phone.overrides, retry: noRetry);
    addTearDown(container.dispose);
    addTearDown(() {
      if (phone.cacheDirectory.existsSync()) phone.cacheDirectory.deleteSync(recursive: true);
    });
    return container;
  }

  Future<T> until<T extends SessionState>(ProviderContainer c) async {
    for (var i = 0; i < 400; i++) {
      final state = c.read(sessionProvider);
      if (state is T) return state;
      await Future<void>.delayed(const Duration(milliseconds: 5));
    }
    fail('the session never became $T; it is ${c.read(sessionProvider)}');
  }

  /// A phone that signed in once, so its cache file and cache key exist on disk.
  Future<(FixtureServer, TestPhone)> phoneWithACache() async {
    final server = FixtureServer.load('demo', state: 'demo');
    final phone = TestPhone(server: server)..signedIn('owner');
    final first = ProviderContainer(overrides: phone.overrides, retry: noRetry);
    while (first.read(sessionProvider) is! SessionSignedIn) {
      await Future<void>.delayed(const Duration(milliseconds: 5));
    }
    first.dispose();
    expect(phone.cacheFile.existsSync(), isTrue);
    expect(phone.store.values.containsKey(cacheKey), isTrue);
    return (server, phone);
  }

  group('R1: no token, no cache', () {
    test(
      'a sign-out killed between taking the token and its wipe leaves nothing to reopen',
      () async {
        final (server, phone) = await phoneWithACache();
        final oldKey = phone.store.values[cacheKey];
        // What TokenStore.take() leaves when the app dies before LocalState.wipe() runs.
        phone.store.values
          ..remove(tokenKey)
          ..remove(originKey);

        final again = containerFor(phone);
        await until<SessionSignedOut>(again);

        expect(phone.cacheFile.existsSync(), isFalse, reason: 'the cache file went at start-up');
        expect(phone.store.values.keys, ['budget_owl.server'], reason: 'and its key with it');

        // The next sign-in starts a new database under a new key, never the old one.
        server.state = 'connect';
        final body = fixtureRequest(
          'demo',
          state: 'connect',
          path: '/api/auth/token',
          then: 'demo',
        );
        await again
            .read(sessionProvider.notifier)
            .signIn(
              email: body['email']! as String,
              password: body['password']! as String,
              deviceLabel: body['deviceLabel']! as String,
            );
        expect(phone.store.values[cacheKey], isNot(oldKey));
      },
    );

    test('is idempotent and costs nothing on a fresh install: one wipe, no request', () async {
      final server = FixtureServer.load('demo', state: 'connect');
      final phone = TestPhone(server: server, freshInstall: true);
      final c = containerFor(phone);

      await until<SessionNoServer>(c);

      expect(c.read(localStateProvider).wipes, 1, reason: 'the fresh-install wipe is the only one');
      expect(server.served, isEmpty);
      expect(phone.store.values, isEmpty);
    });

    test(
      'a signed-out phone relaunched again and again stays signed out, with nothing left',
      () async {
        final server = FixtureServer.load('demo', state: 'connect');
        final phone = TestPhone(server: server)..connected();

        for (var launch = 0; launch < 3; launch++) {
          final c = ProviderContainer(overrides: phone.overrides, retry: noRetry);
          await until<SessionSignedOut>(c);
          expect(c.read(localStateProvider).wipes, 1);
          c.dispose();
        }

        expect(phone.store.values, {'budget_owl.server': testServerOrigin});
        expect(phone.cacheFile.existsSync(), isFalse);
        expect(
          server.served,
          everyElement('GET /api/meta'),
          reason: 'nothing but the check is sent',
        );
        expect(server.authorizations, everyElement(isNull));
      },
    );

    test('an unreadable server address takes everything else with it', () async {
      final (_, phone) = await phoneWithACache();
      phone.store.values['budget_owl.server'] = 'not an address';

      final again = containerFor(phone);
      await until<SessionNoServer>(again);

      expect(phone.store.values, isEmpty);
      expect(phone.cacheFile.existsSync(), isFalse);
    });
  });

  group('R3: a malformed stored origin', () {
    test('is not an origin, and never throws', () async {
      for (final bad in [null, '', 'owl.lan', 'ftp://owl.lan', 'https://', '::']) {
        expect(TokenStore.originOf(bad), isNull, reason: '$bad');
      }
      expect(TokenStore.originOf('https://owl.lan:8443/x'), 'https://owl.lan:8443');

      for (final bad in ['', 'owl.lan', 'https://']) {
        final store = MemorySecureStore();
        store.values
          ..[tokenKey] = 'fixture-token-owner'
          ..[originKey] = bad;
        final tokens = TokenStore(store);

        expect(await tokens.load(origin: testServerOrigin), TokenLoad.foreign, reason: bad);
        expect(tokens.hasToken, isFalse);
        expect(store.values, isEmpty, reason: 'the token is deleted, not loaded');
      }
    });

    for (final bad in ['', 'owl.lan']) {
      test('"$bad" is a token for another origin: a full wipe, and signed out', () async {
        final (server, phone) = await phoneWithACache();
        phone.store.values[originKey] = bad;
        server.served.clear();
        server.authorizations.clear();

        final again = containerFor(phone);
        final state = await until<SessionSignedOut>(again);

        expect(state.notice, isNull, reason: 'a foreign token, not a failed start-up');
        expect(phone.cacheFile.existsSync(), isFalse);
        expect(phone.store.values.keys, ['budget_owl.server']);
        expect(server.authorizations, everyElement(isNull), reason: 'the token was never sent');
      });
    }
  });

  group('R3: any failure while resuming', () {
    test('wipes and shows sign-in with a message, never a spinner', () async {
      final (_, phone) = await phoneWithACache();
      phone.store.unreadable.add(tokenKey);

      final again = containerFor(phone);
      final state = await until<SessionSignedOut>(again);

      expect(state.notice, SignInNotice.expired);
      expect(phone.cacheFile.existsSync(), isFalse);
      expect(phone.store.values.keys, ['budget_owl.server']);
      expect(again.read(tokenStoreProvider).hasToken, isFalse);
    });

    test('storage that cannot delete the token still loses the cache file and its key', () async {
      final (_, phone) = await phoneWithACache();
      phone.store.unreadable.add(tokenKey);
      phone.store.undeletable.add(tokenKey);

      final again = containerFor(phone);
      // The token is still stored, so this is not "your session ended" (round 7, W1): the phone
      // says it could not finish, and remembers to try again at the next launch (W2).
      await until<SessionSignOutIncomplete>(again);

      expect(phone.cacheFile.existsSync(), isFalse, reason: 'each step of the wipe is attempted');
      expect(phone.store.values.containsKey(cacheKey), isFalse);
      expect(again.read(tokenStoreProvider).hasToken, isFalse, reason: 'and none in memory');
    });

    test('with not even the server readable, wipes everything and asks for one', () async {
      final (_, phone) = await phoneWithACache();
      phone.store.unreadable.add('budget_owl.server');

      final again = containerFor(phone);
      await until<SessionNoServer>(again);

      expect(phone.store.values, isEmpty);
      expect(phone.cacheFile.existsSync(), isFalse);
    });

    testWidgets('the person sees the sign-in screen and why, not the starting spinner', (
      tester,
    ) async {
      final phone = TestPhone(server: FixtureServer.load('demo', state: 'connect'))
        ..signedIn('owner');
      phone.store.unreadable.add(originKey);
      addTearDown(() {
        if (phone.cacheDirectory.existsSync()) phone.cacheDirectory.deleteSync(recursive: true);
      });

      await pumpApp(tester, phone);

      expect(find.text(AppLocalizationsEn().login_notice_expired), findsOneWidget);
      expect(find.byType(CircularProgressIndicator), findsNothing);
    });
  });

  group('P3: a failed start-up ends its token; a failed cache write ends nothing', () {
    test('a token that was loaded is revoked on the server before the wipe', () async {
      final server = FixtureServer.load('demo', state: 'demo');
      // The cache directory is a file: nothing can be opened or written under it.
      final notADirectory = File('${Directory.systemTemp.createTempSync('budget-owl-p3').path}/x')
        ..writeAsStringSync('');
      addTearDown(() => notADirectory.parent.deleteSync(recursive: true));
      final phone = TestPhone(server: server, cacheDirectory: Directory(notADirectory.path))
        ..signedIn('owner');

      final c = containerFor(phone);
      final state = await until<SessionSignedOut>(c);

      expect(state.notice, SignInNotice.expired);
      expect(server.served, contains('POST /api/auth/logout'));
      final logout = server.served.indexOf('POST /api/auth/logout');
      expect(server.authorizations[logout], 'Bearer fixture-token-owner');
      expect(phone.store.values.keys, ['budget_owl.server']);
    });

    test('a cache write that fails after the member is shown keeps the session', () async {
      final (fixture, first) = await phoneWithACache();
      fixture.served.clear();
      final answer = Completer<ResponseBody>();
      final gate = MetaGate(fixture, (_) => answer.future);
      final phone = TestPhone(
        server: gate,
        store: first.store,
        cacheDirectory: first.cacheDirectory,
        supportDirectory: first.supportDirectory,
      );
      final c = containerFor(phone);
      await until<SessionSignedIn>(c);

      // Anything that makes the write fail: here the cache is sealed under the refresh.
      await c.read(cacheHolderProvider).destroy();
      answer.complete(
        json(200, {'app': 'budget-owl', 'apiVersion': 1, 'minimumClientApiVersion': 1}),
      );
      for (var i = 0; i < 400 && !fixture.served.contains('GET /api/auth/me'); i++) {
        await Future<void>.delayed(const Duration(milliseconds: 5));
      }
      await Future<void>.delayed(const Duration(milliseconds: 100));

      expect(c.read(sessionProvider), isA<SessionSignedIn>(), reason: 'the fetched member shows');
      expect(c.read(localStateProvider).wipes, 0);
      expect(fixture.served, isNot(contains('POST /api/auth/logout')));
      expect(phone.store.values[tokenKey], 'fixture-token-owner');
    });
  });
}
