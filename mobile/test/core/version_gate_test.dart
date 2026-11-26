import 'dart:async';
import 'dart:typed_data';

import 'package:budget_owl/app.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:budget_owl/l10n/gen/app_localizations_en.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/adapters.dart';
import '../support/fixture_server.dart';
import '../support/harness.dart';

/// R2: **no write before `/api/meta` has passed this launch**, and a failed check fails closed.
///
/// A member with a cached copy sees their screens before the check answers, and reads go on from
/// the cache and the server whatever it says. Writes do not: `canWriteNow` and every writer's
/// `reachabilityProvider.canWrite` say no until it passes, the API client refuses a write at the
/// transport besides, and a busy or silent server (a stranger can force "busy" through the shared
/// rate limit) keeps them off rather than skipping the gate. The next request that reaches the
/// server tries the check again.
void main() {
  final l = AppLocalizationsEn();
  const ledger = {'page': '0', 'size': '50'};

  ProviderContainer containerFor(TestPhone phone) {
    final container = ProviderContainer(overrides: phone.overrides, retry: noRetry);
    addTearDown(container.dispose);
    addTearDown(() {
      if (phone.cacheDirectory.existsSync()) phone.cacheDirectory.deleteSync(recursive: true);
    });
    return container;
  }

  Future<void> eventually(bool Function() condition, String what) async {
    for (var i = 0; i < 400; i++) {
      if (condition()) return;
      await Future<void>.delayed(const Duration(milliseconds: 5));
    }
    fail('never: $what');
  }

  /// A phone that signed in once (so it has a cached member), relaunched against [meta]'s answers
  /// for `/api/meta` and the fixture's for everything else.
  Future<(ProviderContainer, _MetaGate)> relaunch(
    Future<ResponseBody> Function(RequestOptions) meta, {
    bool cached = true,
  }) async {
    final fixture = FixtureServer.load('demo', state: 'demo');
    final phone = TestPhone(server: fixture)..signedIn('owner');
    if (cached) {
      final first = ProviderContainer(overrides: phone.overrides, retry: noRetry);
      while (first.read(sessionProvider) is! SessionSignedIn) {
        await Future<void>.delayed(const Duration(milliseconds: 5));
      }
      first.dispose();
      fixture.served.clear();
    }
    final gate = _MetaGate(fixture, meta);
    final again = TestPhone(
      server: gate,
      store: phone.store,
      cacheDirectory: phone.cacheDirectory,
      supportDirectory: phone.supportDirectory,
    );
    // The app starts: a provider is built when it is first read.
    final c = containerFor(again)..read(sessionProvider);
    return (c, gate);
  }

  /// Until no check has been out for a few turns: the launch check, and any retry it provoked.
  Future<void> settleChecks(_MetaGate gate) async {
    var quiet = 0;
    for (var i = 0; i < 400 && quiet < 10; i++) {
      quiet = gate.metaInFlight ? 0 : quiet + 1;
      await Future<void>.delayed(const Duration(milliseconds: 5));
    }
  }

  Future<ResponseBody> passes(RequestOptions _) async =>
      json(200, {'app': 'budget-owl', 'apiVersion': 1, 'minimumClientApiVersion': 1});

  Future<Object?> attemptWrite(ProviderContainer c) async {
    try {
      await c.read(apiClientProvider)!.write('POST', '/api/transactions', body: {'payee': 'x'});
      return null;
    } on AppError catch (e) {
      return e;
    }
  }

  test('with a cached member, writes stay off until /api/meta has answered', () async {
    final answer = Completer<ResponseBody>();
    final (c, gate) = await relaunch((_) => answer.future);

    await eventually(() => c.read(sessionProvider) is SessionSignedIn, 'the cached member shows');
    // A screen's read reaches the server while the check is still out.
    await c.read(apiClientProvider)!.getText('/api/transactions', query: ledger);

    expect(c.read(reachabilityProvider), Reachability.unconfirmed);
    expect(c.read(reachabilityProvider).canWrite, isFalse);
    expect((await attemptWrite(c) as AppError?)?.kind, AppErrorKind.offline);
    expect(gate.writes, isEmpty, reason: 'the transport refused it before sending');

    answer.complete(await passes(RequestOptions()));
    await eventually(() => c.read(reachabilityProvider).canWrite, 'writes open on a pass');
    expect(gate.metaRequests, 1);
  });

  for (final (name, failure) in <(String, Future<ResponseBody> Function(RequestOptions))>[
    ('busy (429)', (_) async => json(429, {'code': 'rate-limited'})),
    (
      'timed out',
      (o) async => throw DioException.connectionTimeout(
        timeout: const Duration(seconds: 10),
        requestOptions: o,
      ),
    ),
    ('not answering as Budget Owl', (_) async => text(503, 'Bad gateway')),
  ]) {
    test(
      'a check that is $name keeps writes off, reads on, and is retried on the next refresh',
      () async {
        var failing = true;
        final (c, gate) = await relaunch((o) => failing ? failure(o) : passes(o));

        await eventually(() => gate.served.contains('GET /api/auth/me'), 'the member is re-read');
        await settleChecks(gate);

        expect(c.read(sessionProvider), isA<SessionSignedIn>(), reason: 'reads go on');
        expect(c.read(reachabilityProvider), Reachability.unconfirmed);
        expect((await attemptWrite(c) as AppError?)?.kind, AppErrorKind.offline);
        expect(gate.writes, isEmpty);
        final before = gate.metaRequests;

        // The server is checkable again; the member pulls to refresh.
        failing = false;
        await c.read(apiClientProvider)!.getText('/api/transactions', query: ledger);

        await eventually(() => c.read(reachabilityProvider).canWrite, 'writes open after a retry');
        expect(gate.metaRequests, before + 1, reason: 'the refresh retried the check, once');
      },
    );
  }

  test('reads that reach the server while a check is out start no second check', () async {
    final answer = Completer<ResponseBody>();
    final (c, gate) = await relaunch((_) => answer.future);
    await eventually(() => c.read(sessionProvider) is SessionSignedIn, 'the cached member shows');
    final api = c.read(apiClientProvider)!;

    await Future.wait([
      for (var i = 0; i < 5; i++) api.getText('/api/transactions', query: ledger),
    ]);

    expect(gate.metaRequests, 1);
    answer.complete(await passes(RequestOptions()));
    await eventually(() => c.read(reachabilityProvider).canWrite, 'writes open on a pass');
  });

  test('with no cached member, a failed check still lets the member in, read-only', () async {
    final (c, gate) = await relaunch(
      (_) async => json(429, {'code': 'rate-limited'}),
      cached: false,
    );

    await eventually(() => c.read(sessionProvider) is SessionSignedIn, 'signed in from the server');
    await settleChecks(gate);

    expect(c.read(reachabilityProvider), Reachability.unconfirmed);
    expect((await attemptWrite(c) as AppError?)?.kind, AppErrorKind.offline);
    expect(gate.writes, isEmpty);
  });

  test('a retry that finds a new API stops the app talking to the server', () async {
    var version = 0;
    final (c, gate) = await relaunch((_) async {
      version++;
      return version == 1
          ? json(429, {'code': 'rate-limited'})
          : json(200, {'app': 'budget-owl', 'apiVersion': 2, 'minimumClientApiVersion': 2});
    });
    await eventually(() => gate.served.contains('GET /api/auth/me'), 'the member is re-read');
    await settleChecks(gate);

    await c.read(apiClientProvider)!.getText('/api/transactions', query: ledger);

    await eventually(() => c.read(sessionProvider) is SessionUnsupported, 'the mismatch is shown');
    expect(c.read(reachabilityProvider).canWrite, isFalse);
  });

  group('signing in', () {
    Future<(ProviderContainer, _MetaGate)> signedOutPhone(
      Future<ResponseBody> Function(RequestOptions) meta,
    ) async {
      final fixture = FixtureServer.load('demo', state: 'connect');
      final gate = _MetaGate(fixture, meta);
      final c = containerFor(TestPhone(server: gate)..connected());
      await eventually(() => c.read(sessionProvider) is SessionSignedOut, 'signed out');
      await settleChecks(gate);
      return (c, gate);
    }

    Future<void> signIn(ProviderContainer c) {
      final body = fixtureRequest('demo', state: 'connect', path: '/api/auth/token', then: 'demo');
      return c
          .read(sessionProvider.notifier)
          .signIn(
            email: body['email']! as String,
            password: body['password']! as String,
            deviceLabel: body['deviceLabel']! as String,
          );
    }

    test('the password is not sent while the version cannot be confirmed', () async {
      final (c, gate) = await signedOutPhone((_) async => json(429, {'code': 'rate-limited'}));

      await expectLater(signIn(c), throwsA(isA<AppError>()));

      expect(gate.writes, isEmpty, reason: 'no POST /api/auth/token');
      expect(gate.metaRequests, 2, reason: 'the launch check, and one more at sign-in');
    });

    test('a launch check that failed is tried again at sign-in, and then it goes ahead', () async {
      var calls = 0;
      final (c, gate) = await signedOutPhone(
        (o) => calls++ == 0 ? Future.value(json(429, {'code': 'rate-limited'})) : passes(o),
      );

      await signIn(c);

      expect(c.read(sessionProvider), isA<SessionSignedIn>());
      expect(gate.writes, ['POST /api/auth/token']);
    });
  });

  group('the note under a disabled write control says why', () {
    Future<ProviderContainer> pumpNote(WidgetTester tester) async {
      final container = ProviderContainer(retry: noRetry);
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const MaterialApp(
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            home: Scaffold(body: OfflineNote()),
          ),
        ),
      );
      return container;
    }

    testWidgets('the version not confirmed, offline, or nothing at all', (tester) async {
      final c = await pumpNote(tester);
      final reach = c.read(reachabilityProvider.notifier)..record(Reachability.reached);
      await tester.pump();
      expect(find.text(l.offline_versionUnconfirmed), findsOneWidget);

      reach.confirmVersion();
      await tester.pump();
      expect(find.byType(NoticeBanner), findsNothing);

      reach.record(Reachability.offline);
      await tester.pump();
      expect(find.text(l.offline_write), findsOneWidget);
    });
  });

  test('the gate: a request that reached is a write only once the version is confirmed', () {
    final c = ProviderContainer(retry: noRetry);
    addTearDown(c.dispose);
    final reach = c.read(reachabilityProvider.notifier);

    expect(c.read(reachabilityProvider), Reachability.unknown);
    reach.record(Reachability.reached);
    expect(c.read(reachabilityProvider), Reachability.unconfirmed);
    reach.confirmVersion();
    expect(c.read(reachabilityProvider), Reachability.reached);
    reach.record(Reachability.noAnswer);
    expect(c.read(reachabilityProvider), Reachability.noAnswer);
    reach.record(Reachability.reached);
    expect(c.read(reachabilityProvider).canWrite, isTrue);
    reach.unconfirmVersion();
    expect(c.read(reachabilityProvider), Reachability.unconfirmed);
    expect(Reachability.unconfirmed.canWrite, isFalse);
  });
}

/// The fixture server, with `/api/meta` answered by the test, recording what was written.
final class _MetaGate implements HttpClientAdapter {
  _MetaGate(this._fixture, this._meta);

  final FixtureServer _fixture;
  final Future<ResponseBody> Function(RequestOptions) _meta;
  int metaRequests = 0;
  bool metaInFlight = false;
  final List<String> writes = [];

  List<String> get served => _fixture.served;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (options.path.endsWith('/api/meta')) {
      metaRequests++;
      metaInFlight = true;
      try {
        return await _meta(options);
      } finally {
        metaInFlight = false;
      }
    }
    if (options.method != 'GET') writes.add('${options.method} ${options.uri.path}');
    return await _fixture.fetch(options, requestStream, cancelFuture);
  }

  @override
  void close({bool force = false}) => _fixture.close(force: force);
}
