// Builds the app's English ARB from the web's en.json plus the phone's own strings.
//
// ADR-0023 makes English the source; mobile-app.md asks that ARB keys mirror the web's en.json keys
// wherever the text is the same, so the two stay in step. The strongest form of "mirror" is not
// copying: every web key is carried over mechanically (dots and dashes become underscores, so
// `transactions.addTitle` is `transactions_addTitle`), and the phone's own text lives in
// l10n/mobile.en.json under keys the web does not have. Two rules keep that honest, and the
// generator refuses to write a file that breaks either:
//
//   1. A mobile key may not reuse a web key's name — it would silently fork the text.
//   2. A mobile key may not repeat a web key's text — use the web key instead.
//
// It also writes lib/l10n/message_lookup.g.dart, a switch from a dotted key to its message, for the
// places that look a message up by name at run time: an API error code (`error.<code>`), a status
// or a role.
//
//   dart run tool/l10n/generate_l10n.dart          writes both files
//   dart run tool/l10n/generate_l10n.dart --check  fails if either committed file is stale
import 'dart:convert';
import 'dart:io';

const String webPath = '../frontend/src/i18n/en.json';
const String mobilePath = 'l10n/mobile.en.json';
const String arbPath = 'lib/l10n/app_en.arb';
const String lookupPath = 'lib/l10n/message_lookup.g.dart';

Future<void> main(List<String> args) async {
  final web = _flat(File(webPath).readAsStringSync());
  final mobile = _flat(File(mobilePath).readAsStringSync());
  final outputs = generate(web, mobile);
  final check = args.contains('--check');
  var stale = false;
  outputs.forEach((path, content) {
    final file = File(path);
    if (check) {
      if (!file.existsSync() || file.readAsStringSync() != content) {
        stderr.writeln('$path is stale: run tools/mobile-l10n.sh and commit it.');
        stale = true;
      }
    } else {
      file.writeAsStringSync(content);
    }
  });
  if (stale) exit(1);
}

/// The ARB key for a dotted web or mobile key.
String arbKey(String dotted) => dotted.replaceAll(RegExp('[^A-Za-z0-9]'), '_');

const Set<String> _reserved = {
  'abstract', 'as', 'assert', 'async', 'await', 'base', 'break', 'case', 'catch', 'class', //
  'const', 'continue', 'covariant', 'default', 'deferred', 'do', 'dynamic', 'else', 'enum',
  'export', 'extends', 'extension', 'external', 'factory', 'false', 'final', 'finally', 'for',
  'function', 'get', 'hide', 'if', 'implements', 'import', 'in', 'interface', 'is', 'late',
  'library', 'mixin', 'new', 'null', 'of', 'on', 'operator', 'part', 'required', 'rethrow',
  'return', 'sealed', 'set', 'show', 'static', 'super', 'switch', 'sync', 'this', 'throw',
  'true', 'try', 'type', 'typedef', 'var', 'void', 'when', 'while', 'with', 'yield',
};

/// The parameter name gen-l10n is given for a web placeholder.
String dartName(String placeholder) =>
    _reserved.contains(placeholder) ? '${placeholder}Value' : placeholder;

/// Placeholder names, in order of first appearance.
List<String> placeholders(String text) {
  final names = <String>[];
  for (final match in RegExp(r'\{([A-Za-z][A-Za-z0-9_]*)\}').allMatches(text)) {
    final name = match.group(1)!;
    if (!names.contains(name)) names.add(name);
  }
  return names;
}

/// Both generated files, keyed by path.
Map<String, String> generate(Map<String, String> web, Map<String, String> mobile) {
  for (final key in mobile.keys) {
    if (web.containsKey(key)) {
      throw StateError(
        'mobile key "$key" is also a web key: the phone would fork the web\'s text. '
        'Use the web key, or name the mobile one differently.',
      );
    }
    // Same text in the same screen's namespace (or in common.*) is the same message, and must be
    // the same key. "Balance" on a debt table and "Balance" on the ledger are different messages
    // that happen to agree today, and may stop agreeing — so other namespaces may repeat it.
    final namespace = key.split('.').first;
    web.forEach((webKey, text) {
      final webNamespace = webKey.split('.').first;
      if (text == mobile[key] && (webNamespace == namespace || webNamespace == 'common')) {
        throw StateError(
          'mobile key "$key" repeats the text of web key "$webKey". Use "$webKey" instead, '
          'so the two stay in step.',
        );
      }
    });
  }

  final all = <String, String>{...web, ...mobile};
  final seen = <String, String>{};
  for (final key in all.keys) {
    final arb = arbKey(key);
    final clash = seen[arb];
    if (clash != null) {
      throw StateError('"$key" and "$clash" both become the ARB key "$arb".');
    }
    seen[arb] = key;
  }

  final arb = <String, Object>{'@@locale': 'en'};
  final keys = all.keys.toList()..sort();
  for (final key in keys) {
    final names = placeholders(all[key]!);
    var text = all[key]!;
    // A placeholder that is a Dart keyword ({in}) cannot be a parameter name. It is renamed in
    // the ARB only; callers still pass it by its web name through lookupMessage.
    for (final n in names) {
      text = text.replaceAll('{$n}', '{${dartName(n)}}');
    }
    final name = arbKey(key);
    arb[name] = text;
    arb['@$name'] = {
      'description': web.containsKey(key)
          ? 'Web en.json "$key".'
          : 'Mobile only (l10n/mobile.en.json "$key").',
      if (names.isNotEmpty)
        'placeholders': {
          for (final n in names) dartName(n): {'type': 'String'},
        },
    };
  }
  final arbText = '${const JsonEncoder.withIndent('  ').convert(arb)}\n';

  final lookup = StringBuffer()
    ..writeln('// GENERATED by tools/mobile-l10n.sh. Do not edit by hand.')
    ..writeln('//')
    ..writeln('// A message by its dotted key, for the places that only know the key at run')
    ..writeln('// time: an API error code, a status, a role. Every argument is already a')
    ..writeln('// formatted string — numbers, dates and amounts are formatted by the caller')
    ..writeln("// with intl for the user's locale, never by this table.")
    ..writeln()
    ..writeln("import 'package:budget_owl/l10n/gen/app_localizations.dart';")
    ..writeln()
    ..writeln('/// The message for [key], or null when there is no such key.')
    ..writeln('String? lookupMessage(')
    ..writeln('  AppLocalizations l,')
    ..writeln('  String key, [')
    ..writeln('  Map<String, String> args = const {},')
    ..writeln(']) {')
    ..writeln("  String a(String name) => args[name] ?? '';")
    ..writeln('  return switch (key) {');
  for (final key in keys) {
    final names = placeholders(all[key]!);
    final name = arbKey(key);
    final call = names.isEmpty
        ? 'l.$name'
        : 'l.$name(${names.map((n) => "a('$n')").join(', ')})';
    lookup.writeln("    '${key.replaceAll("'", r"\'")}' => $call,");
  }
  lookup
    ..writeln('    _ => null,')
    ..writeln('  };')
    ..writeln('}');

  return {arbPath: arbText, lookupPath: lookup.toString()};
}

Map<String, String> _flat(String json) {
  final decoded = jsonDecode(json);
  if (decoded is! Map<String, Object?>) {
    throw const FormatException('expected a JSON object of strings');
  }
  final out = <String, String>{};
  void walk(Map<String, Object?> node, String prefix) {
    node.forEach((key, value) {
      final full = prefix.isEmpty ? key : '$prefix.$key';
      if (value is String) {
        out[full] = value;
      } else if (value is Map<String, Object?>) {
        walk(value, full);
      } else {
        throw FormatException('"$full" is neither a string nor an object');
      }
    });
  }

  walk(decoded, '');
  return out;
}
