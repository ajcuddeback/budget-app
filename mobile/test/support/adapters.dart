import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';

/// A transport whose every answer a unit test writes, for the shapes no real server sends — a
/// redirect, a 4 MB body, a handshake failure — and which records what it was asked.
///
/// Not for financial figures: those come from the generated fixtures (FixtureServer).
final class ScriptedAdapter implements HttpClientAdapter {
  ScriptedAdapter(this.answer);

  final Future<ResponseBody> Function(RequestOptions options) answer;
  final List<RequestOptions> requests = [];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) {
    requests.add(options);
    return answer(options);
  }

  @override
  void close({bool force = false}) {}
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
