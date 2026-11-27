import 'dart:convert';
import 'dart:typed_data';

import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/money/money.dart';

/// File import as the API speaks it (file-import.md, ADR-0037, ADR-0038), and the web's
/// `import-data.ts` rules for what is sent.
///
/// **Every amount is [Money] from its wire string and stays the server's**: money in, money out
/// and the two balances are its arithmetic, shown and never added up here. The one comparison —
/// does the bank's balance match ours — is an equality of two decimals, never a difference.

enum ImportFormat {
  ofx('OFX'),
  qif('QIF'),
  csv('CSV');

  ImportFormat(this.wire);
  final String wire;

  static ImportFormat parse(String wire) => values.firstWhere(
    (f) => f.wire == wire,
    orElse: () => throw const UnexpectedJson('format is not a known value'),
  );
}

/// The format a file's name suggests. The person can change it; the server verifies it.
ImportFormat? formatFromName(String name) => switch (name.toLowerCase().split('.').last) {
  'csv' => ImportFormat.csv,
  'ofx' || 'qfx' => ImportFormat.ofx,
  'qif' => ImportFormat.qif,
  _ => null,
};

/// The client's courtesy check on size. **The server's is the control** (rule 3).
const int maxFileBytes = 5 * 1024 * 1024;

const List<String> columnRoles = [
  'DATE',
  'AMOUNT',
  'DEBIT',
  'CREDIT',
  'DIRECTION',
  'PAYEE',
  'NOTE',
  'REFERENCE',
  'CURRENCY',
];

/// The date formats the server allows: an allowlist, never a pattern somebody types.
const List<String> dateFormats = [
  'uuuu-MM-dd',
  'dd/MM/uuuu',
  'MM/dd/uuuu',
  'd/M/uuuu',
  'M/d/uuuu',
  'dd.MM.uuuu',
  'dd-MM-uuuu',
  'uuuuMMdd',
  'M/d/yy',
  "M/d'yy",
  'd/M/yy',
];

const List<String> delimiters = [',', ';', '\t', '|'];

/// `''` is "none".
const List<String> groupingSeparators = ['', ',', '.', ' ', ' ', ' ', "'"];

/// What the mapping step holds. `''` in a column means "not used".
final class ImportSettings {
  const ImportSettings({
    this.charset = '',
    this.delimiter = ',',
    this.headerRow = 1,
    this.dateFormat = '',
    this.decimalSeparator = '.',
    this.groupingSeparator = '',
    this.invertSign = false,
    this.columns = const [],
    this.directionIn = '',
    this.directionOut = '',
  });

  final String charset;
  final String delimiter;
  final int headerRow;
  final String dateFormat;
  final String decimalSeparator;
  final String groupingSeparator;
  final bool invertSign;
  final List<String> columns;
  final String directionIn;
  final String directionOut;

  ImportSettings copyWith({
    String? charset,
    String? delimiter,
    int? headerRow,
    String? dateFormat,
    String? decimalSeparator,
    String? groupingSeparator,
    bool? invertSign,
    List<String>? columns,
    String? directionIn,
    String? directionOut,
  }) => ImportSettings(
    charset: charset ?? this.charset,
    delimiter: delimiter ?? this.delimiter,
    headerRow: headerRow ?? this.headerRow,
    dateFormat: dateFormat ?? this.dateFormat,
    decimalSeparator: decimalSeparator ?? this.decimalSeparator,
    groupingSeparator: groupingSeparator ?? this.groupingSeparator,
    invertSign: invertSign ?? this.invertSign,
    columns: columns ?? this.columns,
    directionIn: directionIn ?? this.directionIn,
    directionOut: directionOut ?? this.directionOut,
  );
}

/// The file, in memory only, as base64. Its name is never sent (decision 4) and its bytes are
/// never written anywhere on the phone (mobile-app.md, Screens).
final class ChosenFile {
  ChosenFile(Uint8List bytes) : base64 = base64Encode(bytes), size = bytes.length;

  final String base64;
  final int size;
}

/// The preview's request: the account, the format, the file, and the options for that format.
/// **No file name, no household id.**
typedef ImportRequest = Map<String, Object?>;

ImportRequest previewRequest({
  required String accountId,
  required ImportFormat format,
  required ChosenFile file,
  Map<String, Object?>? options,
}) => {'accountId': accountId, 'format': format.wire, 'file': file.base64, 'options': ?options};

/// The options to send for this format, and **only** those: the server refuses an option that
/// does not apply rather than ignoring it. OFX takes the charset and the sign; QIF adds the date
/// format; everything else is CSV, and the mapping is sent only once confirmed ([mapped]).
Map<String, Object?>? optionsFor(
  ImportFormat format,
  ImportSettings settings, {
  required bool mapped,
}) {
  final options = <String, Object?>{};
  if (settings.charset.isNotEmpty) options['charset'] = settings.charset;
  if (settings.invertSign) options['invertSign'] = true;
  if (format != ImportFormat.ofx && settings.dateFormat.isNotEmpty) {
    options['dateFormat'] = settings.dateFormat;
  }
  if (format == ImportFormat.csv && mapped) {
    options['delimiter'] = settings.delimiter;
    options['headerRow'] = settings.headerRow;
    options['decimalSeparator'] = settings.decimalSeparator;
    if (settings.groupingSeparator.isNotEmpty) {
      options['groupingSeparator'] = settings.groupingSeparator;
    }
    options['columns'] = columnsRecord(settings.columns);
    if (settings.columns.contains('DIRECTION')) {
      options['directionIn'] = settings.directionIn.trim();
      options['directionOut'] = settings.directionOut.trim();
    }
  }
  return options.isEmpty ? null : options;
}

/// Column index → role, for the columns that have one.
Map<String, String> columnsRecord(List<String> columns) => {
  for (var i = 0; i < columns.length; i++)
    if (columns[i].isNotEmpty) '$i': columns[i],
};

/// The commit: **exactly the request the preview was made from**, plus the digest and the
/// decisions. Never rebuilt from the form: a setting changed since would read the file
/// differently, and the server would refuse the digest. The one addition is `saveMapping`.
ImportRequest commitRequestFor(
  ImportRequest previewed,
  String previewDigest,
  Decisions decisions, {
  required bool saveMapping,
}) {
  final options = previewed['options'] as Map<String, Object?>?;
  final withSave =
      previewed['format'] == ImportFormat.csv.wire && saveMapping && options?['columns'] != null
      ? {...?options, 'saveMapping': true}
      : options;
  return {
    'accountId': previewed['accountId'],
    'format': previewed['format'],
    'file': previewed['file'],
    'options': ?withSave,
    'previewDigest': previewDigest,
    'suspectedDuplicates': decisions.includeAll ? 'INCLUDE' : 'EXCLUDE',
    if (decisions.overrides.isNotEmpty)
      'overrides': [
        for (final o in decisions.overrides) {'row': o.row, 'include': o.include},
      ],
  };
}

/// The default for suspected duplicates, and each suspected row flipped away from it.
final class Decisions {
  const Decisions({required this.includeAll, this.overrides = const []});

  final bool includeAll;
  final List<({int row, bool include})> overrides;
}

/// **Overrides are only ever for suspected rows**: an exact duplicate is already in the ledger and
/// an unreadable row has nothing to write; naming either is a `400`, so neither is sent.
Decisions decisionsFor(List<Attention> attention, Set<int> included, {required bool includeAll}) =>
    Decisions(
      includeAll: includeAll,
      overrides: [
        for (final row in suspectedRows(attention))
          if (included.contains(row) != includeAll) (row: row, include: included.contains(row)),
      ],
    );

List<int> suspectedRows(List<Attention> attention) => [
  for (final item in attention)
    if (item.kind == AttentionKind.suspected) item.row,
];

/// How many rows the confirm button will import, from the server's counts and the choices.
int rowsToImport(int fresh, List<Attention> attention, Set<int> included) =>
    fresh + suspectedRows(attention).where(included.contains).length;

/// The settings to show first: the account's saved mapping, else the server's suggestion —
/// **shown, never applied** until the person presses Update preview (rule 5).
ImportSettings settingsFrom(CsvInfo csv, ImportMapping? saved, ImportSettings current) {
  final source = saved?.settings ?? csv.suggested;
  return current.copyWith(
    charset: current.charset.isNotEmpty ? current.charset : source.charset,
    delimiter: source.delimiter,
    headerRow: source.headerRow,
    dateFormat: source.dateFormat,
    decimalSeparator: source.decimalSeparator,
    groupingSeparator: source.groupingSeparator,
    invertSign: source.invertSign,
    columns: [
      for (var i = 0; i < csv.columnCount; i++)
        if (columnRoles.contains(source.columnFor(i))) source.columnFor(i)! else '',
    ],
    directionIn: source.directionIn,
    directionOut: source.directionOut,
  );
}

// --------------------------------------------------------------------------------- responses

Money _money(JsonMap json, String key, String currency) {
  try {
    return Money.fromWire(json.string(key), currency);
  } on FormatException {
    throw UnexpectedJson('$key is not an amount');
  }
}

Money? _moneyOrNull(JsonMap json, String key, String currency) =>
    json.stringOrNull(key) == null ? null : _money(json, key, currency);

CalendarDate? _dateOrNull(JsonMap json, String key) {
  final text = json.stringOrNull(key);
  if (text == null) return null;
  try {
    return CalendarDate.parse(text);
  } on FormatException {
    throw UnexpectedJson('$key is not a date');
  }
}

int? _intOrNull(JsonMap json, String key) {
  final value = json[key];
  if (value == null || value is int) return value as int?;
  throw UnexpectedJson('$key is not an integer');
}

enum AttentionKind { suspected, duplicate, unreadable }

final class Attention {
  const Attention({
    required this.row,
    required this.kind,
    this.date,
    this.amount,
    this.payeeName,
    this.code,
    this.column,
    this.text,
    this.resemblesDate,
  });

  factory Attention.fromJson(JsonMap json, String currency) => Attention(
    row: json.integer('row'),
    kind: switch (json.string('kind')) {
      'SUSPECTED_DUPLICATE' => AttentionKind.suspected,
      'DUPLICATE' => AttentionKind.duplicate,
      'UNREADABLE' => AttentionKind.unreadable,
      _ => throw const UnexpectedJson('kind is not a known value'),
    },
    date: _dateOrNull(json, 'date'),
    amount: _moneyOrNull(json, 'amount', currency),
    payeeName: json.stringOrNull('payeeName'),
    code: json.stringOrNull('code'),
    column: _intOrNull(json, 'column'),
    text: json.stringOrNull('text'),
    resemblesDate: json.objectOrNull('resembles') == null
        ? null
        : _dateOrNull(json.object('resembles'), 'date'),
  );

  final int row;
  final AttentionKind kind;
  final CalendarDate? date;
  final Money? amount;

  /// From the file: shown as its own text, never inside a sentence's markup.
  final String? payeeName;

  /// An unreadable row's `row-*` code.
  final String? code;
  final int? column;

  /// The offending cell, cut to 64 characters: the person's own data, never logged or kept.
  final String? text;
  final CalendarDate? resemblesDate;
}

final class SampleRow {
  const SampleRow({
    required this.row,
    required this.date,
    required this.amount,
    this.payeeName,
    this.note,
  });

  factory SampleRow.fromJson(JsonMap json) => SampleRow(
    row: json.integer('row'),
    date: _dateOrNull(json, 'date') ?? (throw const UnexpectedJson('date is missing')),
    amount: _money(json, 'amount', json.string('currency')),
    payeeName: json.stringOrNull('payeeName'),
    note: json.stringOrNull('note'),
  );

  final int row;
  final CalendarDate date;
  final Money amount;
  final String? payeeName;
  final String? note;
}

/// A CSV's settings as the server suggests or the account saved them.
final class CsvSettings {
  const CsvSettings({
    required this.delimiter,
    required this.headerRow,
    required this.dateFormat,
    required this.decimalSeparator,
    required this.groupingSeparator,
    required this.columns,
    this.charset = '',
    this.invertSign = false,
    this.directionIn = '',
    this.directionOut = '',
  });

  factory CsvSettings.fromJson(JsonMap json) => CsvSettings(
    delimiter: json.string('delimiter'),
    headerRow: json.integer('headerRow'),
    dateFormat: json.stringOrNull('dateFormat') ?? '',
    decimalSeparator: json.string('decimalSeparator'),
    groupingSeparator: json.stringOrNull('groupingSeparator') ?? '',
    columns: {
      for (final entry in json.object('columns').entries)
        if (entry.value is String) entry.key: entry.value! as String,
    },
    charset: json.stringOrNull('charset') ?? '',
    invertSign: json.booleanOr('invertSign', fallback: false),
    directionIn: json.stringOrNull('directionIn') ?? '',
    directionOut: json.stringOrNull('directionOut') ?? '',
  );

  final String delimiter;
  final int headerRow;
  final String dateFormat;
  final String decimalSeparator;
  final String groupingSeparator;
  final Map<String, String> columns;
  final String charset;
  final bool invertSign;
  final String directionIn;
  final String directionOut;

  String? columnFor(int index) => columns['$index'];
}

final class CsvInfo {
  const CsvInfo({
    required this.columnCount,
    required this.headerLabels,
    required this.firstRecords,
    required this.suggested,
    this.mappingMatchesSaved,
  });

  factory CsvInfo.fromJson(JsonMap json) => CsvInfo(
    columnCount: json.integer('columnCount'),
    headerLabels: json.strings('headerLabels'),
    firstRecords: [
      for (final record in (json['firstRecords'] as List<Object?>?) ?? const <Object?>[])
        if (record is List<Object?>)
          [
            for (final cell in record)
              if (cell is String) cell else '',
          ],
    ],
    suggested: CsvSettings.fromJson(json.object('suggested')),
    mappingMatchesSaved: json['mappingMatchesSaved'] is bool
        ? json['mappingMatchesSaved']! as bool
        : null,
  );

  final int columnCount;
  final List<String> headerLabels;
  final List<List<String>> firstRecords;
  final CsvSettings suggested;
  final bool? mappingMatchesSaved;

  String? label(int index) {
    if (index >= headerLabels.length) return null;
    final label = headerLabels[index].trim();
    return label.isEmpty ? null : label;
  }
}

final class Counts {
  const Counts({
    required this.fresh,
    required this.duplicate,
    required this.suspected,
    required this.unreadable,
    required this.zero,
  });

  factory Counts.fromJson(JsonMap json) => Counts(
    fresh: json.integer('new'),
    duplicate: json.integer('duplicate'),
    suspected: json.integer('suspected'),
    unreadable: json.integer('unreadable'),
    zero: json.integer('zero'),
  );

  final int fresh;
  final int duplicate;
  final int suspected;
  final int unreadable;
  final int zero;
}

final class ImportPreview {
  const ImportPreview({
    required this.accountName,
    required this.currency,
    required this.format,
    required this.charsetsAllowed,
    required this.charsetCandidates,
    required this.dateFormatCandidates,
    required this.needs,
    required this.attention,
    required this.attentionTruncated,
    required this.sample,
    required this.identical,
    required this.reusedIdentifier,
    required this.textCut,
    this.previewDigest,
    this.accountLastFour,
    this.suggestedCharset,
    this.statementFrom,
    this.statementTo,
    this.bankClosingBalance,
    this.accountHint,
    this.counts,
    this.moneyIn,
    this.moneyOut,
    this.balanceNow,
    this.balanceAfter,
    this.balanceAfterWithSuspected,
    this.csv,
  });

  factory ImportPreview.fromJson(JsonMap json) {
    final account = json.object('account');
    final currency = account.string('currency');
    final statement = json.objectOrNull('statement');
    final totals = json.objectOrNull('totals');
    return ImportPreview(
      previewDigest: json.stringOrNull('previewDigest'),
      accountName: account.string('name'),
      accountLastFour: account.stringOrNull('lastFour'),
      currency: currency,
      format: ImportFormat.parse(json.string('format')),
      charsetsAllowed: json.strings('charsetsAllowed'),
      charsetCandidates: [
        for (final c in json.objects('charsetCandidates'))
          (charset: c.string('charset'), samples: c.strings('samples')),
      ],
      suggestedCharset: json.stringOrNull('suggestedCharset'),
      dateFormatCandidates: json.strings('dateFormatCandidates'),
      needs: json.strings('needs'),
      statementFrom: statement == null ? null : _dateOrNull(statement, 'from'),
      statementTo: statement == null ? null : _dateOrNull(statement, 'to'),
      bankClosingBalance: statement == null
          ? null
          : _moneyOrNull(statement, 'bankClosingBalance', currency),
      accountHint: statement?.string('accountHint'),
      counts: json.objectOrNull('counts') == null ? null : Counts.fromJson(json.object('counts')),
      moneyIn: totals == null ? null : _money(totals, 'moneyIn', totals.string('currency')),
      moneyOut: totals == null ? null : _money(totals, 'moneyOut', totals.string('currency')),
      balanceNow: _moneyOrNull(json, 'balanceNow', currency),
      balanceAfter: _moneyOrNull(json, 'balanceAfter', currency),
      balanceAfterWithSuspected: _moneyOrNull(json, 'balanceAfterWithSuspected', currency),
      attention: [for (final a in json.objects('attention')) Attention.fromJson(a, currency)],
      attentionTruncated: json.boolean('attentionTruncated'),
      sample: [for (final s in json.objects('sample')) SampleRow.fromJson(s)],
      identical: [
        for (final i in json.objects('identical'))
          (
            date: _dateOrNull(i, 'date') ?? (throw const UnexpectedJson('date is missing')),
            amount: _money(i, 'amount', currency),
            payeeName: i.stringOrNull('payeeName'),
            count: i.integer('count'),
          ),
      ],
      reusedIdentifier: json.boolean('reusedIdentifier'),
      textCut: json.boolean('textCut'),
      csv: json.objectOrNull('csv') == null ? null : CsvInfo.fromJson(json.object('csv')),
    );
  }

  /// Null while a question is open: nothing was classified, so there is nothing to commit.
  final String? previewDigest;
  final String accountName;
  final String? accountLastFour;
  final String currency;
  final ImportFormat format;
  final List<String> charsetsAllowed;
  final List<({String charset, List<String> samples})> charsetCandidates;
  final String? suggestedCharset;
  final List<String> dateFormatCandidates;
  final List<String> needs;
  final CalendarDate? statementFrom;
  final CalendarDate? statementTo;

  /// OFX `LEDGERBAL`: "your bank says". Never stored.
  final Money? bankClosingBalance;
  final String? accountHint;
  final Counts? counts;
  final Money? moneyIn;
  final Money? moneyOut;
  final Money? balanceNow;
  final Money? balanceAfter;
  final Money? balanceAfterWithSuspected;
  final List<Attention> attention;
  final bool attentionTruncated;
  final List<SampleRow> sample;
  final List<({CalendarDate date, Money amount, String? payeeName, int count})> identical;
  final bool reusedIdentifier;
  final bool textCut;
  final CsvInfo? csv;

  /// Whether the bank's balance and ours after the import are the same figure.
  bool? get balancesMatch {
    final bank = bankClosingBalance;
    final after = balanceAfter;
    if (bank == null || after == null) return null;
    return bank.amount == after.amount;
  }
}

/// One committed import. No file name: none is kept.
final class ImportBatch {
  const ImportBatch({
    required this.id,
    required this.accountId,
    required this.accountName,
    required this.format,
    required this.imported,
    required this.duplicate,
    required this.excluded,
    required this.unreadable,
    required this.createdAt,
    required this.removable,
  });

  factory ImportBatch.fromJson(JsonMap json) => ImportBatch(
    id: json.string('id'),
    accountId: json.string('accountId'),
    accountName: json.string('accountName'),
    format: ImportFormat.parse(json.string('format')),
    imported: json.integer('imported'),
    duplicate: json.integer('duplicate'),
    excluded: json.integer('excluded'),
    unreadable: json.integer('unreadable'),
    createdAt:
        DateTime.tryParse(json.string('createdAt')) ??
        (throw const UnexpectedJson('createdAt is not a moment')),
    removable: json.integer('removable'),
  );

  final String id;
  final String accountId;
  final String accountName;
  final ImportFormat format;
  final int imported;
  final int duplicate;
  final int excluded;
  final int unreadable;
  final DateTime createdAt;

  /// How many rows an undo would remove now.
  final int removable;
}

/// An account's saved CSV mapping.
final class ImportMapping {
  const ImportMapping(this.settings);

  factory ImportMapping.fromJson(JsonMap json) => ImportMapping(CsvSettings.fromJson(json));

  final CsvSettings settings;
}
