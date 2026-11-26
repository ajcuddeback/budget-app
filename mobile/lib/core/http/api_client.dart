import 'dart:convert';

import 'package:budget_owl/core/auth/token_store.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/http/transport_failure.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

/// The one HTTP client the app talks to its server through.
///
/// Every control the threat model puts on the wire is here, once, so no feature can forget one:
///
/// - **Only the server the person chose.** A request whose origin is not [server]'s is refused
///   before a header is attached ([_OriginGuard]), so a bearer token cannot be sent anywhere else
///   whatever path a caller builds.
/// - **Redirects are not followed** (`followRedirects: false`, `maxRedirects: 0`): a `3xx` is an
///   error, so a server cannot bounce the token to another host (attack 4).
/// - **The bearer is attached by [AuthInterceptor]** and only there, and only to a request for the
///   origin that issued it (audit H-a); any `401` on a request that carried it ends the session
///   through `onSessionEnded`, once, with no retry.
/// - **Nothing is logged** but method, a redacted path and a status — in a debug build only.
/// - **Errors leave as [AppError]**: a problem response's code and params, or which kind of
///   connection failure it was. Never the server's title or detail.
/// - **No write before the version check has passed this launch** ([versionConfirmed]): anything
///   but a `GET` is refused before it is sent, whoever calls. The screens disable their write
///   controls on the same fact (`Reachability.unconfirmed`); this is the backstop for a caller that
///   forgets. A revocation is not a write in this sense and is never held back.
final class ApiClient {
  ApiClient({
    required this.server,
    required HttpClientAdapter adapter,
    required TokenStore tokens,
    required void Function() onSessionEnded,
    required void Function(Reachability) onOutcome,
    required this.versionConfirmed,
    bool logRequests = kDebugMode,
  }) : _dio = Dio(
         BaseOptions(
           baseUrl: server.stored,
           followRedirects: false,
           maxRedirects: 0,
           connectTimeout: const Duration(seconds: 10),
           sendTimeout: const Duration(seconds: 20),
           receiveTimeout: const Duration(seconds: 20),
           responseType: ResponseType.plain,
           headers: {'Accept': 'application/json'},
         ),
       ) {
    _dio.httpClientAdapter = adapter;
    _dio.interceptors.addAll([
      _OriginGuard(server),
      AuthInterceptor(tokens, onSessionEnded),
      _ReachabilityRecorder(onOutcome),
      if (logRequests) const _RedactedLog(),
    ]);
  }

  final ServerAddress server;
  final Dio _dio;

  /// Whether `/api/meta` has confirmed this server's version this launch. Until it says so, only
  /// reads are sent.
  final bool Function() versionConfirmed;

  /// A `GET` whose body is a JSON object.
  Future<JsonMap> getJson(String path, {Map<String, String> query = const {}}) async =>
      decodeObject(await getText(path, query: query));

  /// A `GET`, returning the body exactly as received — what the cache stores (ADR-0042: "API
  /// response bodies as received").
  ///
  /// [headers] is for asking for another representation of the same resource: the reports answer
  /// `Accept: text/csv` with a file (reporting.md, CSV export). It replaces the JSON `Accept`.
  Future<String> getText(
    String path, {
    Map<String, String> query = const {},
    Map<String, String> headers = const {},
    bool public = false,
  }) async {
    final response = await _send('GET', path, query: query, headers: headers, public: public);
    return response.data ?? '';
  }

  /// A write. Returns the decoded body, or null for a `204`.
  ///
  /// [headers] is for the CSRF pair a public write needs; [public] sends no bearer.
  Future<JsonMap?> write(
    String method,
    String path, {
    Object? body,
    Map<String, String> headers = const {},
    bool public = false,
  }) async {
    final response = await _send(method, path, body: body, headers: headers, public: public);
    final text = response.data;
    if (text == null || text.isEmpty) return null;
    try {
      return decodeObject(text);
    } on UnexpectedJson {
      throw AppError.unexpected(status: response.statusCode);
    }
  }

  /// `POST /api/auth/logout` carrying [token] — one the session has already taken out of storage
  /// (audit N1). It passes the origin guard and the token's own origin check like any request; its
  /// `401` (already revoked) ends nothing, because the session it belonged to is already over.
  Future<void> revoke(IssuedToken token) async {
    try {
      await _dio.request<String>(
        '/api/auth/logout',
        options: Options(method: 'POST', extra: {_revokingKey: token}),
      );
    } on DioException catch (failure) {
      throw toAppError(failure);
    }
  }

  /// A raw exchange, for the few callers that need a response header (the CSRF cookie).
  Future<Response<String>> exchange(
    String method,
    String path, {
    Map<String, String> query = const {},
    bool public = false,
  }) => _send(method, path, query: query, public: public);

  Future<Response<String>> _send(
    String method,
    String path, {
    Map<String, String> query = const {},
    Object? body,
    Map<String, String> headers = const {},
    bool public = false,
  }) async {
    // Fails closed: until /api/meta has confirmed the version this launch, nothing but a read
    // leaves the phone. Refused as "offline", the message every write control already shows.
    if (method.toUpperCase() != 'GET' && !versionConfirmed()) throw const AppError.offline();
    try {
      return await _dio.request<String>(
        path,
        queryParameters: query.isEmpty ? null : query,
        data: body == null ? null : jsonEncode(body),
        options: Options(
          method: method,
          headers: {...headers, if (body != null) 'Content-Type': 'application/json'},
          extra: {_publicKey: public},
        ),
      );
    } on DioException catch (failure) {
      throw toAppError(failure);
    }
  }

  /// What a failed exchange means, as data.
  static AppError toAppError(DioException failure) {
    final response = failure.response;
    if (response != null) {
      final status = response.statusCode ?? 0;
      if (status >= 300 && status < 400) {
        // Not followed, and not trusted: the server tried to send this request somewhere else.
        return AppError.unexpected(status: status);
      }
      Object? body;
      final text = response.data;
      if (text is String && text.isNotEmpty) {
        try {
          body = jsonDecode(text);
        } on FormatException {
          body = null;
        }
      }
      return AppError.fromProblem(
        status,
        body,
        retryAfterHeader: response.headers.value('retry-after'),
      );
    }
    return switch (classifyTransportFailure(failure)) {
      TransportFailure.hostNotFound || TransportFailure.noNetwork => const AppError.offline(),
      _ => const AppError.noAnswer(),
    };
  }
}

const String _publicKey = 'budget_owl.public';
const String _bearerKey = 'budget_owl.bearer';
const String _revokingKey = 'budget_owl.revoking';

/// Refuses a request to any origin but the chosen server's, before any header is attached.
final class _OriginGuard extends Interceptor {
  const _OriginGuard(this.server);

  final ServerAddress server;

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    if (options.uri.origin != server.origin.origin) {
      handler.reject(
        DioException(
          requestOptions: options,
          type: DioExceptionType.cancel,
          message: 'refused: not the chosen server',
        ),
      );
      return;
    }
    handler.next(options);
  }
}

/// Attaches the bearer token, and ends the session on the `401` that says it no longer works.
///
/// **No retry, no refresh.** A `401` on a request that carried the token means the token is gone
/// — revoked from the web, expired, or the member removed — and the only correct response is to
/// stop: `onSessionEnded` wipes local state and routes to sign-in, and is idempotent, so ten
/// requests failing at once still wipe once and navigate once (flutter-style.md: "not a retry
/// storm"). A `401` on a request that carried no token (a wrong password at sign-in) is the
/// caller's to explain and ends nothing.
final class AuthInterceptor extends Interceptor {
  AuthInterceptor(this._tokens, this._onSessionEnded);

  final TokenStore _tokens;
  final void Function() _onSessionEnded;

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    if (options.extra[_publicKey] == true) {
      handler.next(options);
      return;
    }
    final revoking = options.extra[_revokingKey];
    final token = revoking is IssuedToken ? revoking : _tokens.held;
    if (token == null) {
      handler.next(options);
      return;
    }
    // The token goes only to the origin that issued it — checked here, on every request, not only
    // when it is loaded (audit H-a). A mismatch sends nothing at all.
    if (!token.isFor(options.uri)) {
      handler.reject(
        DioException(
          requestOptions: options,
          type: DioExceptionType.cancel,
          message: 'refused: the token was not issued by this origin',
        ),
      );
      return;
    }
    options.headers['Authorization'] = 'Bearer ${token.value}';
    // A revocation's 401 ends nothing: its session is already over (audit N1).
    if (revoking == null) options.extra[_bearerKey] = true;
    handler.next(options);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    if (err.response?.statusCode == 401 && err.requestOptions.extra[_bearerKey] == true) {
      _onSessionEnded();
    }
    handler.next(err);
  }
}

/// Records whether each exchange reached the server, for [reachabilityProvider].
final class _ReachabilityRecorder extends Interceptor {
  const _ReachabilityRecorder(this._record);

  final void Function(Reachability) _record;

  @override
  void onResponse(Response<dynamic> response, ResponseInterceptorHandler handler) {
    _record(Reachability.reached);
    handler.next(response);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    if (err.type == DioExceptionType.cancel) {
      handler.next(err);
      return;
    }
    if (err.response != null) {
      _record(Reachability.reached);
    } else {
      _record(switch (classifyTransportFailure(err)) {
        TransportFailure.hostNotFound || TransportFailure.noNetwork => Reachability.offline,
        _ => Reachability.noAnswer,
      });
    }
    handler.next(err);
  }
}

/// Debug builds only: one line per exchange — method, path with credentials redacted, status.
///
/// Never a header, never a body, never a query string: a query can carry a search term, a body an
/// amount, a header the token (mobile-app.md, attack 6). The invitation token lives in a path
/// (`/api/invitations/{token}/accept`), so any long opaque segment is redacted too.
final class _RedactedLog extends Interceptor {
  const _RedactedLog();

  @override
  void onResponse(Response<dynamic> response, ResponseInterceptorHandler handler) {
    debugPrint(redactedLine(response.requestOptions, response.statusCode));
    handler.next(response);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    debugPrint(redactedLine(err.requestOptions, err.response?.statusCode));
    handler.next(err);
  }
}

/// Debug builds only: one line saying which local step failed, and the error's **type** — never
/// its message, which can carry a path, a key name or a figure (attack 6). The app's only other
/// log line is [_RedactedLog]'s; both live in this file, which the guards hold to that.
void debugLogFailure(String what, Object error) {
  if (kDebugMode) debugPrint(failureLine(what, error));
}

/// The line [debugLogFailure] prints. Public for its test.
String failureLine(String what, Object error) => 'local $what failed (${error.runtimeType})';

final RegExp _uuid = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');

/// The log line for an exchange. Public for its test.
String redactedLine(RequestOptions options, int? status) {
  final segments = options.uri.pathSegments.map(
    (s) => s.length >= 24 && !_uuid.hasMatch(s) ? '<redacted>' : s,
  );
  return 'api ${options.method} /${segments.join('/')} → ${status ?? 'no answer'}';
}
