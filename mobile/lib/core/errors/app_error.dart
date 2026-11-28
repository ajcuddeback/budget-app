import 'package:budget_owl/core/json/json.dart';

/// Why a request did not produce what was asked for.
enum AppErrorKind {
  /// The server answered with a problem response: [AppError.code] says what, translated from ARB.
  http,

  /// The request never reached a server: no network, or a name that does not resolve.
  offline,

  /// A server was there to talk to, and it did not answer in time or refused the connection.
  noAnswer,

  /// The server answered with something this client cannot read.
  unexpected,
}

/// What the API said went wrong, as data — never as a sentence (ADR-0023, api-style.md).
///
/// The problem's `title` and `detail` are developer-facing English and are deliberately not kept:
/// a user is shown the ARB text for [code] and [params], so a translation ships without a server
/// release and a server's wording never reaches the screen.
///
/// Nothing the person typed is held here, and [toString] is the status and code only — an error
/// that is logged must not be able to carry a password or an amount.
final class AppError implements Exception {
  const AppError({
    required this.kind,
    this.status,
    this.code,
    this.params = const {},
    this.fields = const [],
    this.correlationId,
    this.retryAfterSeconds,
  });

  const AppError.offline() : this(kind: AppErrorKind.offline);

  const AppError.noAnswer() : this(kind: AppErrorKind.noAnswer);

  const AppError.unexpected({int? status}) : this(kind: AppErrorKind.unexpected, status: status);

  /// A problem response, read as the web's `ApiError.from` reads one.
  factory AppError.fromProblem(int status, Object? body, {String? retryAfterHeader}) {
    final problem = body is Map<String, Object?> ? body : const <String, Object?>{};
    final params = problem['params'] is Map<String, Object?>
        ? problem['params']! as Map<String, Object?>
        : const <String, Object?>{};
    final code = problem['code'];
    final fields = <String>[
      ...switch (params['fields']) {
        final List<Object?> listed => listed.whereType<String>(),
        _ => const <String>[],
      },
      if (params['field'] case final String one) one,
      ...switch (problem['errors']) {
        final List<Object?> errors =>
          errors.whereType<Map<String, Object?>>().map((e) => e['field']).whereType<String>(),
        _ => const <String>[],
      },
    ];
    final retryFromParams = params['retryAfterSeconds'];
    final retryFromHeader = int.tryParse(retryAfterHeader ?? '');
    return AppError(
      kind: AppErrorKind.http,
      status: status,
      code: code is String ? code : null,
      params: params,
      fields: fields,
      correlationId: problem['correlationId'] is String
          ? problem['correlationId']! as String
          : null,
      retryAfterSeconds: retryFromParams is int
          ? retryFromParams
          : (retryFromHeader != null && retryFromHeader > 0 ? retryFromHeader : null),
    );
  }

  final AppErrorKind kind;
  final int? status;
  final String? code;
  final JsonMap params;
  final List<String> fields;
  final String? correlationId;
  final int? retryAfterSeconds;

  /// True when the failure was the network, not the server's answer: the write controls go
  /// offline and nothing is retried (mobile-app.md, Writes).
  bool get isConnectivity => kind == AppErrorKind.offline || kind == AppErrorKind.noAnswer;

  String? get reason => params['reason'] is String ? params['reason']! as String : null;

  @override
  String toString() =>
      'AppError(${kind.name}${status == null ? '' : ' $status'}'
      '${code == null ? '' : ' $code'})';
}
