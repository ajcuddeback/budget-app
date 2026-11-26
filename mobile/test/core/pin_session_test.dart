import 'dart:typed_data';

import 'package:budget_owl/app.dart';
import 'package:budget_owl/core/http/http_adapter.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/server/server_check.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fixture_server.dart';
import '../support/harness.dart';

/// PIN, the session's half: the fingerprint `checkAndPin` chose is stored **with the server
/// address**, given to the transport before anything is sent at every start, kept by a sign-out
/// (so signing back in never re-pins), and cleared — in storage and in the transport — whenever the
/// address is forgotten. The sockets half is user_trust_test.dart.
void main() {
  const pinKey = 'budget_owl.server_ca_pin';
  final pinned = 'c1' * 32;

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

  ServerAddress testServer() => (ServerAddress.parse(
    testServerOrigin,
    allowLocalCleartext: false,
  ) as AddressAccepted).address;

  test('connecting stores the pin the check chose, with the address, and applies it', () async {
    final trust = _Trust(FixtureServer.load('demo', state: 'connect'), pinOnConnect: pinned);
    final phone = TestPhone(server: trust);
    final c = containerFor(phone);
    await until<SessionNoServer>(c);
    trust.events.clear();

    await c.read(sessionProvider.notifier).connect(testServer());

    expect(phone.store.values, {'budget_owl.server': testServerOrigin, pinKey: pinned});
    expect(trust.events, ['checkAndPin', 'pinTo null', 'pinTo $pinned']);
  });

  test('a server the system roots verify stores no pin at all', () async {
    final trust = _Trust(FixtureServer.load('demo', state: 'connect'));
    final phone = TestPhone(server: trust);
    final c = containerFor(phone);
    await until<SessionNoServer>(c);

    await c.read(sessionProvider.notifier).connect(testServer());

    expect(phone.store.values, {'budget_owl.server': testServerOrigin});
  });

  test('every start gives the transport its pin before anything is sent', () async {
    final trust = _Trust(FixtureServer.load('demo', state: 'demo'));
    final phone = TestPhone(server: trust)..signedIn('owner');
    phone.store.values[pinKey] = pinned;
    final c = containerFor(phone);

    await until<SessionSignedIn>(c);

    expect(trust.events.first, 'pinTo $pinned');
    expect(trust.events.where((e) => e.startsWith('fetch')).first, 'fetch /api/meta');
  });

  test('a stored pin that is not a fingerprint is no pin: the system roots only', () async {
    final trust = _Trust(FixtureServer.load('demo', state: 'connect'));
    final phone = TestPhone(server: trust)..connected();
    phone.store.values[pinKey] = 'not-a-fingerprint';
    final c = containerFor(phone);

    await until<SessionSignedOut>(c);

    expect(trust.events.first, 'pinTo null');
  });

  test('a sign-out keeps the pin with the address; a change of server clears both', () async {
    final trust = _Trust(FixtureServer.load('demo', state: 'demo'));
    final phone = TestPhone(server: trust)..signedIn('owner');
    phone.store.values[pinKey] = pinned;
    final c = containerFor(phone);
    await until<SessionSignedIn>(c);

    await c.read(sessionProvider.notifier).signOut();
    expect(phone.store.values, {'budget_owl.server': testServerOrigin, pinKey: pinned});
    trust.events.clear();

    await c.read(sessionProvider.notifier).signOut(forgetServer: true);
    expect(phone.store.values, isEmpty);
    expect(trust.events, contains('pinTo null'));
  });
}

/// A transport that is a [ServerTrust], over the fixture server, recording what the session asked.
final class _Trust implements ServerTrust {
  _Trust(this._server, {this.pinOnConnect});

  final FixtureServer _server;
  final String? pinOnConnect;
  final List<String> events = [];

  @override
  Future<String?> checkAndPin(ServerAddress address) async {
    events.add('checkAndPin');
    await ServerCheck(_server).check(address);
    return pinOnConnect;
  }

  @override
  void pinTo(String? fingerprint) => events.add('pinTo $fingerprint');

  @override
  Future<void> reloadPhoneStore() async => events.add('reload');

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) {
    events.add('fetch ${options.path}');
    return _server.fetch(options, requestStream, cancelFuture);
  }

  @override
  void close({bool force = false}) => _server.close(force: force);
}
