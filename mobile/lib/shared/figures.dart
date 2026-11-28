import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:decimal/decimal.dart';
import 'package:intl/intl.dart';

/// Reading the plans screens' figures at the JSON boundary.
///
/// An amount is kept as the server's string — every one is displayed, compared or sent back as it
/// came — but it is **checked by the strict lexer here**, so a malformed figure is the generic
/// "unexpected answer" error at the boundary rather than a crash later inside a widget.
extension WireFigures on JsonMap {
  String amount(String key) {
    final value = string(key);
    try {
      parseWireAmount(value);
    } on FormatException {
      throw UnexpectedJson('$key is not an amount');
    }
    return value;
  }

  String? amountOrNull(String key) => this[key] == null ? null : amount(key);

  int? integerOrNull(String key) => this[key] == null ? null : integer(key);

  /// A `YYYY-MM` month, or null. A malformed one is refused at the boundary.
  String? monthOrNull(String key) {
    final value = stringOrNull(key);
    if (value != null && !RegExp(r'^\d{4}-(0[1-9]|1[0-2])$').hasMatch(value)) {
      throw UnexpectedJson('$key is not a month');
    }
    return value;
  }

  String month(String key) {
    final value = monthOrNull(key);
    if (value == null) throw UnexpectedJson('$key is not a month');
    return value;
  }
}

/// Small, exact helpers for the figures the plans screens state — bills, the debt plan and goals.
///
/// **None of them computes a figure that is shown as the server's.** They trim a wire string for a
/// form field, say which way a figure points, format a rate, and turn two amounts into the
/// dimensionless ratio a bar or a chart needs for its geometry. Every amount stays a [Decimal] from
/// its wire string; nothing here is a `double` until it is a fraction of a picture's width.

/// An unsigned amount as a person types one: digits and at most one dot, at most 15 whole digits
/// and 4 decimals — the web's `AMOUNT` pattern, zero included (a minimum payment may be zero). It is
/// sent as typed, as a string.
bool isTypedAmount(String typed) => RegExp(r'^\d{1,15}(\.\d{1,4})?$').hasMatch(typed.trim());

/// A percentage as a statement prints it, to three places — the web's `RATE` pattern.
bool isTypedRate(String typed) => RegExp(r'^\d{1,3}(\.\d{1,3})?$').hasMatch(typed.trim());

/// The same figure as somebody about to edit it would write it: `445.0000` → `445`, `52.3000` →
/// `52.3`. String surgery, never a parse — the web's `trimAmount`, and it assumes nothing about a
/// currency's scale. Anything that is not a wire amount is returned untouched.
String trimAmount(String wire) {
  if (!RegExp(r'^-?[0-9]{1,15}(\.[0-9]{1,4})?$').hasMatch(wire) || !wire.contains('.')) {
    return wire;
  }
  final trimmed = wire.replaceFirst(RegExp(r'0+$'), '').replaceFirst(RegExp(r'\.$'), '');
  return trimmed.isEmpty || trimmed == '-' ? wire : trimmed;
}

/// Which way a wire amount points: -1, 0 or 1. Null for anything that is not an amount.
int? signOf(String? wire) {
  if (wire == null) return null;
  try {
    return parseWireAmount(wire).sign;
  } on FormatException {
    return null;
  }
}

/// Whether a wire amount is below zero, read from the figure itself.
bool isNegativeWire(String wire) => (signOf(wire) ?? 0) < 0;

/// How two wire amounts compare (-1, 0, 1), exactly. Null when either is not an amount.
int? compareWire(String left, String right) {
  try {
    return parseWireAmount(left).compareTo(parseWireAmount(right));
  } on FormatException {
    return null;
  }
}

/// A rate as a statement prints it, in the reader's locale: `22.900` → `22.9`, `4.500` → `4.5`.
///
/// The web's `formatRate`. The whole part is grouped by intl from an integer, the fraction is the
/// wire's own digits with trailing zeros dropped, so no rate passes through a `double`.
String formatRate(String? wire, String locale) {
  final text = (wire ?? '').trim();
  if (!RegExp(r'^-?[0-9]{1,15}(\.[0-9]{1,6})?$').hasMatch(text)) return '';
  final negative = text.startsWith('-');
  final digits = negative ? text.substring(1) : text;
  final parts = digits.split('.');
  final format = NumberFormat.decimalPattern(locale);
  final symbols = format.symbols;
  final zero = symbols.ZERO_DIGIT.codeUnitAt(0);
  String local(String ascii) => String.fromCharCodes(ascii.codeUnits.map((c) => zero + (c - 0x30)));
  final whole = BigInt.parse(parts.first);
  final wholeText = whole.isValidInt ? format.format(whole.toInt()) : local(parts.first);
  final fraction = parts.length > 1 ? parts[1].replaceFirst(RegExp(r'0+$'), '') : '';
  final sign = negative ? symbols.MINUS_SIGN : '';
  return fraction.isEmpty
      ? '$sign$wholeText'
      : '$sign$wholeText${symbols.DECIMAL_SEP}${local(fraction)}';
}

/// [value] as a fraction of [largest], for drawing — the web's `amountFraction`.
///
/// **Geometry only, never a figure.** The ratio is computed exactly, in minor units at the
/// server's scale and integer division, and only the final dimensionless result becomes a
/// floating-point number, through an integer division. Null when either is not an amount or
/// [largest] is zero.
double? amountFraction(String value, String largest) {
  final Decimal numerator;
  final Decimal denominator;
  try {
    numerator = parseWireAmount(value);
    denominator = parseWireAmount(largest);
  } on FormatException {
    return null;
  }
  if (denominator == Decimal.zero) return null;
  final scale = Decimal.fromInt(10000);
  final top = (numerator * scale).toBigInt();
  final bottom = (denominator * scale).toBigInt();
  if (bottom == BigInt.zero) return null;
  const precision = 1000000;
  final ratio = (top * BigInt.from(precision)) ~/ bottom;
  if (!ratio.isValidInt) return null;
  return ratio.toInt() / precision;
}

/// `plan + step`, at the server's scale, for a query that **stores nothing** — the what-if.
///
/// The web's `addAmounts([monthlyAmount, '50'])`: an exact decimal addition whose result is a query
/// parameter on a read-only projection, never a figure sent to be stored or shown as the server's.
String? addForQuery(String wire, String step) {
  try {
    return (parseWireAmount(wire) + parseWireAmount(step)).toStringAsFixed(4);
  } on FormatException {
    return null;
  }
}
