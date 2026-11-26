import 'dart:async';
import 'dart:io';

import 'package:budget_owl/core/http/cleartext_policy.dart';
import 'package:budget_owl/core/http/http_adapter.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

/// The socket-level half of "HTTPS only": Flutter's engine refuses an `http://` connection inside
/// the release zone, through the app's own adapter, before any byte is sent.
void main() {
  // TEST-NET-2: never routed, so a connection that is attempted fails slowly rather than
  // reaching anything. The refusal under test happens before any connect.
  final cleartext = Uri.parse('http://198.51.100.7:8080/api/meta');

  Future<Object?> fetchInside({required bool allowLocalCleartext}) =>
      HttpOverrides.runWithHttpOverrides(
        () => runWithCleartextPolicy(() async {
          final dio = Dio(BaseOptions(connectTimeout: const Duration(milliseconds: 300)))
            ..httpClientAdapter = platformHttpAdapter();
          try {
            await dio.getUri<String>(cleartext);
            return null;
          } on DioException catch (e) {
            return e.error;
          }
        }, allowLocalCleartext: allowLocalCleartext),
        _RealSockets(),
      );

  test('a release build refuses http:// at the socket, before connecting', () async {
    final error = await fetchInside(allowLocalCleartext: false);

    expect(error, isA<UnsupportedError>());
    expect('$error', contains('Non-https connection'));
  });

  test('a debug build leaves the socket alone (ServerAddress.parse limits it)', () async {
    final error = await fetchInside(allowLocalCleartext: true);

    expect(error, isNot(isA<UnsupportedError>()));
  });

  test('the release zone carries the flag the engine reads', () {
    expect(
      runWithCleartextPolicy(() => Zone.current[allowHttpZoneKey], allowLocalCleartext: false),
      isFalse,
    );
    expect(
      runWithCleartextPolicy(() => Zone.current[allowHttpZoneKey], allowLocalCleartext: true),
      isNull,
    );
  });
}

/// flutter_test replaces HttpClient for widget tests; this needs the real one, and its hook.
final class _RealSockets extends HttpOverrides {}
