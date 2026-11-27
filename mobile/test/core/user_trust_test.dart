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

  group("S1: a CA's name, for the screen and nothing else", () {
    /// A self-signed certificate with [subject], as DER.
    Future<Uint8List> certificateFor(String subject, String file) async {
      await openssl([
        'req',
        '-x509',
        '-newkey',
        'rsa:2048',
        '-nodes',
        '-utf8',
        '-keyout',
        '$file.key',
        '-out',
        '$file.pem',
        '-days',
        '1',
        '-subj',
        subject,
      ]);
      await openssl(['x509', '-in', '$file.pem', '-outform', 'DER', '-out', '$file.der']);
      return File('${pki.path}/$file.der').readAsBytesSync();
    }

    test('the subject CN, else the O, else nothing', () async {
      expect(caDisplayName(ca['CA1']!), 'Budget Owl test CA1');
      expect(
        caDisplayName(await certificateFor('/O=Rivera Household/CN=Rivera Household CA', 'both')),
        'Rivera Household CA',
      );
      expect(
        caDisplayName(await certificateFor('/C=GB/O=Rivera Household', 'org')),
        'Rivera Household',
      );
      expect(caDisplayName(await certificateFor('/C=GB/L=Leeds', 'none')), isNull);
      expect(
        caDisplayName(await certificateFor('/CN=Ålesund Hjemme-CA', 'utf8')),
        'Ålesund Hjemme-CA',
      );
    });

    test('a name cannot reorder or break the sentence it is shown in', () async {
      final hostile = await certificateFor('/CN=Acme\u202E AC noitcepsnI\n here', 'hostile');
      expect(caDisplayName(hostile), 'Acme AC noitcepsnI here', reason: 'no override, no newline');
      expect(cleanCaName('a${'b' * 100}')!.runes.length, caNameMaxLength);
      expect(cleanCaName('  \t '), isNull);
      expect(caDisplayName(Uint8List.fromList([0x30, 0x82, 0xff, 0xff, 0x01])), isNull);
      expect(caDisplayName(const []), isNull);
    });

    test('never in a log line: a pin prints without its name', () {
      const pin = CaPin(
        'c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1',
        subject: 'Acme',
      );
      expect('$pin', isNot(contains('Acme')));
    });
  });

  group('T1: a CA name keeps only letters, digits, spaces and punctuation that is not a quote', () {
    String chars(List<int> runes) => String.fromCharCodes(runes);

    /// 'A', [rune], 'B': what is left of a name with [rune] between two letters.
    String? between(int rune) => cleanCaName(chars([0x41, rune, 0x42]));

    test('a quote cannot be closed: the ASCII ones and every look-alike are dropped', () {
      expect(
        cleanCaName("Home CA', not one you installed. Nothing to check"),
        'Home CA, not one you installed. Nothing to check',
      );
      expect(cleanCaName('Home CA", "trusted'), 'Home CA, trusted');
      for (final quote in const [
        0x22, 0x27, 0x60, 0xAB, 0xB4, 0xBB, 0x2B9, 0x2BB, 0x2BC, 0x2C8, 0x5F3, 0x5F4, //
        0x2018, 0x2019, 0x201A, 0x201B, 0x201C, 0x201D, 0x201E, 0x201F, 0x2032, 0x2033,
        0x2039, 0x203A, 0x2E42, 0x300C, 0x300D, 0x300E, 0x301D, 0x301E, 0xA78B, 0xA78C,
        0xFE41, 0xFF02, 0xFF07, 0xFF62,
      ]) {
        expect(between(quote), 'AB', reason: 'U+${quote.toRadixString(16)}');
      }
    });

    test('format characters: direction marks, overrides and isolates, and the rest of Cf', () {
      for (final format in const [
        0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, //
        0x2069, 0x061C, 0x00AD, 0x180E, 0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0xFFF9,
        0xFFFA, 0xFFFB, 0x0600, 0x110BD,
      ]) {
        expect(between(format), 'AB', reason: 'U+${format.toRadixString(16)}');
      }
    });

    test('invisible letters and marks: Hangul fillers, the grapheme joiner, selectors', () {
      for (final invisible in const [
        0x115F, 0x1160, 0x3164, 0xFFA0, 0x034F, 0x17B4, 0x180B, 0xFE00, 0xFE0F, 0xE0100, //
        0xE01EF,
      ]) {
        expect(between(invisible), 'AB', reason: 'U+${invisible.toRadixString(16)}');
      }
    });

    test('tag characters, private use, unassigned, surrogates and controls', () {
      for (final other in const [
        0xE0001, 0xE0020, 0xE0041, 0xE007F, 0xE000, 0xF8FF, 0xF0000, 0x10FFFD, 0x0378, //
        0x10FFFF, 0xFFFF, 0xD800, 0xDC00, 0x00, 0x07, 0x1B, 0x7F, 0x85, 0x9B,
      ]) {
        expect(between(other), 'AB', reason: 'U+${other.toRadixString(16)}');
      }
    });

    test('symbols are dropped; whitespace of any kind is one space', () {
      expect(cleanCaName(chars([0x41, 0xA9, 0x2B, 0x1F512, 0x2800, 0x42])), 'AB');
      expect(cleanCaName(chars([0x41, 0x0A, 0x09, 0x3000, 0x2028, 0xA0, 0x42])), 'A B');
      expect(cleanCaName(chars([0x20, 0x41, 0x20, 0x20])), 'A');
    });

    test('combining marks: at most two on a base, none without one', () {
      expect(cleanCaName(chars([0x65, ...List.filled(40, 0x301)])), chars([0x65, 0x301, 0x301]));
      expect(cleanCaName(chars([0x301, 0x41])), 'A', reason: 'a mark with no base');
      expect(cleanCaName(chars([0x41, 0x20, 0x301, 0x42])), 'A B', reason: 'not on a space');
      expect(cleanCaName('Ålesund Hjemme-CA'), 'Ålesund Hjemme-CA');
      expect(cleanCaName(chars([0x41, 0x30A, 0x42])), chars([0x41, 0x30A, 0x42]));
    });

    test('strong right-to-left letters are kept: the screen isolates the name instead', () {
      expect(cleanCaName('רשות אישורים ביתית'), 'רשות אישורים ביתית');
      expect(cleanCaName('مرجع الشهادات (المنزل)'), 'مرجع الشهادات (المنزل)');
    });

    test('nothing left is no name at all', () {
      expect(cleanCaName(chars([0x202E, 0x3164, 0x27, 0x20, 0xE0041])), isNull);
      expect(cleanCaName(''), isNull);
      expect(cleanCaName(null), isNull);
    });
  });

  group('T1: a malformed certificate has no name, so the fingerprint is shown', () {
    List<int> length(int n) => n < 0x80
        ? [n]
        : n < 0x100
        ? [0x81, n]
        : [0x82, n >> 8, n & 0xff];
    List<int> tlv(int tag, List<int> content) => [tag, ...length(content.length), ...content];
    List<int> seq(List<int> content) => tlv(0x30, content);
    const cn = [0x06, 0x03, 0x55, 0x04, 0x03];

    /// A subject of one CN whose value is [value], already encoded.
    List<int> subjectOf(List<int> value) => seq(tlv(0x31, seq([...cn, ...value])));

    /// A certificate whose `tbsCertificate` is [tbs] (header included): what follows it in the
    /// certificate is the signature algorithm and signature.
    List<int> certificateAround(List<int> tbs) => seq([
      ...tbs,
      ...seq(tlv(0x06, [0x2A])),
      ...tlv(0x03, [0x00]),
    ]);
    List<int> tbsContent(List<int> subject, {List<int>? serial}) => [
      ...tlv(0xA0, tlv(0x02, [0x02])),
      ...serial ?? tlv(0x02, [0x01]),
      ...seq(tlv(0x06, [0x2A])),
      ...subjectOf(tlv(0x0C, utf8.encode('Issuer'))),
      ...seq([]),
      ...subject,
    ];
    List<int> certificateWith(List<int> subject, {List<int>? serial}) =>
        certificateAround(seq(tbsContent(subject, serial: serial)));
    final name = subjectOf(tlv(0x0C, utf8.encode('Hand CA')));

    test('the hand-built certificate itself is read (so each failure below is the change)', () {
      expect(caDisplayName(certificateWith(name)), 'Hand CA');
      expect(caDisplayName(certificateWith(subjectOf(tlv(0x1C, [0, 0, 0, 0x41])))), 'A');
      expect(caDisplayName(certificateWith(subjectOf(tlv(0x1E, [0, 0x41])))), 'A');
    });

    test('an indefinite length (0x80)', () {
      final content = tbsContent(name);
      expect(caDisplayName(certificateAround([0x30, 0x80, ...content, 0x00, 0x00])), isNull);
    });

    test('a length field of more than 4 bytes', () {
      final content = tbsContent(name);
      final n = content.length;
      expect(
        caDisplayName(certificateAround([0x30, 0x85, 0, 0, 0, n >> 8, n & 0xff, ...content])),
        isNull,
      );
    });

    test('a child running past its parent', () {
      // The CN's value claims two bytes more than its attribute holds: they are there, in the
      // signature algorithm after it, but not inside the attribute.
      final value = [0x0C, 7 + 2, ...utf8.encode('Hand CA')];
      final attribute = [0x30, cn.length + value.length, ...cn, ...value];
      final subject = seq(tlv(0x31, attribute));
      expect(caDisplayName(certificateWith(subject)), isNull);
    });

    test('an odd-length BMPString', () {
      expect(caDisplayName(certificateWith(subjectOf(tlv(0x1E, [0, 0x41, 0])))), isNull);
    });

    test('a UniversalString above U+10FFFF', () {
      expect(caDisplayName(certificateWith(subjectOf(tlv(0x1C, [0, 0x11, 0, 0])))), isNull);
      expect(caDisplayName(certificateWith(subjectOf(tlv(0x1C, [0x7F, 0, 0, 0x41])))), isNull);
    });

    test('a multi-byte tag', () {
      // Tag number 128 in the high-tag form, as the serial number: no certificate field has one.
      expect(caDisplayName(certificateWith(name, serial: [0x1F, 0x81, 0x00, 0x01, 0x00])), isNull);
      expect(caDisplayName(certificateWith(subjectOf([0x1F, 0x0C, 0x01, 0x41]))), isNull);
      // Read as a one-byte tag, [0x1F, 0x05] is tag 0x1F with five bytes of content, and the
      // fields after it line up again: a name would be read. The tag itself is refused.
      expect(
        caDisplayName(certificateWith(name, serial: [0x1F, 0x05, 0x00, 0x02, 0x01, 0x01, 0x00])),
        isNull,
      );
    });
  });

  group('T2, T3: the per-server trust', () {
    test('T2: the adapter inside the shared transport refuses to be closed too', () {
      final adapter = phoneWith([ca['CA1']!]);
      expect(adapter.close, throwsStateError, reason: 'a debug build, as every test is');
      expect(() => adapter.close(force: true), throwsStateError);
      final trust = serverTrustOf(SharedTransport(adapter))!;
      expect(trust.close, throwsStateError, reason: 'serverTrustOf hands out this adapter');
      final dio = Dio()..httpClientAdapter = trust;
      expect(dio.close, throwsStateError, reason: 'Dio.close() closes its adapter');
    });

    test('T3: whether the pinned CA is on the phone, once the store has been read', () async {
      final store = [ca['CA1']!];
      final firstRead = Completer<void>();
      final adapter = PhoneTrustAdapter(() async {
        await firstRead.future;
        return List.of(store);
      }, systemRoots: systemWithCa0);
      final installed = adapter.pinnedCaInstalled;
      expect(installed.value, isNull, reason: 'nothing pinned');

      adapter.pinTo(fingerprintOf(ca['CA1']!));
      expect(installed.value, isNull, reason: 'the store has not been read: nothing is claimed');
      firstRead.complete();
      await adapter.reloadPhoneStore();
      expect(installed.value, isTrue);

      store.clear(); // removed in Settings
      await adapter.reloadPhoneStore();
      expect(installed.value, isFalse);
      expect(adapter.anchored, isFalse);

      store.add(ca['CA1']!);
      await adapter.reloadPhoneStore();
      expect(installed.value, isTrue);

      adapter.pinTo(null);
      expect(installed.value, isNull, reason: 'nothing pinned any more');
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

      final pin = (await pinFor(adapter, address))! as CaPin;
      expect(pin.fingerprint, fingerprintByOpenssl['CA1']);
      expect(pin.subject, 'Budget Owl test CA1', reason: 'S1: its name, for the screen');
      adapter.pinTo(pin.fingerprint);
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
        adapter.pinTo(((await pinFor(adapter, address))! as CaPin).fingerprint);
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
      expect((pin! as CaPin).fingerprint, fingerprintByOpenssl['CA1']);
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
      adapter.pinTo(((await pinFor(adapter, address))! as CaPin).fingerprint);
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
