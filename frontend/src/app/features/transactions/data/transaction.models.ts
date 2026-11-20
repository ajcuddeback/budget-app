import { MessageKey } from '../../../core/i18n/messages';

/**
 * How settled a transaction is. A closed set the server also enforces.
 *
 * With no bank connection everything a person types is `CLEARED`: they watched it happen and are
 * the authority on whether it did. `PENDING` is for something expected to settle — a cheque in the
 * post — and `RECONCILED` is set by reconciliation, not by a user toggling it.
 */
export const TRANSACTION_STATUSES = ['PENDING', 'CLEARED', 'RECONCILED'] as const;

export type TransactionStatus = (typeof TRANSACTION_STATUSES)[number];

/**
 * The statuses a person may choose.
 *
 * **`RECONCILED` is deliberately absent.** It means a statement period was closed against the
 * transaction, which is reconciliation's to say and not a user's — the server refuses it with
 * `400`. It stays in {@link TRANSACTION_STATUSES} because a transaction the server has marked
 * reconciled still has to be *displayed*; the two lists answer different questions.
 */
export const SETTABLE_TRANSACTION_STATUSES = ['PENDING', 'CLEARED'] as const;

const STATUS_KEY: Readonly<Record<TransactionStatus, MessageKey>> = {
  PENDING: 'transactions.status.PENDING',
  CLEARED: 'transactions.status.CLEARED',
  RECONCILED: 'transactions.status.RECONCILED',
};

export function isTransactionStatus(value: string): value is TransactionStatus {
  return (TRANSACTION_STATUSES as readonly string[]).includes(value);
}

export function transactionStatusKey(status: string): MessageKey {
  return isTransactionStatus(status) ? STATUS_KEY[status] : 'transactions.status.unknown';
}

/**
 * One transaction, exactly as `TransactionResponse` sends it.
 *
 * **`amount` is a string and stays a string** (ADR-0006). It is signed: negative is money leaving
 * the account. There is no separate income/expense flag, because the sign already says it — and a
 * positive amount against an expense category is a refund, which is ordinary.
 *
 * **`currency` is the account's own**, never a converted one (ADR-0022). Two transactions in two
 * currencies are never added together anywhere in this client.
 *
 * **`categoryId` of `null` means "Needs a category"** — a real state the UI shows, not missing
 * data, and the work queue that turns a pile of transactions into a budget.
 */
export interface Transaction {
  id: string;
  accountId: string;
  accountName: string;
  /** A calendar date, `YYYY-MM-DD`. Not an instant: a receipt's date is not a moment. */
  date: string;
  amount: string;
  currency: string;
  payeeId: string | null;
  payeeName: string | null;
  categoryId: string | null;
  categoryName: string | null;
  /** `null` when the category is top-level or absent; lets the client render `Bills → Utilities`. */
  categoryParentName: string | null;
  note: string | null;
  status: TransactionStatus;
  /** One half of a transfer. Reporting must not count it as income or expense. */
  transfer: boolean;
  createdAt: string;
  updatedAt: string;
  /** What a `PATCH` must send back, or the optimistic lock cannot work. */
  version: number;
}

/**
 * A page of the ledger. The four paging fields match every other collection in this API; the
 * total is the one addition.
 *
 * `pageTotal` is `null` when the page mixes currencies — never a converted figure, because
 * conversion is display-only (ADR-0022) and a cross-currency total would be a number nobody could
 * check.
 */
export interface Ledger {
  content: Transaction[];
  pageTotal: string | null;
  pageTotalCurrency: string | null;
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Every field optional; all absent is "everything, newest first". */
export interface LedgerQuery {
  accountId?: string;
  categoryId?: string;
  /**
   * Only transactions with no category. Separate from `categoryId` because an absent `categoryId`
   * already means "any category", so "has none" needs its own flag.
   */
  uncategorised?: boolean;
  from?: string;
  to?: string;
  search?: string;
  page?: number;
}

export interface CreateTransactionRequest {
  accountId: string;
  date: string;
  /** A string. A JSON number here is refused by the server with `400`. */
  amount: string;
  /** Omitted when there is no payee — cash out of a machine has none. */
  payeeName?: string;
  /** Omitted for "Needs a category". */
  categoryId?: string;
  note?: string;
  status?: TransactionStatus;
}

/**
 * A partial update. **An absent property means "leave it alone"**.
 *
 * `payeeName`, `categoryId` and `note` are three-way: absent keeps the value, `null` clears it, a
 * value sets it. A client that sent all three back on every save would clear whatever the user had
 * not typed into — which is why {@link buildTransactionPatch} is the only place that decides.
 *
 * There is no `accountId`: moving a transaction between accounts changes two balances and, when the
 * currencies differ, the meaning of the amount. The server does not accept it.
 */
export interface UpdateTransactionRequest {
  /** Required: optimistic locking only works if the caller says which version it read. */
  version: number;
  date?: string;
  amount?: string;
  payeeName?: string | null;
  categoryId?: string | null;
  note?: string | null;
  status?: TransactionStatus;
}
