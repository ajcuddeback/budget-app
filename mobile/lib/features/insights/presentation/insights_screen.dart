import 'dart:async';

import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/insights/data/csv_export.dart';
import 'package:budget_owl/features/insights/data/reports_repository.dart';
import 'package:budget_owl/features/insights/domain/reports.dart';
import 'package:budget_owl/features/insights/presentation/income_chart.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:decimal/decimal.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:material_ui/material_ui.dart';

/// Insights: what actually happened in the ledger, month by month (reporting.md).
///
/// Two cards — income against spending by month, and where one month went — each read from its
/// own report, each computed by the server on request. **This screen does no money arithmetic**:
/// every figure, average, change and share is the server's. What it decides is which months to ask
/// about, from the phone's own date, and how to say what came back.
///
/// A `VIEWER` sees exactly what an `OWNER` sees: there is no write control here to hide. The CSV
/// export is a read, offered to everyone, through the share sheet (mobile-app.md, Screens).
class InsightsScreen extends ConsumerStatefulWidget {
  const InsightsScreen({super.key});

  @override
  ConsumerState<InsightsScreen> createState() => _InsightsScreenState();
}

class _InsightsScreenState extends ConsumerState<InsightsScreen> {
  RangeMonths _range = RangeMonths.twelve;
  String? _month;
  String? _currency;
  bool _showAll = false;
  final Set<String> _expanded = {};
  ReportName? _exporting;
  Object? _exportError;

  @override
  void initState() {
    super.initState();
    // A shared export's platform copy is left for the app that received it; by the time the
    // person is back here it has had its chance to read it (csv_export.dart).
    unawaited(_sweep());
  }

  Future<void> _sweep() async {
    try {
      final temporary = await ref.read(exportDirectoryProvider.future);
      await ref.read(fileSharerProvider).sweep(temporary);
    } on Object {
      // Nowhere to sweep: the next export sweeps again.
    }
  }

  DateTime get _today => ref.read(clockProvider)();

  ({String from, String to}) get _rangeQuery => lastMonths(_range, _today);

  String get _shownMonth => _month ?? lastCompleteMonth(_today);

  /// The next month exists only up to the current one: the future has nothing to show yet.
  bool get _canGoForward => _shownMonth.compareTo(periodOf(_today)) < 0;

  void _step(int by) => setState(() {
    final next = shiftPeriod(_shownMonth, by);
    if (by > 0 && next.compareTo(periodOf(_today)) > 0) return;
    _month = next;
    _showAll = false;
    _expanded.clear();
  });

  Future<void> _export(BuildContext anchor, ReportName report, String from, String to) async {
    if (_exporting != null) return;
    final box = anchor.findRenderObject();
    final origin = box is RenderBox && box.hasSize
        ? box.localToGlobal(Offset.zero) & box.size
        : null;
    setState(() {
      _exporting = report;
      _exportError = null;
    });
    try {
      await ref.read(csvExporterProvider).export(report, from: from, to: to, origin: origin);
    } on Object catch (error) {
      if (mounted) setState(() => _exportError = error);
    } finally {
      if (mounted) setState(() => _exporting = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final income = ref.watch(incomeAndSpendingProvider(_rangeQuery));
    final spending = ref.watch(spendingByCategoryProvider(_shownMonth));
    return Screen(
      title: l.insights_title,
      onRefresh: () => Future.wait([
        ref.read(incomeAndSpendingProvider(_rangeQuery).notifier).refresh(),
        ref.read(spendingByCategoryProvider(_shownMonth).notifier).refresh(),
      ]),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Lede(l.insights_lede),
          if (_exportError != null)
            Gutter(
              child: NoticeBanner(
                icon: Icons.error_outline,
                text: switch (_exportError) {
                  final AppError error => context.errorMessage(error),
                  _ => l.insights_shareFailed,
                },
              ),
            ),
          SnapshotView<IncomeAndSpending>(
            snapshot: income,
            onRetry: () => ref.read(incomeAndSpendingProvider(_rangeQuery).notifier).refresh(),
            builder: (context, report, stale) {
              final currency = report.of(_currency ?? '') == null
                  ? report.baseCurrency
                  : _currency!;
              final figures = report.of(currency);
              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  ?(stale == null ? null : Gutter(child: stale)),
                  if (report.currencies.length > 1) _currencySwitch(context, report, currency),
                  if (figures != null) _incomeCard(context, report, figures),
                  _whereCard(context, spending, currency),
                ],
              );
            },
          ),
        ],
      ),
    );
  }

  Widget _currencySwitch(BuildContext context, IncomeAndSpending report, String currency) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    return Gutter(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l.insights_currency_label, style: text.titleSmall),
          Wrap(
            spacing: SpaceTokens.s2,
            children: [
              for (final c in report.currencies)
                ChoiceChip(
                  label: Text(c.currency),
                  selected: c.currency == currency,
                  showCheckmark: false,
                  onSelected: (_) => setState(() => _currency = c.currency),
                ),
            ],
          ),
          Text(
            l.insights_currency_note,
            style: text.bodySmall?.copyWith(color: context.colors.muted),
          ),
        ],
      ),
    );
  }

  // ------------------------------------------------------------------- income vs spending

  Widget _incomeCard(BuildContext context, IncomeAndSpending report, CurrencyIncome figures) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    final words = InsightWords(context);
    final empty = figures.transactionCount == 0;
    final average = figures.averageKept;
    final months = figures.months;
    final range = words.range(report.from, report.to);
    final kept = words.keptLong(figures);
    final uncategorised = words.uncategorised(figures);
    return _Card(
      children: [
        Wrap(
          alignment: WrapAlignment.spaceBetween,
          crossAxisAlignment: WrapCrossAlignment.center,
          spacing: SpaceTokens.s2,
          children: [
            Semantics(
              header: true,
              child: Text(
                l.insights_income_heading.toUpperCase(),
                style: text.labelSmall?.copyWith(color: context.colors.muted),
              ),
            ),
            if (average != null && !empty)
              Text(
                l.insights_keptShort(words.money(average)),
                style: text.labelLarge?.copyWith(color: context.palette.incoming),
              ),
          ],
        ),
        const SizedBox(height: SpaceTokens.s2),
        // Pressed chips, as the web's: a chip asks report 1 again and saves nothing.
        Semantics(
          label: l.insights_range_label,
          container: true,
          explicitChildNodes: true,
          child: Wrap(
            spacing: SpaceTokens.s2,
            children: [
              for (final option in RangeMonths.values)
                Semantics(
                  label: words.rangeName(option),
                  selected: option == _range,
                  button: true,
                  excludeSemantics: true,
                  child: ChoiceChip(
                    label: Text(words.rangeShort(option)),
                    selected: option == _range,
                    showCheckmark: false,
                    onSelected: (_) => setState(() => _range = option),
                  ),
                ),
            ],
          ),
        ),
        const SizedBox(height: SpaceTokens.s2),
        if (empty) ...[
          _Note(l.insights_empty(words.month(report.from), words.month(report.to))),
          _Note(l.insights_envelopes),
          _Link(l.insights_empty_link, () => context.go('/transactions')),
        ] else ...[
          ?(kept == null ? null : _Note(kept)),
          const SizedBox(height: SpaceTokens.s2),
          IncomeChart(months: months, label: words.chartLabel(figures)),
          const SizedBox(height: SpaceTokens.s2),
          _Legend(coincident: coincident(months)),
          Text(range, style: text.bodySmall?.copyWith(color: context.colors.muted)),
          if (coincident(months)) _Note(l.insights_coincident),
          if (months.any((m) => m.spending.isNegative)) _Note(l.insights_chart_negative),
          if (uncategorised != null) ...[
            _Note(uncategorised),
            _Link(l.insights_uncategorised_link, () => context.go('/transactions')),
          ],
          _FiguresTable(
            months: months,
            words: words,
            caption: l.insights_figures_caption(range, figures.currency),
          ),
          _exportButton(
            l.insights_downloadIncome,
            ReportName.incomeAndSpending,
            report.from,
            report.to,
          ),
        ],
      ],
    );
  }

  // ------------------------------------------------------------------------ where it went

  Widget _whereCard(BuildContext context, Snapshot<SpendingByCategory> snapshot, String currency) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    final words = InsightWords(context);
    final month = words.month(_shownMonth);
    final report = snapshot.data;
    return _Card(
      children: [
        Row(
          children: [
            Expanded(
              child: Semantics(
                header: true,
                child: Text(
                  (report?.complete == false
                          ? l.insights_where_headingSoFar(month)
                          : l.insights_where_heading(month))
                      .toUpperCase(),
                  style: text.labelSmall?.copyWith(color: context.colors.muted),
                ),
              ),
            ),
            IconButton(
              tooltip: l.insights_where_previous,
              onPressed: () => _step(-1),
              icon: const Icon(Icons.chevron_left),
            ),
            IconButton(
              tooltip: l.insights_where_next,
              onPressed: _canGoForward ? () => _step(1) : null,
              icon: const Icon(Icons.chevron_right),
            ),
          ],
        ),
        SnapshotView<SpendingByCategory>(
          snapshot: snapshot,
          onRetry: () => ref.read(spendingByCategoryProvider(_shownMonth).notifier).refresh(),
          builder: (context, report, stale) {
            final figures = report.of(currency);
            final rows = figures?.categories ?? const <CategorySpending>[];
            if (figures == null || (rows.isEmpty && figures.uncategorisedCount == 0)) {
              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [?stale, _Note(l.insights_where_nothing(month))],
              );
            }
            final visible = _showAll ? rows : rows.take(visibleCategories).toList();
            final hidden = rows.length - visibleCategories;
            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                ?stale,
                _ShareBar(rows: rows),
                if (rows.isNotEmpty)
                  _Note(
                    l.insights_where_total(
                      words.money(figures.spending),
                      words.money(figures.comparedSpending),
                      words.month(report.comparedFrom),
                    ),
                  ),
                Semantics(
                  label: l.insights_where_list(month),
                  container: true,
                  explicitChildNodes: true,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      for (var i = 0; i < visible.length; i++)
                        _CategoryRow(
                          row: visible[i],
                          tone: segmentTone(context, i),
                          words: words,
                          expanded: _expanded.contains(visible[i].categoryId),
                          onToggle: () => setState(() {
                            final id = visible[i].categoryId;
                            if (!_expanded.remove(id)) _expanded.add(id);
                          }),
                        ),
                    ],
                  ),
                ),
                if (hidden > 0)
                  _Link(
                    _showAll
                        ? l.insights_where_showFewer
                        : l.insights_where_showAll(words.count(rows.length)),
                    () => setState(() => _showAll = !_showAll),
                  ),
                if (figures.uncategorisedCount > 0) ...[
                  _Note(
                    figures.uncategorisedCount == 1
                        ? l.insights_where_uncategorised_one(
                            words.money(figures.uncategorisedSpending),
                          )
                        : l.insights_where_uncategorised_many(
                            words.money(figures.uncategorisedSpending),
                            words.count(figures.uncategorisedCount),
                          ),
                  ),
                  _Link(l.insights_uncategorised_link, () => context.go('/transactions')),
                ],
                _exportButton(
                  l.insights_downloadWhere(month),
                  ReportName.spendingByCategory,
                  _shownMonth,
                  _shownMonth,
                ),
              ],
            );
          },
        ),
      ],
    );
  }

  Widget _exportButton(String what, ReportName report, String from, String to) => Builder(
    builder: (anchor) {
      final l = anchor.l10n;
      final busy = _exporting == report;
      return Padding(
        padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s2),
        child: Align(
          alignment: AlignmentDirectional.centerStart,
          child: Semantics(
            label: '${busy ? l.insights_downloading : l.insights_download} $what',
            button: true,
            excludeSemantics: true,
            child: OutlinedButton.icon(
              onPressed: _exporting == null ? () => _export(anchor, report, from, to) : null,
              icon: const Icon(Icons.ios_share),
              label: Text(busy ? l.insights_downloading : l.insights_download),
            ),
          ),
        ),
      );
    },
  );
}

/// Every sentence Insights shows, from the web's keys, with the server's figures.
final class InsightWords {
  InsightWords(BuildContext context) : l = context.l10n, locale = context.localeName;

  InsightWords.of(this.l, this.locale);

  final AppLocalizations l;
  final String locale;

  DateTime _at(String period) =>
      DateTime.utc(int.parse(period.substring(0, 4)), int.parse(period.substring(5, 7)));

  /// "August 2026".
  String month(String period) => DateFormat.yMMMM(locale).format(_at(period));

  /// "Aug 2026".
  String monthShort(String period) => DateFormat.yMMM(locale).format(_at(period));

  /// "Sep 2025 – Aug 2026": month and year, never two days of the month.
  String range(String from, String to) => l.insights_monthRange(monthShort(from), monthShort(to));

  String money(Money value) => formatMoney(value, locale);

  String count(int value) => NumberFormat.decimalPattern(locale).format(value);

  /// A server ratio as a whole percentage, without its sign: the direction is said in words. The
  /// ratio is rounded in [Decimal]; only the whole percentage reaches the formatter.
  String percent(Decimal ratio) {
    final whole = (ratio.abs() * Decimal.fromInt(100)).round().toBigInt().toInt();
    return NumberFormat.percentPattern(locale).format(whole / 100);
  }

  String rangeShort(RangeMonths range) => switch (range) {
    RangeMonths.twelve => l.insights_range_12,
    RangeMonths.six => l.insights_range_6,
    RangeMonths.three => l.insights_range_3,
  };

  String rangeName(RangeMonths range) => switch (range) {
    RangeMonths.twelve => l.insights_range_12_name,
    RangeMonths.six => l.insights_range_6_name,
    RangeMonths.three => l.insights_range_3_name,
  };

  /// The long insight. It says how many months it covers whenever a month in range is
  /// incomplete: "over 11 months" and "over 12" otherwise look alike.
  String? keptLong(CurrencyIncome figures) {
    final average = figures.averageKept;
    final period = figures.bestPeriod;
    final best = figures.bestKept;
    if (average == null || period == null || best == null) return null;
    if (figures.completeMonths == 1) {
      return l.insights_keptLongPartialOne(money(average), month(period));
    }
    return figures.completeMonths < figures.months.length
        ? l.insights_keptLongPartial(
            money(average),
            count(figures.completeMonths),
            month(period),
            money(best),
          )
        : l.insights_keptLong(money(average), month(period), money(best));
  }

  /// The chart's text alternative: its label and each line's first and last month.
  String chartLabel(CurrencyIncome figures) {
    final months = figures.months;
    if (months.isEmpty) return l.insights_chart_label;
    final first = months.first;
    final last = months.last;
    return [
      l.insights_chart_label,
      l.insights_said_income(
        money(first.income),
        month(first.period),
        money(last.income),
        month(last.period),
      ),
      l.insights_said_spending(
        money(first.spending),
        month(first.period),
        money(last.spending),
        month(last.period),
      ),
    ].join(' ');
  }

  /// "3 transactions need a category — $54.80 isn't counted …", from the server's totals.
  String? uncategorised(CurrencyIncome figures) {
    final n = figures.uncategorisedCount;
    if (n == 0) return null;
    final out = figures.uncategorisedOut;
    final into = figures.uncategorisedIn;
    final hasOut = out.amount != Decimal.zero;
    final hasIn = into.amount != Decimal.zero;
    if (hasOut && hasIn) {
      return n == 1
          ? l.insights_uncategorised_one_both(money(out), money(into))
          : l.insights_uncategorised_many_both(count(n), money(out), money(into));
    }
    final amount = money(hasIn ? into : out);
    return n == 1
        ? l.insights_uncategorised_one_single(amount)
        : l.insights_uncategorised_many_single(count(n), amount);
  }

  /// "up 16%", "down 22%", "flat", "new" — the direction in words as well as colour.
  String? change(CategorySpending row) {
    final change = row.change;
    switch (directionOf(change, row.spending)) {
      case Direction.flat:
        return l.insights_change_flat;
      case Direction.fresh:
        return l.insights_change_new;
      case Direction.none:
        return null;
      case Direction.up:
        return isUnderOnePercent(change!)
            ? l.insights_change_upSmall
            : l.insights_change_up(percent(change));
      case Direction.down:
        return isUnderOnePercent(change!)
            ? l.insights_change_downSmall
            : l.insights_change_down(percent(change));
    }
  }
}

/// The proportion bar's segment tokens, in the web's order; the seventh is every row after.
Color segmentTone(BuildContext context, int index) => switch (index) {
  0 => context.colors.accent,
  1 => RampTokens.accent500,
  2 => RampTokens.accent2500,
  3 => RampTokens.accent2400,
  4 => RampTokens.neutral500,
  _ => RampTokens.neutral300,
};

/// The share of a row as a whole number of ten-thousandths, for a flex factor: decided in
/// [Decimal] from the server's own ratio, never from amounts.
int shareFlex(Decimal share) {
  final whole = (share * Decimal.fromInt(10000)).round().toBigInt().toInt();
  return whole < 1 ? 1 : whole;
}

class _ShareBar extends StatelessWidget {
  const _ShareBar({required this.rows});

  final List<CategorySpending> rows;

  @override
  Widget build(BuildContext context) {
    final shared = [
      for (var i = 0; i < rows.length; i++)
        if (rows[i].share != null && rows[i].share!.sign > 0) (index: i, share: rows[i].share!),
    ];
    if (shared.isEmpty) return const SizedBox.shrink();
    // A picture of the rows' shares and nothing more: every name and figure is in the list.
    return ExcludeSemantics(
      child: Padding(
        padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s2),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(RadiusTokens.sm),
          child: SizedBox(
            height: SpaceTokens.s3,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final segment in shared)
                  Expanded(
                    flex: shareFlex(segment.share),
                    child: ColoredBox(color: segmentTone(context, segment.index)),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _CategoryRow extends StatelessWidget {
  const _CategoryRow({
    required this.row,
    required this.tone,
    required this.words,
    required this.expanded,
    required this.onToggle,
  });

  final CategorySpending row;
  final Color tone;
  final InsightWords words;
  final bool expanded;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    return Container(
      decoration: BoxDecoration(
        border: BorderDirectional(bottom: BorderSide(color: context.colors.divider)),
      ),
      padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s2),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _Line(row: row, words: words, dot: tone),
          if (row.children.isNotEmpty) ...[
            Align(
              alignment: AlignmentDirectional.centerStart,
              child: TextButton(
                onPressed: onToggle,
                child: Text(
                  expanded
                      ? l.insights_where_hideChildren(row.name)
                      : l.insights_where_showChildren(row.name),
                ),
              ),
            ),
            if (expanded)
              Padding(
                padding: const EdgeInsetsDirectional.only(start: SpaceTokens.s6),
                child: Column(
                  children: [for (final child in row.children) _Line(row: child, words: words)],
                ),
              ),
          ],
        ],
      ),
    );
  }
}

/// A dot, the name, the amount and the change, wrapping at large text rather than squeezing.
class _Line extends StatelessWidget {
  const _Line({required this.row, required this.words, this.dot});

  final CategorySpending row;
  final InsightWords words;
  final Color? dot;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final change = words.change(row);
    final direction = directionOf(row.change, row.spending);
    // A rise in spending in the accent, a fall in the second accent, both as text roles; the
    // word says which as well, so colour carries nothing alone.
    final tone = switch (direction) {
      Direction.up => context.palette.needsCategory,
      Direction.down => context.palette.incoming,
      _ => context.colors.muted,
    };
    return Padding(
      padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s1),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (dot != null) ...[
            Padding(
              padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s1),
              child: ExcludeSemantics(
                child: Container(
                  width: SpaceTokens.s3,
                  height: SpaceTokens.s3,
                  decoration: BoxDecoration(color: dot, shape: BoxShape.circle),
                ),
              ),
            ),
            const SizedBox(width: SpaceTokens.s2),
          ],
          Expanded(
            child: Wrap(
              spacing: SpaceTokens.s2,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                Text(row.name, style: text.bodyLarge),
                if (row.archived)
                  Text(
                    '(${context.l10n.insights_where_archived})',
                    style: text.bodySmall?.copyWith(color: context.colors.muted),
                  ),
              ],
            ),
          ),
          const SizedBox(width: SpaceTokens.s2),
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                words.money(row.spending),
                textDirection: context.amountDirection,
                style: text.bodyLarge?.copyWith(fontWeight: FontWeight.w600),
              ),
              if (change != null) Text(change, style: text.bodySmall?.copyWith(color: tone)),
            ],
          ),
        ],
      ),
    );
  }
}

class _Legend extends StatelessWidget {
  const _Legend({required this.coincident});

  final bool coincident;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final dark = Theme.of(context).brightness == Brightness.dark;
    Widget key(String label, Color colour, {required bool dashed}) => Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        ExcludeSemantics(
          child: SizedBox(
            width: SpaceTokens.s6,
            height: SpaceTokens.s2,
            child: CustomPaint(painter: _KeyPainter(colour, dashed: dashed)),
          ),
        ),
        const SizedBox(width: SpaceTokens.s1),
        Text(label, style: Theme.of(context).textTheme.bodySmall),
      ],
    );
    return Wrap(
      spacing: SpaceTokens.s4,
      children: [
        key(
          l.insights_legend_income,
          dark ? RampTokens.accent2400 : RampTokens.accent2600,
          dashed: false,
        ),
        if (!coincident)
          key(
            l.insights_legend_spending,
            dark ? RampTokens.accent400 : RampTokens.accent600,
            dashed: true,
          ),
      ],
    );
  }
}

final class _KeyPainter extends CustomPainter {
  _KeyPainter(this.colour, {required this.dashed});

  final Color colour;
  final bool dashed;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = colour
      ..strokeWidth = dashed ? 2 : 3
      ..strokeCap = StrokeCap.round;
    final y = size.height / 2;
    if (!dashed) {
      canvas.drawLine(Offset(0, y), Offset(size.width, y), paint);
      return;
    }
    for (var x = 0.0; x < size.width; x += 9) {
      canvas.drawLine(Offset(x, y), Offset(x + 5 < size.width ? x + 5 : size.width, y), paint);
    }
  }

  @override
  bool shouldRepaint(_KeyPainter old) => old.colour != colour || old.dashed != dashed;
}

/// "Show the figures": exactly the CSV's rows. On a phone each month is a block of labelled
/// figures rather than a table that scrolls sideways (the web's known gap at 412 px).
class _FiguresTable extends StatelessWidget {
  const _FiguresTable({required this.months, required this.words, required this.caption});

  final List<MonthFigures> months;
  final InsightWords words;
  final String caption;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    Widget figure(String label, Money value) => Padding(
      padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s1 / 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Text(label, style: text.bodySmall?.copyWith(color: context.colors.muted)),
          ),
          Text(words.money(value), textDirection: context.amountDirection, style: text.bodyMedium),
        ],
      ),
    );
    return Theme(
      data: Theme.of(context).copyWith(dividerColor: _noHairline(context)),
      child: ExpansionTile(
        tilePadding: EdgeInsetsDirectional.zero,
        childrenPadding: EdgeInsetsDirectional.zero,
        title: Text(l.insights_figures_show, style: text.titleSmall),
        children: [
          Text(caption, style: text.bodySmall?.copyWith(color: context.colors.muted)),
          for (final month in months)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s3),
              child: Semantics(
                container: true,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Semantics(
                      header: true,
                      child: Text(
                        month.complete
                            ? words.month(month.period)
                            : l.insights_soFar(words.month(month.period)),
                        style: text.titleSmall,
                      ),
                    ),
                    figure(l.insights_figures_income, month.income),
                    figure(l.insights_figures_spending, month.spending),
                    figure(l.insights_figures_uncategorisedIn, month.uncategorisedIn),
                    figure(l.insights_figures_uncategorisedOut, month.uncategorisedOut),
                    figure(l.insights_figures_kept, month.kept),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// The divider an expansion tile draws, as the ground it sits on: no hairlines around the
/// disclosure, from the theme rather than a literal.
Color _noHairline(BuildContext context) => context.colors.card.withAlpha(0);

class _Card extends StatelessWidget {
  const _Card({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Gutter(
    child: Card(
      child: Padding(
        padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children),
      ),
    ),
  );
}

class _Note extends StatelessWidget {
  const _Note(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s1),
    child: Text(text, style: Theme.of(context).textTheme.bodyMedium),
  );
}

class _Link extends StatelessWidget {
  const _Link(this.label, this.onPressed);

  final String label;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) => Align(
    alignment: AlignmentDirectional.centerStart,
    child: TextButton(onPressed: onPressed, child: Text(label)),
  );
}
