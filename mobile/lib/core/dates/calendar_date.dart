import 'package:intl/intl.dart';
import 'package:meta/meta.dart';

/// A date with no time and no zone: a receipt's date, a statement's date (`2026-08-20`).
///
/// **Never converted through a time zone** (mobile-app.md, Language). It is formatted from a UTC
/// `DateTime` at midnight, whose fields are exactly the year, month and day it was built from, so
/// a phone in Auckland and a phone in Honolulu print the same day the server stored.
@immutable
final class CalendarDate implements Comparable<CalendarDate> {
  CalendarDate(this.year, this.month, this.day) {
    final probe = DateTime.utc(year, month, day);
    if (probe.year != year || probe.month != month || probe.day != day) {
      throw FormatException('not a calendar date', '$year-$month-$day');
    }
  }

  /// `YYYY-MM-DD`, as the API sends and receives it.
  factory CalendarDate.parse(String iso) {
    final match = RegExp(r'^(\d{4})-(\d{2})-(\d{2})$').firstMatch(iso);
    if (match == null) throw FormatException('not an ISO date', iso);
    return CalendarDate(
      int.parse(match.group(1)!),
      int.parse(match.group(2)!),
      int.parse(match.group(3)!),
    );
  }

  /// The calendar date [now] falls on where the person is. "Today" is a question about the wall
  /// they are looking at, so this is the one place a local time is read.
  factory CalendarDate.of(DateTime now) => CalendarDate(now.year, now.month, now.day);

  final int year;
  final int month;
  final int day;

  String get iso =>
      '${year.toString().padLeft(4, '0')}-${month.toString().padLeft(2, '0')}-'
      '${day.toString().padLeft(2, '0')}';

  DateTime get _utc => DateTime.utc(year, month, day);

  /// "20 Aug 2026" / "Aug 20, 2026", by locale.
  String medium(String locale) => DateFormat.yMMMd(locale).format(_utc);

  /// "Thursday 20 August" / "Thursday, August 20", by locale — a ledger's day heading.
  String heading(String locale) => DateFormat.MMMMEEEEd(locale).format(_utc);

  /// Midday local, for a platform date picker. Never used to compute a date back.
  DateTime get forPicker => DateTime(year, month, day, 12);

  @override
  int compareTo(CalendarDate other) => iso.compareTo(other.iso);

  @override
  bool operator ==(Object other) => other is CalendarDate && other.iso == iso;

  @override
  int get hashCode => iso.hashCode;

  @override
  String toString() => iso;
}
