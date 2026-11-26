import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/year_month.dart';
import 'package:material_ui/material_ui.dart';

/// The goal row — a name, an optional month, *saved of target*, and a bar under it (goals.md, UI).
/// Shared and presentational only, so the home screen's Goals card can reuse it.
///
/// - **"$890.00 of $3,500.00"**, a sentence rather than a slash, which reads as a ratio.
/// - **The name and the month are composed by a message**, so a right-to-left language can reorder
///   them.
/// - **The bar is the one place an amount becomes a number**: an exact dimensionless ratio. It is
///   hidden from assistive technology; the sentence is its equivalent. Over 100% fills it; a
///   negative figure empties it and is drawn in the colour a negative "left" uses.
/// - **Track and fill are tokens**: the goal track and the second accent.
class GoalProgress extends StatelessWidget {
  const GoalProgress({
    required this.name,
    required this.saved,
    required this.target,
    required this.currency,
    this.targetMonth,
    this.large = false,
    super.key,
  });

  final String name;
  final String? targetMonth;
  final String saved;
  final String target;
  final String currency;
  final bool large;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    final month = targetMonth == null ? null : monthText(targetMonth, context.localeName);
    final negative = isNegativeWire(saved);
    final fraction = amountFraction(saved, target) ?? 0;
    final filled = fraction <= 0 ? 0.0 : (fraction > 1 ? 1.0 : fraction);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Wrap(
          alignment: WrapAlignment.spaceBetween,
          spacing: SpaceTokens.s3,
          runSpacing: SpaceTokens.s1,
          children: [
            Text(
              month == null ? name : l.goals_row_nameAndMonth(name, month),
              style: (large ? text.titleMedium : text.titleSmall),
            ),
            Text(
              textDirection: context.amountDirection,
              l.goals_row_savedOf(
                context.money(Money.fromWire(saved, currency)),
                context.money(Money.fromWire(target, currency)),
              ),
              style: text.bodyMedium?.copyWith(
                color: negative ? c.accentText : null,
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
          ],
        ),
        const SizedBox(height: SpaceTokens.s2),
        ExcludeSemantics(
          child: ClipRRect(
            borderRadius: BorderRadius.circular(RadiusTokens.sm),
            child: SizedBox(
              height: large ? SpaceTokens.s3 : SpaceTokens.s2,
              child: Stack(
                children: [
                  Positioned.fill(child: ColoredBox(color: c.goalTrack)),
                  FractionallySizedBox(
                    alignment: AlignmentDirectional.centerStart,
                    widthFactor: filled,
                    heightFactor: 1,
                    child: ColoredBox(color: c.accent2),
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}
