import { MessageKey, isMessageKey } from '../../../core/i18n/messages';
import {
  COLUMN_ROLES,
  ColumnRole,
  CommitRequest,
  CsvInfo,
  ImportAttention,
  ImportFormat,
  ImportMapping,
  ImportOptions,
  ImportRequest,
} from './import.models';

/**
 * The client's courtesy check on size. **The server's is the control** (rule 3); this only saves
 * somebody uploading a file twice to be told no.
 */
export const MAX_FILE_BYTES = 5 * 1024 * 1024;

/** What the mapping step holds. `''` in a column means "not used". */
export interface ImportSettings {
  charset: string;
  delimiter: string;
  headerRow: number;
  dateFormat: string;
  decimalSeparator: string;
  groupingSeparator: string;
  invertSign: boolean;
  columns: (ColumnRole | '')[];
  directionIn: string;
  directionOut: string;
}

export function emptySettings(): ImportSettings {
  return {
    charset: '',
    delimiter: ',',
    headerRow: 1,
    dateFormat: '',
    decimalSeparator: '.',
    groupingSeparator: '',
    invertSign: false,
    columns: [],
    directionIn: '',
    directionOut: '',
  };
}

/** The format a file's name suggests. The person can change it; the server verifies it. */
export function formatFromName(name: string): ImportFormat | null {
  const extension = name.toLowerCase().split('.').pop() ?? '';
  switch (extension) {
    case 'csv':
      return 'CSV';
    case 'ofx':
    case 'qfx':
      return 'OFX';
    case 'qif':
      return 'QIF';
    default:
      return null;
  }
}

/**
 * The bytes as base64, **without reading them as anything**.
 *
 * The file is never decoded, split or parsed in the browser: what the server reads is exactly what
 * the bank wrote. Chunked so a five-megabyte file does not build one enormous argument list.
 */
export function encodeBase64(bytes: Uint8Array): string {
  const chunk = 0x8000;
  let binary = '';
  for (let start = 0; start < bytes.length; start += chunk) {
    binary += String.fromCharCode(...bytes.subarray(start, start + chunk));
  }
  return btoa(binary);
}

/**
 * The options to send for this format, and **only** those: the server refuses an option that does
 * not apply rather than ignoring it, so a CSV delimiter sent with an OFX file is a `400`.
 *
 * For OFX, only the charset and the sign. QIF adds the date format. Everything else is CSV, and the
 * column mapping is sent only once the person has confirmed one (`mapped`).
 */
export function optionsFor(
  format: ImportFormat,
  settings: ImportSettings,
  mapped: boolean,
): ImportOptions | undefined {
  const options: ImportOptions = {};
  if (settings.charset !== '') {
    options.charset = settings.charset;
  }
  if (settings.invertSign) {
    options.invertSign = true;
  }
  if (format !== 'OFX' && settings.dateFormat !== '') {
    options.dateFormat = settings.dateFormat;
  }
  if (format === 'CSV' && mapped) {
    options.delimiter = settings.delimiter;
    options.headerRow = settings.headerRow;
    options.decimalSeparator = settings.decimalSeparator;
    if (settings.groupingSeparator !== '') {
      options.groupingSeparator = settings.groupingSeparator;
    }
    options.columns = columnsRecord(settings.columns);
    if (settings.columns.includes('DIRECTION')) {
      options.directionIn = settings.directionIn.trim();
      options.directionOut = settings.directionOut.trim();
    }
  }
  return Object.keys(options).length === 0 ? undefined : options;
}

/**
 * The commit: **exactly the request the preview was made from**, plus the digest and decisions.
 *
 * Never rebuilt from the form. A setting changed after the preview would read the file differently,
 * the digest would not match, and the server would refuse it with `409 import-preview-stale` — so
 * the page keeps the request it previewed and sends that. The one addition is `saveMapping`, which
 * changes what is remembered for next time and not how this file is read.
 */
export function commitRequestFor(
  previewed: ImportRequest,
  previewDigest: string,
  decisions: Pick<CommitRequest, 'suspectedDuplicates' | 'overrides'>,
  saveMapping: boolean,
): CommitRequest {
  const options =
    previewed.format === 'CSV' && saveMapping && previewed.options?.columns !== undefined
      ? { ...previewed.options, saveMapping: true }
      : previewed.options;
  return {
    accountId: previewed.accountId,
    format: previewed.format,
    file: previewed.file,
    ...(options === undefined ? {} : { options }),
    previewDigest,
    ...decisions,
  };
}

/** Column index → role, for the columns that have one. */
export function columnsRecord(columns: readonly (ColumnRole | '')[]): Record<string, ColumnRole> {
  const record: Record<string, ColumnRole> = {};
  columns.forEach((role, index) => {
    if (role !== '') {
      record[String(index)] = role;
    }
  });
  return record;
}

function isRole(value: string): value is ColumnRole {
  return (COLUMN_ROLES as readonly string[]).includes(value);
}

/**
 * The settings to show first: the account's saved mapping when it has one, otherwise what the
 * server suggested. **Neither is applied until the person presses Update preview** — a suggestion
 * is never acted on without appearing on screen first (rule 5).
 */
export function settingsFrom(
  csv: CsvInfo,
  saved: ImportMapping | null,
  current: ImportSettings,
): ImportSettings {
  const source = saved ?? {
    delimiter: csv.suggested.delimiter,
    headerRow: csv.suggested.headerRow,
    dateFormat: csv.suggested.dateFormat ?? '',
    decimalSeparator: csv.suggested.decimalSeparator,
    groupingSeparator: csv.suggested.groupingSeparator,
    columns: csv.suggested.columns,
    directionIn: null,
    directionOut: null,
    invertSign: false,
    charset: null,
  };
  const columns: (ColumnRole | '')[] = [];
  for (let index = 0; index < csv.columnCount; index++) {
    const role = source.columns[String(index)];
    columns.push(role !== undefined && isRole(role) ? role : '');
  }
  return {
    ...current,
    charset: current.charset !== '' ? current.charset : (source.charset ?? ''),
    delimiter: source.delimiter,
    headerRow: source.headerRow,
    dateFormat: source.dateFormat ?? '',
    decimalSeparator: source.decimalSeparator,
    groupingSeparator: source.groupingSeparator ?? '',
    invertSign: source.invertSign,
    columns,
    directionIn: source.directionIn ?? '',
    directionOut: source.directionOut ?? '',
  };
}

/** The suspected duplicates a preview lists, by row. */
export function suspectedRows(attention: readonly ImportAttention[]): number[] {
  return attention.filter((item) => item.kind === 'SUSPECTED_DUPLICATE').map((item) => item.row);
}

/**
 * The commit's decisions: the default for suspected duplicates, and an override for each suspected
 * row the person flipped away from it.
 *
 * **Overrides are only ever for suspected rows.** An exact duplicate is already in the ledger and
 * an unreadable row has nothing to write; naming either is a `400`, so they are never sent.
 */
export function decisionsFor(
  attention: readonly ImportAttention[],
  included: ReadonlySet<number>,
  includeAll: boolean,
): Pick<CommitRequest, 'suspectedDuplicates' | 'overrides'> {
  const overrides: { row: number; include: boolean }[] = [];
  for (const row of suspectedRows(attention)) {
    const include = included.has(row);
    if (include !== includeAll) {
      overrides.push({ row, include });
    }
  }
  return {
    suspectedDuplicates: includeAll ? 'INCLUDE' : 'EXCLUDE',
    ...(overrides.length > 0 ? { overrides } : {}),
  };
}

/** How many rows the confirm button will import, from the server's counts and the choices. */
export function rowsToImport(
  fresh: number,
  attention: readonly ImportAttention[],
  included: ReadonlySet<number>,
): number {
  return fresh + suspectedRows(attention).filter((row) => included.has(row)).length;
}

/**
 * Whether two decimal strings from the API are the same amount — `"-12.50"` and `"-12.5000"` are.
 *
 * **Compared as text, never as numbers** (ADR-0006): both are canonicalised (sign, no leading
 * zeros, no trailing fractional zeros) and the strings compared. Anything that is not a plain
 * decimal is never "the same" as anything, so a figure we cannot read never hides a difference.
 */
export function sameAmount(left: string | null, right: string | null): boolean {
  const a = canonicalAmount(left);
  const b = canonicalAmount(right);
  return a !== null && a === b;
}

function canonicalAmount(text: string | null): string | null {
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec((text ?? '').trim());
  if (!match) {
    return null;
  }
  const whole = match[2].replace(/^0+(?=\d)/, '');
  const fraction = (match[3] ?? '').replace(/0+$/, '');
  const digits = fraction === '' ? whole : `${whole}.${fraction}`;
  return digits === '0' ? '0' : `${match[1]}${digits}`;
}

/** How each allowed date format is named on screen. Keys, never the pattern itself. */
export const DATE_FORMAT_LABELS: Readonly<Record<string, MessageKey>> = {
  'uuuu-MM-dd': 'import.dateFormat.ymd',
  'dd/MM/uuuu': 'import.dateFormat.dmyPadded',
  'MM/dd/uuuu': 'import.dateFormat.mdyPadded',
  'd/M/uuuu': 'import.dateFormat.dmy',
  'M/d/uuuu': 'import.dateFormat.mdy',
  'dd.MM.uuuu': 'import.dateFormat.dmyDots',
  'dd-MM-uuuu': 'import.dateFormat.dmyDashes',
  uuuuMMdd: 'import.dateFormat.compact',
  'M/d/yy': 'import.dateFormat.mdyShort',
  "M/d'yy": 'import.dateFormat.mdyQuicken',
  'd/M/yy': 'import.dateFormat.dmyShort',
};

export const DELIMITER_LABELS: Readonly<Record<string, MessageKey>> = {
  ',': 'import.delimiter.comma',
  ';': 'import.delimiter.semicolon',
  '\t': 'import.delimiter.tab',
  '|': 'import.delimiter.pipe',
};

export const GROUPING_LABELS: Readonly<Record<string, MessageKey>> = {
  '': 'import.grouping.none',
  ',': 'import.grouping.comma',
  '.': 'import.grouping.dot',
  ' ': 'import.grouping.space',
  ' ': 'import.grouping.nbsp',
  ' ': 'import.grouping.narrow',
  "'": 'import.grouping.apostrophe',
};

export const ROLE_LABELS: Readonly<Record<ColumnRole, MessageKey>> = {
  DATE: 'import.role.DATE',
  AMOUNT: 'import.role.AMOUNT',
  DEBIT: 'import.role.DEBIT',
  CREDIT: 'import.role.CREDIT',
  DIRECTION: 'import.role.DIRECTION',
  PAYEE: 'import.role.PAYEE',
  NOTE: 'import.role.NOTE',
  REFERENCE: 'import.role.REFERENCE',
  CURRENCY: 'import.role.CURRENCY',
};

/** An unreadable row's code, in words. A code this client does not know gets a plain sentence. */
export function rowProblemKey(code: string | null): MessageKey {
  const key = `import.row.${code ?? ''}`;
  return isMessageKey(key) ? key : 'import.row.unknown';
}
