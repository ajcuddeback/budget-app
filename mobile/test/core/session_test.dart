import 'package:budget_owl/app.dart';
import 'package:budget_owl/core/cache/cache_holder.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/server/server_check.dart';
import 'package:budget_owl/core/session/local_state.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/adapters.dart';
import '../support/fixture_server.dart';
import '../support/harness.dart';

/// The guess DemoFixtureIT recorded a refusal for.
const String _wrongGuess = 'wrong-guess-1';

/// The session's life with one server: resuming, connecting, signing in, and the two ways it ends
/// — sign-out (revoke, then wipe regardless) and any `401` (wipe once, no retry storm).
void main() {
  ProviderContainer containerFor(TestPhone phone) {
    final container = ProviderContainer(overrides: phone.overrides, retry: noRetry);
    addTearDown(container.dispose);
    addTearDown(() {
      if (phone.cacheDirectory.existsSync()) phone.cacheDirectory.deleteSync(recursive: true);
    });
    return container;
  }

  Future<T> until<T extends SessionState>(ProviderContainer c) async {
    for (var i = 0; i < 200; i++) {
      final state = c.read(sessionProvider);
      if (state is T) return state;
      await Future<void>.delayed(const Duration(milliseconds: 5));
    }
    fail('the session never became $T; it is ${c.read(sessionProvider)}');
  }

  ServerAddress testServer() => (ServerAddress.parse(
    testServerOrigin,
    allowLocalCleartext: false,
  ) as AddressAccepted).address;

  test('a new phone knows no server', () async {
    final c = containerFor(TestPhone(server: FixtureServer.load('demo', state: 'connect')));

    await until<SessionNoServer>(c);
  });

  test('connecting checks /api/meta, then remembers the server and nothing else', () async {
    final server = FixtureServer.load('demo', state: 'connect');
    final phone = TestPhone(server: server);
    final c = containerFor(phone);
    await until<SessionNoServer>(c);

    await c.read(sessionProvider.notifier).connect(testServer());

    expect(server.served, ['GET /api/meta']);
    expect(phone.store.values, {'budget_owl.server': testServerOrigin});
    expect(c.read(sessionProvider), isA<SessionSignedOut>());
  });

  test('a server that is not Budget Owl is not remembered', () async {
    final phone = TestPhone(server: ScriptedAdapter((_) async => text(200, '<html>')));
    final c = containerFor(phone);
    await until<SessionNoServer>(c);

    await expectLater(
      c.read(sessionProvider.notifier).connect(testServer()),
      throwsA(isA<ConnectFailure>()),
    );
    expect(phone.store.values, isEmpty);
  });

  test('signing in keeps the token in secure storage and the member in the cache', () async {
    final server = FixtureServer.load('demo', state: 'connect');
    final phone = TestPhone(server: server)..connected();
    final c = containerFor(phone);
    await until<SessionSignedOut>(c);
    final body = fixtureRequest('demo', state: 'connect', path: '/api/auth/token', then: 'demo');

    await c
        .read(sessionProvider.notifier)
        .signIn(
          email: body['email']! as String,
          password: body['password']! as String,
          deviceLabel: body['deviceLabel']! as String,
        );

    final session = c.read(sessionProvider) as SessionSignedIn;
    expect(session.membership?.householdName, 'Rivera Household');
    expect(phone.store.values['budget_owl.bearer_token'], 'fixture-token-owner');
    expect(phone.cacheFile.existsSync(), isTrue);
  });

  test("a wrong password is the server's sentence, and leaves nothing behind", () async {
    final phone = TestPhone(server: FixtureServer.load('demo', state: 'connect'))..connected();
    final c = containerFor(phone);
    await until<SessionSignedOut>(c);

    try {
      await c
          .read(sessionProvider.notifier)
          .signIn(email: 'ada@example.com', password: _wrongGuess, deviceLabel: 'Phone · Android');
      fail('signed in with a wrong password');
    } on AppError catch (error) {
      expect(error.code, 'authentication-failed');
    }
    expect(phone.store.values.keys, ['budget_owl.server']);
  });

  test('a burst of 401s wipes once and routes to sign-in once — no retry storm', () async {
    final server = FixtureServer.load('demo', state: 'demo');
    final phone = TestPhone(server: server)..signedIn('owner');
    final c = containerFor(phone);
    await until<SessionSignedIn>(c);
    final transitions = <SessionState>[];
    c.listen(sessionProvider, (_, next) => transitions.add(next));
    server.state = 'revoked';
    final api = c.read(apiClientProvider)!;

    await Future.wait([
      for (final path in _queries.keys)
        api.getText(path, query: _queries[path]!).then<Object?>((v) => v, onError: (Object e) => e),
    ]);
    await until<SessionSignedOut>(c);

    expect(c.read(localStateProvider).wipes, 1);
    expect(transitions.whereType<SessionSignedOut>(), hasLength(1));
    expect((c.read(sessionProvider) as SessionSignedOut).notice, SignInNotice.expired);
    expect(server.served.where((s) => s.startsWith('GET /api/auth/me')), hasLength(2));
    expect(phone.store.values.keys, ['budget_owl.server']);
    expect(phone.cacheFile.existsSync(), isFalse);
  });

  test('sign-out revokes the token on the server, then wipes', () async {
    final server = FixtureServer.load('demo', state: 'demo');
    final phone = TestPhone(server: server)..signedIn('owner');
    final c = containerFor(phone);
    await until<SessionSignedIn>(c);

    await c.read(sessionProvider.notifier).signOut();

    expect(server.served, contains('POST /api/auth/logout'));
    expect(phone.store.values.keys, ['budget_owl.server']);
    expect(c.read(cacheHolderProvider).isSealed, isTrue);
    expect(c.read(sessionProvider), isA<SessionSignedOut>());
  });

  test('sign-out wipes even when the server cannot be reached', () async {
    final server = FixtureServer.load('demo', state: 'demo');
    final phone = TestPhone(server: server)..signedIn('owner');
    final c = containerFor(phone);
    await until<SessionSignedIn>(c);
    server.offline = true;

    await c.read(sessionProvider.notifier).signOut();

    expect(phone.store.values.keys, ['budget_owl.server']);
    expect(phone.cacheFile.existsSync(), isFalse);
  });

  test('changing server forgets it too', () async {
    final phone = TestPhone(server: FixtureServer.load('demo', state: 'demo'))..signedIn('owner');
    final c = containerFor(phone);
    await until<SessionSignedIn>(c);

    await c.read(sessionProvider.notifier).signOut(forgetServer: true);

    expect(phone.store.values, isEmpty);
    expect(c.read(sessionProvider), isA<SessionNoServer>());
    expect(c.read(apiClientProvider), isNull);
  });

  test('offline, a returning member opens to the cached copy of who they are', () async {
    final server = FixtureServer.load('demo', state: 'demo');
    final phone = TestPhone(server: server)..signedIn('owner');
    final first = ProviderContainer(overrides: phone.overrides, retry: noRetry);
    while (first.read(sessionProvider) is! SessionSignedIn) {
      await Future<void>.delayed(const Duration(milliseconds: 5));
    }
    first.dispose();

    server.offline = true;
    final again = containerFor(phone);

    final session = await until<SessionSignedIn>(again);
    expect(session.user.email, 'ada@example.com');
  });

  test('offline with nothing cached, it says so rather than pretending', () async {
    final server = FixtureServer.load('demo', state: 'demo')..offline = true;
    final phone = TestPhone(server: server)..signedIn('owner');
    final c = containerFor(phone);

    for (var i = 0; i < 200; i++) {
      final s = c.read(sessionProvider);
      if (s is SessionStarting && s.failure != null) break;
      await Future<void>.delayed(const Duration(milliseconds: 5));
    }

    final state = c.read(sessionProvider) as SessionStarting;
    expect(state.failure?.kind, AppErrorKind.offline);
    expect(phone.store.values['budget_owl.bearer_token'], 'fixture-token-owner');
  });

  test('a server that has moved to another API is reported, not talked to', () async {
    final phone = TestPhone(
      server: ScriptedAdapter(
        (_) async =>
            json(200, {'app': 'budget-owl', 'apiVersion': 2, 'minimumClientApiVersion': 2}),
      ),
    )..connected();
    final c = containerFor(phone);

    final state = await until<SessionUnsupported>(c);

    expect(state.failure.problem, ConnectProblem.versionMismatch);
  });

  test('after setup or joining, sign-in opens with the note and the email', () async {
    final phone = TestPhone(server: FixtureServer.load('demo', state: 'connect'))..connected();
    final c = containerFor(phone);
    await until<SessionSignedOut>(c);

    c.read(sessionProvider.notifier).signedOutWith(SignInNotice.joined, email: 'grace@example.com');

    final state = c.read(sessionProvider) as SessionSignedOut;
    expect(state.notice, SignInNotice.joined);
    expect(state.email, 'grace@example.com');
  });
}

const Map<String, Map<String, String>> _queries = {
  '/api/auth/me': {},
  '/api/transactions': {'page': '0', 'size': '50'},
  '/api/accounts': {'includeArchived': 'true', 'page': '0', 'size': '200'},
};
