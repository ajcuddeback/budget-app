import 'dart:typed_data';

import 'package:budget_owl/features/import/domain/import.dart';
import 'package:flutter_test/flutter_test.dart';

/// Every body import sends, as the web's `import-data.spec.ts` pins them.
void main() {
  final file = ChosenFile(Uint8List.fromList([0x4f, 0x46, 0x58]));

  const suspected = Attention(row: 2, kind: AttentionKind.suspected);
  const duplicate = Attention(row: 1, kind: AttentionKind.duplicate);
  const unreadable = Attention(row: 3, kind: AttentionKind.unreadable);

  test('a preview is the account, the format and the file as base64 — never a name', () {
    expect(previewRequest(accountId: 'acc-1', format: ImportFormat.ofx, file: file), {
      'accountId': 'acc-1',
      'format': 'OFX',
      'file': 'T0ZY',
    });
  });

  test('OFX takes only the charset and the sign; QIF adds the date format', () {
    const settings = ImportSettings(
      charset: 'windows-1252',
      invertSign: true,
      dateFormat: 'dd/MM/uuuu',
      delimiter: ';',
    );
    expect(optionsFor(ImportFormat.ofx, settings, mapped: true), {
      'charset': 'windows-1252',
      'invertSign': true,
    });
    expect(optionsFor(ImportFormat.qif, settings, mapped: true), {
      'charset': 'windows-1252',
      'invertSign': true,
      'dateFormat': 'dd/MM/uuuu',
    });
    expect(optionsFor(ImportFormat.ofx, const ImportSettings(), mapped: true), isNull);
  });

  test('a CSV mapping is sent only once confirmed, with only the columns that have a role', () {
    const settings = ImportSettings(
      dateFormat: 'dd/MM/uuuu',
      columns: ['DATE', '', 'AMOUNT', 'DIRECTION'],
      directionIn: ' CR ',
      directionOut: 'DR',
    );
    expect(optionsFor(ImportFormat.csv, settings, mapped: false), {'dateFormat': 'dd/MM/uuuu'});
    expect(optionsFor(ImportFormat.csv, settings, mapped: true), {
      'dateFormat': 'dd/MM/uuuu',
      'delimiter': ',',
      'headerRow': 1,
      'decimalSeparator': '.',
      'columns': {'0': 'DATE', '2': 'AMOUNT', '3': 'DIRECTION'},
      'directionIn': 'CR',
      'directionOut': 'DR',
    });
  });

  test('the commit is the previewed request, the digest and the decisions', () {
    final previewed = previewRequest(accountId: 'acc-1', format: ImportFormat.ofx, file: file);
    final decisions = decisionsFor([duplicate, suspected, unreadable], {2}, includeAll: false);
    expect(commitRequestFor(previewed, 'd' * 64, decisions, saveMapping: true), {
      'accountId': 'acc-1',
      'format': 'OFX',
      'file': 'T0ZY',
      'previewDigest': 'd' * 64,
      'suspectedDuplicates': 'EXCLUDE',
      'overrides': [
        {'row': 2, 'include': true},
      ],
    });
  });

  test('saveMapping rides along only on a mapped CSV', () {
    final csv = previewRequest(
      accountId: 'acc-1',
      format: ImportFormat.csv,
      file: file,
      options: const {
        'columns': {'0': 'DATE'},
      },
    );
    final commit = commitRequestFor(
      csv,
      'd' * 64,
      const Decisions(includeAll: true),
      saveMapping: true,
    );
    expect(commit['options'], {
      'columns': {'0': 'DATE'},
      'saveMapping': true,
    });
    expect(commit['suspectedDuplicates'], 'INCLUDE');
    expect(commit.containsKey('overrides'), isFalse);
  });

  test('overrides only ever name suspected rows, and only those flipped', () {
    final all = [duplicate, suspected, unreadable];
    expect(decisionsFor(all, {1, 2, 3}, includeAll: true).overrides, isEmpty);
    expect(decisionsFor(all, {}, includeAll: true).overrides, [(row: 2, include: false)]);
    expect(rowsToImport(3, all, {1, 2, 3}), 4);
  });

  test("the format comes from the file's name, and nothing else", () {
    expect(formatFromName('August.QFX'), ImportFormat.ofx);
    expect(formatFromName('export.csv'), ImportFormat.csv);
    expect(formatFromName('money.qif'), ImportFormat.qif);
    expect(formatFromName('statement.pdf'), isNull);
  });
}
