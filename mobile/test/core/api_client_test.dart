import 'dart:io';

import 'package:budget_owl/core/auth/token_store.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/api_client.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/adapters.dart';
import '../support/fixture_server.dart';
import '../support/harness.dart';

/// The wire controls that live in [ApiClient]: the bearer is attached by one interceptor and
/// only to the chosen server; any 401 to a request that carried it ends the session; redirects are
/// not followed; nothing but method, redacted path and status is ever logged.
void main() {
  final server = (ServerAddress.parse(
    'owl.example.test',
    allowLocalCleartext: false,
  ) as AddressAccepted).address;

  late TokenStore tokens;
  late int ended;
  late List<Reachability> outcomes;

  setUp(() async {
    tokens = TokenStore(MemorySecureStore());
    await tokens.save('fixture-token-owner', origin: testServerOrigin);
    ended = 0;
    outcomes = [];
  });

  ApiClient client(ScriptedAdapter adapter, {bool versionConfirmed = true}) => ApiClient(
    server: server,
    adapter: adapter,
    tokens: tokens,
    onSessionEnded: () => ended++,
    onOutcome: outcomes.add,
    versionConfirmed: () => versionConfirmed,
    logRequests: false,
  );

  Future<AppError> failure(Future<Object?> call) async {
    try {
      await call;
    } on AppError catch (e) {
      return e;
    }
    fail('expected an AppError');
  }

  test('attaches the bearer token to an authenticated request', () async {
    final adapter = ScriptedAdapter((_) async => json(200, {'ok': true}));

    await client(adapter).getJson('/api/auth/me');

    expect(adapter.requests.single.headers['Authorization'], 'Bearer fixture-token-owner');
  });

  test(
    'refuses, and sends nothing, when the token was issued by another origin (audit H-a)',
    () async {
      // The client is for owl.example.test, so the origin guard lets the request through; the token
      // in the store is another server's. Every request is checked, not only the load.
      await tokens.save('fixture-token-owner', origin: 'https://old.example.test');
      final adapter = ScriptedAdapter((_) async => json(200, {'ok': true}));

      final error = await failure(client(adapter).getJson('/api/auth/me'));

      expect(adapter.requests, isEmpty);
      expect(error.status, isNull);
      expect(ended, 0, reason: 'a refusal on the phone is not the server ending the session');
    },
  );

  test('a revocation carries the token it is given, and its 401 ends nothing (audit N1)', () async {
    final taken = await tokens.take();
    final adapter = ScriptedAdapter((_) async => json(401, {'code': 'unauthenticated'}));

    await failure(client(adapter).revoke(taken!));

    expect(adapter.requests.single.method, 'POST');
    expect(adapter.requests.single.path, '/api/auth/logout');
    expect(adapter.requests.single.headers['Authorization'], 'Bearer fixture-token-owner');
    expect(ended, 0);
  });

  test('a revocation is refused for any origin but the one that issued the token', () async {
    final adapter = ScriptedAdapter((_) async => json(204, null));

    await failure(
      client(adapter).revoke(IssuedToken('fixture-token-owner', 'https://old.example.test')),
    );

    expect(adapter.requests, isEmpty);
  });

  test('sends no bearer on a public request', () async {
    final adapter = ScriptedAdapter((_) async => json(200, {'ok': true}));

    await client(adapter).getText('/api/setup/status', public: true);

    expect(adapter.requests.single.headers.containsKey('Authorization'), isFalse);
  });

  test('sends no bearer once signed out', () async {
    await tokens.clear();
    final adapter = ScriptedAdapter((_) async => json(200, {'ok': true}));

    await client(adapter).getJson('/api/auth/me');

    expect(adapter.requests.single.headers.containsKey('Authorization'), isFalse);
  });

  test('never sends anything to another origin, so the token cannot leave', () async {
    final adapter = ScriptedAdapter((_) async => json(200, {'ok': true}));

    final error = await failure(client(adapter).getJson('https://elsewhere.example.test/api/x'));

    expect(adapter.requests, isEmpty);
    expect(error.kind, AppErrorKind.noAnswer);
  });

  test('asks the transport not to follow redirects', () async {
    final adapter = ScriptedAdapter((_) async => json(200, {'ok': true}));

    await client(adapter).getJson('/api/auth/me');

    expect(adapter.requests.single.followRedirects, isFalse);
    expect(adapter.requests.single.maxRedirects, 0);
  });

  test('treats a redirect as an error, never as somewhere to go', () async {
    final adapter = ScriptedAdapter(
      (_) async => text(302, '', headers: {'Location': 'https://elsewhere.example.test/'}),
    );

    final error = await failure(client(adapter).getJson('/api/auth/me'));

    expect(error.kind, AppErrorKind.unexpected);
    expect(error.status, 302);
    expect(adapter.requests, hasLength(1));
  });

  test('a 401 on a request that carried the token ends the session, without a retry', () async {
    final adapter = ScriptedAdapter(
      (_) async => json(401, {'code': 'not-authenticated', 'status': 401}),
    );

    final error = await failure(client(adapter).getJson('/api/transactions'));

    expect(error.code, 'not-authenticated');
    expect(ended, 1);
    expect(adapter.requests, hasLength(1));
  });

  test('a 401 on sign-in itself ends nothing: it is a wrong password', () async {
    await tokens.clear();
    final adapter = ScriptedAdapter(
      (_) async => json(401, {'code': 'authentication-failed', 'status': 401}),
    );

    final error = await failure(
      client(adapter).write('POST', '/api/auth/token', body: {'email': 'a'}, public: true),
    );

    expect(error.code, 'authentication-failed');
    expect(ended, 0);
  });

  test('reads a problem response as code and params, and keeps none of its prose', () async {
    final adapter = ScriptedAdapter(
      (_) async => json(422, {
        'title': 'Server-side words',
        'detail': 'More server-side words',
        'code': 'transfer-pair-mismatch',
        'params': {
          'reason': 'same-sign',
          'fields': ['amount'],
        },
        'correlationId': 'abc123',
      }),
    );

    final error = await failure(client(adapter).write('POST', '/api/x', body: {'a': 1}));

    expect(error.status, 422);
    expect(error.code, 'transfer-pair-mismatch');
    expect(error.reason, 'same-sign');
    expect(error.fields, ['amount']);
    expect(error.correlationId, 'abc123');
    expect(error.toString(), isNot(contains('Server-side words')));
  });

  test('reads how long a rate limit asks to wait', () async {
    final adapter = ScriptedAdapter(
      (_) async => json(429, {'code': 'rate-limited'}, headers: {'Retry-After': '90'}),
    );

    final error = await failure(client(adapter).getJson('/api/meta'));

    expect(error.retryAfterSeconds, 90);
  });

  test('tells offline from a server that does not answer, and records which', () async {
    final offline = ScriptedAdapter(
      (o) async => throw DioException.connectionError(
        requestOptions: o,
        reason: 'lookup',
        error: const SocketException('Failed host lookup: owl.example.test'),
      ),
    );
    final silent = ScriptedAdapter(
      (o) async => throw DioException.connectionTimeout(requestOptions: o, timeout: Duration.zero),
    );

    expect((await failure(client(offline).getJson('/api/x'))).kind, AppErrorKind.offline);
    expect((await failure(client(silent).getJson('/api/x'))).kind, AppErrorKind.noAnswer);
    expect(outcomes, [Reachability.offline, Reachability.noAnswer]);
  });

  test('any HTTP answer, even an error, means the server was reached', () async {
    await failure(client(ScriptedAdapter((_) async => json(500, null))).getJson('/api/x'));
    await client(ScriptedAdapter((_) async => json(200, {'a': 1}))).getJson('/api/x');

    expect(outcomes, [Reachability.reached, Reachability.reached]);
  });

  test('a write sends JSON and returns null for a 204', () async {
    final adapter = ScriptedAdapter((_) async => json(204, null));

    final result = await client(adapter).write('DELETE', '/api/transactions/x');

    expect(result, isNull);
  });

  test(
    'R2: before the version is confirmed, no write leaves; a read and a revocation do',
    () async {
      final adapter = ScriptedAdapter((_) async => json(200, {'ok': true}));
      final api = client(adapter, versionConfirmed: false);

      for (final method in ['POST', 'PUT', 'PATCH', 'DELETE', 'post']) {
        final refused = await failure(api.write(method, '/api/transactions', body: {'a': 1}));
        expect(refused.kind, AppErrorKind.offline, reason: method);
      }
      await failure(api.exchange('POST', '/api/setup/first-user', public: true));
      expect(adapter.requests, isEmpty, reason: 'refused before anything was sent');

      await api.getText('/api/transactions');
      final taken = await tokens.take();
      await api.revoke(taken!);
      expect(adapter.requests.map((o) => '${o.method} ${o.path}'), [
        'GET /api/transactions',
        'POST /api/auth/logout',
      ]);
    },
  );

  test("P3: a local failure's log line is what failed and the error's type, never its message", () {
    final line = failureLine(
      'caching the member',
      StateError('/data/user/0/app.budgetowl/cache/budget_owl_cache.db: balance 1234.56'),
    );

    expect(line, 'local caching the member failed (StateError)');
  });

  test('a body that is not JSON is "unexpected", not a crash', () async {
    final adapter = ScriptedAdapter((_) async => text(200, '<html>'));

    final error = await failure(client(adapter).write('POST', '/api/x', body: {'a': 1}));

    expect(error.kind, AppErrorKind.unexpected);
  });

  test('the debug log line carries no query, no body and no long opaque path segment', () {
    final options = RequestOptions(
      baseUrl: 'https://owl.example.test',
      path: '/api/invitations/QpL0a9rZ3x-Y_s8vKm2N7bT1uW4cD6eF/accept',
      queryParameters: {'search': 'pharmacy'},
      method: 'POST',
    );

    final line = redactedLine(options, 200);

    expect(line, 'api POST /api/invitations/<redacted>/accept → 200');
    expect(line, isNot(contains('pharmacy')));
  });

  test('the debug log line keeps an id, which is not a credential', () {
    final options = RequestOptions(
      baseUrl: 'https://owl.example.test',
      path: '/api/transactions/0f9c1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b',
      method: 'DELETE',
    );

    expect(
      redactedLine(options, null),
      'api DELETE /api/transactions/0f9c1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b → no answer',
    );
  });
}
