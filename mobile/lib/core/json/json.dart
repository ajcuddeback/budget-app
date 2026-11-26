import 'dart:convert';

/// A decoded JSON object. Every API response becomes a typed model at the boundary, through these
/// readers, or not at all: nothing past the data layer ever sees a `dynamic` or a raw map.
typedef JsonMap = Map<String, Object?>;

/// The server sent a body this client cannot read. Rendered as "the server answered with
/// something unexpected", never as a crash.
final class UnexpectedJson implements Exception {
  const UnexpectedJson(this.what);

  /// Which field or shape was wrong. Never the value: a value may be somebody's money.
  final String what;

  @override
  String toString() => 'UnexpectedJson($what)';
}

/// Decodes [text] as a JSON object.
JsonMap decodeObject(String text) {
  final Object? decoded;
  try {
    decoded = jsonDecode(text);
  } on FormatException {
    throw const UnexpectedJson('body is not JSON');
  }
  return asObject(decoded, 'body');
}

JsonMap asObject(Object? value, String what) {
  if (value is Map<String, Object?>) return value;
  throw UnexpectedJson('$what is not an object');
}

extension JsonRead on JsonMap {
  String string(String key) {
    final value = this[key];
    if (value is String) return value;
    throw UnexpectedJson('$key is not a string');
  }

  String? stringOrNull(String key) {
    final value = this[key];
    if (value == null || value is String) return value as String?;
    throw UnexpectedJson('$key is not a string or null');
  }

  int integer(String key) {
    final value = this[key];
    if (value is int) return value;
    throw UnexpectedJson('$key is not an integer');
  }

  bool boolean(String key) {
    final value = this[key];
    if (value is bool) return value;
    throw UnexpectedJson('$key is not a boolean');
  }

  bool booleanOr(String key, {required bool fallback}) {
    final value = this[key];
    if (value == null) return fallback;
    if (value is bool) return value;
    throw UnexpectedJson('$key is not a boolean');
  }

  JsonMap object(String key) => asObject(this[key], key);

  JsonMap? objectOrNull(String key) {
    final value = this[key];
    return value == null ? null : asObject(value, key);
  }

  List<JsonMap> objects(String key) {
    final value = this[key];
    if (value is! List<Object?>) throw UnexpectedJson('$key is not a list');
    return [for (final item in value) asObject(item, '$key[]')];
  }

  List<String> strings(String key) {
    final value = this[key];
    if (value is! List<Object?>) throw UnexpectedJson('$key is not a list');
    return [
      for (final item in value)
        if (item is String) item else throw UnexpectedJson('$key[] is not a string'),
    ];
  }
}
