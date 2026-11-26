import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:budget_owl/core/auth/token_store.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/api_client.dart';
import 'package:budget_owl/core/http/cleartext_policy.dart';
import 'package:budget_owl/core/http/http_adapter.dart';
import 'package:budget_owl/core/http/user_trust.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/server/server_check.dart';
import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/harness.dart';

/// AND1: on Android, a CA the person installed on the phone is trusted by the app's requests, as
/// `network_security_config.xml` already declares (`<certificates src="user" />`) — and nothing
/// else changes. Certificates and host names are still verified, a store that cannot be read means
/// the system roots only, and the transport's other controls (no redirects, the cleartext zone,
/// the origin guard, never closed) hold through the Android adapter too.
///
/// The real-socket tests mint a CA per run with openssl into a temporary directory (nothing private
/// is committed) and hand its DER bytes to the same code path `MainActivity`'s channel feeds.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory pki;
  late Uint8List caDer;

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
    pki = Directory.systemTemp.createTempSync('budget-owl-user-ca');
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
      '/CN=Budget Owl test user CA',
    ]);
    await openssl(['x509', '-in', 'ca.pem', '-outform', 'DER', '-out', 'ca.der']);
    caDer = File('${pki.path}/ca.der').readAsBytesSync();
  });

  tearDownAll(() => pki.deleteSync(recursive: true));

  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  void answerChannel(Future<Object?>? Function(MethodCall call) answer) {
    messenger.setMockMethodCallHandler(trustChannel, answer);
    addTearDown(() => messenger.setMockMethodCallHandler(trustChannel, null));
  }

  group('the channel', () {
    test('reads the certificates MainActivity returns, as bytes', () async {
      final calls = <String>[];
      answerChannel((call) async {
        calls.add(call.method);
        return [
          caDer,
          'not bytes',
          Uint8List.fromList([1, 2]),
        ];
      });

      final read = await readUserCaCertificates();

      expect(calls, ['userCaCertificates']);
      expect(read, hasLength(2), reason: 'anything that is not bytes is dropped');
      expect(read.first, caDer);
    });

    test('an empty store is an empty list', () async {
      answerChannel((_) async => null);

      expect(await readUserCaCertificates(), isEmpty);
    });
  });

  group('the context', () {
    test('adds the certificates it is given, on top of the system roots', () async {
      expect(await phoneTrustContext(() async => [caDer]), isNotNull);
      expect(pemOf(caDer), startsWith('-----BEGIN CERTIFICATE-----\n'));
      expect(
        LineSplitter.split(pemOf(caDer)).every((line) => line.length <= 64),
        isTrue,
        reason: 'PEM lines are at most 64 characters',
      );
    });

    test('falls back to the system roots alone when there is nothing to add or it fails', () async {
      // null is HttpClient's default context: the system store, exactly as before AND1.
      expect(await phoneTrustContext(() async => []), isNull, reason: 'empty');
      expect(
        await phoneTrustContext(() async => throw PlatformException(code: 'unavailable')),
        isNull,
        reason: 'the store could not be read',
      );
      expect(
        await phoneTrustContext(() async => throw MissingPluginException()),
        isNull,
        reason: 'no channel',
      );
      expect(
        await phoneTrustContext(
          () => Completer<List<Uint8List>>().future,
          timeout: const Duration(milliseconds: 20),
        ),
        isNull,
        reason: 'no answer in time',
      );
      expect(
        await phoneTrustContext(
          () async => [
            Uint8List.fromList([1, 2, 3]),
          ],
        ),
        isNull,
        reason: 'nothing that is a certificate',
      );
    });

    test('a channel that fails, through the real channel code, is the system roots', () async {
      answerChannel((_) async => throw PlatformException(code: 'unavailable'));

      expect(await phoneTrustContext(readUserCaCertificates), isNull);
    });

    test('one bad certificate is skipped, the good ones stand', () async {
      expect(
        await phoneTrustContext(
          () async => [
            Uint8List.fromList([1, 2, 3]),
            caDer,
          ],
        ),
        isNotNull,
      );
    });

    test('the system roots are kept, and no check is relaxed (source)', () {
      final source = File('lib/core/http/user_trust.dart').readAsStringSync();
      expect(source, contains('SecurityContext(withTrustedRoots: true)'));
      expect(source, isNot(contains('withTrustedRoots: false')));
      final adapter = File('lib/core/http/http_adapter.dart').readAsStringSync();
      expect(adapter, contains('HttpClient(context: context)'));
      expect(adapter, contains('Platform.isAndroid'), reason: 'Android only');
    });
  });

  group('real sockets, through the Android adapter', () {
    Future<HttpServer> metaServer(SecurityContext context, {List<String>? seen}) async {
      final server = await HttpServer.bindSecure('localhost', 0, context);
      server.listen((request) async {
        seen?.add(
          '${request.method} ${request.uri.path} ${request.headers.value('authorization')}',
        );
        request.response
          ..headers.contentType = ContentType.json
          ..write(
            request.uri.path == '/api/meta'
                ? jsonEncode({'app': 'budget-owl', 'apiVersion': 1, 'minimumClientApiVersion': 1})
                : '{}',
          );
        await request.response.close();
      });
      addTearDown(server.close);
      return server;
    }

    ServerAddress at(int port) => (ServerAddress.parse(
      'https://localhost:$port',
      allowLocalCleartext: false,
    ) as AddressAccepted).address;

    Future<Object> check(HttpClientAdapter adapter, int port) =>
        HttpOverrides.runWithHttpOverrides(() async {
          try {
            return await ServerCheck(adapter).check(at(port));
          } on ConnectFailure catch (failure) {
            return failure.problem;
          }
        }, _RealSockets());

    test('without the user CA, a server it signed is not trusted', () async {
      final server = await metaServer(await serverFor('localhost'));

      final adapter = platformHttpAdapter(android: true, userCertificates: () async => []);

      expect(await check(adapter, server.port), ConnectProblem.certificateUntrusted);
    });

    test('with the user CA from the phone, the same server is trusted', () async {
      final server = await metaServer(await serverFor('localhost'));

      final adapter = platformHttpAdapter(android: true, userCertificates: () async => [caDer]);

      expect(await check(adapter, server.port), isA<ServerIdentity>());
    });

    test("through MainActivity's channel, as the app does it", () async {
      final server = await metaServer(await serverFor('localhost'));
      answerChannel((_) async => [caDer]);

      expect(await check(platformHttpAdapter(android: true), server.port), isA<ServerIdentity>());
    });

    test('a store that cannot be read fails closed, without a crash', () async {
      final server = await metaServer(await serverFor('localhost'));
      answerChannel((_) async => throw PlatformException(code: 'unavailable'));

      expect(
        await check(platformHttpAdapter(android: true), server.port),
        ConnectProblem.certificateUntrusted,
      );
    });

    test('the host name is still checked: a trusted CA cannot vouch for another name', () async {
      final server = await metaServer(await serverFor('other.test'));

      final adapter = platformHttpAdapter(android: true, userCertificates: () async => [caDer]);

      expect(await check(adapter, server.port), ConnectProblem.certificateWrongName);
    });

    test('the store is read once, before the first request, for every request after', () async {
      final seen = <String>[];
      final server = await metaServer(await serverFor('localhost'), seen: seen);
      var reads = 0;
      final adapter = platformHttpAdapter(
        android: true,
        userCertificates: () async {
          reads++;
          return [caDer];
        },
      );
      final address = at(server.port);
      final tokens = TokenStore(MemorySecureStore());
      await tokens.save('fixture-token-owner', origin: address.stored);
      final api = ApiClient(
        server: address,
        adapter: adapter,
        tokens: tokens,
        onSessionEnded: () {},
        onOutcome: (_) {},
        versionConfirmed: () => true,
        logRequests: false,
      );

      // Two checks and a bearer request through one shared adapter, which nothing closes (H1).
      await HttpOverrides.runWithHttpOverrides(() async {
        await ServerCheck(adapter).check(address);
        await ServerCheck(adapter).check(address);
        await api.getText('/api/auth/me');
      }, _RealSockets());

      expect(reads, 1);
      expect(seen, [
        'GET /api/meta null',
        'GET /api/meta null',
        'GET /api/auth/me Bearer fixture-token-owner',
      ]);
    });

    test(
      'a redirect from a trusted server is still an error, and the token goes nowhere',
      () async {
        final elsewhere = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
        final reachedElsewhere = <String?>[];
        elsewhere.listen((request) async {
          reachedElsewhere.add(request.headers.value('authorization'));
          await request.response.close();
        });
        addTearDown(elsewhere.close);
        final server = await HttpServer.bindSecure('localhost', 0, await serverFor('localhost'));
        server.listen((request) async {
          request.response
            ..statusCode = HttpStatus.found
            ..headers.set('Location', 'http://127.0.0.1:${elsewhere.port}/steal');
          await request.response.close();
        });
        addTearDown(server.close);
        final address = at(server.port);
        final tokens = TokenStore(MemorySecureStore());
        await tokens.save('fixture-token-owner', origin: address.stored);
        final api = ApiClient(
          server: address,
          adapter: platformHttpAdapter(android: true, userCertificates: () async => [caDer]),
          tokens: tokens,
          onSessionEnded: () {},
          onOutcome: (_) {},
          versionConfirmed: () => true,
          logRequests: false,
        );

        final failure = await HttpOverrides.runWithHttpOverrides(() async {
          try {
            await api.getText('/api/transactions');
            return null;
          } on AppError catch (e) {
            return e;
          }
        }, _RealSockets());

        expect(failure?.status, HttpStatus.found);
        expect(reachedElsewhere, isEmpty);
      },
    );

    test('the origin guard still refuses another origin before anything is sent', () async {
      final seen = <String>[];
      final server = await metaServer(await serverFor('localhost'), seen: seen);
      final address = at(server.port);
      final tokens = TokenStore(MemorySecureStore());
      await tokens.save('fixture-token-owner', origin: address.stored);
      final api = ApiClient(
        server: address,
        adapter: platformHttpAdapter(android: true, userCertificates: () async => [caDer]),
        tokens: tokens,
        onSessionEnded: () {},
        onOutcome: (_) {},
        versionConfirmed: () => true,
        logRequests: false,
      );

      final failure = await HttpOverrides.runWithHttpOverrides(() async {
        try {
          await api.getText('https://127.0.0.1:${server.port}/api/auth/me');
          return null;
        } on AppError catch (e) {
          return e;
        }
      }, _RealSockets());

      expect(failure, isNotNull);
      expect(seen, isEmpty);
    });

    test('a release build still refuses http:// at the socket through it', () async {
      final error = await HttpOverrides.runWithHttpOverrides(
        () => runWithCleartextPolicy(() async {
          final dio = Dio(BaseOptions(connectTimeout: const Duration(milliseconds: 300)))
            ..httpClientAdapter = platformHttpAdapter(
              android: true,
              userCertificates: () async => [caDer],
            );
          try {
            await dio.getUri<String>(Uri.parse('http://198.51.100.7:8080/api/meta'));
            return null;
          } on DioException catch (e) {
            return e.error;
          }
        }, allowLocalCleartext: false),
        _RealSockets(),
      );

      expect(error, isA<UnsupportedError>());
      expect('$error', contains('Non-https connection'));
    });
  });
}

/// flutter_test replaces HttpClient for widget tests; these need the real one, with whatever
/// context the adapter under test builds.
final class _RealSockets extends HttpOverrides;
