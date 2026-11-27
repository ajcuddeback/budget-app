import 'dart:async';

import 'package:budget_owl/app.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/server/server_check.dart';
import 'package:budget_owl/core/session/local_state.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/storage/temp_files.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

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
