import 'dart:async';
import 'dart:io';

import 'package:budget_owl/app.dart';
import 'package:budget_owl/core/auth/token_store.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/server/server_check.dart';
import 'package:budget_owl/core/session/local_state.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/storage/sign_out_marker.dart';
import 'package:budget_owl/core/storage/temp_files.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/adapters.dart';
import '../support/fixture_server.dart';
import '../support/harness.dart';

/// U1: a wipe that reports a failure still ends the session. The token, cache and key go with
/// every step attempted (LocalState.wipe), and whatever it rethrows, a sign-out, a `401` or a
/// change of server always reaches sign-in or connect — never a signed-in screen over data whose
/// storage is gone. connect() turns a failed wipe into its own failure, connected to nothing.
void main() {
  const cacheKey = 'budget_owl.cache_key';

  ServerAddress testServer() => (ServerAddress.parse(
    testServerOrigin,
    allowLocalCleartext: false,
  ) as AddressAccepted).address;

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

  Future<(TestPhone, ProviderContainer)> signedIn() async {
    final phone = TestPhone(server: FixtureServer.load('demo', state: 'demo'))..signedIn('owner');
    final c = containerFor(phone);
    await until<SessionSignedIn>(c);
    return (phone, c);
  }

  /// Nothing of the session is left in memory: no member, no signed-in state.
  void nothingInMemory(ProviderContainer c) {
    expect(c.read(signedInProvider), isNull);
    expect(c.read(sessionProvider), isNot(isA<SessionSignedIn>()));
  }

  test('a sign-out whose wipe fails still reaches sign-in', () async {
    final (phone, c) = await signedIn();
    phone.store.undeletable.add(cacheKey);

    await c.read(sessionProvider.notifier).signOut();

    expect(c.read(sessionProvider), isA<SessionSignedOut>());
    nothingInMemory(c);
    expect(phone.store.values.containsKey('budget_owl.bearer_token'), isFalse);
    expect(phone.cacheFile.existsSync(), isFalse, reason: 'the steps after the failure ran');
  });

  test('a 401 whose wipe fails still reaches sign-in, with "your session ended"', () async {
    final (phone, c) = await signedIn();
    phone.store.undeletable.add(cacheKey);

    await c.read(sessionProvider.notifier).sessionEnded();

    final state = c.read(sessionProvider);
    expect(state, isA<SessionSignedOut>());
    expect((state as SessionSignedOut).notice, SignInNotice.expired);
    nothingInMemory(c);
  });

  test('a change of server whose wipe fails still reaches connect, with no server', () async {
    final (phone, c) = await signedIn();
    phone.store.undeletable.add(cacheKey);

    await c.read(sessionProvider.notifier).signOut(forgetServer: true);

    expect(c.read(sessionProvider), isA<SessionNoServer>());
    expect(c.read(currentServerProvider), isNull, reason: 'the old server is not shown');
    nothingInMemory(c);
  });

  test('connect: a failed wipe is its own failure, and nothing is connected', () async {
    final phone = TestPhone(server: FixtureServer.load('demo', state: 'connect'));
    final c = containerFor(phone);
    await until<SessionNoServer>(c);
    phone.store.undeletable.add(cacheKey);

    await expectLater(
      c.read(sessionProvider.notifier).connect(testServer()),
      throwsA(
        isA<ConnectFailure>().having((f) => f.problem, 'problem', ConnectProblem.phoneNotCleared),
      ),
    );

    expect(c.read(sessionProvider), isA<SessionNoServer>());
    expect(c.read(currentServerProvider), isNull);
    expect(phone.store.values.containsKey('budget_owl.server'), isFalse, reason: 'not saved');
  });

  test('a temporary directory that cannot be had fails no wipe, and the session ends', () async {
    final (phone, c) = await signedIn();
    phone.temporaryDirectoryFails = true;
    // Asked again: the start-up sweep had already resolved it.
    c.invalidate(temporaryDirectoryProvider);

    await c.read(localStateProvider).wipe();
    await c.read(sessionProvider.notifier).signOut();

    expect(c.read(sessionProvider), isA<SessionSignedOut>());
    expect(phone.store.values.keys, ['budget_owl.server']);
  });

  group('V1: a token that cannot be deleted', () {
    const tokenKey = 'budget_owl.bearer_token';
    const incomplete =
        "This phone couldn't finish signing out. Try again, or remove Budget Owl from the phone.";

    test('sign-out still revokes it and wipes the rest, and says it could not finish', () async {
      final (phone, c) = await signedIn();
      final server = phone.server as FixtureServer;
      phone.store.undeletable.add(tokenKey);

      await c.read(sessionProvider.notifier).signOut();

      expect(c.read(sessionProvider), isA<SessionSignOutIncomplete>(), reason: 'not "signed out"');
      nothingInMemory(c);
      expect(server.served, contains(startsWith('POST /api/auth/logout')), reason: 'revoked');
      expect(phone.store.values.containsKey(cacheKey), isFalse, reason: 'the key went');
      expect(phone.cacheFile.existsSync(), isFalse, reason: 'the cache went');
      expect(phone.store.values.containsKey(tokenKey), isTrue);
    });

    test('a delete that fails once is retried by the wipe, and the sign-out finishes', () async {
      final (phone, c) = await signedIn();
      phone.store.failingDeletes[tokenKey] = 1;

      await c.read(sessionProvider.notifier).signOut();

      expect(c.read(sessionProvider), isA<SessionSignedOut>());
      expect(phone.store.values.keys, ['budget_owl.server']);
    });

    test('a change of server that could not finish keeps that, to try again', () async {
      final (phone, c) = await signedIn();
      phone.store.undeletable.add(tokenKey);

      await c.read(sessionProvider.notifier).signOut(forgetServer: true);
      final state = c.read(sessionProvider);
      expect(state, isA<SessionSignOutIncomplete>());
      expect((state as SessionSignOutIncomplete).forgetServer, isTrue);

      phone.store.undeletable.remove(tokenKey);
      await c.read(sessionProvider.notifier).signOut(forgetServer: state.forgetServer);
      expect(c.read(sessionProvider), isA<SessionNoServer>());
      expect(phone.store.values, isEmpty);
    });

    test('a store that cannot be read cannot confirm it gone', () async {
      final store = MemorySecureStore()
        ..values[tokenKey] = 'fixture-token-owner'
        ..unreadable.add(tokenKey);
      expect(await TokenStore(store).isStored(), isTrue);
      expect(await TokenStore(MemorySecureStore()).isStored(), isFalse);
    });

    test('a 401 that cannot delete it says the same', () async {
      final (phone, c) = await signedIn();
      phone.store.undeletable.add(tokenKey);

      await c.read(sessionProvider.notifier).sessionEnded();

      expect(c.read(sessionProvider), isA<SessionSignOutIncomplete>());
      nothingInMemory(c);
    });

    test("a failed sign-in's clean-up that cannot delete it says the same", () async {
      final adapter = ScriptedAdapter((o) async {
        if (o.path.endsWith('/api/meta')) {
          return json(200, {'app': 'budget-owl', 'apiVersion': 1, 'minimumClientApiVersion': 1});
        }
        if (o.path.endsWith('/api/auth/token')) return json(201, {'token': 'fixture-token-new'});
        if (o.path.endsWith('/api/auth/logout')) return json(204, null);
        return json(500, {'code': 'internal-error'});
      });
      final phone = TestPhone(server: adapter)..connected();
      final c = containerFor(phone);
      await until<SessionSignedOut>(c);
      phone.store.undeletable.add(tokenKey);

      await expectLater(
        c
            .read(sessionProvider.notifier)
            .signIn(email: 'ada@example.com', password: 'not-the-password', deviceLabel: 'Phone'),
        throwsA(anything),
      );

      expect(c.read(sessionProvider), isA<SessionSignOutIncomplete>());
      expect(adapter.requests.where((o) => o.path.endsWith('/api/auth/logout')), hasLength(1));
    });

    testWidgets('on screen: the could-not-finish state, and Try again finishing it', (
      tester,
    ) async {
      final phone = TestPhone(server: FixtureServer.load('demo', state: 'demo'))..signedIn('owner');
      await pumpApp(tester, phone);
      phone.store.undeletable.add(tokenKey);
      final container = ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));

      unawaited(container.read(sessionProvider.notifier).signOut());
      await settleUntil(
        tester,
        () => container.read(sessionProvider) is SessionSignOutIncomplete,
        what: 'the sign-out could not finish',
      );

      expect(find.text(incomplete), findsOneWidget);
      expect(find.text('Transactions'), findsNothing);

      phone.store.undeletable.remove(tokenKey);
      await tapVisible(tester, find.text('Try again'));
      await untilSignedOut(tester);
      expect(find.text(incomplete), findsNothing);
    });
  });

  group('W1, W2: an unfinished sign-out survives a relaunch', () {
    const tokenKey = 'budget_owl.bearer_token';

    File markerOf(TestPhone phone) =>
        File('${phone.supportDirectory.path}/${SignOutMarker.fileName}');

    /// A launch of the app on [phone]: a new container, as a cold start is.
    Future<ProviderContainer> launch(TestPhone phone) async {
      final c = ProviderContainer(overrides: phone.overrides, retry: noRetry);
      for (var i = 0; i < 400 && c.read(sessionProvider) is SessionStarting; i++) {
        await Future<void>.delayed(const Duration(milliseconds: 5));
      }
      return c;
    }

    int reads(FixtureServer server) =>
        server.served.where((s) => s.startsWith('GET /api/auth/me')).length;

    test('W1: a failed start whose token cannot be deleted says so, not "session ended"', () async {
      final phone = TestPhone(server: FixtureServer.load('demo', state: 'demo'))..signedIn('owner');
      // Start-up fails reading the pin; the recovery then cannot delete the token.
      phone.store
        ..values['budget_owl.server_ca_pin'] = 'c1' * 32
        ..unreadable.add('budget_owl.server_ca_pin');
      phone.store.undeletable.add(tokenKey);

      final c = await launch(phone);
      addTearDown(c.dispose);

      expect(c.read(sessionProvider), isA<SessionSignOutIncomplete>());
      expect(markerOf(phone).existsSync(), isTrue);
    });

    test('W1: a failed start whose token goes ends finished, the marker cleared', () async {
      final phone = TestPhone(server: FixtureServer.load('demo', state: 'demo'))..signedIn('owner');
      phone.store
        ..values['budget_owl.server_ca_pin'] = 'c1' * 32
        ..unreadable.add('budget_owl.server_ca_pin');

      final c = await launch(phone);
      addTearDown(c.dispose);

      // Start-up failed before the server was current, so the recovery forgets it too.
      expect(c.read(sessionProvider), isA<SessionNoServer>());
      expect(phone.store.values.containsKey(tokenKey), isFalse);
      expect(markerOf(phone).existsSync(), isFalse);
    });

    test('closed on the incomplete screen: the next launch loads nothing and says it again; '
        'a later one with working storage finishes it', () async {
      final server = FixtureServer.load('demo', state: 'demo');
      final phone = TestPhone(server: server)..signedIn('owner');
      final first = await launch(phone);
      expect(first.read(sessionProvider), isA<SessionSignedIn>());
      phone.store.undeletable.add(tokenKey);
      await first.read(sessionProvider.notifier).signOut();
      expect(first.read(sessionProvider), isA<SessionSignOutIncomplete>());
      expect(markerOf(phone).existsSync(), isTrue);
      first.dispose();

      final readsBefore = reads(server);
      final second = await launch(phone);
      expect(second.read(sessionProvider), isA<SessionSignOutIncomplete>());
      expect(second.read(signedInProvider), isNull);
      expect(reads(server), readsBefore, reason: 'nothing loaded: the member was not asked for');
      expect(phone.cacheFile.existsSync(), isFalse, reason: 'no cache opened');
      expect(markerOf(phone).existsSync(), isTrue);
      second.dispose();

      phone.store.undeletable.remove(tokenKey);
      final third = await launch(phone);
      addTearDown(third.dispose);
      expect(third.read(sessionProvider), isA<SessionSignedOut>());
      expect(phone.store.values.keys, ['budget_owl.server']);
      expect(markerOf(phone).existsSync(), isFalse);
    });

    test('a change of server stays one across the relaunch', () async {
      final phone = TestPhone(server: FixtureServer.load('demo', state: 'demo'))..signedIn('owner');
      final first = await launch(phone);
      phone.store.undeletable.add(tokenKey);
      await first.read(sessionProvider.notifier).signOut(forgetServer: true);
      first.dispose();

      final second = await launch(phone);
      final state = second.read(sessionProvider);
      expect(state, isA<SessionSignOutIncomplete>());
      expect((state as SessionSignOutIncomplete).forgetServer, isTrue);
      second.dispose();

      phone.store.undeletable.remove(tokenKey);
      final third = await launch(phone);
      addTearDown(third.dispose);
      expect(third.read(sessionProvider), isA<SessionNoServer>());
      expect(phone.store.values, isEmpty);
    });

    test('a marker that cannot be written does not stop the sign-out', () async {
      final phone = TestPhone(server: FixtureServer.load('demo', state: 'demo'))..signedIn('owner');
      final c = await launch(phone);
      addTearDown(c.dispose);
      // Application Support becomes a file: nothing can be written in it.
      phone.supportDirectory.deleteSync(recursive: true);
      File(phone.supportDirectory.path).writeAsStringSync('not a directory');

      await c.read(sessionProvider.notifier).signOut();

      expect(c.read(sessionProvider), isA<SessionSignedOut>());
      expect(phone.store.values.keys, ['budget_owl.server']);
    });

    test(
      'X1: Try again after a relaunch finishes, and sign-in works without another launch',
      () async {
        final server = FixtureServer.load('demo', state: 'demo');
        final phone = TestPhone(server: server)..signedIn('owner');
        final first = await launch(phone);
        phone.store.undeletable.add(tokenKey);
        await first.read(sessionProvider.notifier).signOut();
        first.dispose();

        // Relaunched on the unfinished state: no current server, no CA pin applied yet.
        server.state = 'connect';
        final c = await launch(phone);
        addTearDown(c.dispose);
        expect(c.read(sessionProvider), isA<SessionSignOutIncomplete>());
        expect(c.read(currentServerProvider), isNull);

        phone.store.undeletable.remove(tokenKey);
        await c.read(sessionProvider.notifier).signOut();
        for (var i = 0; i < 400 && c.read(sessionProvider) is! SessionSignedOut; i++) {
          await Future<void>.delayed(const Duration(milliseconds: 5));
        }
        expect(c.read(sessionProvider), isA<SessionSignedOut>());
        expect(c.read(currentServerProvider), isNotNull, reason: 'start-up set it again');

        final body = fixtureRequest(
          'demo',
          state: 'connect',
          path: '/api/auth/token',
          then: 'demo',
        );
        await c
            .read(sessionProvider.notifier)
            .signIn(
              email: body['email']! as String,
              password: body['password']! as String,
              deviceLabel: body['deviceLabel']! as String,
            );
        expect(c.read(sessionProvider), isA<SessionSignedIn>());
      },
    );

    test('X2: a sign-in removes a stale marker before it saves the new token', () async {
      final phone = TestPhone(server: FixtureServer.load('demo', state: 'connect'))..connected();
      final c = await launch(phone);
      addTearDown(c.dispose);
      markerOf(phone).writeAsStringSync('keep-server');
      bool? markerWhenTokenSaved;
      phone.store.onWrite = (key) {
        if (key == tokenKey) markerWhenTokenSaved = markerOf(phone).existsSync();
      };

      final body = fixtureRequest('demo', state: 'connect', path: '/api/auth/token', then: 'demo');
      await c
          .read(sessionProvider.notifier)
          .signIn(
            email: body['email']! as String,
            password: body['password']! as String,
            deviceLabel: body['deviceLabel']! as String,
          );

      expect(markerWhenTokenSaved, isFalse, reason: 'a kill after the save must find no marker');
    });

    test(
      'a store that can never read the token ends, deliberately, at "could not finish"',
      () async {
        final phone = TestPhone(server: FixtureServer.load('demo', state: 'demo'))
          ..signedIn('owner');
        final c = await launch(phone);
        addTearDown(c.dispose);
        phone.store.alwaysUnreadable.add(tokenKey);

        await c.read(sessionProvider.notifier).signOut();

        expect(c.read(sessionProvider), isA<SessionSignOutIncomplete>());
        expect(phone.store.values.containsKey(tokenKey), isFalse, reason: 'deleted, unconfirmable');
        expect(markerOf(phone).existsSync(), isTrue);
      },
    );

    test('a new sign-in ends a sign-out still marked pending', () async {
      final phone = TestPhone(server: FixtureServer.load('demo', state: 'connect'))..connected();
      final first = await launch(phone);
      markerOf(phone).writeAsStringSync('keep-server');
      final body = fixtureRequest('demo', state: 'connect', path: '/api/auth/token', then: 'demo');
      await first
          .read(sessionProvider.notifier)
          .signIn(
            email: body['email']! as String,
            password: body['password']! as String,
            deviceLabel: body['deviceLabel']! as String,
          );
      expect(first.read(sessionProvider), isA<SessionSignedIn>());
      first.dispose();

      expect(markerOf(phone).existsSync(), isFalse);
      final second = await launch(phone);
      addTearDown(second.dispose);
      expect(second.read(sessionProvider), isA<SessionSignedIn>(), reason: 'not signed out again');
    });
  });

  testWidgets('on screen: sign-out with a failing wipe shows sign-in, not the old data', (
    tester,
  ) async {
    final phone = TestPhone(server: FixtureServer.load('demo', state: 'demo'))..signedIn('owner');
    await pumpApp(tester, phone);
    phone.store.undeletable.add(cacheKey);
    final container = ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));

    unawaited(container.read(sessionProvider.notifier).signOut());
    await untilSignedOut(tester);

    expect(find.text('Transactions'), findsNothing, reason: 'no tab bar: not signed in');
    expect(container.read(signedInProvider), isNull);
  });

  testWidgets('on screen: a connect whose wipe fails says so', (tester) async {
    final phone = TestPhone(server: FixtureServer.load('demo', state: 'connect'));
    await pumpApp(tester, phone);
    phone.store.undeletable.add(cacheKey);

    await tester.enterText(find.byType(TextField), testServerOrigin);
    await tapVisible(tester, find.text('Connect'));

    expect(
      find.text(
        'This phone could not clear what it held for the previous server, so nothing was '
        'connected. Try again.',
      ),
      findsOneWidget,
    );
  });
}
