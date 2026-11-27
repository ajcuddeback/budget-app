import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/insights/domain/reports.dart';
import 'package:decimal/decimal.dart';
import 'package:material_ui/material_ui.dart';

/// Where a value sits between the baseline and the top of the chart, in ten-thousandths:
/// `0`–`10000`. **A dimensionless ratio, worked out in [Decimal]**, so no amount ever passes
/// through a floating-point number; only this integer reaches the painter (reporting.md, Charts).
///
/// A value below zero sits on the baseline, as the web's chart holds it, and the screen says so.
int heightRatio(Money value, Decimal top) {
  if (top <= Decimal.zero || value.amount <= Decimal.zero) return 0;
  final ratio = (value.amount * Decimal.fromInt(10000) / top).toDecimal(
    scaleOnInfinitePrecision: 4,
  );
  final whole = ratio.round().toBigInt().toInt();
  return whole > 10000 ? 10000 : whole;
}

/// The highest month of either line: the chart's top.
Decimal chartTop(List<MonthFigures> months) {
  var top = Decimal.zero;
  for (final month in months) {
    if (month.income.amount > top) top = month.income.amount;
    if (month.spending.amount > top) top = month.spending.amount;
  }
  return top;
}

/// Every month's income equal to its spending: drawn as one line, and said so.
bool coincident(List<MonthFigures> months) =>
    months.isNotEmpty && months.every((m) => m.income.amount == m.spending.amount);

/// Income and spending by month, as two peer lines: **income heavier and solid, spending lighter
/// and dashed**, so they are told apart by weight and dash and never by colour alone (WCAG 1.4.1).
/// Light mode strokes are the 600 steps and dark the 400 tints, as the web measured them.
class IncomeChart extends StatelessWidget {
  const IncomeChart({required this.months, required this.label, super.key});

  final List<MonthFigures> months;

  /// The chart's text alternative: what the lines show, from the same figures, in sentences.
  final String label;

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    final top = chartTop(months);
    final one = coincident(months);
    return Semantics(
      label: label,
      image: true,
      child: ExcludeSemantics(
        child: SizedBox(
          height: SpaceTokens.s8 * 4,
          width: double.infinity,
          child: CustomPaint(
            painter: _ChartPainter(
              income: [for (final m in months) heightRatio(m.income, top)],
              spending: one ? const [] : [for (final m in months) heightRatio(m.spending, top)],
              incomeColour: dark ? RampTokens.accent2400 : RampTokens.accent2600,
              spendingColour: dark ? RampTokens.accent400 : RampTokens.accent600,
              baseline: context.colors.divider,
            ),
          ),
        ),
      ),
    );
  }
}

final class _ChartPainter extends CustomPainter {
  _ChartPainter({
    required this.income,
    required this.spending,
    required this.incomeColour,
    required this.spendingColour,
    required this.baseline,
  });

  final List<int> income;
  final List<int> spending;
  final Color incomeColour;
  final Color spendingColour;
  final Color baseline;

  static const double _inset = SpaceTokens.s2;

  @override
  void paint(Canvas canvas, Size size) {
    final height = size.height - _inset * 2;
    final width = size.width - _inset * 2;
    canvas.drawLine(
      Offset(_inset, _inset + height),
      Offset(_inset + width, _inset + height),
      Paint()
        ..color = baseline
        ..strokeWidth = 1,
    );
    Offset at(int index, int ratio, int count) => Offset(
      _inset + (count < 2 ? width / 2 : width * (index / (count - 1))),
      _inset + height - height * (ratio / 10000),
    );

    void line(List<int> ratios, Color colour, double stroke, {required bool dashed}) {
      if (ratios.isEmpty) return;
      final paint = Paint()
        ..color = colour
        ..strokeWidth = stroke
        ..style = PaintingStyle.stroke
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round;
      final points = [for (var i = 0; i < ratios.length; i++) at(i, ratios[i], ratios.length)];
      if (!dashed) {
        final path = Path()..moveTo(points.first.dx, points.first.dy);
        for (final point in points.skip(1)) {
          path.lineTo(point.dx, point.dy);
        }
        canvas.drawPath(path, paint);
      } else {
        for (var i = 1; i < points.length; i++) {
          _dash(canvas, points[i - 1], points[i], paint);
        }
      }
      final dot = Paint()..color = colour;
      for (final point in points) {
        canvas.drawCircle(point, stroke, dot);
      }
    }

    line(income, incomeColour, 3, dashed: false);
    line(spending, spendingColour, 2, dashed: true);
  }

  void _dash(Canvas canvas, Offset from, Offset to, Paint paint) {
    const dash = 6.0;
    const gap = 4.0;
    final delta = to - from;
    final length = delta.distance;
    if (length == 0) return;
    final step = delta / length;
    var drawn = 0.0;
    while (drawn < length) {
      final end = drawn + dash < length ? drawn + dash : length;
      canvas.drawLine(from + step * drawn, from + step * end, paint);
      drawn = end + gap;
    }
  }

  @override
  bool shouldRepaint(_ChartPainter old) =>
      old.incomeColour != incomeColour ||
      old.spendingColour != spendingColour ||
      old.income.join(',') != income.join(',') ||
      old.spending.join(',') != spending.join(',');
}
