import 'dart:io';

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
HttpClientAdapter platformHttpAdapter() => IOHttpClientAdapter(
  createHttpClient: () => HttpClient()
    // A server that keeps a connection open forever should not keep the phone's radio awake.
    ..idleTimeout = const Duration(seconds: 15)
    // No proxy auto-discovery and no credentials cache: nothing is offered to anyone but the
    // server itself.
    ..findProxy = HttpClient.findProxyFromEnvironment,
);

final httpAdapterProvider = Provider<HttpClientAdapter>((ref) => platformHttpAdapter());
