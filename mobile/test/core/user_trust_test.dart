import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:budget_owl/app.dart';
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
import 'package:flutter/widgets.dart' show AppLifecycleState;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/harness.dart';

/// AND1 and PIN: on Android, a server whose certificate only a CA the person installed verifies
/// is trusted through **that one CA**, chosen when the server is connected to, and never through
/// any other user CA — such as one an employer's device management pushed for TLS inspection. A
/// server the system roots verify gets no user CA at all. P2: the phone's store is read again on
/// every return to the foreground, so a CA removed in Settings stops being trusted for every
/// connection made after.
///
/// Real sockets throughout. Three CAs are minted per run by openssl into a temporary directory
/// (nothing private is committed): **CA0** stands in for the system roots, **CA1** is the
/// self-hoster's own, **CA2** is the device-management one. A small TCP front lets one origin
/// present a different server — and so a different certificate — from one test step to the next.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory pki;
  late Map<String, Uint8List> ca;
  late Map<String, String> fingerprintByOpenssl;

  Future<String> openssl(List<String> args) async {
    final result = await Process.run('openssl', args, workingDirectory: pki.path);
    if (result.exitCode != 0) throw StateError('openssl ${args.first} failed: ${result.stderr}');
    return result.stdout as String;
  }

  Future<void> mintCa(String name) async {
    await openssl([
      'req',
      '-x509',
      '-newkey',
      'rsa:2048',
      '-nodes',
      '-keyout',
      '$name.key',
      '-out',
      '$name.pem',
      '-days',
      '2',
      '-subj',
      '/CN=Budget Owl test $name',
    ]);
    await openssl(['x509', '-in', '$name.pem', '-outform', 'DER', '-out', '$name.der']);
    ca[name] = File('${pki.path}/$name.der').readAsBytesSync();
    final printed = await openssl([
      'x509',
      '-in',
      '$name.pem',
      '-noout',
      '-fingerprint',
      '-sha256',
    ]);
    fingerprintByOpenssl[name] = printed.split('=').last.trim().replaceAll(':', '').toLowerCase();
  }

  /// A server certificate for [host], issued by [issuer].
  Future<SecurityContext> serverCert(String host, String issuer) async {
    final name = '$host-$issuer';
    File('${pki.path}/$name.ext').writeAsStringSync('subjectAltName=DNS:$host\n');
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
      '/CN=$host',
    ]);
    await openssl([
      'x509',
      '-req',
      '-in',
      '$name.csr',
      '-CA',
      '$issuer.pem',
      '-CAkey',
      '$issuer.key',
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

  /// "The system roots": the real ones, plus CA0.
  SecurityContext systemWithCa0() =>
      SecurityContext(withTrustedRoots: true)..setTrustedCertificates('${pki.path}/CA0.pem');

  setUpAll(() async {
    pki = Directory.systemTemp.createTempSync('budget-owl-user-ca');
    ca = {};
    fingerprintByOpenssl = {};
    for (final name in ['CA0', 'CA1', 'CA2']) {
      await mintCa(name);
    }
  });

  tearDownAll(() => pki.deleteSync(recursive: true));

  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  void answerChannel(Future<Object?>? Function(MethodCall call) answer) {
    messenger.setMockMethodCallHandler(trustChannel, answer);
    addTearDown(() => messenger.setMockMethodCallHandler(trustChannel, null));
  }

  /// A Budget Owl meta server with [context]'s certificate, recording what it was asked.
  ///
  /// Behind a [_Front], [keepAlive] is off: the front cuts connections when it switches servers,
  /// and a request on a cut keep-alive connection fails as "unreachable", which is the harness,
  /// not the app. Keep-alive itself has its own test, on a direct server.
  Future<HttpServer> metaServer(
    SecurityContext context, {
    List<String>? seen,
    bool keepAlive = true,
  }) async {
    final server = await HttpServer.bindSecure('localhost', 0, context);
    server.listen((request) async {
      seen?.add('${request.method} ${request.uri.path} ${request.headers.value('authorization')}');
      request.response.persistentConnection = keepAlive;
      request.response
        ..headers.contentType = ContentType.json
        ..write(
          request.uri.path == '/api/meta'
              ? jsonEncode({'app': 'budget-owl', 'apiVersion': 1, 'minimumClientApiVersion': 1})
              : '{}',
        );
      await request.response.close();
    });
    addTearDown(() => server.close(force: true));
    return server;
  }

  ServerAddress at(int port) => (ServerAddress.parse(
    'https://localhost:$port',
    allowLocalCleartext: false,
  ) as AddressAccepted).address;

  Future<T> realSockets<T>(Future<T> Function() body) =>
      HttpOverrides.runWithHttpOverrides(body, _RealSockets());

  /// The check through [adapter], as the problem's name or the identity.
  Future<Object> check(HttpClientAdapter adapter, ServerAddress address) => realSockets(() async {
    try {
      return await ServerCheck(adapter).check(address);
    } on ConnectFailure catch (failure) {
      return failure.problem;
    }
  });

  Future<Object?> pinFor(PhoneTrustAdapter adapter, ServerAddress address) => realSockets(() async {
    try {
      return await adapter.checkAndPin(address);
    } on ConnectFailure catch (failure) {
      return failure.problem;
    }
  });

  /// The phone's user-CA store, which a test changes as the person does in Settings.
  PhoneTrustAdapter phoneWith(List<Uint8List> store) =>
      PhoneTrustAdapter(() async => List.of(store), systemRoots: systemWithCa0);

  group('the channel', () {
    test('reads the certificates MainActivity returns, as bytes', () async {
      final calls = <String>[];
      answerChannel((call) async {
        calls.add(call.method);
        return [
          ca['CA1'],
          'not bytes',
          Uint8List.fromList([1, 2]),
        ];
      });

      final read = await readUserCaCertificates();

      expect(calls, ['userCaCertificates']);
      expect(read, hasLength(2), reason: 'anything that is not bytes is dropped');
      expect(read.first, ca['CA1']);
    });

    test('an empty store is an empty list', () async {
      answerChannel((_) async => null);

      expect(await readUserCaCertificates(), isEmpty);
    });
  });

  group('fingerprints and contexts', () {
    test("a pin is the SHA-256 of the CA's DER, as openssl prints it", () {
      for (final name in ['CA0', 'CA1', 'CA2']) {
        expect(fingerprintOf(ca[name]!), fingerprintByOpenssl[name], reason: name);
        expect(isFingerprint(fingerprintOf(ca[name]!)), isTrue);
      }
      for (final bad in [null, '', 'CA1', 'AB' * 32, '${'a' * 63}g']) {
        expect(isFingerprint(bad), isFalse, reason: '$bad');
      }
      expect(pemOf(ca['CA1']!), startsWith('-----BEGIN CERTIFICATE-----\n'));
      expect(LineSplitter.split(pemOf(ca['CA1']!)).every((line) => line.length <= 64), isTrue);
    });

    test('bytes that are not a certificate add nothing, and throw nothing', () {
      expect(
        () => contextTrusting(Uint8List.fromList([1, 2, 3]), systemRoots: systemRootsOnly),
        returnsNormally,
      );
    });

    test('the system roots are kept, and no check is relaxed (source)', () {
      final trust = File('lib/core/http/user_trust.dart').readAsStringSync();
      expect(trust, contains('SecurityContext(withTrustedRoots: true)'));
      expect(trust, isNot(contains('withTrustedRoots: false')));
      final adapter = File('lib/core/http/http_adapter.dart').readAsStringSync();
      expect(adapter, contains('HttpClient(context: context)'));
      expect(adapter, contains('Platform.isAndroid'), reason: 'Android only');
      expect(adapter, contains('reloadTrustOnResume(ref, adapter);'), reason: 'P2 is wired');
    });
  });

  group('PIN: a server is trusted through the one CA it was connected through', () {
    test('a server the system roots verify is pinned to nothing, and gets no user CA', () async {
      final front = await _Front.start();
      addTearDown(front.close);
      front.to(await metaServer(await serverCert('localhost', 'CA0'), keepAlive: false));
      final address = at(front.port);
      final adapter = phoneWith([ca['CA1']!, ca['CA2']!]);

      expect(await pinFor(adapter, address), isNull);
      adapter.pinTo(null);
      expect(await check(adapter, address), isA<ServerIdentity>());

      // The same origin now presents a certificate a user CA on the phone would vouch for.
      front.to(await metaServer(await serverCert('localhost', 'CA1'), keepAlive: false));
      expect(await check(adapter, address), ConnectProblem.certificateUntrusted);
    });

    test('a server only a user CA verifies is pinned to that CA; the device-management CA is '
        'refused for it, and so is a CA that has gone', () async {
      final front = await _Front.start();
      addTearDown(front.close);
      front.to(await metaServer(await serverCert('localhost', 'CA1'), keepAlive: false));
      final address = at(front.port);
      final store = [ca['CA2']!, ca['CA1']!]; // the MDM's CA first: order decides nothing
      final adapter = PhoneTrustAdapter(() async => List.of(store), systemRoots: systemWithCa0);

      final pin = await pinFor(adapter, address);
      expect(pin, fingerprintByOpenssl['CA1']);
      adapter.pinTo(pin! as String);
      expect(await check(adapter, address), isA<ServerIdentity>());

      // The MDM case: the same origin, intercepted with CA2's certificate. CA2 is on the phone.
      front.to(await metaServer(await serverCert('localhost', 'CA2'), keepAlive: false));
      expect(await check(adapter, address), ConnectProblem.certificateUntrusted);

      // Back to the real server; then the person removes CA1 in Settings and comes back.
      front.to(await metaServer(await serverCert('localhost', 'CA1'), keepAlive: false));
      expect(await check(adapter, address), isA<ServerIdentity>());
      store.remove(ca['CA1']);
      await adapter.reloadPhoneStore();
      expect(adapter.anchored, isFalse);
      expect(await check(adapter, address), ConnectProblem.certificateUntrusted);

      // Installed again (the same CA): trusted again, by its fingerprint.
      store.add(ca['CA1']!);
      await adapter.reloadPhoneStore();
      expect(await check(adapter, address), isA<ServerIdentity>());
    });

    test(
      'a CA removed while a connection is open is not trusted through that connection',
      () async {
        // Keep-alive: the first exchange leaves a connection open, made under CA1. After the store
        // loses CA1, the next request must not ride it.
        final store = [ca['CA1']!];
        final adapter = PhoneTrustAdapter(() async => List.of(store), systemRoots: systemWithCa0);
        final server = await metaServer(await serverCert('localhost', 'CA1'));
        final address = at(server.port);
        adapter.pinTo((await pinFor(adapter, address))! as String);
        expect(await check(adapter, address), isA<ServerIdentity>());

        store.clear();
        await adapter.reloadPhoneStore();

        expect(await check(adapter, address), ConnectProblem.certificateUntrusted);
      },
    );

    test('no CA on the phone verifies it: not trusted, and nothing to pin', () async {
      final server = await metaServer(await serverCert('localhost', 'CA2'));

      expect(
        await pinFor(phoneWith([ca['CA1']!]), at(server.port)),
        ConnectProblem.certificateUntrusted,
      );
    });

    test('a user CA that verifies the chain cannot vouch for another name', () async {
      final server = await metaServer(await serverCert('other.test', 'CA1'));

      expect(
        await pinFor(phoneWith([ca['CA2']!, ca['CA1']!]), at(server.port)),
        ConnectProblem.certificateWrongName,
      );
    });

    test('a store that cannot be read, or does not answer, trusts no user CA (no crash)', () async {
      final server = await metaServer(await serverCert('localhost', 'CA1'));
      final failing = PhoneTrustAdapter(
        () async => throw PlatformException(code: 'unavailable'),
        systemRoots: systemWithCa0,
      );
      final silent = PhoneTrustAdapter(
        () => Completer<List<Uint8List>>().future,
        systemRoots: systemWithCa0,
        timeout: const Duration(milliseconds: 50),
      );

      for (final adapter in [failing, silent]) {
        expect(await pinFor(adapter, at(server.port)), ConnectProblem.certificateUntrusted);
        adapter.pinTo(fingerprintOf(ca['CA1']!));
        expect(await check(adapter, at(server.port)), ConnectProblem.certificateUntrusted);
      }
    });

    test('a stored pin that is not a fingerprint is the system roots only', () async {
      final server = await metaServer(await serverCert('localhost', 'CA1'));
      final adapter = phoneWith([ca['CA1']!])..pinTo('not-a-fingerprint');

      expect(adapter.pin, isNull);
      expect(await check(adapter, at(server.port)), ConnectProblem.certificateUntrusted);
    });

    test("through MainActivity's channel, as the app does it", () async {
      final server = await metaServer(await serverCert('localhost', 'CA1'));
      answerChannel((_) async => [ca['CA2'], ca['CA1']]);
      final adapter = platformHttpAdapter(android: true, systemRoots: systemWithCa0);

      expect(adapter, isA<PhoneTrustAdapter>());
      final pin = await pinFor(adapter as PhoneTrustAdapter, at(server.port));
      expect(pin, fingerprintByOpenssl['CA1']);
    });
  });

  group('P2: the store is read again when the app comes back', () {
    testWidgets('a return to the foreground re-reads it', (tester) async {
      var reads = 0;
      final container = ProviderContainer(
        overrides: [
          httpAdapterProvider.overrideWith((ref) {
            final adapter = platformHttpAdapter(
              android: true,
              userCertificates: () async {
                reads++;
                return const [];
              },
            );
            reloadTrustOnResume(ref, adapter);
            return adapter;
          }),
        ],
        retry: noRetry,
      );
      addTearDown(container.dispose);
      container.read(httpAdapterProvider);
      await tester.pump();
      expect(reads, 1, reason: 'read once at start');

      // To the background and back, one step at a time, as the platform reports it.
      [
        AppLifecycleState.inactive,
        AppLifecycleState.hidden,
        AppLifecycleState.paused,
        AppLifecycleState.hidden,
        AppLifecycleState.inactive,
        AppLifecycleState.resumed,
      ].forEach(tester.binding.handleAppLifecycleStateChanged);
      await tester.pump();

      expect(reads, 2);
    });
  });

  group('the other transport controls hold through the Android adapter', () {
    Future<(PhoneTrustAdapter, ServerAddress, List<String>)> pinnedServer() async {
      final seen = <String>[];
      final server = await metaServer(await serverCert('localhost', 'CA1'), seen: seen);
      final address = at(server.port);
      final adapter = phoneWith([ca['CA1']!]);
      adapter.pinTo((await pinFor(adapter, address))! as String);
      seen.clear();
      return (adapter, address, seen);
    }

    Future<ApiClient> clientFor(HttpClientAdapter adapter, ServerAddress address) async {
      final tokens = TokenStore(MemorySecureStore());
      await tokens.save('fixture-token-owner', origin: address.stored);
      return ApiClient(
        server: address,
        adapter: adapter,
        tokens: tokens,
        onSessionEnded: () {},
        onOutcome: (_) {},
        versionConfirmed: () => true,
        logRequests: false,
      );
    }

    test('one shared transport, never closed, through checks, a bearer read and a swap', () async {
      final (adapter, address, seen) = await pinnedServer();
      final api = await clientFor(adapter, address);

      await realSockets(() async {
        await ServerCheck(adapter).check(address);
        await api.getText('/api/auth/me');
        await adapter.reloadPhoneStore(); // nothing changed: no swap needed, none harmful
        adapter // two swaps of the inner client
          ..pinTo(null)
          ..pinTo(fingerprintOf(ca['CA1']!));
        await ServerCheck(adapter).check(address);
      });

      expect(seen, [
        'GET /api/meta null',
        'GET /api/auth/me Bearer fixture-token-owner',
        'GET /api/meta null',
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
        final server = await HttpServer.bindSecure(
          'localhost',
          0,
          await serverCert('localhost', 'CA1'),
        );
        server.listen((request) async {
          request.response
            ..statusCode = HttpStatus.found
            ..headers.set('Location', 'http://127.0.0.1:${elsewhere.port}/steal');
          await request.response.close();
        });
        addTearDown(server.close);
        final address = at(server.port);
        final adapter = phoneWith([ca['CA1']!])..pinTo(fingerprintOf(ca['CA1']!));
        final api = await clientFor(adapter, address);

        final failure = await realSockets(() async {
          try {
            await api.getText('/api/transactions');
            return null;
          } on AppError catch (e) {
            return e;
          }
        });

        expect(failure?.status, HttpStatus.found);
        expect(reachedElsewhere, isEmpty);
      },
    );

    test('the origin guard still refuses another origin before anything is sent', () async {
      final (adapter, address, seen) = await pinnedServer();
      final api = await clientFor(adapter, address);

      final failure = await realSockets(() async {
        try {
          await api.getText('https://127.0.0.1:${address.origin.port}/api/auth/me');
          return null;
        } on AppError catch (e) {
          return e;
        }
      });

      expect(failure, isNotNull);
      expect(seen, isEmpty);
    });

    test('a release build still refuses http:// at the socket through it', () async {
      final adapter = phoneWith([ca['CA1']!])..pinTo(fingerprintOf(ca['CA1']!));
      final error = await realSockets(
        () => runWithCleartextPolicy(() async {
          final dio = Dio(BaseOptions(connectTimeout: const Duration(milliseconds: 300)))
            ..httpClientAdapter = adapter;
          try {
            await dio.getUri<String>(Uri.parse('http://198.51.100.7:8080/api/meta'));
            return null;
          } on DioException catch (e) {
            return e.error;
          }
        }, allowLocalCleartext: false),
      );

      expect(error, isA<UnsupportedError>());
      expect('$error', contains('Non-https connection'));
    });
  });
}

/// One origin (`localhost:<port>`) whose TLS is passed, untouched, to whichever server the test
/// points it at — so the same origin can present another certificate, as an interceptor would.
final class _Front {
  _Front._(this._socket) {
    _socket.listen((client) async {
      final target = _target;
      if (target == null) {
        client.destroy();
        return;
      }
      try {
        final upstream = await Socket.connect('localhost', target);
        _open.addAll([client, upstream]);
        unawaited(client.cast<List<int>>().pipe(upstream).catchError((Object _) {}));
        unawaited(upstream.cast<List<int>>().pipe(client).catchError((Object _) {}));
      } on SocketException {
        client.destroy();
      }
    });
  }

  static Future<_Front> start() async =>
      _Front._(await ServerSocket.bind(InternetAddress.loopbackIPv4, 0));

  final ServerSocket _socket;
  final List<Socket> _open = [];
  int? _target;

  int get port => _socket.port;

  /// New connections go to [server]; open ones are cut, as a network change would.
  void to(HttpServer server) {
    _target = server.port;
    for (final socket in _open) {
      socket.destroy();
    }
    _open.clear();
  }

  Future<void> close() async {
    for (final socket in _open) {
      socket.destroy();
    }
    await _socket.close();
  }
}

/// flutter_test replaces HttpClient for widget tests; these need the real one, with whatever
/// context the adapter under test builds.
final class _RealSockets extends HttpOverrides;
