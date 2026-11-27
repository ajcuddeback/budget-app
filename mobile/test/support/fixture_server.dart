import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

/// The phone's fake server: it answers **only** the exchanges `DemoFixtureIT` recorded against the
/// real application, and fails the test on anything else.
///
/// The mobile twin of the web harness's mock, which answers an unknown route with a loud `501`.
/// Here the same `501` comes back *and* the request is recorded as a miss, and every test that
/// builds a server checks there were none when it ends — a screen that asked for something nobody
/// generated cannot pass by rendering an error state nobody looked at.
///
/// An exchange matches on state, method, path and query (in any order), the bearer's owner, any
/// required header, and the JSON body exactly. A matched write moves the server to the state its
/// recording left the real one in, so a test sees the ledger the API would have shown it.
final class FixtureServer implements HttpClientAdapter {
  FixtureServer._(this._exchanges, this.state, this.origin);

  /// Loads `test/fixtures/<name>.generated.json`, starting in [state], serving [origin].
  factory FixtureServer.load(
    String name, {
    required String state,
    String origin = testServerOrigin,
  }) {
    final file = File('test/fixtures/$name.generated.json');
    final decoded = jsonDecode(file.readAsStringSync()) as Map<String, Object?>;
    final exchanges = [
      for (final e in decoded['exchanges']! as List<Object?>)
        _Exchange.fromJson(e! as Map<String, Object?>),
    ];
    _refuseAmbiguity(name, exchanges);
    final server = FixtureServer._(exchanges, state, Uri.parse(origin));
    addTearDown(server.verifyNoMisses);
    return server;
  }

  /// Two recordings of the same request in the same state with different answers would make the
  /// fake answer whichever comes first — a test passing or failing on file order. Refused.
  static void _refuseAmbiguity(String name, List<_Exchange> exchanges) {
    final answers = <String, String>{};
    for (final e in exchanges) {
      final query = e.uri.queryParameters.entries.toList()..sort((a, b) => a.key.compareTo(b.key));
      final headers = e.requestHeaders.entries.toList()..sort((a, b) => a.key.compareTo(b.key));
      final key = jsonEncode([
        e.state,
        e.method,
        e.uri.path,
        [for (final q in query) '${q.key}=${q.value}'],
        e.auth,
        [for (final h in headers) '${h.key}=${h.value}'],
        e.request,
      ]);
      final answer = jsonEncode([e.status, e.body, e.then, e.responseHeaders]);
      final earlier = answers.putIfAbsent(key, () => answer);
      if (earlier != answer) {
        throw StateError('$name.generated.json answers $key two different ways');
      }
    }
  }

  final List<_Exchange> _exchanges;
  final Uri origin;

  /// The state the next request is answered in.
  String state;

  /// Requests that matched nothing. Checked at tear-down.
  final List<String> misses = [];

  /// Every request answered, in order: `METHOD /path?query`.
  final List<String> served = [];

  /// The `Authorization` header of every request, or null where there was none.
  final List<String?> authorizations = [];

  /// Whether anything closed this transport. The app shares one adapter, so closing it breaks every
  /// later request — as `IOHttpClientAdapter` does. A request after it is a miss and an error.
  bool closed = false;

  /// While set, every request fails as a phone with no network does.
  bool offline = false;

  /// While set, every request fails as a server that does not answer does.
  bool noAnswer = false;

  /// Goes offline as soon as an exchange whose `METHOD /path` starts with this has been served —
  /// for a screen that must meet a failure on its own first read.
  String? offlineAfter;

  void verifyNoMisses() {
    expect(
      misses,
      isEmpty,
      reason:
          'the app asked for exchanges no fixture records. Generate them with DemoFixtureIT '
          '(mvn -f backend test -Dtest=DemoFixtureIT -Dfixtures.write=true) rather than writing '
          'a response by hand.',
    );
  }

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (closed) {
      misses.add('${options.method} ${options.uri.path} — after the shared adapter was closed');
      throw StateError("Can't establish connection after the adapter was closed (as the real one)");
    }
    if (offline) {
      throw DioException.connectionError(
        requestOptions: options,
        reason: 'Failed host lookup',
        error: const SocketException('Failed host lookup: no network'),
      );
    }
    if (noAnswer) {
      throw DioException.connectionTimeout(
        requestOptions: options,
        timeout: const Duration(seconds: 10),
      );
    }

    final bytes = <int>[];
    if (requestStream != null) await requestStream.forEach(bytes.addAll);
    final Object? body = bytes.isEmpty ? null : jsonDecode(utf8.decode(bytes));
    final uri = options.uri;
    final auth = options.headers['Authorization'] as String?;
    authorizations.add(auth);
    final described =
        '$state: ${options.method} ${uri.path}'
        '${uri.hasQuery ? '?${uri.query}' : ''}${body == null ? '' : ' ${jsonEncode(body)}'}';

    if (uri.origin != origin.origin) {
      misses.add('$described — sent to ${uri.origin}, not the test server');
      return _miss();
    }
    final match = _find(options, uri, body, auth);
    if (match == null) {
      misses.add(described);
      return _miss();
    }
    final line = '${options.method} ${uri.path}${uri.hasQuery ? '?${uri.query}' : ''}';
    served.add(line);
    if (offlineAfter != null && line.startsWith(offlineAfter!)) offline = true;
    if (match.then != null) state = match.then!;
    // A recorded string is a text representation (a report's CSV), served as the bytes the API
    // sent; anything else is a JSON document. No API route answers a bare JSON string.
    final answer = match.body;
    return ResponseBody.fromString(
      answer == null
          ? ''
          : answer is String
          ? answer
          : jsonEncode(answer),
      match.status,
      headers: {
        Headers.contentTypeHeader: [
          if (match.status >= 400) 'application/problem+json' else 'application/json',
        ],
        for (final h in match.responseHeaders.entries) h.key.toLowerCase(): [h.value],
      },
    );
  }

  _Exchange? _find(RequestOptions options, Uri uri, Object? body, String? auth) {
    bool matches(_Exchange e, {required bool exactState}) {
      if (exactState ? e.state != state : e.state != '*') return false;
      if (e.method != options.method) return false;
      if (e.uri.path != uri.path) return false;
      if (!_sameMap(e.uri.queryParameters, uri.queryParameters)) return false;
      final expectedAuth = e.auth == null ? null : 'Bearer fixture-token-${e.auth}';
      if (expectedAuth != auth) return false;
      for (final h in e.requestHeaders.entries) {
        if (options.headers[h.key] != h.value) return false;
      }
      return _deepEquals(e.request, body);
    }

    for (final e in _exchanges) {
      if (matches(e, exactState: true)) return e;
    }
    for (final e in _exchanges) {
      if (matches(e, exactState: false)) return e;
    }
    return null;
  }

  ResponseBody _miss() => ResponseBody.fromString(
    '{"code":"fixture-miss"}',
    501,
    headers: {
      Headers.contentTypeHeader: ['application/problem+json'],
    },
  );

  @override
  void close({bool force = false}) => closed = true;
}

/// The origin every widget test's server is at. Not a real host, and never contacted.
const String testServerOrigin = 'https://owl.example.test';

final class _Exchange {
  _Exchange({
    required this.state,
    required this.method,
    required this.uri,
    required this.auth,
    required this.requestHeaders,
    required this.request,
    required this.status,
    required this.responseHeaders,
    required this.body,
    required this.then,
  });

  factory _Exchange.fromJson(Map<String, Object?> json) => _Exchange(
    state: json['state']! as String,
    method: json['method']! as String,
    uri: Uri.parse(json['path']! as String),
    auth: json['auth'] as String?,
    requestHeaders: {
      ...?(json['requestHeaders'] as Map<String, Object?>?)?.map(
        (k, v) => MapEntry(k, v! as String),
      ),
    },
    request: json['request'],
    status: json['status']! as int,
    responseHeaders: {
      ...?(json['responseHeaders'] as Map<String, Object?>?)?.map(
        (k, v) => MapEntry(k, v! as String),
      ),
    },
    body: json['body'],
    then: json['then'] as String?,
  );

  final String state;
  final String method;
  final Uri uri;
  final String? auth;
  final Map<String, String> requestHeaders;
  final Object? request;
  final int status;
  final Map<String, String> responseHeaders;
  final Object? body;
  final String? then;
}

bool _sameMap(Map<String, String> a, Map<String, String> b) =>
    a.length == b.length && a.entries.every((e) => b[e.key] == e.value);

bool _deepEquals(Object? a, Object? b) {
  if (a is Map && b is Map) {
    return a.length == b.length && a.keys.every((k) => b.containsKey(k) && _deepEquals(a[k], b[k]));
  }
  if (a is List && b is List) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (!_deepEquals(a[i], b[i])) return false;
    }
    return true;
  }
  return a == b;
}

/// The fixture's exchanges, for a test that needs a recorded body (a password it must type).
Map<String, Object?> fixtureRequest(
  String name, {
  required String state,
  required String path,
  String? then,
}) {
  final decoded = jsonDecode(
    File('test/fixtures/$name.generated.json').readAsStringSync(),
  ) as Map<String, Object?>;
  for (final e in decoded['exchanges']! as List<Object?>) {
    final exchange = e! as Map<String, Object?>;
    if (exchange['state'] == state &&
        exchange['path'] == path &&
        exchange['request'] != null &&
        (then == null || exchange['then'] == then)) {
      return exchange['request']! as Map<String, Object?>;
    }
  }
  throw StateError('no recorded request for $path in $state');
}

/// The fixture's response body for one exchange, for a test that asserts a figure the API sent.
Object? fixtureBody(String name, {required String state, required String path}) {
  final decoded = jsonDecode(
    File('test/fixtures/$name.generated.json').readAsStringSync(),
  ) as Map<String, Object?>;
  for (final e in decoded['exchanges']! as List<Object?>) {
    final exchange = e! as Map<String, Object?>;
    if (exchange['state'] == state && exchange['path'] == path && exchange['method'] == 'GET') {
      return exchange['body'];
    }
  }
  throw StateError('no recorded response for $path in $state');
}
