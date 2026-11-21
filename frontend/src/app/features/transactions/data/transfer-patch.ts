import { sameAmount } from '../../../core/money';
import { CreateTransferRequest, Transfer, UpdateTransferRequest } from './transfer.models';

/**
 * What the transfer sheet holds.
 *
 * **Both amounts are unsigned magnitudes.** Nobody moving £200 to their savings types a minus sign,
 * and they should not have to: the direction is in `fromAccountId` and `toAccountId`, which is also
 * the shape the API takes.
 *
 * `amountReceived` is `''` whenever the two accounts share a currency — the field is not on screen
 * then, and nothing may send it (the server answers `400`).
 */
export interface TransferFormValue {
  fromAccountId: string;
  toAccountId: string;
  date: string;
  /** What left the "from" account, in its currency. */
  amount: string;
  /** What arrived in the "to" account, in *its* currency. Only ever filled in across currencies. */
  amountReceived: string;
  note: string;
}

/** Just enough of an account to answer the one question the form asks of it. */
export interface CurrencyOfAccount {
  id: string;
  currency: string;
}

/**
 * Whether the chosen pair of accounts needs two figures.
 *
 * The decision the whole form turns on, and it is made from the account list the accounts service
 * already returns — not from a second request, and never guessed. Two accounts we cannot both find
 * are **not** cross-currency: an unanswerable question should not put an extra required field on
 * screen, and the server has the final say either way.
 */
export function isCrossCurrency(
  accounts: readonly CurrencyOfAccount[],
  fromAccountId: string,
  toAccountId: string,
): boolean {
  const from = accounts.find((account) => account.id === fromAccountId);
  const to = accounts.find((account) => account.id === toAccountId);
  return from !== undefined && to !== undefined && from.currency !== to.currency;
}

/**
 * The body for a new transfer.
 *
 * **`amountReceived` is included when and only when `crossCurrency`.** Both halves of that are a
 * `400`: absent across currencies, because only the household knows what landed; present within
 * one, because a second figure that silently did nothing would let a client believe it had recorded
 * something it had not (ADR-0033).
 */
export function buildTransferCreate(
  form: TransferFormValue,
  crossCurrency: boolean,
): CreateTransferRequest {
  const request: CreateTransferRequest = {
    fromAccountId: form.fromAccountId,
    toAccountId: form.toAccountId,
    date: form.date,
    // A string from the first keystroke to the database (ADR-0006). Nothing here parses it.
    amount: form.amount.trim(),
  };
  if (crossCurrency) {
    request.amountReceived = form.amountReceived.trim();
  }
  // Omitted, not sent empty: the server's CHECK refuses a blank note, while no note at all is
  // ordinary.
  if (form.note.trim() !== '') {
    request.note = form.note.trim();
  }
  return request;
}

/**
 * The patch for an edit: the version, and **only what changed**.
 *
 * `amountReceived` is sent only when the person actually changed what arrived, and only across
 * currencies. Absent means "leave that leg alone", as an absent field does everywhere else in this
 * API — so a note-only edit of a cross-currency transfer is a note-only edit, and the figure on the
 * receiving leg is not quietly recomputed from the other one. Within one currency the field is
 * never sent at all: there it would be a second name for the figure `amount` already carries, and
 * the server refuses it (`400`) rather than letting the two disagree.
 *
 * There are no account ids here, because the server accepts none.
 */
export function buildTransferPatch(
  original: Transfer,
  form: TransferFormValue,
): UpdateTransferRequest {
  const patch: UpdateTransferRequest = { version: original.version };

  if (form.date !== original.date) {
    patch.date = form.date;
  }

  const amount = form.amount.trim();
  // Compared as amounts, not as strings: the server echoes "200.0000" for a typed "200", so a
  // string comparison would send a pointless patch after every edit (core/money.ts).
  if (!sameAmount(amount, original.sent)) {
    patch.amount = amount;
  }

  const received = form.amountReceived.trim();
  if (original.crossCurrency && !sameAmount(received, original.received)) {
    patch.amountReceived = received;
  }

  const note = form.note.trim();
  const originalNote = original.note ?? '';
  if (note !== originalNote) {
    patch.note = note === '' ? null : note;
  }

  return patch;
}

/**
 * Whether a patch would change anything beyond the version it has to carry.
 *
 * Saving an untouched sheet must send nothing: a `PATCH` would bump the pair's version for no
 * reason and make somebody else's open edit stale.
 */
export function transferPatchIsEmpty(patch: UpdateTransferRequest): boolean {
  return Object.keys(patch).length === 1;
}

/** An existing transfer, in the shape the sheet edits. */
export function transferFormValue(transfer: Transfer): TransferFormValue {
  return {
    fromAccountId: transfer.fromAccountId,
    toAccountId: transfer.toAccountId,
    date: transfer.date,
    amount: transfer.sent,
    // Empty within one currency: the field is off screen, and filling it would send a figure the
    // server refuses.
    amountReceived: transfer.crossCurrency ? transfer.received : '',
    note: transfer.note ?? '',
  };
}
