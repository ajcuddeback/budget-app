import {
  CreateTransactionRequest,
  Transaction,
  TransactionStatus,
  UpdateTransactionRequest,
} from './transaction.models';

/**
 * What the entry form holds.
 *
 * **`amount` is the magnitude the person typed and `direction` is which way it went.** Nobody
 * writing down a £42 shop types a minus sign, and asking them to is how a month of income ends up
 * recorded as spending. The sign is applied here, once, on the way out.
 */
export interface TransactionFormValue {
  accountId: string;
  date: string;
  direction: 'out' | 'in';
  /** Unsigned. A leading `-` is tolerated and folded into `direction` by {@link signedAmount}. */
  amount: string;
  payeeName: string;
  categoryId: string;
  note: string;
  status: TransactionStatus;
}

/**
 * Applies the direction to the typed magnitude.
 *
 * A typed `-` is honoured rather than fought: somebody who writes `-42` with "Money out" selected
 * means forty-two out, not forty-two in. Normalising both inputs to the same answer is kinder than
 * a validation error about a character that expressed the right intent.
 */
export function signedAmount(form: Pick<TransactionFormValue, 'amount' | 'direction'>): string {
  const magnitude = form.amount.trim().replace(/^[+-]/, '');
  return form.direction === 'out' ? `-${magnitude}` : magnitude;
}

/** Which way an existing transaction went, for re-opening it in the form. */
export function directionOf(transaction: Transaction): 'out' | 'in' {
  return transaction.amount.trimStart().startsWith('-') ? 'out' : 'in';
}

/** The unsigned magnitude of an existing transaction, for the form's amount field. */
export function magnitudeOf(transaction: Transaction): string {
  return transaction.amount.trim().replace(/^[+-]/, '');
}

export function buildTransactionCreate(form: TransactionFormValue): CreateTransactionRequest {
  const request: CreateTransactionRequest = {
    accountId: form.accountId,
    date: form.date,
    amount: signedAmount(form),
    status: form.status,
  };
  // Omitted, not sent empty: an empty payee name is a validation failure, while no payee at all is
  // an ordinary transaction.
  if (form.payeeName.trim() !== '') {
    request.payeeName = form.payeeName.trim();
  }
  if (form.categoryId !== '') {
    request.categoryId = form.categoryId;
  }
  if (form.note.trim() !== '') {
    request.note = form.note.trim();
  }
  return request;
}

/**
 * The patch for an edit: **only what actually changed**.
 *
 * The three nullable fields are why this is a function and not a spread. For each one, absent means
 * "leave it", `null` means "clear it", and a value means "set it" — so a user who clears the note
 * field gets `null` and a user who never touched it gets no key at all. Sending the form wholesale
 * would clear every field the user had not filled in.
 */
export function buildTransactionPatch(
  original: Transaction,
  form: TransactionFormValue,
): UpdateTransactionRequest {
  const patch: UpdateTransactionRequest = { version: original.version };

  if (form.date !== original.date) {
    patch.date = form.date;
  }

  const amount = signedAmount(form);
  // Compared as numbers: "-42.5" and "-42.5000" are the same amount, and the server echoes the
  // padded form, so a string comparison would send a pointless patch after every edit.
  if (!sameAmount(amount, original.amount)) {
    patch.amount = amount;
  }

  const payee = form.payeeName.trim();
  const originalPayee = original.payeeName ?? '';
  if (payee !== originalPayee) {
    patch.payeeName = payee === '' ? null : payee;
  }

  const category = form.categoryId === '' ? null : form.categoryId;
  if (category !== original.categoryId) {
    patch.categoryId = category;
  }

  const note = form.note.trim();
  const originalNote = original.note ?? '';
  if (note !== originalNote) {
    patch.note = note === '' ? null : note;
  }

  if (form.status !== original.status) {
    patch.status = form.status;
  }

  return patch;
}

/** Whether a patch would change anything beyond the version it has to carry. */
export function patchIsEmpty(patch: UpdateTransactionRequest): boolean {
  return Object.keys(patch).length === 1;
}

/**
 * Whether two decimal strings are the same amount.
 *
 * Compared digit by digit after normalising, never by `Number()`: a double cannot hold every
 * `NUMERIC(19,4)`, and the comparison that decides whether to send somebody's corrected amount is
 * not a place to introduce one (ADR-0006).
 */
function sameAmount(left: string, right: string): boolean {
  return normalise(left) === normalise(right);
}

function normalise(amount: string): string {
  const trimmed = amount.trim();
  const negative = trimmed.startsWith('-');
  const digits = trimmed.replace(/^[+-]/, '');
  const [whole, fraction = ''] = digits.split('.');
  const wholeDigits = whole.replace(/^0+(?=\d)/, '');
  const fractionDigits = fraction.replace(/0+$/, '');
  const magnitude = fractionDigits === '' ? wholeDigits : `${wholeDigits}.${fractionDigits}`;
  // Zero has no sign. It is refused by the server anyway, but a signed zero comparing unequal to
  // an unsigned one would be a confusing way to find that out.
  if (/^0*(\.0*)?$/.test(magnitude)) {
    return '0';
  }
  return negative ? `-${magnitude}` : magnitude;
}
