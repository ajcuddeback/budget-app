/**
 * File import, as the API speaks it (docs/features/file-import.md, ADR-0037, ADR-0038).
 *
 * **Every amount is a string and stays a string** (ADR-0006). Nothing in this feature parses one:
 * money in, money out and the two balances are the server's arithmetic, formatted for display and
 * never added up here.
 */

export type ImportFormat = 'CSV' | 'OFX' | 'QIF';

export const COLUMN_ROLES = [
  'DATE',
  'AMOUNT',
  'DEBIT',
  'CREDIT',
  'DIRECTION',
  'PAYEE',
  'NOTE',
  'REFERENCE',
  'CURRENCY',
] as const;

export type ColumnRole = (typeof COLUMN_ROLES)[number];

/** The date formats the server allows — an allowlist, never a pattern somebody types. */
export const DATE_FORMATS = [
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
] as const;

export const DELIMITERS = [',', ';', '\t', '|'] as const;

/** `''` is "none". */
export const GROUPING_SEPARATORS = ['', ',', '.', ' ', ' ', ' ', "'"] as const;

/**
 * What the person told us about the file. Every field is optional; which ones are *sent* depends on
 * the format, and {@link import-data.ts}'s `optionsFor` is the only place that decides — the
 * server refuses an option that does not apply rather than ignoring it.
 */
export interface ImportOptions {
  charset?: string;
  delimiter?: string;
  headerRow?: number;
  dateFormat?: string;
  decimalSeparator?: string;
  groupingSeparator?: string;
  invertSign?: boolean;
  columns?: Record<string, ColumnRole>;
  directionIn?: string;
  directionOut?: string;
  saveMapping?: boolean;
}

/**
 * The file and what to do with it. **There is no file-name field** (decision 4): file names carry
 * account numbers and holders' names, and the server refuses an unknown property.
 */
export interface ImportRequest {
  accountId: string;
  format: ImportFormat;
  /** Base64, strict, no line breaks. The bytes are never parsed in the browser. */
  file: string;
  options?: ImportOptions;
}

export interface CommitRequest extends ImportRequest {
  previewDigest: string;
  suspectedDuplicates: 'EXCLUDE' | 'INCLUDE';
  /** Only ever names suspected rows; naming any other kind is a `400`. */
  overrides?: { row: number; include: boolean }[];
}

export type ImportNeed = 'CHARSET' | 'MAPPING' | 'DATE_FORMAT';

export interface ImportAttention {
  row: number;
  kind: 'SUSPECTED_DUPLICATE' | 'DUPLICATE' | 'UNREADABLE';
  date: string | null;
  amount: string | null;
  payeeName: string | null;
  /** For an unreadable row: its `row-*` code. */
  code: string | null;
  column: number | null;
  /** The offending cell, cut to 64 characters — the person's own data, so they can find it. */
  text: string | null;
  resembles: { transactionId: string; date: string } | null;
}

export interface ImportSample {
  row: number;
  date: string;
  amount: string;
  currency: string;
  payeeName: string | null;
  note: string | null;
  payeeCut: boolean;
  noteCut: boolean;
}

export interface CsvSuggestion {
  delimiter: string;
  headerRow: number;
  dateFormat: string | null;
  dateFormatCandidates: string[];
  decimalSeparator: string;
  groupingSeparator: string | null;
  columns: Record<string, ColumnRole>;
}

export interface CsvInfo {
  columnCount: number;
  headerLabels: string[];
  firstRecords: string[][];
  suggested: CsvSuggestion;
  /** `null` when there is no saved mapping, or no header to compare it with. */
  mappingMatchesSaved: boolean | null;
}

export interface ImportPreview {
  /** `null` while a question is open: nothing was classified, so there is nothing to commit. */
  previewDigest: string | null;
  account: { id: string; name: string; lastFour: string | null; currency: string };
  format: ImportFormat;
  decodedBytes: number;
  charset: string | null;
  charsetSource: 'BOM' | 'DECLARED' | 'CHOSEN' | 'DETECTED' | null;
  charsetCandidates: { charset: string; samples: string[] }[];
  charsetsAllowed: string[];
  suggestedCharset: string | null;
  dateFormat: string | null;
  dateFormatSource: 'CHOSEN' | 'DETECTED' | null;
  dateFormatCandidates: string[];
  needs: ImportNeed[];
  statement: {
    from: string | null;
    to: string | null;
    /** OFX `LEDGERBAL`: "your bank says". Never stored. */
    bankClosingBalance: string | null;
    accountHint: 'MATCHES' | 'DIFFERS' | 'UNKNOWN';
  } | null;
  counts: {
    read: number;
    new: number;
    duplicate: number;
    suspected: number;
    unreadable: number;
    zero: number;
  } | null;
  totals: { moneyIn: string; moneyOut: string; currency: string } | null;
  balanceNow: string | null;
  balanceAfter: string | null;
  balanceAfterWithSuspected: string | null;
  attention: ImportAttention[];
  attentionTruncated: boolean;
  sample: ImportSample[];
  identical: { date: string; amount: string; payeeName: string | null; count: number }[];
  reusedIdentifier: boolean;
  textCut: boolean;
  csv: CsvInfo | null;
}

/** One committed import. No file name: none is kept. */
export interface ImportBatch {
  id: string;
  accountId: string;
  accountName: string;
  format: ImportFormat;
  imported: number;
  duplicate: number;
  excluded: number;
  unreadable: number;
  zero: number;
  importedBy: string | null;
  createdAt: string;
  /** How many rows an undo would remove now. */
  removable: number;
}

export interface ImportHistory {
  content: ImportBatch[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** An account's saved CSV mapping. */
export interface ImportMapping {
  accountId: string;
  delimiter: string;
  charset: string | null;
  headerRow: number;
  dateFormat: string;
  decimalSeparator: string;
  groupingSeparator: string | null;
  invertSign: boolean;
  columns: Record<string, ColumnRole>;
  directionIn: string | null;
  directionOut: string | null;
  labels: Record<string, string>;
  updatedAt: string;
  version: number;
}
