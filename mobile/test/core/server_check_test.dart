import 'dart:convert';
import 'dart:io';

import 'package:budget_owl/core/http/transport_failure.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/server/server_check.dart';
import 'package:budget_owl/features/connect/presentation/connect_screen.dart';
import 'package:budget_owl/l10n/gen/app_localizations_en.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/adapters.dart';

/// `GET /api/meta` before anything else is sent (attack 4), and a distinct reason for each way a
/// server can fail to be one this app talks to.
void main() {
  final address = (ServerAddress.parse(
    'owl.example.test',
    allowLocalCleartext: false,
  ) as AddressAccepted).address;
  const meta = {'app': 'budget-owl', 'apiVersion': 1, 'minimumClientApiVersion': 1};

  Future<ConnectProblem?> problemFor(ScriptedAdapter adapter) async {
    try {
      await ServerCheck(adapter).check(address);
      return null;
    } on ConnectFailure catch (failure) {
      return failure.problem;
    }
  }

  test('accepts the exact identity the API sends', () async {
    final adapter = ScriptedAdapter((_) async => json(200, meta));

    final identity = await ServerCheck(adapter).check(address);

    expect(identity.apiVersion, 1);
    expect(adapter.requests.single.uri.toString(), 'https://owl.example.test/api/meta');
  });

  test('sends no credential, no cookie and follows no redirect', () async {
    final adapter = ScriptedAdapter((_) async => json(200, meta));

    await ServerCheck(adapter).check(address);

    final request = adapter.requests.single;
    expect(request.headers.keys.map((k) => k.toLowerCase()), isNot(contains('authorization')));
    expect(request.headers.keys.map((k) => k.toLowerCase()), isNot(contains('cookie')));
    expect(request.followRedirects, isFalse);
    expect(request.maxRedirects, 0);
  });

  test('a redirect is "not Budget Owl", and its target is never asked', () async {
    final adapter = ScriptedAdapter(
      (_) async => text(302, '', headers: {'Location': 'https://elsewhere.example.test/api/meta'}),
    );

    expect(await problemFor(adapter), ConnectProblem.notBudgetOwl);
    expect(adapter.requests, hasLength(1));
  });

  test('an HTML page at the address is not Budget Owl', () async {
    final adapter = ScriptedAdapter((_) async => text(200, '<html>router login</html>'));

    expect(await problemFor(adapter), ConnectProblem.notBudgetOwl);
  });

  test("another app's JSON is not Budget Owl", () async {
    // Every other field is what Budget Owl would send, so only the name can refuse it.
    final adapter = ScriptedAdapter(
      (_) async => json(200, {'app': 'other', 'apiVersion': 1, 'minimumClientApiVersion': 1}),
    );

    expect(await problemFor(adapter), ConnectProblem.notBudgetOwl);
  });

  test("the server's rate limit is \"busy\", in its own words — not \"not Budget Owl\"", () async {
    final problem = await problemFor(
      ScriptedAdapter(
        (_) async => json(429, {'code': 'rate-limited'}, headers: {'Retry-After': '42'}),
      ),
    );

    expect(problem, ConnectProblem.busy);
    expect(
      connectFailureText(AppLocalizationsEn(), const ConnectFailure(ConnectProblem.busy)),
      'The server is busy. Try again in a minute.',
    );
  });

  test('a 404 is not Budget Owl', () async {
    expect(
      await problemFor(ScriptedAdapter((_) async => json(404, null))),
      ConnectProblem.notBudgetOwl,
    );
  });

  test('reads at most a few kilobytes from a server that is not Budget Owl', () async {
    final adapter = ScriptedAdapter(
      (_) async => text(200, '{"app":"${'x' * (ServerCheck.maximumBytes * 4)}"}'),
    );

    expect(await problemFor(adapter), ConnectProblem.notBudgetOwl);
  });

  test('says which version each side is when the server has moved on', () async {
    final adapter = ScriptedAdapter(
      (_) async => json(200, {'app': 'budget-owl', 'apiVersion': 2, 'minimumClientApiVersion': 2}),
    );

    try {
      await ServerCheck(adapter).check(address);
      fail('a server that no longer serves API 1 was accepted');
    } on ConnectFailure catch (failure) {
      expect(failure.problem, ConnectProblem.versionMismatch);
      expect(failure.serverApiVersion, 2);
      expect(failure.toString(), contains('versionMismatch'));
    }
  });

  test('refuses a server older than the API this app was written for', () async {
    final adapter = ScriptedAdapter(
      (_) async => json(200, {'app': 'budget-owl', 'apiVersion': 0, 'minimumClientApiVersion': 0}),
    );

    expect(await problemFor(adapter), ConnectProblem.versionMismatch);
  });

  test("talks to a newer server that still serves this app's API", () async {
    final adapter = ScriptedAdapter(
      (_) async => json(200, {'app': 'budget-owl', 'apiVersion': 2, 'minimumClientApiVersion': 1}),
    );

    expect(await problemFor(adapter), isNull);
  });

  group('each connection failure has its own sentence', () {
    DioException wrap(RequestOptions o, Object error) =>
        DioException(requestOptions: o, error: error);

    final cases = <String, (DioException Function(RequestOptions), ConnectProblem)>{
      'host not found': (
        (o) => wrap(o, const SocketException('Failed host lookup: owl.example.test')),
        ConnectProblem.hostNotFound,
      ),
      'connection refused': (
        (o) => wrap(o, const SocketException('Connection refused', osError: OSError('', 111))),
        ConnectProblem.unreachable,
      ),
      'timed out': (
        (o) => DioException.connectionTimeout(requestOptions: o, timeout: Duration.zero),
        ConnectProblem.unreachable,
      ),
      'certificate not trusted': (
        (o) => wrap(
          o,
          const HandshakeException(
            'Handshake error',
            OSError('CERTIFICATE_VERIFY_FAILED: unable to get local issuer certificate'),
          ),
        ),
        ConnectProblem.certificateUntrusted,
      ),
      'certificate for another name': (
        (o) => wrap(
          o,
          const HandshakeException(
            'Handshake error',
            OSError('CERTIFICATE_VERIFY_FAILED: Hostname mismatch'),
          ),
        ),
        ConnectProblem.certificateWrongName,
      ),
      'something else': ((o) => wrap(o, StateError('odd')), ConnectProblem.transport),
    };

    for (final MapEntry(key: name, value: scenario) in cases.entries) {
      test(name, () async {
        final adapter = ScriptedAdapter((o) async => throw scenario.$1(o));
        expect(await problemFor(adapter), scenario.$2);
      });
    }
  });

  test('the transport classifier reads what dart:io and Dio throw', () {
    final o = RequestOptions(path: '/');
    expect(
      classifyTransportFailure(const SocketException('Network is unreachable')),
      TransportFailure.noNetwork,
    );
    expect(
      classifyTransportFailure(DioException.badCertificate(requestOptions: o)),
      TransportFailure.certificateUntrusted,
    );
    expect(
      classifyTransportFailure(
        DioException.receiveTimeout(requestOptions: o, timeout: Duration.zero),
      ),
      TransportFailure.unreachable,
    );
    expect(classifyTransportFailure(const HttpException('closed')), TransportFailure.unreachable);
    expect(classifyTransportFailure(Object()), TransportFailure.other);
  });

  test('identify reads raw bytes', () {
    expect(ServerCheck.identify(utf8.encode(jsonEncode(meta))).apiVersion, 1);
    expect(() => ServerCheck.identify(utf8.encode('{')), throwsA(isA<ConnectFailure>()));
    expect(
      () => ServerCheck.identify(utf8.encode('{"app":"budget-owl","apiVersion":"1"}')),
      throwsA(isA<ConnectFailure>()),
    );
  });
}
