import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';

import 'fixture_server.dart';

/// A transport whose every answer a unit test writes, for the shapes no real server sends — a
/// redirect, a 4 MB body, a handshake failure — and which records what it was asked.
///
/// Not for financial figures: those come from the generated fixtures (FixtureServer).
final class ScriptedAdapter implements HttpClientAdapter {
  ScriptedAdapter(this.answer);

  final Future<ResponseBody> Function(RequestOptions options) answer;
  final List<RequestOptions> requests = [];

  /// Whether anything closed this transport. The app shares one adapter across every client, so a
  /// close is a bug: the real adapter refuses every request after it.
  bool closed = false;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) {
    if (closed) {
      throw StateError("Can't establish connection after the adapter was closed (as the real one)");
    }
    requests.add(options);
    return answer(options);
  }

  @override
  void close({bool force = false}) => closed = true;
}

ResponseBody json(int status, Object? body, {Map<String, String> headers = const {}}) =>
    ResponseBody.fromString(
      body == null ? '' : jsonEncode(body),
      status,
      headers: {
        Headers.contentTypeHeader: ['application/json'],
        for (final h in headers.entries) h.key.toLowerCase(): [h.value],
      },
    );

ResponseBody text(int status, String body, {Map<String, String> headers = const {}}) =>
    ResponseBody.fromString(
      body,
      status,
      headers: {
        for (final h in headers.entries) h.key.toLowerCase(): [h.value],
      },
    );

/// The fixture server, with `/api/meta` answered by the test, recording what was written.
final class MetaGate implements HttpClientAdapter {
  MetaGate(this._fixture, this._meta);

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
