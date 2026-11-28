import { Transaction } from './transaction.models';

/**
 * A transfer: money moving between two of the household's own accounts.
 *
 * **A transfer is two transaction rows, and this is the pair** (ADR-0033). Both legs appear in the
 * ledger — each account has to show its own movement — but neither leg's id names the transfer, and
 * neither can be edited or deleted on its own (`409 transfer-leg-not-separate`). Everything here is
 * addressed by {@link Transfer.id}, the `transferGroupId`.
 *
 * **There is no category and no payee.** A transfer is not spending and the other side is not a
 * third party; the API refuses both rather than ignoring them, so neither appears in these shapes.
 */

/**
 * One transfer, exactly as `TransferResponse` sends it.
 *
 * **`sent` and `received` are strings and stay strings** (ADR-0006), and both are **unsigned**: the
 * direction is in the field names, not in a minus sign. They are *not* the same figure when
 * {@link crossCurrency} is true — each leg is the true figure for its own account, and the pair
 * does not sum to zero, which is the absence of a lie rather than a defect (ADR-0033).
 */
export interface Transfer {
  /** The pair's `transferGroupId`. The pair is the object, so neither leg's id is a name for it. */
  id: string;
  fromAccountId: string;
  fromAccountName: string;
  toAccountId: string;
  toAccountName: string;
  /**
   * The outgoing leg's date, `YYYY-MM-DD`, and {@link toDate} the incoming leg's.
   *
   * **Each leg keeps its own** (file-import.md rule 12). A transfer made with *Move money* has the
   * same date on both; two statement rows paired into a transfer usually cleared on different
   * days, and each bank's date is the true one, so nothing rewrites either.
   */
  fromDate: string;
  toDate: string;
  /** What left, unsigned, in {@link sentCurrency}. */
  sent: string;
  sentCurrency: string;
  /** What arrived, in {@link receivedCurrency}. The same figure as `sent` within one currency. */
  received: string;
  receivedCurrency: string;
  /** Whether the two accounts are denominated differently — whether one figure could be honest. */
  crossCurrency: boolean;
  /**
   * `received / sent`, or `null` within one currency.
   *
   * **Derived for display and read by nothing** (ADR-0022). It is not the rate the bank gave: that
   * had a fee inside it, which is exactly why the household supplies both figures instead.
   */
  impliedRate: string | null;
  /** The outgoing leg's note, and {@link toNote} the incoming leg's — the same, unless paired. */
  fromNote: string | null;
  toNote: string | null;
  createdAt: string;
  updatedAt: string;
  /**
   * The **pair's** version: the sum of the two legs'. It moves by two on every edit, is stale
   * exactly when either leg is, and is what a `PATCH` must send back.
   */
  version: number;
}

/**
 * Creating the pair.
 *
 * `amountReceived` is sent **when and only when the two accounts are in different currencies**:
 * required there, and refused within one currency (`400 validation-failed` either way, naming the
 * field). {@link buildTransferCreate} is the only place that decides.
 */
export interface CreateTransferRequest {
  fromAccountId: string;
  toAccountId: string;
  date: string;
  /** Unsigned, a string. A JSON number here is refused by the server (ADR-0006). */
  amount: string;
  amountReceived?: string;
  note?: string;
}

/**
 * A change to both legs: the date, the figures, the note.
 *
 * **There are no account ids.** A transfer's accounts are fixed at creation, as a transaction's
 * single account is: moving it would change up to four balances and, across currencies, what the
 * amounts mean. Moving a transfer is a delete and a re-create, and the form says so.
 */
export interface UpdateTransferRequest {
  /** Required: optimistic locking only works if the caller says which version it read. */
  version: number;
  /** Absent leaves each leg's date alone; a value moves both legs to it. */
  date?: string;
  amount?: string;
  /** Carried on **every** write to a cross-currency pair, changed or not — see the patch builder. */
  amountReceived?: string;
  /** Absent leaves both legs' notes alone, `null` clears both, a value sets both. */
  note?: string | null;
}

/** Pairs the row in the path with `withTransactionId`. Both versions are the ones the caller read. */
export interface PairTransactionRequest {
  withTransactionId: string;
  version: number;
  withVersion: number;
}

/** The two rows a split leaves behind, as the ledger sends them. */
export interface SplitTransferResponse {
  transactions: Transaction[];
}
