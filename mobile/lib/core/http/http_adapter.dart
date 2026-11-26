import 'dart:io';
import 'dart:typed_data';

import 'package:budget_owl/core/http/user_trust.dart';
import 'package:dio/dio.dart';
import 'package:dio/io.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The transport every request goes through. Overridden in tests by the fixture fake
/// (test/support/fixture_server.dart), which answers only generated exchanges.
///
/// Certificates are verified by the platform's TLS and **never** relaxed: there is no
/// `badCertificateCallback` and no `validateCertificate` here, so an untrusted or misnamed
/// certificate is a refused connection, not a question for the person (ADR-0042: no "trust this
/// certificate anyway" button). A test asserts the client this builds has neither.
///
/// **On Android** the trust anchors are the system roots plus the CAs the person installed on the
/// phone (user_trust.dart), read once, before the first request is sent; every connection then
/// uses that context. A store that cannot be read means the system roots alone. Elsewhere (iOS,
/// and the tests' host) the platform's default context is used, as before.
///
/// [android] and [userCertificates] are for tests; the app passes neither.
HttpClientAdapter platformHttpAdapter({
  bool? android,
  Future<List<Uint8List>> Function()? userCertificates,
}) {
  if (!(android ?? Platform.isAndroid)) {
    return IOHttpClientAdapter(createHttpClient: () => _httpClient(null));
  }
  return _PhoneTrustAdapter(phoneTrustContext(userCertificates ?? readUserCaCertificates));
}

HttpClient _httpClient(SecurityContext? context) => HttpClient(context: context)
  // A server that keeps a connection open forever should not keep the phone's radio awake.
  ..idleTimeout = const Duration(seconds: 15)
  // No proxy auto-discovery and no credentials cache: nothing is offered to anyone but the
  // server itself.
  ..findProxy = HttpClient.findProxyFromEnvironment;

/// The Android transport: waits once for the phone's trust, then is an [IOHttpClientAdapter]
/// whose one `HttpClient` is built with it. Closing it closes that adapter (only tests do).
final class _PhoneTrustAdapter implements HttpClientAdapter {
  _PhoneTrustAdapter(Future<SecurityContext?> trust) {
    // Whatever happens building the context, the answer is a context or the system roots.
    _ready = trust.then<void>(
      (context) => _context = context,
      onError: (Object _) => _context = null,
    );
    _inner = IOHttpClientAdapter(createHttpClient: () => _httpClient(_context));
  }

  SecurityContext? _context;
  late final Future<void> _ready;
  late final IOHttpClientAdapter _inner;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    // Never fails: phoneTrustContext turns every failure into "system roots only".
    await _ready;
    return await _inner.fetch(options, requestStream, cancelFuture);
  }

  @override
  void close({bool force = false}) => _inner.close(force: force);
}

final httpAdapterProvider = Provider<HttpClientAdapter>((ref) => platformHttpAdapter());
