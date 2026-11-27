import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:intl/intl.dart';
import 'package:meta/meta.dart';

/// A month as a **label** — `2026-08` — never a range of instants (ADR-0031).
///
/// The API sends a period, a payoff month, a goal's target month and a chart point's month in this
/// shape. Everything here is arithmetic on the year and the month, never on a `DateTime`: which
/// month a household is budgeting is a calendar question, and a time zone has no say in it.
@immutable
final class YearMonth implements Comparable<YearMonth> {
  factory YearMonth(int year, int month) {
    if (month < 1 || month > 12 || year < 0 || year > 9999) {
      throw FormatException('not a month', '$year-$month');
    }
    return YearMonth._(year, month);
  }

  const YearMonth._(this.year, this.month);

  /// `YYYY-MM`, as the API sends it. Anything else is refused.
  factory YearMonth.parse(String wire) {
    final match = _wire.firstMatch(wire);
    if (match == null) throw FormatException('not a month', wire.length > 16 ? '…' : wire);
    return YearMonth._(int.parse(match.group(1)!), int.parse(match.group(2)!));
  }

  /// The month [date] falls in.
  factory YearMonth.of(CalendarDate date) => YearMonth._(date.year, date.month);

  static final RegExp _wire = RegExp(r'^(\d{4})-(0[1-9]|1[0-2])$');

  /// The month a wire string names, or null for null or anything that is not one.
  static YearMonth? tryParse(String? wire) =>
      wire == null || !_wire.hasMatch(wire) ? null : YearMonth.parse(wire);

  final int year;
  final int month;

  /// Months since year zero, for placing a point along an axis and for "N months sooner".
  int get ordinal => year * 12 + month - 1;

  /// The month [months] later (or earlier, when negative). December wraps without a special case.
  YearMonth shift(int months) {
    final total = ordinal + months;
    return YearMonth._(total ~/ 12, total % 12 + 1);
  }

  /// How many months from this one to [other]: positive when [other] is later.
  int monthsUntil(YearMonth other) => other.ordinal - ordinal;

  String get wire => '${year.toString().padLeft(4, '0')}-${month.toString().padLeft(2, '0')}';

  /// "August 2026", for the reader's locale — the web's `formatMonth`.
  String long(String locale) => DateFormat.yMMMM(locale).format(DateTime.utc(year, month));

  @override
  int compareTo(YearMonth other) => ordinal.compareTo(other.ordinal);

  bool operator <(YearMonth other) => ordinal < other.ordinal;

  bool operator >(YearMonth other) => ordinal > other.ordinal;

  @override
  bool operator ==(Object other) => other is YearMonth && other.ordinal == ordinal;

  @override
  int get hashCode => ordinal.hashCode;

  @override
  String toString() => wire;
}

/// "August 2026" for a wire month, or an empty string for anything that is not one — the web's
/// `formatMonth`, which a sentence then leaves without a month rather than with a raw code.
String monthText(String? wire, String locale) => YearMonth.tryParse(wire)?.long(locale) ?? '';
