import 'dart:async';
import 'dart:io';

import 'package:budget_owl/core/http/user_trust.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/server/server_check.dart';
import 'package:dio/dio.dart';
import 'package:dio/io.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The transport every request goes through. Overridden in tests by the fixture fake
/// (test/support/fixture_server.dart), which answers only generated exchanges.
///
/// Certificates are verified by the platform's TLS and **never** relaxed: there is no
/// `badCertificateCallback` and no `validateCertificate` here, so an untrusted or misnamed
/// certificate is a refused connection, not a question for the person (ADR-0042: no "trust this
/// certificate anyway" button). A test asserts the client this builds has neither.
///
/// **On Android** it is a [PhoneTrustAdapter]: the system roots, plus — for a server that needed
/// one when it was connected to — the one user-installed CA it is pinned to (user_trust.dart).
/// Elsewhere (iOS, and the tests' host) the platform's default context is used, as before.
///
/// [android], [userCertificates] and [systemRoots] are for tests; the app passes none of them.
HttpClientAdapter platformHttpAdapter({
  bool? android,
  Future<List<Uint8List>> Function()? userCertificates,
  SecurityContext Function()? systemRoots,
}) {
  if (!(android ?? Platform.isAndroid)) {
    return IOHttpClientAdapter(createHttpClient: () => _httpClient(null));
  }
  return PhoneTrustAdapter(
    userCertificates ?? readUserCaCertificates,
    systemRoots: systemRoots ?? systemRootsOnly,
  );
}

HttpClient _httpClient(SecurityContext? context) => HttpClient(context: context)
  // A server that keeps a connection open forever should not keep the phone's radio awake.
  ..idleTimeout = const Duration(seconds: 15)
  // No proxy auto-discovery and no credentials cache: nothing is offered to anyone but the
  // server itself.
  ..findProxy = HttpClient.findProxyFromEnvironment;

/// A transport that decides, per server, which user-installed CA — if any — it trusts.
abstract interface class ServerTrust implements HttpClientAdapter {
  /// The connect-time `GET /api/meta` check of [address], which also decides its trust: null when
  /// the system roots verify it (no user CA is ever added for it), or the one user CA that does —
  /// its fingerprint, and its name for the screen (S1). Throws the check's [ConnectFailure] otherwise, "not trusted" when no CA
  /// on the phone verifies it. Nothing is changed: the caller stores the answer and [pinTo]s it.
  Future<CaPin?> checkAndPin(ServerAddress address);

  /// From now on, trust the system roots and, while it is on the phone, the CA [fingerprint] names.
  /// Null — or anything that is not a fingerprint — is the system roots only.
  void pinTo(String? fingerprint);

  /// Reads the phone's user CAs again: on start and whenever the app returns to the foreground,
  /// since the person may have removed one in Settings meanwhile.
  Future<void> reloadPhoneStore();
}

/// The Android transport (ADR-0042, pinned user CAs).
///
/// Holds the phone's user CAs and the current server's pin, and one inner [IOHttpClientAdapter]
/// whose `HttpClient` trusts the system roots plus the pinned CA **only while it is on the phone**.
/// When either changes so the trust does, the inner adapter is **swapped**: new connections use the
/// new trust, the old one finishes what it has in flight and is closed. This adapter itself is the
/// app's one shared transport and is never closed (mobile/README.md, Gotchas). Every request waits
/// for the latest read of the store, so none is sent under a trust that has just been withdrawn.
final class PhoneTrustAdapter implements ServerTrust {
  PhoneTrustAdapter(
    this._readStore, {
    this.systemRoots = systemRootsOnly,
    this.timeout = userTrustTimeout,
  }) {
    _inner = _clientTrusting(null);
    _pending = reloadPhoneStore();
  }

  final Future<List<Uint8List>> Function() _readStore;

  /// The system's trust store. A test stands a test CA in for it.
  final SecurityContext Function() systemRoots;
  final Duration timeout;

  List<Uint8List> _store = const [];
  String? _pin;
  Uint8List? _anchor;
  int _generation = 0;
  late IOHttpClientAdapter _inner;
  late Future<void> _pending;

  /// The fingerprint this server is pinned to, if any.
  String? get pin => _pin;

  /// Whether the pinned CA is on the phone and trusted now.
  bool get anchored => _anchor != null;

  @override
  Future<void> reloadPhoneStore() {
    final generation = ++_generation;
    final reading = _read().then((certificates) {
      // A later read has started: its answer is the newer one.
      if (generation != _generation) return;
      _store = certificates;
      _apply();
    });
    _pending = reading;
    return reading;
  }

  Future<List<Uint8List>> _read() async {
    try {
      return await _readStore().timeout(timeout);
    } on Object {
      // The store could not be read: no user CA is trusted until it can be (fails closed).
      return const [];
    }
  }

  @override
  void pinTo(String? fingerprint) {
    _pin = isFingerprint(fingerprint) ? fingerprint : null;
    _apply();
  }

  @override
  Future<CaPin?> checkAndPin(ServerAddress address) async {
    await _pending;
    final bySystem = await _probe(address, null);
    if (bySystem == null) return null;
    if (bySystem.problem != ConnectProblem.certificateUntrusted) throw bySystem;
    // Each user CA alone, on top of the system roots: the first that verifies the server is the
    // one it is pinned to. One that verifies the chain but fails otherwise (a wrong name, another
    // API) is that failure, said as such.
    for (final candidate in _store) {
      final failure = await _probe(address, candidate);
      if (failure == null) {
        return CaPin(fingerprintOf(candidate), subject: caDisplayName(candidate));
      }
      if (failure.problem != ConnectProblem.certificateUntrusted) throw failure;
    }
    throw bySystem;
  }

  Future<ConnectFailure?> _probe(ServerAddress address, Uint8List? anchor) async {
    // A throwaway transport of its own, closed when done: not the shared one.
    final probe = _clientTrusting(anchor);
    try {
      await ServerCheck(probe).check(address);
      return null;
    } on ConnectFailure catch (failure) {
      return failure;
    } finally {
      probe.close(force: true);
    }
  }

  void _apply() {
    Uint8List? anchor;
    final pin = _pin;
    if (pin != null) {
      for (final certificate in _store) {
        if (fingerprintOf(certificate) == pin) {
          anchor = certificate;
          break;
        }
      }
    }
    if (_sameAnchor(anchor, _anchor)) return;
    _anchor = anchor;
    final previous = _inner;
    _inner = _clientTrusting(anchor);
    // Finishes what is in flight, then closes its connections: none made under the old trust is
    // used again.
    previous.close();
  }

  static bool _sameAnchor(Uint8List? a, Uint8List? b) =>
      (a == null && b == null) || (a != null && b != null && fingerprintOf(a) == fingerprintOf(b));

  IOHttpClientAdapter _clientTrusting(Uint8List? anchor) => IOHttpClientAdapter(
    createHttpClient: () => _httpClient(contextTrusting(anchor, systemRoots: systemRoots)),
  );

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    // Never fails: a store that cannot be read is read as empty.
    await _pending;
    return await _inner.fetch(options, requestStream, cancelFuture);
  }

  @override
  void close({bool force = false}) => _inner.close(force: force);
}

/// The app's one transport, as [httpAdapterProvider] hands it out (security audit H1).
///
/// Every client — the API client, each server check — sends through this one adapter, and
/// `Dio.close()` closes the adapter it was given: one close once broke every request after it,
/// sign-out's logout included. So closing it is refused **whatever the variable is called**: in a
/// debug build, which every test is, [close] throws; in a release build it does nothing, so the app
/// keeps working. The grep guard in guards_test is the second line.
final class SharedTransport implements HttpClientAdapter {
  const SharedTransport(this.inner);

  /// The platform transport: a [PhoneTrustAdapter] on Android.
  final HttpClientAdapter inner;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) => inner.fetch(options, requestStream, cancelFuture);

  @override
  void close({bool force = false}) {
    if (kDebugMode) {
      throw StateError('the shared transport is never closed (mobile/README.md, Gotchas)');
    }
  }
}

/// The per-server trust [transport] carries, if any: the [ServerTrust] itself, or the one inside
/// the [SharedTransport].
ServerTrust? serverTrustOf(HttpClientAdapter transport) => switch (transport) {
  final ServerTrust trust => trust,
  SharedTransport(:final inner) => serverTrustOf(inner),
  _ => null,
};

/// Re-reads the phone's user CAs whenever the app comes back to the foreground, so a CA removed in
/// Settings meanwhile stops being trusted for every connection made after.
void reloadTrustOnResume(Ref ref, HttpClientAdapter adapter) {
  if (adapter is! ServerTrust) return;
  final listener = AppLifecycleListener(onResume: () => unawaited(adapter.reloadPhoneStore()));
  ref.onDispose(listener.dispose);
}

final httpAdapterProvider = Provider<HttpClientAdapter>((ref) {
  final adapter = platformHttpAdapter();
  reloadTrustOnResume(ref, adapter);
  return SharedTransport(adapter);
});
