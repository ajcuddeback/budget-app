import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import '../../tool/l10n/generate_l10n.dart' as l10n;
import '../../tool/tokens/generate_tokens.dart' as tokens;

/// The two generated sources are what their generators produce now — a token or string change is
/// a re-run of a script, never a hand edit (mobile-app.md, Design and Language).
void main() {
  test("tokens.dart is what tools/mobile-tokens.sh generates from the web's token sheet", () {
    final generated = tokens.generateTokens(
      File(tokens.sourcePath).readAsStringSync(),
      File(tokens.componentsPath).readAsStringSync(),
    );
    expect(File(tokens.outputPath).readAsStringSync(), generated);
  });

  test("the dark palette is the canvas's warm near-black, not a grey", () {
    final source = File(tokens.outputPath).readAsStringSync();
    expect(source, contains('bg: Color(0xFF1A1613)'));
    expect(source, contains('bg: Color(0xFFF5EAD8)'));
  });

  test('the ARB and the lookup are what tools/mobile-l10n.sh generates', () {
    final outputs = l10n.generate(_flat(l10n.webPath), _flat(l10n.mobilePath));
    for (final entry in outputs.entries) {
      expect(File(entry.key).readAsStringSync(), entry.value, reason: entry.key);
    }
  });

  test('every web key is carried over, so the two clients stay in step', () {
    final arb = jsonDecode(File(l10n.arbPath).readAsStringSync()) as Map<String, Object?>;
    for (final key in _flat(l10n.webPath).keys) {
      expect(arb.containsKey(l10n.arbKey(key)), isTrue, reason: key);
    }
  });

  test('a mobile key may not fork a web key', () {
    expect(
      () => l10n.generate({'login.title': 'Sign in'}, {'login.title': 'Log in'}),
      throwsStateError,
    );
  });

  test("a mobile key may not repeat a web text in the same screen's namespace", () {
    expect(
      () => l10n.generate({'login.title': 'Sign in'}, {'login.heading': 'Sign in'}),
      throwsStateError,
    );
    expect(
      () => l10n.generate({'common.save': 'Save'}, {'connect.save': 'Save'}),
      throwsStateError,
    );
    expect(
      l10n.generate({'debt.col.balance': 'Balance'}, {'transactions.balance': 'Balance'}),
      isNotEmpty,
    );
  });

  test('two keys that would collide as ARB names are refused', () {
    expect(() => l10n.generate({'a.b-c': 'x', 'a.b.c': 'y'}, {}), throwsStateError);
  });

  test('a placeholder that is a Dart keyword is renamed for gen-l10n only', () {
    final out = l10n.generate({'insights.x': '{in} and {out}'}, {});
    final arb = jsonDecode(out[l10n.arbPath]!) as Map<String, Object?>;
    expect(arb['insights_x'], '{inValue} and {out}');
    expect(out[l10n.lookupPath], contains("l.insights_x(a('in'), a('out'))"));
  });
}

Map<String, String> _flat(String path) {
  final out = <String, String>{};
  void walk(Map<String, Object?> node, String prefix) {
    node.forEach((k, v) {
      final key = prefix.isEmpty ? k : '$prefix.$k';
      if (v is String) out[key] = v;
      if (v is Map<String, Object?>) walk(v, key);
    });
  }

  walk(jsonDecode(File(path).readAsStringSync()) as Map<String, Object?>, '');
  return out;
}
