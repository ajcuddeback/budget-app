import { MessageKey } from '../../../core/i18n/messages';

/**
 * Where an entry's figure came from.
 *
 * **A person decided, either way.** `SYNCED` does not mean the app matched a transaction on its
 * own — a transaction never files itself. It means somebody attached one they had already
 * recorded. The distinction is shown on every row of the log, because a figure you typed and a
 * figure taken from a transaction are different kinds of claim.
 */
export const ENTRY_SOURCES = ['MANUAL', 'SYNCED'] as const;

export type EntrySource = (typeof ENTRY_SOURCES)[number];

const SOURCE_KEY: Readonly<Record<EntrySource, MessageKey>> = {
  MANUAL: 'envelopes.source.MANUAL',
  SYNCED: 'envelopes.source.SYNCED',
};

export function isEntrySource(value: string): value is EntrySource {
  return (ENTRY_SOURCES as readonly string[]).includes(value);
}

export function entrySourceKey(source: string): MessageKey {
  return isEntrySource(source) ? SOURCE_KEY[source] : 'envelopes.source.unknown';
}

/**
 * One envelope, exactly as `EnvelopeResponse` sends it.
 *
 * **`remaining` is the headline** and is computed on the server: `amount` minus `spent`, stored
 * nowhere. It is **negative** for an overspent envelope, and this client renders that rather than
 * clamping it — "you are £40 over" is the information.
 *
 * **`period` is a label**, `2026-08`, not a date range (ADR-0031). An entry belongs to an envelope
 * because somebody logged it there.
 *
 * Every amount is a string and stays a string (ADR-0006). Nothing here parses one into a number:
 * the only arithmetic this client does on money is none.
 */
export interface Envelope {
  id: string;
  name: string;
  /** `YYYY-MM`. */
  period: string;
  categoryId: string | null;
  categoryName: string | null;
  categoryParentName: string | null;
  amount: string;
  spent: string;
  /** Negative when overspent. Never clamp it. */
  remaining: string;
  /** The household's base currency. Every envelope shares it, so totals are safe here. */
  currency: string;
  entryCount: number;
  /** Whether anything has been logged at all — not the same fact as `spent` being zero. */
  empty: boolean;
  archived: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
}

/** One period's envelopes with its own totals. Safe to total: one household, one currency. */
export interface EnvelopePage {
  content: Envelope[];
  totalAllocated: string;
  totalSpent: string;
  totalRemaining: string;
  currency: string;
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** One row of an envelope's log. */
export interface EnvelopeEntry {
  id: string;
  envelopeId: string;
  date: string;
  /** Positive: how much of the decided amount this used. */
  amount: string;
  currency: string;
  payeeId: string | null;
  payeeName: string | null;
  note: string | null;
  source: EntrySource;
  /** Null for a typed entry, and for a synced one whose transaction was later deleted. */
  transactionId: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface CreateEnvelopeRequest {
  name: string;
  /** `YYYY-MM`. */
  period: string;
  amount: string;
  categoryId?: string;
}

/**
 * A partial update. An absent property means "leave it alone".
 *
 * `categoryId` is three-way: absent keeps it, `null` clears it, a value sets it. There is no
 * `period` — moving an envelope between months would carry its entries with it.
 */
export interface UpdateEnvelopeRequest {
  version: number;
  name?: string;
  amount?: string;
  categoryId?: string | null;
}

/**
 * Something to log.
 *
 * **Exactly one of `amount` and `transactionId`.** An amount is typed; a transaction id attaches
 * one already recorded, and the figure then comes from it.
 */
export interface CreateEntryRequest {
  amount?: string;
  transactionId?: string;
  date?: string;
  payeeName?: string;
  note?: string;
}

export interface UpdateEntryRequest {
  version: number;
  date?: string;
  amount?: string;
  payeeName?: string | null;
  note?: string | null;
}
