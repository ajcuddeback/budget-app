import 'dart:convert';
import 'dart:io';

import 'package:budget_owl/core/json/json.dart';

/// The exchanges MobilePlansFixtureIT recorded against the real API, read directly: a request
/// builder's body is checked against **the body the server accepted**, and a domain object is
/// decoded from **what the server answered** — never from JSON typed into a test.
final class Recorded {
  Recorded(String file)
    : _exchanges = [
        for (final e
            in (jsonDecode(File('test/fixtures/$file.generated.json').readAsStringSync())
                    as Map<String, Object?>)['exchanges']!
                as List<Object?>)
          e! as Map<String, Object?>,
      ];

  final List<Map<String, Object?>> _exchanges;

  Map<String, Object?> _one(String state, String method, String path, {String who = 'owner'}) {
    final found = _exchanges.where(
      (e) =>
          e['state'] == state && e['method'] == method && e['path'] == path && e['auth'] == who,
    );
    if (found.isEmpty) throw StateError('nothing recorded for $state $method $path');
    return found.first;
  }

  /// The body the server answered [method] [path] with in [state].
  JsonMap answer(String state, String path, {String method = 'GET', String who = 'owner'}) =>
      _one(state, method, path, who: who)['body']! as JsonMap;

  /// The body the phone's write must have sent: the one the real API took in [state].
  Object? sent(String state, String method, String path) => _one(state, method, path)['request'];

  /// Every write recorded in [state], as `METHOD path`.
  List<String> writesIn(String state) => [
    for (final e in _exchanges)
      if (e['state'] == state && e['method'] != 'GET') '${e['method']} ${e['path']}',
  ];
}
