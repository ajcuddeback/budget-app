import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:decimal/decimal.dart';
import 'package:intl/intl.dart';

/// One envelope, as `EnvelopeResponse` sends it (envelopes.md).
///
/// **`remaining` is the headline and is the server's**: `amount` minus what was logged, stored
/// nowhere and computed nowhere here. It is negative for an overspent envelope, and shown so —
/// "you are $11.20 over" is the information. Every figure is the server's string as a [Money].
final class Envelope {
  const Envelope({
    required this.id,
    required this.name,
    required this.period,
    required this.amountWire,
    required this.amount,
    required this.spent,
    required this.remaining,
    required this.currency,
    required this.entryCount,
    required this.archived,
    required this.version,
    this.categoryId,
    this.categoryName,
    this.categoryParentName,
  });

  factory Envelope.fromJson(JsonMap json) {
    final currency = json.string('currency');
    final amount = json.string('amount');
    return Envelope(
      id: json.string('id'),
      name: json.string('name'),
      period: json.string('period'),
      categoryId: json.stringOrNull('categoryId'),
      categoryName: json.stringOrNull('categoryName'),
      categoryParentName: json.stringOrNull('categoryParentName'),
      amountWire: amount,
      amount: wireMoney(amount, currency, 'amount'),
      spent: wireMoney(json.string('spent'), currency, 'spent'),
      remaining: wireMoney(json.string('remaining'), currency, 'remaining'),
      currency: currency,
      entryCount: json.integer('entryCount'),
      archived: json.boolean('archived'),
      version: json.integer('version'),
    );
  }

  final String id;
  final String name;

  /// `YYYY-MM`: a label, not a date range (ADR-0031).
  final String period;
  final String? categoryId;
  final String? categoryName;
  final String? categoryParentName;

  /// The decided amount as the server sent it, for reopening the form and comparing against.
  final String amountWire;
  final Money amount;
  final Money spent;
  final Money remaining;

  /// The household's base currency: every envelope shares it.
  final String currency;
  final int entryCount;
  final bool archived;
  final int version;

  /// Read from the sign the server sent.
  bool get overspent => remaining.isNegative;

  /// How full the bar is, in whole percent, 0 to 100.
  ///
  /// The web puts the two figures through `Number` for this, deliberately, because a bar width is
  /// not a figure anybody reads. Here it stays exact: a [Decimal] ratio rounded to an integer, and
  /// no `double` is ever made from an amount. Clamped for the bar only — the figures beside it are
  /// not, and an overspent bar is marked so a full one cannot read as "exactly on plan".
  int get spentPercent {
    final whole = amount.amount;
    final used = spent.amount;
    if (whole <= Decimal.zero) return used > Decimal.zero ? 100 : 0;
    final percent = (used * Decimal.fromInt(100) / whole).round().toInt();
    return percent.clamp(0, 100);
  }
}

/// One month's envelopes and its totals — **the server's totals**, safe because one household has
/// one base currency, and computed there anyway: this app adds nothing.
final class EnvelopePage {
  const EnvelopePage({
    required this.envelopes,
    required this.totalAllocated,
    required this.totalSpent,
    required this.totalRemaining,
  });

  factory EnvelopePage.fromJson(JsonMap json) {
    final currency = json.string('currency');
    return EnvelopePage(
      envelopes: [for (final e in json.objects('content')) Envelope.fromJson(e)],
      totalAllocated: wireMoney(json.string('totalAllocated'), currency, 'totalAllocated'),
      totalSpent: wireMoney(json.string('totalSpent'), currency, 'totalSpent'),
      totalRemaining: wireMoney(json.string('totalRemaining'), currency, 'totalRemaining'),
    );
  }

  final List<Envelope> envelopes;
  final Money totalAllocated;
  final Money totalSpent;

  /// Excludes archived envelopes, as the server computes it (envelopes.md, API).
  final Money totalRemaining;
}

/// Where an entry's figure came from. **A person decided either way**: `SYNCED` means somebody
/// attached a transaction they had recorded, never that the app filed one by itself.
enum EntrySource { manual, synced, unknown }

/// One row of an envelope's log.
final class EnvelopeEntry {
  const EnvelopeEntry({
    required this.id,
    required this.date,
    required this.amountWire,
    required this.amount,
    required this.source,
    required this.version,
    this.payeeName,
    this.note,
  });

  factory EnvelopeEntry.fromJson(JsonMap json) {
    final amount = json.string('amount');
    return EnvelopeEntry(
      id: json.string('id'),
      date: _date(json.string('date')),
      amountWire: amount,
      amount: wireMoney(amount, json.string('currency'), 'amount'),
      payeeName: json.stringOrNull('payeeName'),
      note: json.stringOrNull('note'),
      source: switch (json.string('source')) {
        'MANUAL' => EntrySource.manual,
        'SYNCED' => EntrySource.synced,
        _ => EntrySource.unknown,
      },
      version: json.integer('version'),
    );
  }

  final String id;
  final CalendarDate date;
  final String amountWire;

  /// Positive: how much of the decided amount this used.
  final Money amount;
  final String? payeeName;
  final String? note;
  final EntrySource source;
  final int version;

  /// The web's key for [source], said on every row of the log.
  String get sourceKey => switch (source) {
    EntrySource.manual => 'envelopes.source.MANUAL',
    EntrySource.synced => 'envelopes.source.SYNCED',
    EntrySource.unknown => 'envelopes.source.unknown',
  };
}

CalendarDate _date(String iso) {
  try {
    return CalendarDate.parse(iso);
  } on FormatException {
    throw const UnexpectedJson('date');
  }
}

final RegExp _period = RegExp(r'^(\d{4})-(\d{2})$');

/// The month [now] falls in on the person's own calendar, `YYYY-MM`.
String currentPeriod(DateTime now) => _format(now.year, now.month);

String _format(int year, int month) =>
    '${year.toString().padLeft(4, '0')}-${month.toString().padLeft(2, '0')}';

/// [period] moved by [step] months: `2026-01` back one is `2025-12`.
String shiftPeriod(String period, int step) {
  final match = _period.firstMatch(period);
  if (match == null) throw FormatException('not a period', period);
  final index = int.parse(match.group(1)!) * 12 + int.parse(match.group(2)!) - 1 + step;
  return _format(index ~/ 12, index % 12 + 1);
}

/// "August 2026" in the viewer's language — never "2026-08".
String periodName(String period, String locale) {
  final match = _period.firstMatch(period);
  if (match == null) return period;
  return DateFormat.yMMMM(locale)
      .format(DateTime.utc(int.parse(match.group(1)!), int.parse(match.group(2)!)));
}

/// Which month the screen opens on, as the web decides it: the current one when it has envelopes
/// or when no month has any; otherwise the most recent month that has some (the server lists them
/// newest first). Landing on an empty month looks broken to somebody who set envelopes up.
String openingPeriod(List<String> periods, String current) =>
    periods.isNotEmpty && !periods.contains(current) ? periods.first : current;
