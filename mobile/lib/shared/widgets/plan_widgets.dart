import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// The building blocks Bills & income, the debt plan and goals share: a titled card, a tag, an
/// amount drawn in the language's direction, and the frame every sheet opens in.
///
/// Colours come from the palette, sizes from the tokens; insets are directional.

/// One region of a screen, on the card surface: a heading, an optional action beside it, and its
/// rows. The heading wraps above the action at large text rather than squeezing it.
class PlanCard extends StatelessWidget {
  const PlanCard({required this.title, required this.children, this.action, super.key});

  final String title;
  final Widget? action;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.fromSTEB(
      SpaceTokens.s4,
      SpaceTokens.s2,
      SpaceTokens.s4,
      SpaceTokens.s2,
    ),
    child: Card(
      margin: EdgeInsetsDirectional.zero,
      child: Padding(
        padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Wrap(
              alignment: WrapAlignment.spaceBetween,
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: SpaceTokens.s2,
              runSpacing: SpaceTokens.s2,
              children: [
                Semantics(
                  header: true,
                  child: Text(title, style: Theme.of(context).textTheme.titleMedium),
                ),
                ?action,
              ],
            ),
            const SizedBox(height: SpaceTokens.s2),
            ...children,
          ],
        ),
      ),
    ),
  );
}

/// A sentence that may carry figures — *"$216.00 of $2,072.30 still to pay"* — drawn in the
/// **language's** direction and aligned to the layout's start.
///
/// Under a mirrored layout, a sentence in a left-to-right language laid out right-to-left has its
/// leading figure moved to the other end by the bidi algorithm, so the words no longer say which
/// figure is which. The language decides the order of a sentence; the layout decides only where
/// it sits.
class Sentence extends StatelessWidget {
  const Sentence(this.text, {this.style, super.key});

  final String text;
  final TextStyle? style;

  @override
  Widget build(BuildContext context) {
    final language = context.amountDirection;
    final layout = Directionality.of(context);
    return SizedBox(
      width: double.infinity,
      child: Text(
        text,
        style: style ?? Theme.of(context).textTheme.bodyMedium,
        textDirection: language,
        // The layout's start, whichever way the sentence itself runs.
        textAlign: layout == TextDirection.rtl ? TextAlign.right : TextAlign.left,
      ),
    );
  }
}

/// A quiet sentence inside a card, in the muted colour.
class CardNote extends StatelessWidget {
  const CardNote(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s2),
    child: Sentence(
      text,
      style: Theme.of(context).textTheme.bodySmall?.copyWith(color: context.colors.muted),
    ),
  );
}

/// A small marked label: *Overdue*, *Solved*, *In use*. [strong] uses the accent, for a state that
/// asks for attention; otherwise it is neutral.
class Tag extends StatelessWidget {
  const Tag(this.text, {this.strong = false, super.key});

  final String text;
  final bool strong;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final edge = strong ? c.accentText : c.fieldBorder;
    return Container(
      padding: const EdgeInsetsDirectional.symmetric(
        horizontal: SpaceTokens.s2,
        vertical: SpaceTokens.s1 / 2,
      ),
      decoration: BoxDecoration(
        color: strong ? Color.alphaBlend(c.accent.withAlpha(36), c.card) : c.surface,
        borderRadius: BorderRadius.circular(RadiusTokens.sm),
        border: Border.all(color: edge),
      ),
      child: Text(
        text,
        style: Theme.of(context).textTheme.labelSmall
            ?.copyWith(color: strong ? c.accentText : c.text),
      ),
    );
  }
}

/// An amount, formatted for the reader and drawn in the **language's** direction, so a mirrored
/// layout never moves an English minus sign to the wrong end.
class AmountText extends StatelessWidget {
  const AmountText(
    this.money, {
    this.style,
    this.negativeIsBad = false,
    this.struck = false,
    super.key,
  });

  final Money money;
  final TextStyle? style;

  /// Draw a negative figure in the accent text colour: the second accent reads as success.
  final bool negativeIsBad;

  /// Struck through: shown, and visibly not counted.
  final bool struck;

  @override
  Widget build(BuildContext context) {
    final base = style ?? Theme.of(context).textTheme.bodyLarge;
    return Text(
      context.money(money),
      textDirection: context.amountDirection,
      style: base?.copyWith(
        color: negativeIsBad && money.isNegative ? context.colors.accentText : null,
        decoration: struck ? TextDecoration.lineThrough : null,
        fontFeatures: const [FontFeature.tabularFigures()],
      ),
    );
  }
}

/// A label and its figure on one line, the figure at the end. At large text the figure moves under
/// the label rather than squeezing it.
class FigureLine extends StatelessWidget {
  const FigureLine({required this.label, required this.figure, this.strong = false, super.key});

  final String label;
  final Widget figure;
  final bool strong;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s1),
      child: Wrap(
        alignment: WrapAlignment.spaceBetween,
        crossAxisAlignment: WrapCrossAlignment.center,
        spacing: SpaceTokens.s2,
        children: [
          Text(
            label,
            style: strong
                ? text.bodyMedium
                : text.bodyMedium?.copyWith(color: context.colors.muted),
          ),
          figure,
        ],
      ),
    );
  }
}

/// Opens [sheet] full-screen on the **root** navigator, above the tab bar. Returns what it popped
/// with: the notice to show after a save, or null when nothing was saved.
Future<String?> openSheet(BuildContext context, Widget sheet) => Navigator.of(
  context,
  rootNavigator: true,
).push<String>(MaterialPageRoute(fullscreenDialog: true, builder: (_) => sheet));

/// The frame every plans sheet uses: a title that may take two lines, a close button, the error a
/// save met at the top, the fields, and the offline note under the save button.
class SheetFrame extends ConsumerWidget {
  const SheetFrame({
    required this.title,
    required this.children,
    required this.busy,
    this.formKey,
    this.error,
    super.key,
  });

  final String title;
  final List<Widget> children;
  final bool busy;
  final GlobalKey<FormState>? formKey;
  final AppError? error;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.l10n;
    final failure = error;
    final reachability = ref.watch(reachabilityProvider);
    return Scaffold(
      appBar: AppBar(
        title: Text(title, maxLines: 2),
        leading: IconButton(
          icon: const Icon(Icons.close),
          tooltip: l.common_cancel,
          onPressed: busy ? null : () => Navigator.of(context).pop(),
        ),
      ),
      body: SafeArea(
        child: Form(
          key: formKey,
          child: ListView(
            padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
            children: [
              if (failure != null) ...[
                NoticeBanner(
                  text: writeErrorText(context, reachability, failure),
                  icon: Icons.error_outline,
                ),
                const SizedBox(height: SpaceTokens.s4),
              ],
              ...children,
            ],
          ),
        ),
      ),
    );
  }
}

/// The space between two fields.
const Widget fieldGap = SizedBox(height: SpaceTokens.s4);

/// Why a typed amount cannot be sent, or null. Unsigned: a direction is a separate choice, never a
/// minus sign. An optional field may be left empty.
String? amountProblem(BuildContext context, String? typed, {bool required = true}) {
  final text = (typed ?? '').trim();
  if (text.isEmpty) return required ? context.l10n.validation_required : null;
  return isTypedAmount(text) ? null : context.l10n.validation_positiveAmount;
}

/// The sentence for a write that failed or was refused before it was sent: why writes are off —
/// offline, or this server's version not confirmed yet this launch — or the problem's own words.
String writeErrorText(BuildContext context, Reachability reachability, AppError error) =>
    error.isConnectivity ? writesOffText(context.l10n, reachability) : context.errorMessage(error);
