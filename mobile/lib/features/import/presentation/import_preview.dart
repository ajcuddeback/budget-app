import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/import/domain/import.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:intl/intl.dart';
import 'package:material_ui/material_ui.dart';

/// What a preview says will happen: the counts, the money, the rows that need a look, and the
/// first rows to be added. It renders the server's figures and reports which suspected rows were
/// ticked; it decides nothing.
///
/// - **No amount is parsed or added**: every figure is the server's; the one comparison, bank
///   balance against ours, is an equality.
/// - **Every payee and cell from the file is its own text**, never inside a sentence, so a
///   right-to-left override in somebody's statement cannot reorder the words around it.
/// - **Suspected rows are left out unless ticked** (decision 3), each tick naming its row.
class ImportPreviewView extends StatelessWidget {
  const ImportPreviewView({
    required this.preview,
    required this.included,
    required this.accountLabel,
    required this.onToggle,
    required this.onIncludeAll,
    required this.onExcludeAll,
    super.key,
  });

  final ImportPreview preview;
  final Set<int> included;
  final String accountLabel;
  final ValueChanged<int> onToggle;
  final VoidCallback onIncludeAll;
  final VoidCallback onExcludeAll;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    final counts = preview.counts;
    final suspected = preview.attention.where((a) => a.kind == AttentionKind.suspected).toList();
    final duplicates = preview.attention.where((a) => a.kind == AttentionKind.duplicate).toList();
    final unreadable = preview.attention.where((a) => a.kind == AttentionKind.unreadable).toList();
    final numbers = NumberFormat.decimalPattern(context.localeName);
    final matches = preview.balancesMatch;
    const gap = SizedBox(height: SpaceTokens.s3);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (preview.accountHint == 'DIFFERS') ...[
          // Named, not hedged: the likeliest costly mistake is the right file in the wrong account.
          NoticeBanner(text: l.import_accountDiffers(accountLabel), icon: Icons.warning_amber),
          gap,
        ],
        _Heading(l.import_summary),
        if (counts != null)
          Wrap(
            spacing: SpaceTokens.s2,
            runSpacing: SpaceTokens.s2,
            children: [
              _Count(l.import_count_new, numbers.format(counts.fresh)),
              _Count(l.import_count_duplicate, numbers.format(counts.duplicate)),
              _Count(l.import_count_suspected, numbers.format(counts.suspected)),
              _Count(l.import_count_unreadable, numbers.format(counts.unreadable)),
              if (counts.zero > 0) _Count(l.import_count_zero, numbers.format(counts.zero)),
            ],
          ),
        gap,
        if (preview.statementFrom != null && preview.statementTo != null)
          _Figure(
            l.import_dates,
            Text(
              l.import_dateRange(
                context.date(preview.statementFrom!),
                context.date(preview.statementTo!),
              ),
            ),
          ),
        if (preview.moneyIn != null) _Figure(l.import_moneyIn, _Amount(preview.moneyIn!)),
        if (preview.moneyOut != null) _Figure(l.import_moneyOutTotal, _Amount(preview.moneyOut!)),
        if (preview.balanceNow != null) _Figure(l.import_balanceNow, _Amount(preview.balanceNow!)),
        if (preview.balanceAfter != null)
          _Figure(l.import_balanceAfter, _Amount(preview.balanceAfter!)),
        if (preview.balanceAfterWithSuspected != null && (counts?.suspected ?? 0) > 0)
          _Figure(l.import_balanceAfterWithSuspected, _Amount(preview.balanceAfterWithSuspected!)),
        if (matches != null) ...[
          gap,
          NoticeBanner(
            success: matches,
            icon: matches ? Icons.check_circle_outline : Icons.info_outline,
            text: [
              l.import_bankSays(
                context.money(preview.bankClosingBalance!),
                context.money(preview.balanceAfter!),
              ),
              if (matches) l.import_balanceMatches else l.import_balanceDiffers,
            ].join('\n'),
          ),
        ],
        if (preview.reusedIdentifier) _Muted(l.import_reusedIdentifier),
        if (preview.textCut) _Muted(l.import_textCut),
        if (preview.attention.isNotEmpty) ...[
          gap,
          _Heading(l.import_attention),
          if (suspected.isNotEmpty) ...[
            Text(l.import_count_suspected, style: text.titleSmall),
            _Muted(l.import_suspectedIntro),
            Wrap(
              spacing: SpaceTokens.s2,
              children: [
                OutlinedButton(onPressed: onIncludeAll, child: Text(l.import_includeAll)),
                OutlinedButton(onPressed: onExcludeAll, child: Text(l.import_excludeAll)),
              ],
            ),
            for (final item in suspected)
              CheckboxListTile(
                contentPadding: EdgeInsetsDirectional.zero,
                controlAffinity: ListTileControlAffinity.leading,
                value: included.contains(item.row),
                onChanged: (_) => onToggle(item.row),
                title: _RowText(item: item, lead: l.import_include),
                subtitle: item.resemblesDate == null
                    ? null
                    : Text(l.import_resembles(context.date(item.resemblesDate!))),
              ),
          ],
          if (duplicates.isNotEmpty) ...[
            gap,
            Text(l.import_count_duplicate, style: text.titleSmall),
            _Muted(l.import_duplicatesIntro),
            for (final item in duplicates)
              Padding(
                padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s1),
                child: _RowText(item: item, muted: true),
              ),
          ],
          if (unreadable.isNotEmpty) ...[
            gap,
            Text(l.import_count_unreadable, style: text.titleSmall),
            _Muted(l.import_unreadableIntro),
            for (final item in unreadable)
              Padding(
                padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s1),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '${item.column == null ? l.import_line(numbers.format(item.row)) : l.import_lineColumn(numbers.format(item.row), numbers.format(item.column! + 1))} '
                      '${rowProblem(l, item.code)}',
                      style: text.bodyMedium,
                    ),
                    if (item.text != null) ...[
                      Text(l.import_cellSays, style: text.bodySmall),
                      Text(item.text!, style: text.bodySmall),
                    ],
                  ],
                ),
              ),
          ],
          if (preview.attentionTruncated) _Muted(l.import_attentionTruncated),
        ],
        if (preview.identical.isNotEmpty) ...[
          gap,
          _Heading(l.import_identical),
          _Muted(l.import_identicalIntro),
          for (final same in preview.identical)
            Padding(
              padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s1),
              child: Wrap(
                spacing: SpaceTokens.s2,
                children: [
                  Text(context.date(same.date)),
                  Text(same.payeeName ?? l.transactions_noPayee),
                  _Amount(same.amount),
                  Text(l.import_identicalCount(numbers.format(same.count))),
                ],
              ),
            ),
        ],
        gap,
        if (preview.sample.isNotEmpty) ...[
          _Heading(l.import_sample),
          // The ledger's own row layout, so what is about to be added looks like what it will be.
          for (final row in preview.sample)
            Container(
              padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s2),
              decoration: BoxDecoration(
                border: BorderDirectional(bottom: BorderSide(color: context.colors.divider)),
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(row.payeeName ?? l.transactions_noPayee, style: text.titleMedium),
                        Wrap(
                          spacing: SpaceTokens.s1,
                          children: [
                            Text(context.date(row.date), style: text.bodySmall),
                            Text('·', style: text.bodySmall),
                            Text(
                              l.transactions_needsCategory,
                              style: text.bodySmall?.copyWith(color: context.palette.needsCategory),
                            ),
                          ],
                        ),
                        if (row.note != null) Text(row.note!, style: text.bodySmall),
                      ],
                    ),
                  ),
                  const SizedBox(width: SpaceTokens.s2),
                  _Amount(row.amount, colourIncoming: true),
                ],
              ),
            ),
        ] else if (counts != null && counts.fresh == 0 && counts.suspected == 0)
          _Muted(l.import_nothingNew),
      ],
    );
  }
}

/// An unreadable row's code, in words; a code this client does not know gets a plain sentence.
String rowProblem(AppLocalizations l, String? code) => switch (code) {
  'row-date-unreadable' => l.import_row_row_date_unreadable,
  'row-date-ambiguous' => l.import_row_row_date_ambiguous,
  'row-date-out-of-range' => l.import_row_row_date_out_of_range,
  'row-amount-unreadable' => l.import_row_row_amount_unreadable,
  'row-amount-too-precise' => l.import_row_row_amount_too_precise,
  'row-amount-too-large' => l.import_row_row_amount_too_large,
  'row-currency-mismatch' => l.import_row_row_currency_mismatch,
  'row-missing-field' => l.import_row_row_missing_field,
  'row-columns-short' => l.import_row_row_columns_short,
  'row-entity-unknown' => l.import_row_row_entity_unknown,
  _ => l.import_row_unknown,
};

/// "Include · Aug 5, 2026 · CORNER KITCHEN · −$4.50": the row's date, payee and amount, each its
/// own text, so the label names which row a tick is for.
class _RowText extends StatelessWidget {
  const _RowText({required this.item, this.lead, this.muted = false});

  final Attention item;
  final String? lead;
  final bool muted;

  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).textTheme.bodyMedium
        ?.copyWith(color: muted ? context.colors.muted : null);
    return Wrap(
      spacing: SpaceTokens.s2,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        if (lead != null) Text(lead!, style: style),
        if (item.date != null) Text(context.date(item.date!), style: style),
        Text(item.payeeName ?? context.l10n.transactions_noPayee, style: style),
        if (item.amount != null)
          Text(context.money(item.amount!), textDirection: context.amountDirection, style: style),
      ],
    );
  }
}

class _Heading extends StatelessWidget {
  const _Heading(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.only(bottom: SpaceTokens.s2),
    child: Semantics(
      header: true,
      child: Text(text, style: Theme.of(context).textTheme.titleMedium),
    ),
  );
}

class _Count extends StatelessWidget {
  const _Count(this.label, this.value);

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Container(
      padding: const EdgeInsetsDirectional.all(SpaceTokens.s2),
      decoration: BoxDecoration(
        color: context.colors.surface,
        borderRadius: BorderRadius.circular(RadiusTokens.sm),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(value, style: text.titleLarge),
          Text(label, style: text.bodySmall),
        ],
      ),
    );
  }
}

class _Figure extends StatelessWidget {
  const _Figure(this.label, this.value);

  final String label;
  final Widget value;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s1),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: Text(
            label,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: context.colors.muted),
          ),
        ),
        const SizedBox(width: SpaceTokens.s2),
        value,
      ],
    ),
  );
}

class _Amount extends StatelessWidget {
  const _Amount(this.value, {this.colourIncoming = false});

  final Money value;
  final bool colourIncoming;

  @override
  Widget build(BuildContext context) => Text(
    context.money(value),
    textDirection: context.amountDirection,
    style: Theme.of(context).textTheme.bodyLarge?.copyWith(
      fontWeight: FontWeight.w600,
      color: colourIncoming && value.amount.sign > 0 ? context.palette.incoming : null,
    ),
  );
}

class _Muted extends StatelessWidget {
  const _Muted(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s1),
    child: Text(
      text,
      style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: context.colors.muted),
    ),
  );
}
