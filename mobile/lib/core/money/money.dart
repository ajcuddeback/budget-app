import 'package:decimal/decimal.dart';
import 'package:intl/intl.dart';
import 'package:meta/meta.dart';

/// An amount and the currency it is in, exactly as the API sent it (ADR-0006, ADR-0022).
///
/// **There is no `double` anywhere on this path.** The wire string is lexed strictly and held as a
/// [Decimal]; formatting rounds the decimal itself and hands `intl` only whole integers, so no
/// figure is ever approximated. `test/architecture/guards_test.dart` fails the build on
/// `double.parse`, `num.parse` or `.toDouble()` in `lib/`.
///
/// There is no addition across currencies here or anywhere in the app: two amounts in two
/// currencies are two figures, and a converted one would be labelled as converted (ADR-0022).
@immutable
final class Money {
  const Money(this.amount, this.currency);

  /// An amount from the API: `-1234.5600`, in [currency].
  ///
  /// Refuses exponents, a leading `+`, non-ASCII digits, more than 15 integer digits and more than
  /// 4 decimals — the server's `NUMERIC(19,4)` — rather than trusting `Decimal.parse`, which would
  /// accept `1e10000000` and spend seconds expanding it (docs/memory/gotchas.md, AmountText).
  factory Money.fromWire(String wire, String currency) => Money(parseWireAmount(wire), currency);

  final Decimal amount;
  final String currency;

  bool get isNegative => amount.sign < 0;

  @override
  bool operator ==(Object other) =>
      other is Money && other.amount == amount && other.currency == currency;

  @override
  int get hashCode => Object.hash(amount, currency);

  /// Not a display string — use [formatMoney]. Exists for test failure messages only.
  @override
  String toString() => 'Money($amount $currency)';
}

final RegExp _wireAmount = RegExp(r'^-?[0-9]{1,15}(\.[0-9]{1,4})?$');

/// The strict lexer for an amount the server sent.
Decimal parseWireAmount(String wire) {
  if (!_wireAmount.hasMatch(wire)) {
    throw FormatException('not an amount the API sends', wire.length > 32 ? '…' : wire);
  }
  return Decimal.parse(wire);
}

/// U+2212, the minus sign a formatted amount carries, then a word joiner (U+2060) so no line
/// can break between the sign and the figure.
const String minusSign = '\u2212\u2060';

/// [money] for a person reading in [locale], in the amount's own currency.
///
/// Rounds half away from zero to the currency's own decimals (two for USD, none for JPY, three for
/// KWD), then builds the text from integers: intl formats the whole part, with the locale's
/// grouping, currency symbol and sign placement, and the fraction digits are placed after its
/// last digit with the locale's decimal separator.
String formatMoney(Money money, String locale) {
  final probe = NumberFormat.simpleCurrency(locale: locale, name: money.currency);
  final digits = probe.decimalDigits ?? 2;
  final rounded = money.amount.round(scale: digits);
  final negative = rounded.sign < 0;
  final magnitude = rounded.abs();
  final whole = magnitude.truncate().toBigInt();
  if (!whole.isValidInt) {
    // Past 9.2 quintillion: no account holds this, and intl cannot be handed it as an integer.
    return '${negative ? '-' : ''}$magnitude ${money.currency}';
  }

  final wholeFormat = NumberFormat.simpleCurrency(
    locale: locale,
    name: money.currency,
    decimalDigits: 0,
  );
  final symbols = wholeFormat.symbols;
  final zero = symbols.ZERO_DIGIT.codeUnitAt(0);
  String localDigits(String ascii) =>
      String.fromCharCodes(ascii.codeUnits.map((c) => zero + (c - 0x30)));

  String text;
  if (whole == BigInt.zero && negative) {
    // -0.50: there is no negative zero integer to format, so format -1 and swap the digit.
    text = wholeFormat.format(-1).replaceFirst(localDigits('1'), localDigits('0'));
  } else {
    final asInt = whole.toInt();
    text = wholeFormat.format(negative ? -asInt : asInt);
  }
  if (negative && symbols.MINUS_SIGN == '-') {
    // The typographic minus, as the artboards set it. A hyphen-minus is a line-break opportunity,
    // and "−$128.30" split across two lines as "-" and "$128.30" is a different figure at a glance.
    text = text.replaceFirst('-', minusSign);
  }
  if (digits == 0) return text;

  final fraction = (magnitude - magnitude.truncate())
      .shift(digits)
      .toBigInt()
      .toString()
      .padLeft(digits, '0');
  var lastDigit = -1;
  for (var i = 0; i < text.length; i++) {
    final c = text.codeUnitAt(i);
    if (c >= zero && c <= zero + 9) lastDigit = i;
  }
  return text.substring(0, lastDigit + 1) +
      symbols.DECIMAL_SEP +
      localDigits(fraction) +
      text.substring(lastDigit + 1);
}

/// What the person typed into an amount field, or why it cannot be sent.
sealed class AmountInput {
  const AmountInput();
}

/// A magnitude the API will accept, as the string it is sent as: `42.5`, never a number.
final class ValidAmount extends AmountInput {
  const ValidAmount(this.text);
  final String text;
}

enum AmountProblem { empty, malformed, zero, tooPrecise, tooLarge, signed }

final class InvalidAmount extends AmountInput {
  const InvalidAmount(this.problem);
  final AmountProblem problem;
}

final RegExp _typedAmount = RegExp(r'^[0-9]+(\.[0-9]+)?$');

/// An unsigned magnitude typed into a field: digits and at most one dot, as the web asks
/// ("Enter an amount using digits and a dot"). The direction is a separate control, so a sign is
/// refused here when [allowSign] is false and stripped when it is true — somebody who types `-42`
/// with "Money out" chosen means forty-two out (transactions.md, UI).
AmountInput parseAmountInput(String typed, {bool allowSign = false}) {
  var text = typed.trim();
  if (text.isEmpty) return const InvalidAmount(AmountProblem.empty);
  if (text.startsWith('-') || text.startsWith('+')) {
    if (!allowSign) return const InvalidAmount(AmountProblem.signed);
    text = text.substring(1).trim();
  }
  if (!_typedAmount.hasMatch(text)) return const InvalidAmount(AmountProblem.malformed);
  final parts = text.split('.');
  final integer = parts.first.replaceFirst(RegExp('^0+(?=[0-9])'), '');
  final fraction = parts.length > 1 ? parts[1] : '';
  if (integer.length > 15) return const InvalidAmount(AmountProblem.tooLarge);
  if (fraction.length > 4) return const InvalidAmount(AmountProblem.tooPrecise);
  if (Decimal.parse(text) == Decimal.zero) return const InvalidAmount(AmountProblem.zero);
  return ValidAmount(fraction.isEmpty ? integer : '$integer.$fraction');
}

/// Whether two wire strings are the same amount: `-42.5` and `-42.5000` are, without a `double`.
bool sameAmount(String left, String right) {
  final a = Decimal.tryParse(left.trim());
  final b = Decimal.tryParse(right.trim());
  return a != null && b != null && a == b;
}

/// The unsigned magnitude of a wire amount, for re-opening it in a form: `-42.5000` is `42.50`.
String magnitudeOf(String wire) {
  final value = parseWireAmount(wire).abs();
  final scale = value.scale < 2 ? 2 : value.scale;
  return value.toStringAsFixed(scale);
}
