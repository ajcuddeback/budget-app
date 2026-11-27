import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/import/domain/import.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

/// How to read the file: the CSV mapping, and the charset or date format when the server asks.
///
/// It shows the settings it is given and reports each change as a whole new [ImportSettings];
/// **nothing shown is applied until Update preview** (rule 5), which also keeps the rate limit
/// from being spent on taps. Every cell from the file is shown as text, in its own widget.
class ImportMappingView extends StatelessWidget {
  const ImportMappingView({
    required this.preview,
    required this.settings,
    required this.fromSaved,
    required this.busy,
    required this.onChanged,
    required this.onUpdate,
    super.key,
  });

  final ImportPreview preview;
  final ImportSettings settings;

  /// Whether the settings shown came from the account's saved mapping.
  final bool fromSaved;
  final bool busy;
  final ValueChanged<ImportSettings> onChanged;
  final VoidCallback onUpdate;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    final csv = preview.format == ImportFormat.csv ? preview.csv : null;
    final asksCharset = preview.needs.contains('CHARSET') || preview.charsetCandidates.isNotEmpty;
    final asksDate = preview.needs.contains('DATE_FORMAT');
    final showsDate = preview.format != ImportFormat.ofx && (asksDate || csv != null);
    final dateChoices = asksDate && preview.dateFormatCandidates.isNotEmpty
        ? preview.dateFormatCandidates
        : dateFormats;
    final s = settings;
    final moneyOut = s.invertSign
        ? 'positive'
        : s.columns.any((r) => r == 'DEBIT' || r == 'CREDIT' || r == 'DIRECTION')
        ? 'columns'
        : 'negative';
    const gap = SizedBox(height: SpaceTokens.s3);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (csv != null)
          if (fromSaved && csv.mappingMatchesSaved == false)
            NoticeBanner(text: l.import_mappingChanged, icon: Icons.info_outline)
          else
            Text(
              fromSaved ? l.import_mappingSaved : l.import_mappingSuggested,
              style: text.bodyMedium?.copyWith(color: context.colors.muted),
            ),
        gap,
        if (asksCharset) ...[
          _select<String>(
            label: l.import_charsetQuestion,
            hint: l.import_charsetHint,
            value: s.charset,
            items: {'': l.import_charsetAuto, for (final c in preview.charsetsAllowed) c: c},
            onChanged: (v) => onChanged(s.copyWith(charset: v)),
          ),
          for (final candidate in preview.charsetCandidates) ...[
            Text(l.import_charsetSamples(candidate.charset), style: text.titleSmall),
            for (final sample in candidate.samples) Text(sample, style: text.bodySmall),
          ],
          gap,
        ],
        if (showsDate) ...[
          _select<String>(
            label: l.import_dateFormat,
            hint: asksDate ? l.import_dateFormatAmbiguous : l.import_dateFormatHint,
            value: s.dateFormat,
            items: {
              '': l.import_dateFormatChoose,
              for (final pattern in dateChoices) pattern: dateFormatLabel(l, pattern),
            },
            onChanged: (v) => onChanged(s.copyWith(dateFormat: v)),
          ),
          gap,
        ],
        if (csv != null) ...[
          _select<String>(
            label: l.import_delimiter,
            value: s.delimiter,
            items: {for (final d in delimiters) d: delimiterLabel(l, d)},
            onChanged: (v) => onChanged(s.copyWith(delimiter: v)),
          ),
          gap,
          TextFormField(
            initialValue: '${s.headerRow}',
            keyboardType: TextInputType.number,
            inputFormatters: [
              FilteringTextInputFormatter.digitsOnly,
              LengthLimitingTextInputFormatter(2),
            ],
            decoration: InputDecoration(
              labelText: l.import_headerRow,
              helperText: l.import_headerRowHint,
              helperMaxLines: 3,
            ),
            onChanged: (value) {
              final parsed = int.tryParse(value) ?? 0;
              onChanged(s.copyWith(headerRow: parsed > 20 ? 20 : parsed));
            },
          ),
          gap,
          _select<String>(
            label: l.import_decimalSeparator,
            value: s.decimalSeparator,
            items: {'.': l.import_decimal_dot, ',': l.import_decimal_comma},
            onChanged: (v) => onChanged(s.copyWith(decimalSeparator: v)),
          ),
          gap,
          _select<String>(
            label: l.import_groupingSeparator,
            value: s.groupingSeparator,
            items: {for (final g in groupingSeparators) g: groupingLabel(l, g)},
            onChanged: (v) => onChanged(s.copyWith(groupingSeparator: v)),
          ),
          gap,
          _select<String>(
            label: l.import_moneyOutAs,
            hint: moneyOut == 'columns' ? l.import_moneyOutColumnsHint : null,
            value: moneyOut,
            items: {
              'negative': l.import_moneyOut_negative,
              'positive': l.import_moneyOut_positive,
              'columns': l.import_moneyOut_columns,
            },
            onChanged: (v) => onChanged(s.copyWith(invertSign: v == 'positive')),
          ),
          gap,
          Semantics(header: true, child: Text(l.import_columns, style: text.titleMedium)),
          // One list, "Column 1 · Date → [Date]": a select above each column of a table would be
          // two controls with one meaning for a screen reader to get wrong (the web's choice).
          for (var i = 0; i < s.columns.length; i++) ...[
            const SizedBox(height: SpaceTokens.s2),
            _select<String>(
              label: csv.label(i) == null
                  ? l.import_column('${i + 1}')
                  : l.import_columnLabelled('${i + 1}', csv.label(i)!),
              value: s.columns[i],
              items: {'': l.import_role_none, for (final r in columnRoles) r: roleLabel(l, r)},
              onChanged: (v) {
                final columns = [...s.columns];
                columns[i] = v;
                onChanged(s.copyWith(columns: columns));
              },
            ),
          ],
          if (s.columns.contains('DIRECTION')) ...[
            gap,
            TextFormField(
              initialValue: s.directionIn,
              maxLength: 32,
              decoration: InputDecoration(labelText: l.import_directionIn),
              onChanged: (v) => onChanged(s.copyWith(directionIn: v)),
            ),
            TextFormField(
              initialValue: s.directionOut,
              maxLength: 32,
              decoration: InputDecoration(labelText: l.import_directionOut),
              onChanged: (v) => onChanged(s.copyWith(directionOut: v)),
            ),
          ],
          gap,
          Text(l.import_columnsCaption, style: text.titleSmall),
          const SizedBox(height: SpaceTokens.s1),
          _Records(csv: csv, columns: s.columns),
        ],
        gap,
        Align(
          alignment: AlignmentDirectional.centerStart,
          child: FilledButton(
            onPressed: busy ? null : onUpdate,
            child: Text(l.import_updatePreview),
          ),
        ),
      ],
    );
  }

  Widget _select<T>({
    required String label,
    required T value,
    required Map<T, String> items,
    required ValueChanged<T> onChanged,
    String? hint,
  }) => DropdownButtonFormField<T>(
    key: ValueKey('$label=$value'),
    initialValue: items.containsKey(value) ? value : null,
    isExpanded: true,
    decoration: InputDecoration(labelText: label, helperText: hint, helperMaxLines: 4),
    items: [
      for (final entry in items.entries)
        DropdownMenuItem<T>(value: entry.key, child: Text(entry.value)),
    ],
    onChanged: (v) {
      if (v != null) onChanged(v);
    },
  );
}

/// The first rows of the file, one card per record, each cell under its column's name and role.
/// No sideways scrolling on a phone: a record reads top to bottom.
class _Records extends StatelessWidget {
  const _Records({required this.csv, required this.columns});

  final CsvInfo csv;
  final List<String> columns;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final record in csv.firstRecords)
          Container(
            margin: const EdgeInsetsDirectional.only(bottom: SpaceTokens.s2),
            padding: const EdgeInsetsDirectional.all(SpaceTokens.s2),
            decoration: BoxDecoration(
              color: context.colors.surface,
              borderRadius: BorderRadius.circular(RadiusTokens.sm),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (var i = 0; i < record.length; i++)
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        flex: 2,
                        child: Text(
                          i < columns.length && columns[i].isNotEmpty
                              ? roleLabel(l, columns[i])
                              : l.import_column('${i + 1}'),
                          style: text.bodySmall?.copyWith(color: context.colors.muted),
                        ),
                      ),
                      Expanded(flex: 3, child: Text(record[i], style: text.bodySmall)),
                    ],
                  ),
              ],
            ),
          ),
      ],
    );
  }
}

String roleLabel(AppLocalizations l, String role) => switch (role) {
  'DATE' => l.import_role_DATE,
  'AMOUNT' => l.import_role_AMOUNT,
  'DEBIT' => l.import_role_DEBIT,
  'CREDIT' => l.import_role_CREDIT,
  'DIRECTION' => l.import_role_DIRECTION,
  'PAYEE' => l.import_role_PAYEE,
  'NOTE' => l.import_role_NOTE,
  'REFERENCE' => l.import_role_REFERENCE,
  'CURRENCY' => l.import_role_CURRENCY,
  _ => l.import_role_none,
};

/// How each allowed date format is named on screen: by key, never the pattern itself.
String dateFormatLabel(AppLocalizations l, String pattern) => switch (pattern) {
  'uuuu-MM-dd' => l.import_dateFormat_ymd,
  'dd/MM/uuuu' => l.import_dateFormat_dmyPadded,
  'MM/dd/uuuu' => l.import_dateFormat_mdyPadded,
  'd/M/uuuu' => l.import_dateFormat_dmy,
  'M/d/uuuu' => l.import_dateFormat_mdy,
  'dd.MM.uuuu' => l.import_dateFormat_dmyDots,
  'dd-MM-uuuu' => l.import_dateFormat_dmyDashes,
  'uuuuMMdd' => l.import_dateFormat_compact,
  'M/d/yy' => l.import_dateFormat_mdyShort,
  "M/d'yy" => l.import_dateFormat_mdyQuicken,
  'd/M/yy' => l.import_dateFormat_dmyShort,
  _ => pattern,
};

String delimiterLabel(AppLocalizations l, String delimiter) => switch (delimiter) {
  ',' => l.import_delimiter_comma,
  ';' => l.import_delimiter_semicolon,
  '\t' => l.import_delimiter_tab,
  _ => l.import_delimiter_pipe,
};

String groupingLabel(AppLocalizations l, String grouping) => switch (grouping) {
  ',' => l.import_grouping_comma,
  '.' => l.import_grouping_dot,
  ' ' => l.import_grouping_space,
  ' ' => l.import_grouping_nbsp,
  ' ' => l.import_grouping_narrow,
  "'" => l.import_grouping_apostrophe,
  _ => l.import_grouping_none,
};
