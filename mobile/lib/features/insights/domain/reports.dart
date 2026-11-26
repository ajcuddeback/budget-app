import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:decimal/decimal.dart';

/// The two reports, in the shape the API sends them (reporting.md, API), as the web's
/// `reports.models.ts` reads them.
///
/// **Every amount is [Money] from its wire string, and every ratio a [Decimal] from its own.**
/// Nothing here adds, averages or divides money: `averageKept`, `bestMonth`, `change` and `share`
/// are the server's. What the phone decides is only *which* months to ask about, from its own date
/// (reporting.md decision 10), and how to say what came back.

Money _money(JsonMap json, String key, String currency) {
  try {
    return Money.fromWire(json.string(key), currency);
  } on FormatException {
    throw UnexpectedJson('$key is not an amount');
  }
}

Decimal? _ratioOrNull(JsonMap json, String key) {
  final text = json.stringOrNull(key);
  if (text == null) return null;
  try {
    return parseWireAmount(text);
  } on FormatException {
    throw UnexpectedJson('$key is not a ratio');
  }
}

final RegExp _period = RegExp(r'^\d{4}-(0[1-9]|1[0-2])$');

String _periodOf(JsonMap json, String key) {
  final text = json.string(key);
  if (!_period.hasMatch(text)) throw UnexpectedJson('$key is not a month');
  return text;
}

/// One month of one currency.
final class MonthFigures {
  const MonthFigures({
    required this.period,
    required this.income,
    required this.spending,
    required this.uncategorisedIn,
    required this.uncategorisedOut,
    required this.kept,
    required this.complete,
  });

  factory MonthFigures.fromJson(JsonMap json, String currency) => MonthFigures(
    period: _periodOf(json, 'period'),
    income: _money(json, 'income', currency),
    spending: _money(json, 'spending', currency),
    uncategorisedIn: _money(json, 'uncategorisedIn', currency),
    uncategorisedOut: _money(json, 'uncategorisedOut', currency),
    kept: _money(json, 'kept', currency),
    complete: json.boolean('complete'),
  );

  final String period;
  final Money income;

  /// Positive when money left; negative, never clamped, when refunds beat spending.
  final Money spending;
  final Money uncategorisedIn;
  final Money uncategorisedOut;

  /// Signed. Negative means more left than arrived.
  final Money kept;

  /// False for the current month, by the server's clock.
  final bool complete;
}

final class CurrencyIncome {
  const CurrencyIncome({
    required this.currency,
    required this.months,
    required this.uncategorisedIn,
    required this.uncategorisedOut,
    required this.completeMonths,
    required this.uncategorisedCount,
    required this.transactionCount,
    this.averageKept,
    this.bestPeriod,
    this.bestKept,
  });

  factory CurrencyIncome.fromJson(JsonMap json) {
    final currency = json.string('currency');
    final totals = json.object('totals');
    final best = json.objectOrNull('bestMonth');
    return CurrencyIncome(
      currency: currency,
      months: [for (final m in json.objects('months')) MonthFigures.fromJson(m, currency)],
      uncategorisedIn: _money(totals, 'uncategorisedIn', currency),
      uncategorisedOut: _money(totals, 'uncategorisedOut', currency),
      averageKept: json.stringOrNull('averageKept') == null
          ? null
          : _money(json, 'averageKept', currency),
      bestPeriod: best == null ? null : _periodOf(best, 'period'),
      bestKept: best == null ? null : _money(best, 'kept', currency),
      completeMonths: json.integer('completeMonths'),
      uncategorisedCount: json.integer('uncategorisedCount'),
      transactionCount: json.integer('transactionCount'),
    );
  }

  final String currency;

  /// Dense: every month in the range, zeros where nothing happened.
  final List<MonthFigures> months;

  /// The range's uncategorised totals, summed by the server so the phone adds nothing up.
  final Money uncategorisedIn;
  final Money uncategorisedOut;

  /// Null when no month in the range is complete — never shown as zero.
  final Money? averageKept;
  final String? bestPeriod;
  final Money? bestKept;
  final int completeMonths;
  final int uncategorisedCount;

  /// Zero is the empty state: figures that net to zero are not "nothing recorded".
  final int transactionCount;
}

final class IncomeAndSpending {
  const IncomeAndSpending({
    required this.from,
    required this.to,
    required this.baseCurrency,
    required this.currencies,
  });

  factory IncomeAndSpending.fromJson(JsonMap json) => IncomeAndSpending(
    from: _periodOf(json, 'from'),
    to: _periodOf(json, 'to'),
    baseCurrency: json.string('baseCurrency'),
    currencies: [for (final c in json.objects('currencies')) CurrencyIncome.fromJson(c)],
  );

  final String from;
  final String to;
  final String baseCurrency;

  /// The base currency first, always present; then the others in code order.
  final List<CurrencyIncome> currencies;

  CurrencyIncome? of(String currency) {
    for (final c in currencies) {
      if (c.currency == currency) return c;
    }
    return null;
  }
}

final class CategorySpending {
  const CategorySpending({
    required this.categoryId,
    required this.name,
    required this.archived,
    required this.spending,
    required this.children,
    this.change,
    this.share,
  });

  factory CategorySpending.fromJson(JsonMap json, String currency) => CategorySpending(
    categoryId: json.string('categoryId'),
    name: json.string('name'),
    archived: json.boolean('archived'),
    spending: _money(json, 'spending', currency),
    change: _ratioOrNull(json, 'change'),
    share: _ratioOrNull(json, 'share'),
    children: [for (final c in json.objects('children')) CategorySpending.fromJson(c, currency)],
  );

  final String categoryId;
  final String name;
  final bool archived;
  final Money spending;

  /// `(spending − compared) ÷ |compared|`, by the server. Null against nothing.
  final Decimal? change;

  /// This row's fraction of the positive spending, by the server. Null for a non-positive row.
  final Decimal? share;
  final List<CategorySpending> children;
}

final class CurrencySpending {
  const CurrencySpending({
    required this.currency,
    required this.spending,
    required this.comparedSpending,
    required this.categories,
    required this.uncategorisedSpending,
    required this.uncategorisedCount,
  });

  factory CurrencySpending.fromJson(JsonMap json) {
    final currency = json.string('currency');
    final uncategorised = json.object('uncategorised');
    return CurrencySpending(
      currency: currency,
      spending: _money(json, 'spending', currency),
      comparedSpending: _money(json, 'comparedSpending', currency),
      categories: [
        for (final c in json.objects('categories')) CategorySpending.fromJson(c, currency),
      ],
      uncategorisedSpending: _money(uncategorised, 'spending', currency),
      uncategorisedCount: uncategorised.integer('count'),
    );
  }

  final String currency;
  final Money spending;
  final Money comparedSpending;

  /// Top-level rows, spending descending, then name, then id.
  final List<CategorySpending> categories;
  final Money uncategorisedSpending;
  final int uncategorisedCount;
}

final class SpendingByCategory {
  const SpendingByCategory({
    required this.from,
    required this.comparedFrom,
    required this.complete,
    required this.currencies,
  });

  factory SpendingByCategory.fromJson(JsonMap json) => SpendingByCategory(
    from: _periodOf(json, 'from'),
    comparedFrom: _periodOf(json, 'comparedFrom'),
    complete: json.boolean('complete'),
    currencies: [for (final c in json.objects('currencies')) CurrencySpending.fromJson(c)],
  );

  final String from;
  final String comparedFrom;

  /// False when the range reaches the current month, by the server's clock.
  final bool complete;
  final List<CurrencySpending> currencies;

  CurrencySpending? of(String currency) {
    for (final c in currencies) {
      if (c.currency == currency) return c;
    }
    return null;
  }
}

// ----------------------------------------------------------------------- the phone's decisions

/// The three ranges the designs offer. The server accepts up to 24.
enum RangeMonths {
  twelve(12),
  six(6),
  three(3);

  RangeMonths(this.months);
  final int months;
}

/// `2026-08` for the month [today] falls in, where the person is.
String periodOf(DateTime today) =>
    '${today.year.toString().padLeft(4, '0')}-${today.month.toString().padLeft(2, '0')}';

/// [period] moved by [months], whole months only.
String shiftPeriod(String period, int months) {
  final year = int.parse(period.substring(0, 4));
  final month = int.parse(period.substring(5, 7));
  final index = year * 12 + (month - 1) + months;
  return '${(index ~/ 12).toString().padLeft(4, '0')}-${(index % 12 + 1).toString().padLeft(2, '0')}';
}

/// The last [range] months ending with the current one, **by the phone's own date**: only the
/// device knows the person's "today" (reporting.md decision 10).
({String from, String to}) lastMonths(RangeMonths range, DateTime today) {
  final to = periodOf(today);
  return (from: shiftPeriod(to, -(range.months - 1)), to: to);
}

/// "Where {month} went" opens on the last complete month: a half-finished month set against a
/// whole one makes every category look like it fell (decision 6).
String lastCompleteMonth(DateTime today) => shiftPeriod(periodOf(today), -1);

/// How a category's spending moved, said in words as well as in colour.
enum Direction { up, down, flat, fresh, none }

/// The direction of a server-computed change: compared, never computed.
Direction directionOf(Decimal? change, Money spending) {
  if (change == null) return spending.amount.sign > 0 ? Direction.fresh : Direction.none;
  if (change == Decimal.zero) return Direction.flat;
  return change.sign > 0 ? Direction.up : Direction.down;
}

final Decimal _halfPercent = Decimal.parse('0.005');

/// A non-zero change that would round to "0%", so the screen says "less than 1%" instead.
bool isUnderOnePercent(Decimal change) => change != Decimal.zero && change.abs() < _halfPercent;

/// Which report a CSV is of: the two resource names, which are also the file's name.
enum ReportName {
  incomeAndSpending('income-and-spending'),
  spendingByCategory('spending-by-category');

  ReportName(this.path);
  final String path;
}

/// The file name the server would give, built from the report and the two periods only: never a
/// household's or a category's name (reporting.md, CSV export).
String csvFileName(ReportName report, String from, String to) {
  if (!_period.hasMatch(from) || !_period.hasMatch(to)) {
    throw ArgumentError('not a month');
  }
  return 'budget-owl-${report.path}-$from-$to.csv';
}

/// How many category rows show before "Show all". The canvas draws four; six fit a phone.
const int visibleCategories = 6;
