import 'dart:convert';
import 'dart:io';

import 'package:budget_owl/core/auth/token_store.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/api_client.dart';
import 'package:budget_owl/core/http/http_adapter.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/server/server_check.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/harness.dart';

/// The two transport controls proved against **real sockets** with the app's own adapter, not a
/// fake: the platform verifies certificates with no way round it (ADR-0042 decision 1), and a
/// redirect is never followed, so the bearer cannot be bounced to another host (decision 3).
///
/// Certificates are minted per run by openssl into a temporary directory — nothing private is
/// committed (verify.sh refuses key files in the repository).
void main() {
  late Directory pki;
  late SecurityContext trustingTestCa;

  Future<void> openssl(List<String> args) async {
    final result = await Process.run('openssl', args, workingDirectory: pki.path);
    if (result.exitCode != 0) throw StateError('openssl ${args.first} failed: ${result.stderr}');
  }

  Future<SecurityContext> serverFor(String name) async {
    File('${pki.path}/$name.ext').writeAsStringSync('subjectAltName=DNS:$name\n');
    await openssl([
      'req',
      '-newkey',
      'rsa:2048',
      '-nodes',
      '-keyout',
      '$name.key',
      '-out',
      '$name.csr',
      '-subj',
      '/CN=$name',
    ]);
    await openssl([
      'x509',
      '-req',
      '-in',
      '$name.csr',
      '-CA',
      'ca.pem',
      '-CAkey',
      'ca.key',
      '-CAcreateserial',
      '-out',
      '$name.pem',
      '-days',
      '2',
      '-extfile',
      '$name.ext',
    ]);
    return SecurityContext()
      ..useCertificateChain('${pki.path}/$name.pem')
      ..usePrivateKey('${pki.path}/$name.key');
  }

  setUpAll(() async {
    pki = Directory.systemTemp.createTempSync('budget-owl-pki');
    await openssl([
      'req',
      '-x509',
      '-newkey',
      'rsa:2048',
      '-nodes',
      '-keyout',
      'ca.key',
      '-out',
      'ca.pem',
      '-days',
      '2',
      '-subj',
      '/CN=Budget Owl test CA',
    ]);
    trustingTestCa = SecurityContext()..setTrustedCertificates('${pki.path}/ca.pem');
  });

  tearDownAll(() => pki.deleteSync(recursive: true));

  Future<HttpServer> metaServer(SecurityContext context) async {
    final server = await HttpServer.bindSecure('localhost', 0, context);
    server.listen((request) async {
      request.response
        ..headers.contentType = ContentType.json
        ..write(jsonEncode({'app': 'budget-owl', 'apiVersion': 1, 'minimumClientApiVersion': 1}));
      await request.response.close();
    });
    return server;
  }

  ServerAddress at(int port) => (ServerAddress.parse(
    'https://localhost:$port',
    allowLocalCleartext: false,
  ) as AddressAccepted).address;

  Future<Object> check(int port, {SecurityContext? trust}) =>
      HttpOverrides.runWithHttpOverrides(() async {
        try {
          return await ServerCheck(platformHttpAdapter()).check(at(port));
        } on ConnectFailure catch (failure) {
          return failure.problem;
        }
      }, _RealSockets(trust));

  test('refuses a certificate this phone does not trust — no button, no exception', () async {
    final server = await metaServer(await serverFor('localhost'));
    addTearDown(server.close);

    expect(await check(server.port), ConnectProblem.certificateUntrusted);
  });

  test('connects once the authority is trusted, as installing a private CA does', () async {
    final server = await metaServer(await serverFor('localhost'));
    addTearDown(server.close);

    expect(await check(server.port, trust: trustingTestCa), isA<ServerIdentity>());
  });

  test('refuses a trusted certificate issued for a different name', () async {
    final server = await metaServer(await serverFor('other.test'));
    addTearDown(server.close);

    expect(await check(server.port, trust: trustingTestCa), ConnectProblem.certificateWrongName);
  });

  test('the server check leaves the shared transport open for every request after it', () async {
    // One real adapter, as the app has one (httpAdapterProvider): the meta check, then the API
    // client's bearer request, through the same sockets. Dio.close() closes the adapter it was
    // given, so a check that closed its Dio broke the app for good — sign-out's logout included.
    final seen = <String>[];
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((request) async {
      seen.add('${request.method} ${request.uri.path} ${request.headers.value('authorization')}');
      request.response.headers.contentType = ContentType.json;
      request.response.write(
        request.uri.path == '/api/meta'
            ? jsonEncode({'app': 'budget-owl', 'apiVersion': 1, 'minimumClientApiVersion': 1})
            : '{}',
      );
      await request.response.close();
    });
    addTearDown(server.close);
    final address = (ServerAddress.parse(
      'http://127.0.0.1:${server.port}',
      allowLocalCleartext: true,
    ) as AddressAccepted).address;
    final shared = platformHttpAdapter();
    addTearDown(shared.close);
    final tokens = TokenStore(MemorySecureStore());
    await tokens.save('fixture-token-owner', origin: address.stored);
    final api = ApiClient(
      server: address,
      adapter: shared,
      tokens: tokens,
      onSessionEnded: () {},
      onOutcome: (_) {},
      logRequests: false,
    );

    final body = await HttpOverrides.runWithHttpOverrides(() async {
      await ServerCheck(shared).check(address);
      await ServerCheck(shared).check(address);
      return await api.getText('/api/auth/me');
    }, _RealSockets(null));

    expect(body, '{}');
    expect(seen, [
      'GET /api/meta null',
      'GET /api/meta null',
      'GET /api/auth/me Bearer fixture-token-owner',
    ]);
  });

  test('a redirect is an error, and the token never reaches the host it points at', () async {
    final elsewhere = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final reachedElsewhere = <String?>[];
    elsewhere.listen((request) async {
      reachedElsewhere.add(request.headers.value('authorization'));
      await request.response.close();
    });
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((request) async {
      request.response
        ..statusCode = HttpStatus.found
        ..headers.set('Location', 'http://127.0.0.1:${elsewhere.port}/steal');
      await request.response.close();
    });
    addTearDown(() async {
      await server.close();
      await elsewhere.close();
    });

    final store = MemorySecureStore();
    final tokens = TokenStore(store);
    await tokens.save('fixture-token-owner', origin: 'http://127.0.0.1:${server.port}');
    final api = ApiClient(
      server: (ServerAddress.parse(
        'http://127.0.0.1:${server.port}',
        allowLocalCleartext: true,
      ) as AddressAccepted).address,
      adapter: platformHttpAdapter(),
      tokens: tokens,
      onSessionEnded: () {},
      onOutcome: (_) {},
      logRequests: false,
    );

    final failure = await HttpOverrides.runWithHttpOverrides(() async {
      try {
        await api.getText('/api/transactions');
        return null;
      } on AppError catch (e) {
        return e;
      }
    }, _RealSockets(null));

    expect(failure?.kind, AppErrorKind.unexpected);
    expect(failure?.status, HttpStatus.found);
    expect(reachedElsewhere, isEmpty);
  });
}

/// Real sockets, with the default trust store — or [trust] where a test installs its own CA.
/// flutter_test replaces HttpClient with a mock for widget tests; these tests need the real one.
final class _RealSockets extends HttpOverrides {
  _RealSockets(this.trust);

  final SecurityContext? trust;

  @override
  HttpClient createHttpClient(SecurityContext? context) => super.createHttpClient(trust ?? context);
}
