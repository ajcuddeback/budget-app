import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:material_ui/material_ui.dart';

/// One row of a grouped list — an account, a category, an envelope's entry — drawn as the ledger
/// draws its rows: a card strip, rounded where its group starts and ends, with the figure at the
/// end and the details growing under the title.
///
/// - **Rows grow, never clip.** Past 1.5× text the [trailing] figure moves under the title rather
///   than squeezing it into a column one word wide (mobile-app.md, Design).
/// - **A figure keeps its own direction** — the caller passes the amount `Text` with
///   `context.amountDirection`.
/// - [indent] draws a child under its parent with a rule at the start, not a bullet, so two levels
///   read as a hierarchy without relying on colour (categories.md, UI).
/// - [onTap] null is a row with nothing to offer — a `VIEWER`'s — and is not a button.
class GroupedRow extends StatelessWidget {
  const GroupedRow({
    required this.title,
    required this.semanticLabel,
    this.lines = const [],
    this.trailing,
    this.action,
    this.onTap,
    this.first = true,
    this.last = true,
    this.muted = false,
    this.indent = false,
    this.titleDirection,
    super.key,
  });

  final String title;

  /// Everything the row says, in reading order, for a screen reader.
  final String semanticLabel;
  final List<Widget> lines;
  final Widget? trailing;

  /// A control of its own at the row's end (a rule's Remove). Kept out of the row's merged
  /// label, so a screen reader reaches it as a button.
  final Widget? action;
  final VoidCallback? onTap;
  final bool first;
  final bool last;

  /// Archived: the row is read, not used.
  final bool muted;
  final bool indent;

  /// For text that arrived from a bank file and may be in any script (a payee): `null` follows
  /// the layout.
  final TextDirection? titleDirection;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    const big = Radius.circular(RadiusTokens.md + 2);
    const small = Radius.circular(6);
    final shape = BorderRadius.vertical(top: first ? big : small, bottom: last ? big : small);
    final stacked = MediaQuery.textScalerOf(context).scale(1) > 1.5;

    final details = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          title,
          textDirection: titleDirection,
          style: text.titleMedium?.copyWith(color: muted ? c.muted : null),
        ),
        ...lines,
      ],
    );
    final figure = trailing;
    final Widget body = figure == null
        ? details
        : stacked
        ? Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              details,
              Align(alignment: AlignmentDirectional.centerEnd, child: figure),
            ],
          )
        : Row(
            children: [
              Expanded(child: details),
              const SizedBox(width: SpaceTokens.s2),
              figure,
            ],
          );

    // The merged label covers the row's words only; a control of its own stays reachable.
    final said = Semantics(
      button: onTap != null,
      label: semanticLabel,
      excludeSemantics: true,
      child: body,
    );
    final control = action;
    return Padding(
      padding: EdgeInsetsDirectional.only(
        start: SpaceTokens.s4 + (indent ? SpaceTokens.s6 : 0),
        end: SpaceTokens.s4,
        top: 1,
        bottom: 1,
      ),
      child: Material(
        color: c.card,
        borderRadius: shape,
        child: InkWell(
          borderRadius: shape,
          onTap: onTap,
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 48),
            child: Container(
              decoration: indent
                  ? BoxDecoration(
                      border: BorderDirectional(start: BorderSide(color: c.divider, width: 3)),
                    )
                  : null,
              padding: const EdgeInsetsDirectional.symmetric(
                horizontal: SpaceTokens.s4,
                vertical: SpaceTokens.s3,
              ),
              child: control == null
                  ? said
                  : Row(
                      children: [
                        Expanded(child: said),
                        const SizedBox(width: SpaceTokens.s2),
                        control,
                      ],
                    ),
            ),
          ),
        ),
      ),
    );
  }
}

/// A small line under a row's title, in the muted colour.
class RowLine extends StatelessWidget {
  const RowLine(this.text, {this.emphasis = false, this.textDirection, super.key});

  final String text;

  /// Said in the accent text colour — a state worth noticing, such as "Archived".
  final bool emphasis;
  final TextDirection? textDirection;

  @override
  Widget build(BuildContext context) => Text(
    text,
    textDirection: textDirection,
    style: Theme.of(context).textTheme.bodySmall
        ?.copyWith(color: emphasis ? context.colors.accentText : context.colors.muted),
  );
}

/// One choice in a row's actions sheet.
final class RowAction<T> {
  const RowAction(this.value, this.icon, this.label, {this.danger = false});

  final T value;
  final IconData icon;
  final String label;

  /// Cannot be undone: drawn in the accent text colour, as Transactions draws Delete.
  final bool danger;
}

/// A row's actions, in a sheet on the root navigator above the tab bar — the Transactions
/// pattern. Offline every action is disabled and the sheet says why; nothing is queued.
///
/// Returns the chosen value, or null when the sheet was dismissed.
Future<T?> showRowActions<T>(
  BuildContext context, {
  required String title,
  required List<RowAction<T>> actions,
  required bool online,
}) => showModalBottomSheet<T>(
  useRootNavigator: true,
  context: context,
  isScrollControlled: true,
  builder: (sheetContext) => SafeArea(
    child: SingleChildScrollView(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Gutter(
            child: Semantics(
              header: true,
              child: Text(title, style: Theme.of(sheetContext).textTheme.titleMedium),
            ),
          ),
          for (final action in actions)
            ListTile(
              enabled: online,
              leading: Icon(
                action.icon,
                color: action.danger ? sheetContext.colors.accentText : null,
              ),
              title: Text(
                action.label,
                style: action.danger ? TextStyle(color: sheetContext.colors.accentText) : null,
              ),
              onTap: () => Navigator.of(sheetContext).pop(action.value),
            ),
          if (!online) const Gutter(child: OfflineNote()),
          const SizedBox(height: SpaceTokens.s4),
        ],
      ),
    ),
  ),
);
