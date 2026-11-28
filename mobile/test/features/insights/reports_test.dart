import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/features/insights/domain/reports.dart';
import 'package:budget_owl/features/insights/presentation/income_chart.dart';
import 'package:budget_owl/features/insights/presentation/insights_screen.dart';
import 'package:budget_owl/l10n/gen/app_localizations_en.dart';
import 'package:decimal/decimal.dart';
import 'package:flutter_test/flutter_test.dart';

/// Insights' own decisions: which months to ask for, how a change is said, and where a point sits.
/// None of them is money arithmetic.
void main() {
  final today = DateTime(2026, 8, 20, 12);
  Decimal d(String s) => Decimal.parse(s);
  Money usd(String s) => Money.fromWire(s, 'USD');

  test("the ranges end with the phone's own month", () {
    expect(lastMonths(RangeMonths.twelve, today), (from: '2025-09', to: '2026-08'));
    expect(lastMonths(RangeMonths.six, today), (from: '2026-03', to: '2026-08'));
    expect(lastMonths(RangeMonths.three, today), (from: '2026-06', to: '2026-08'));
    expect(lastMonths(RangeMonths.twelve, DateTime(2027, 1, 3)), (from: '2026-02', to: '2027-01'));
    expect(lastCompleteMonth(DateTime(2026, 1, 5)), '2025-12');
  });

  test('a change is compared, never computed', () {
    expect(directionOf(d('0.1602'), usd('612.0000')), Direction.up);
    expect(directionOf(d('-0.2200'), usd('300.0000')), Direction.down);
    expect(directionOf(d('0.0000'), usd('300.0000')), Direction.flat);
    expect(directionOf(null, usd('12.0000')), Direction.fresh);
    expect(directionOf(null, usd('-12.0000')), Direction.none);
    expect(isUnderOnePercent(d('0.0049')), isTrue);
    expect(isUnderOnePercent(d('0.0050')), isFalse);
    expect(isUnderOnePercent(d('0')), isFalse);
  });

  test('a percentage is said without its sign, rounded in Decimal', () {
    final words = InsightWords.of(AppLocalizationsEn(), 'en_US');
    expect(words.percent(d('-0.2249')), '22%');
    expect(words.percent(d('0.1650')), '17%');
  });

  test('the file name holds the report and the two months, and only those', () {
    expect(
      csvFileName(ReportName.spendingByCategory, '2026-07', '2026-07'),
      'budget-owl-spending-by-category-2026-07-2026-07.csv',
    );
    expect(
      () => csvFileName(ReportName.incomeAndSpending, '2026-7', '2026-08'),
      throwsArgumentError,
    );
  });

  test('a point is a dimensionless ratio in ten-thousandths; below zero sits on the baseline', () {
    expect(heightRatio(usd('1825.0000'), d('3650')), 5000);
    expect(heightRatio(usd('3650.0000'), d('3650')), 10000);
    expect(heightRatio(usd('-20.0000'), d('3650')), 0);
    expect(heightRatio(usd('10.0000'), Decimal.zero), 0);
    // The largest amount the API sends keeps its place exactly: no double on the way.
    expect(heightRatio(usd('999999999999999.9999'), d('999999999999999.9999')), 10000);
    expect(
      heightRatio(usd('499999999999999.99995'.substring(0, 20)), d('999999999999999.9999')),
      5000,
    );
  });
}
