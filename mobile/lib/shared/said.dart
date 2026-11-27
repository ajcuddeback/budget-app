import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:flutter/widgets.dart';

/// A translated sentence waiting for its parameters: the web's `Said` (`{ key, params }`).
///
/// The plans screens choose **which** sentence a figure gets in plain Dart — a reason code, a
/// negative extra, a stale balance — and keep that choice testable without a widget tree. Every
/// parameter is already a formatted string (an amount, a month, a count for the reader's locale);
/// the key is the web's own, so the two clients say the same thing.
@immutable
final class Said {
  const Said(this.key, [this.params = const {}]);

  /// The dotted web key, e.g. `debt.clears.on`.
  final String key;
  final Map<String, String> params;

  @override
  bool operator ==(Object other) =>
      other is Said &&
      other.key == key &&
      other.params.length == params.length &&
      params.entries.every((e) => other.params[e.key] == e.value);

  @override
  int get hashCode => Object.hash(key, Object.hashAllUnordered(params.entries.map((e) => e.key)));

  @override
  String toString() => 'Said($key, $params)';
}

extension SaidText on BuildContext {
  /// The sentence in the reader's language.
  String said(Said said) => message(said.key, said.params);
}
