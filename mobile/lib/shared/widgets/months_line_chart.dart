import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/widgets/plan_widgets.dart';
import 'package:budget_owl/shared/year_month.dart';
import 'package:material_ui/material_ui.dart';

/// Lines over months, drawn from the server's figures — the web's shared `line-chart`, on a canvas.
///
/// **No charting package** (none is needed to draw a polyline). Three rules carry over unchanged:
///
/// - **It plots what it is given and never resamples.** The server already thinned a long line
///   and reported its stride; the page says the stride in words.
/// - **No amount is parsed into a number.** Each point becomes a dimensionless ratio of the largest
///   value on the chart, computed exactly ([amountFraction]); the figures the chart *states* are
///   the server's own strings, in the sentences the page composes.
/// - **It is readable without seeing it**: one semantics node carries the chart's name and each
///   line's own sentence — the same dates and totals the picture shows.

/// One point: what was owed at the start of [month] (`YYYY-MM`), as the wire amount.
@immutable
final class ChartPoint {
  const ChartPoint(this.month, this.amount);

  final String month;
  final String amount;
}

/// One line. [said] is its text alternative, already translated by the page.
@immutable
final class ChartSeries {
  const ChartSeries({
    required this.id,
    required this.name,
    required this.said,
    required this.points,
    required this.compared,
  });

  final String id;
  final String name;
  final String said;
  final List<ChartPoint> points;

  /// The comparison: drawn lighter **and** dashed, never told apart by colour alone.
  final bool compared;
}

/// An optional vertical mark — a payoff month — and its label.
@immutable
final class ChartMarker {
  const ChartMarker(this.month, this.label);

  final String month;
  final String label;
}

/// How close two lines have to be before drawing both is a lie: 0.5% of the largest value, under
/// a pixel at this height. A **display** threshold only — the figures are unchanged and both
/// sentences stay in the text alternative. All lines or none collapse.
const double coincidentTolerance = 0.005;

/// One drawn line: points as fractions of the plot, x along the months and y down from the top.
@immutable
final class PlottedLine {
  const PlottedLine({required this.id, required this.points, required this.compared});

  final String id;
  final List<Offset> points;
  final bool compared;
}

/// The geometry, and the only place a figure becomes a number.
@immutable
final class ChartPlot {
  const ChartPlot({
    required this.lines,
    required this.firstMonth,
    required this.lastMonth,
    required this.coincident,
    this.markerX,
  });

  final List<PlottedLine> lines;
  final YearMonth firstMonth;
  final YearMonth lastMonth;

  /// Every line within [coincidentTolerance] of the first: drawn once, keyed once.
  final bool coincident;

  /// Where the marker's month falls, 0–1, or null when it is off the chart.
  final double? markerX;
}

/// The plot for [series] and [marker], or null when nothing can be drawn: no line with two
/// plottable points, every balance zero, or a single month. A chart of nothing is not drawn; the
/// page says why instead.
ChartPlot? plotOf(List<ChartSeries> series, ChartMarker? marker) {
  final largest = _largest(series);
  if (largest == null) return null;
  final coincident =
      series.length > 1 && series.every((line) => _coincide(series.first, line, largest));

  // One axis for every line, in MONTHS, not point indices: a short line drawn "index out of count"
  // would appear to reach zero at the far right, and the chart would contradict the page.
  YearMonth? first;
  YearMonth? last;
  for (final line in series) {
    for (final point in line.points) {
      final month = YearMonth.tryParse(point.month);
      if (month == null) continue;
      if (first == null || month < first) first = month;
      if (last == null || month > last) last = month;
    }
  }
  if (first == null || last == null || first == last) return null;
  final span = first.monthsUntil(last);

  final lines = <PlottedLine>[];
  for (final line in coincident ? [series.first] : series) {
    if (line.points.length < 2) continue;
    final points = <Offset>[];
    for (final point in line.points) {
      final fraction = amountFraction(point.amount, largest);
      final month = YearMonth.tryParse(point.month);
      if (fraction == null || month == null) continue;
      // Clamped at the baseline: an overpaid debt is shown reaching zero, and said in words.
      final y = 1.0 - math.max<double>(0, math.min<double>(1, fraction));
      points.add(Offset(first.monthsUntil(month) / span, y));
    }
    if (points.length >= 2) {
      lines.add(PlottedLine(id: line.id, points: points, compared: line.compared));
    }
  }
  if (lines.isEmpty) return null;

  final mark = YearMonth.tryParse(marker?.month);
  final markerX = mark == null || mark < first || mark > last
      ? null
      : first.monthsUntil(mark) / span;
  return ChartPlot(
    lines: lines,
    firstMonth: first,
    lastMonth: last,
    coincident: coincident,
    markerX: markerX,
  );
}

String? _largest(List<ChartSeries> series) {
  String? largest;
  for (final line in series) {
    for (final point in line.points) {
      if (signOf(point.amount) == null) continue;
      if (largest == null || (compareWire(point.amount, largest) ?? 0) > 0) largest = point.amount;
    }
  }
  // A chart whose largest value is zero has no shape.
  return largest != null && (signOf(largest) ?? 0) > 0 ? largest : null;
}

bool _coincide(ChartSeries left, ChartSeries right, String largest) {
  if (identical(left, right)) return true;
  if (left.points.length != right.points.length || left.points.length < 2) return false;
  for (var i = 0; i < left.points.length; i++) {
    // Same month as well as same height: two lines agreeing on balances at different dates are
    // two lines that happen to share a shape.
    if (left.points[i].month != right.points[i].month) return false;
    final here = amountFraction(left.points[i].amount, largest);
    final there = amountFraction(right.points[i].amount, largest);
    if (here == null || there == null || (here - there).abs() > coincidentTolerance) return false;
  }
  return true;
}

/// The chart, its two end labels, its legend, the marker's label and the assumption it was drawn
/// under — or, with nothing to draw, the sentence that says why, and no legend for lines nobody
/// drew.
class MonthsLineChart extends StatelessWidget {
  const MonthsLineChart({
    required this.label,
    required this.series,
    this.marker,
    this.assumption = '',
    this.unavailable = '',
    this.coincidentLabel = '',
    super.key,
  });

  /// The chart's accessible name, already translated — "Balance to zero".
  final String label;
  final List<ChartSeries> series;
  final ChartMarker? marker;
  final String assumption;
  final String unavailable;

  /// What to say when the lines coincide. Supplied always; used only if they do.
  final String coincidentLabel;

  /// The plot's height: five of the largest space step, the web's 11rem.
  static const double height = SpaceTokens.s8 * 5;

  @override
  Widget build(BuildContext context) {
    final plot = plotOf(series, marker);
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    final muted = text.bodySmall?.copyWith(color: c.muted);
    final locale = Localizations.localeOf(context).toString();
    if (plot == null) {
      return Semantics(
        label: [label, ...series.map((s) => s.said)].join(' '),
        child: CardNote(unavailable),
      );
    }
    final coincident = plot.coincident && coincidentLabel.isNotEmpty;
    final description = [
      label,
      ...series.map((s) => s.said),
      if (coincident) coincidentLabel,
      ?marker?.label,
    ].join(' ');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Semantics(
          label: description,
          image: true,
          excludeSemantics: true,
          child: SizedBox(
            height: height,
            child: CustomPaint(
              painter: _ChartPainter(
                plot: plot,
                rtl: Directionality.of(context) == TextDirection.rtl,
                line: c.accentText,
                compared: c.muted,
                baseline: c.divider,
                mark: c.accent2,
              ),
            ),
          ),
        ),
        const SizedBox(height: SpaceTokens.s1),
        ExcludeSemantics(
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            // Flexible, so two long month names at a large text size wrap rather than overflow:
            // "August 2026" and "November 2030" at 2× did not fit a phone's width.
            children: [
              Flexible(child: Text(plot.firstMonth.long(locale), style: muted)),
              const SizedBox(width: SpaceTokens.s2),
              Flexible(
                child: Text(plot.lastMonth.long(locale), style: muted, textAlign: TextAlign.end),
              ),
            ],
          ),
        ),
        const SizedBox(height: SpaceTokens.s2),
        ExcludeSemantics(
          child: Wrap(
            spacing: SpaceTokens.s4,
            runSpacing: SpaceTokens.s2,
            children: [
              if (coincident)
                _Key(name: series.map((s) => s.name).join(' · '), compared: false)
              else
                for (final s in series) _Key(name: s.name, compared: s.compared),
            ],
          ),
        ),
        if (coincident) CardNote(coincidentLabel),
        if (marker != null) CardNote(marker!.label),
        if (assumption.isNotEmpty) CardNote(assumption),
      ],
    );
  }
}

/// A legend key: a short stroke the same weight and dash as its line.
class _Key extends StatelessWidget {
  const _Key({required this.name, required this.compared});

  final String name;
  final bool compared;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        CustomPaint(
          size: const Size(SpaceTokens.s6, SpaceTokens.s2),
          painter: _KeyPainter(color: compared ? c.muted : c.accentText, dashed: compared),
        ),
        const SizedBox(width: SpaceTokens.s2),
        Flexible(child: Text(name, style: Theme.of(context).textTheme.bodySmall)),
      ],
    );
  }
}

class _KeyPainter extends CustomPainter {
  const _KeyPainter({required this.color, required this.dashed});

  final Color color;
  final bool dashed;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..strokeWidth = 3;
    final y = size.height / 2;
    _stroke(canvas, [Offset(0, y), Offset(size.width, y)], paint, dash: dashed ? (5, 4) : null);
  }

  @override
  bool shouldRepaint(_KeyPainter old) => old.color != color || old.dashed != dashed;
}

class _ChartPainter extends CustomPainter {
  const _ChartPainter({
    required this.plot,
    required this.rtl,
    required this.line,
    required this.compared,
    required this.baseline,
    required this.mark,
  });

  final ChartPlot plot;

  /// Time runs with the reading direction: mirrored, the first month is on the right.
  final bool rtl;
  final Color line;
  final Color compared;
  final Color baseline;
  final Color mark;

  Offset _at(Offset fraction, Size size) {
    final x = rtl ? 1 - fraction.dx : fraction.dx;
    return Offset(x * size.width, fraction.dy * size.height);
  }

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawLine(
      Offset(0, size.height),
      Offset(size.width, size.height),
      Paint()
        ..color = baseline
        ..strokeWidth = 1,
    );
    final markerX = plot.markerX;
    if (markerX != null) {
      final x = (rtl ? 1 - markerX : markerX) * size.width;
      _stroke(
        canvas,
        [Offset(x, 0), Offset(x, size.height)],
        Paint()
          ..color = mark
          ..strokeWidth = 2,
        dash: (2, 3),
      );
    }
    // The comparison first, so the line in force is drawn over it.
    final ordered = [
      ...plot.lines.where((l) => l.compared),
      ...plot.lines.where((l) => !l.compared),
    ];
    for (final drawn in ordered) {
      final paint = Paint()
        ..color = drawn.compared ? compared : line
        ..strokeWidth = drawn.compared ? 2 : 2.5
        ..style = PaintingStyle.stroke
        ..strokeJoin = StrokeJoin.round
        ..strokeCap = StrokeCap.round;
      _stroke(
        canvas,
        [for (final p in drawn.points) _at(p, size)],
        paint,
        dash: drawn.compared ? (5, 4) : null,
      );
    }
  }

  @override
  bool shouldRepaint(_ChartPainter old) =>
      old.plot != plot || old.rtl != rtl || old.line != line || old.compared != compared;
}

/// A polyline, solid or dashed (`on`, `off` in logical pixels).
void _stroke(Canvas canvas, List<Offset> points, Paint paint, {(double, double)? dash}) {
  final path = ui.Path()..moveTo(points.first.dx, points.first.dy);
  for (final p in points.skip(1)) {
    path.lineTo(p.dx, p.dy);
  }
  paint.style = PaintingStyle.stroke;
  if (dash == null) {
    canvas.drawPath(path, paint);
    return;
  }
  final (on, off) = dash;
  for (final metric in path.computeMetrics()) {
    var distance = 0.0;
    while (distance < metric.length) {
      final end = math.min(distance + on, metric.length);
      canvas.drawPath(metric.extractPath(distance, end), paint);
      distance = end + off;
    }
  }
}
