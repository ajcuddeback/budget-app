import {
  ImportSettings,
  commitRequestFor,
  decisionsFor,
  emptySettings,
  encodeBase64,
  formatFromName,
  optionsFor,
  rowProblemKey,
  rowsToImport,
  sameAmount,
  settingsFrom,
  suspectedRows,
} from './import-data';
import { CsvInfo, ImportAttention, ImportMapping, ImportRequest } from './import.models';

const attention = (row: number, kind: ImportAttention['kind']): ImportAttention => ({
  row,
  kind,
  date: '2026-08-03',
  amount: '-3.5000',
  payeeName: 'Blue Bottle',
  code: kind === 'UNREADABLE' ? 'row-date-unreadable' : null,
  column: null,
  text: null,
  resembles: kind === 'SUSPECTED_DUPLICATE' ? { transactionId: 't9', date: '2026-08-02' } : null,
});

const MIXED: ImportAttention[] = [
  attention(3, 'SUSPECTED_DUPLICATE'),
  attention(4, 'DUPLICATE'),
  attention(5, 'UNREADABLE'),
  attention(7, 'SUSPECTED_DUPLICATE'),
];

const CSV: CsvInfo = {
  columnCount: 4,
  headerLabels: ['Date', 'Description', 'Amount', 'Balance'],
  firstRecords: [['03/08/2026', 'Blue Bottle', '-3.50', '96.50']],
  suggested: {
    delimiter: ',',
    headerRow: 1,
    dateFormat: 'dd/MM/uuuu',
    dateFormatCandidates: ['dd/MM/uuuu'],
    decimalSeparator: '.',
    groupingSeparator: null,
    columns: { '0': 'DATE', '1': 'PAYEE', '2': 'AMOUNT' },
  },
  mappingMatchesSaved: null,
};

describe('encodeBase64', () => {
  it('sends the bytes exactly as the bank wrote them, without reading them as text', () => {
    // A byte-order mark, a NUL, a lone UTF-8 continuation byte and windows-1252's "é". Decoding
    // these as text in the browser would change at least three of them; the server decides.
    const bytes = new Uint8Array([0xef, 0xbb, 0xbf, 0x00, 0x80, 0xe9, 0x0a, 0x2c, 0xff]);

    const encoded = encodeBase64(bytes);

    const decoded = Uint8Array.from(atob(encoded), (char) => char.charCodeAt(0));
    expect(Array.from(decoded)).toEqual(Array.from(bytes));
    // Strict base64 with no line breaks: what the server's decoder accepts.
    expect(encoded).toMatch(/^[A-Za-z0-9+/]*={0,2}$/);
  });

  it('encodes a file larger than one chunk without dropping or repeating a byte', () => {
    const bytes = new Uint8Array(0x8000 * 2 + 17).map((_, index) => index % 251);

    const decoded = Uint8Array.from(atob(encodeBase64(bytes)), (char) => char.charCodeAt(0));

    expect(decoded.length).toBe(bytes.length);
    expect(decoded.every((value, index) => value === bytes[index])).toBe(true);
  });
});

describe('formatFromName', () => {
  it('suggests a format from the extension, and nothing for one it does not know', () => {
    expect(formatFromName('Statement.QFX')).toBe('OFX');
    expect(formatFromName('chase.ofx')).toBe('OFX');
    expect(formatFromName('export.qif')).toBe('QIF');
    expect(formatFromName('transactions.csv')).toBe('CSV');
    expect(formatFromName('statement.pdf')).toBeNull();
    expect(formatFromName('no-extension')).toBeNull();
  });
});

describe('optionsFor', () => {
  const settings: ImportSettings = {
    ...emptySettings(),
    charset: 'windows-1252',
    delimiter: ';',
    headerRow: 2,
    dateFormat: 'dd.MM.uuuu',
    decimalSeparator: ',',
    groupingSeparator: '.',
    columns: ['DATE', '', 'PAYEE', 'AMOUNT'],
  };

  it('sends OFX only the charset and the sign: the server refuses anything else', () => {
    expect(optionsFor('OFX', { ...settings, invertSign: true }, true)).toEqual({
      charset: 'windows-1252',
      invertSign: true,
    });
  });

  it('adds the date format for QIF, and still no CSV settings', () => {
    expect(optionsFor('QIF', settings, true)).toEqual({
      charset: 'windows-1252',
      dateFormat: 'dd.MM.uuuu',
    });
  });

  it('sends a CSV mapping only once it has been confirmed', () => {
    expect(optionsFor('CSV', settings, false)).toEqual({
      charset: 'windows-1252',
      dateFormat: 'dd.MM.uuuu',
    });
    expect(optionsFor('CSV', settings, true)).toEqual({
      charset: 'windows-1252',
      dateFormat: 'dd.MM.uuuu',
      delimiter: ';',
      headerRow: 2,
      decimalSeparator: ',',
      groupingSeparator: '.',
      // Unused columns are left out, not sent as an empty role.
      columns: { '0': 'DATE', '2': 'PAYEE', '3': 'AMOUNT' },
    });
  });

  it('sends the direction words only when a column is marked as one', () => {
    const direction = {
      ...settings,
      columns: ['DATE', 'DIRECTION', 'PAYEE', 'AMOUNT'] as ImportSettings['columns'],
      directionIn: ' CR ',
      directionOut: 'DR',
    };

    expect(optionsFor('CSV', direction, true)).toMatchObject({
      directionIn: 'CR',
      directionOut: 'DR',
    });
    expect(optionsFor('CSV', settings, true)).not.toHaveProperty('directionIn');
  });

  it('sends nothing at all when nothing was chosen', () => {
    expect(optionsFor('OFX', emptySettings(), false)).toBeUndefined();
  });
});

describe('decisionsFor', () => {
  it('names only suspected rows, and only the ones flipped away from the default', () => {
    // The person ticked row 7 with "leave all out" as the default.
    expect(decisionsFor(MIXED, new Set([7]), false)).toEqual({
      suspectedDuplicates: 'EXCLUDE',
      overrides: [{ row: 7, include: true }],
    });
    // "Include all", then unticked row 3.
    expect(decisionsFor(MIXED, new Set([7]), true)).toEqual({
      suspectedDuplicates: 'INCLUDE',
      overrides: [{ row: 3, include: false }],
    });
  });

  it('never sends an override for an exact duplicate or an unreadable row', () => {
    // Even if the set somehow held them: naming either is a 400 on the server.
    const decisions = decisionsFor(MIXED, new Set([4, 5]), false);

    expect(decisions).toEqual({ suspectedDuplicates: 'EXCLUDE' });
    expect(decisions).not.toHaveProperty('overrides');
  });

  it('defaults to leaving suspected rows out (decision 3)', () => {
    expect(decisionsFor(MIXED, new Set(), false)).toEqual({ suspectedDuplicates: 'EXCLUDE' });
  });
});

describe('rowsToImport and suspectedRows', () => {
  it('counts the new rows plus the suspected ones ticked, and nothing else', () => {
    expect(suspectedRows(MIXED)).toEqual([3, 7]);
    expect(rowsToImport(409, MIXED, new Set())).toBe(409);
    expect(rowsToImport(409, MIXED, new Set([3, 4, 5]))).toBe(410);
  });
});

describe('commitRequestFor', () => {
  const previewed: ImportRequest = {
    accountId: 'a1',
    format: 'CSV',
    file: 'ZGF0ZQ==',
    options: { delimiter: ',', columns: { '0': 'DATE', '1': 'AMOUNT' } },
  };

  it('resends exactly what was previewed, with the digest and decisions', () => {
    const request = commitRequestFor(
      previewed,
      'a'.repeat(64),
      { suspectedDuplicates: 'EXCLUDE' },
      false,
    );

    expect(request).toEqual({
      accountId: 'a1',
      format: 'CSV',
      file: 'ZGF0ZQ==',
      options: { delimiter: ',', columns: { '0': 'DATE', '1': 'AMOUNT' } },
      previewDigest: 'a'.repeat(64),
      suspectedDuplicates: 'EXCLUDE',
    });
    // No file name, ever (decision 4).
    expect(Object.keys(request)).not.toContain('fileName');
  });

  it('adds saveMapping only for a mapped CSV, without touching how the file is read', () => {
    const saving = commitRequestFor(
      previewed,
      'b'.repeat(64),
      { suspectedDuplicates: 'EXCLUDE' },
      true,
    );
    expect(saving.options).toEqual({ ...previewed.options, saveMapping: true });

    const ofx = commitRequestFor(
      { accountId: 'a1', format: 'OFX', file: 'T0ZY' },
      'c'.repeat(64),
      { suspectedDuplicates: 'EXCLUDE' },
      true,
    );
    expect(ofx).not.toHaveProperty('options');
  });
});

describe('settingsFrom', () => {
  const saved: ImportMapping = {
    accountId: 'a1',
    delimiter: ';',
    charset: 'ISO-8859-1',
    headerRow: 1,
    dateFormat: 'dd.MM.uuuu',
    decimalSeparator: ',',
    groupingSeparator: '.',
    invertSign: true,
    columns: { '0': 'DATE', '3': 'AMOUNT' },
    directionIn: null,
    directionOut: null,
    labels: {},
    updatedAt: '2026-11-01T09:00:00Z',
    version: 3,
  };

  it("prefers the account's saved mapping over the server's guess", () => {
    const settings = settingsFrom(CSV, saved, emptySettings());

    expect(settings.delimiter).toBe(';');
    expect(settings.invertSign).toBe(true);
    expect(settings.charset).toBe('ISO-8859-1');
    expect(settings.columns).toEqual(['DATE', '', '', 'AMOUNT']);
  });

  it("falls back to the server's suggestion, one role per column of the file", () => {
    const settings = settingsFrom(CSV, null, emptySettings());

    expect(settings.columns).toEqual(['DATE', 'PAYEE', 'AMOUNT', '']);
    expect(settings.dateFormat).toBe('dd/MM/uuuu');
    expect(settings.groupingSeparator).toBe('');
  });

  it('drops a role it does not know rather than sending it back', () => {
    const odd = {
      ...CSV,
      suggested: {
        ...CSV.suggested,
        columns: { '0': 'BALANCE' } as unknown as CsvInfo['suggested']['columns'],
      },
    };

    expect(settingsFrom(odd, null, emptySettings()).columns).toEqual(['', '', '', '']);
  });
});

describe('sameAmount', () => {
  it('compares the text, so trailing zeros and scale do not make a difference', () => {
    expect(sameAmount('-700.79', '-700.7900')).toBe(true);
    expect(sameAmount('0.0000', '-0')).toBe(true);
    expect(sameAmount('012.50', '12.5')).toBe(true);
    expect(sameAmount('12.50', '-12.50')).toBe(false);
  });

  it('tells apart two amounts a double would round to the same number', () => {
    // 18 significant digits: as numbers these are equal, and the difference would vanish.
    expect(Number('12345678901234.5678')).toBe(Number('12345678901234.5679'));
    expect(sameAmount('12345678901234.5678', '12345678901234.5679')).toBe(false);
  });

  it('treats anything that is not a plain decimal as matching nothing', () => {
    expect(sameAmount('1e3', '1000')).toBe(false);
    expect(sameAmount(null, null)).toBe(false);
    expect(sameAmount('', '')).toBe(false);
  });
});

describe('rowProblemKey', () => {
  it('names a known code and falls back for one this client does not know', () => {
    expect(rowProblemKey('row-amount-too-precise')).toBe('import.row.row-amount-too-precise');
    expect(rowProblemKey('row-from-a-newer-server')).toBe('import.row.unknown');
    expect(rowProblemKey(null)).toBe('import.row.unknown');
  });
});
