import { sameAmount } from '../../../core/money';
import {
  CreateDebtRequest,
  Debt,
  DebtKind,
  LogPaymentRequest,
  UpdateDebtRequest,
} from './debt.models';

/**
 * Building the debt requests — and in particular **the three-way `PATCH`**.
 *
 * The server distinguishes three instructions per solver field, and they are three different
 * things happening to somebody's payoff date:
 *
 * | What the body carries | What it means |
 * |---|---|
 * | the key is **absent** | leave the field exactly as it is |
 * | the key is present and **`null`** | **release** it: the server solves it, and it reads `Solved` |
 * | the key carries a **value** | enter it, and the solver works around it |
 *
 * JSON cannot tell "absent" from "null" in one nullable value, so the distinction is made by
 * *whether the property is assigned at all* — `JSON.stringify` drops an absent property and keeps
 * an explicit null. Everything in this file therefore assigns keys one at a time and never spreads
 * a whole form into a body.
 *
 * Getting it wrong is silent. A client that sent the whole form would release every field the
 * household had entered, and the screen would go on showing a payoff date computed from figures
 * nobody asserted — which is why this file has its own tests.
 */

/** What a form says about one solver field. */
export type TermChange<T> =
  | { readonly kind: 'unchanged' }
  | { readonly kind: 'released' }
  | { readonly kind: 'entered'; readonly value: T };

/** The field is not mentioned: the stored value stands. */
export function unchanged<T>(): TermChange<T> {
  return { kind: 'unchanged' };
}

/** Hand the field back to the solver. Sent as an explicit `null`, never as an omission. */
export function released<T>(): TermChange<T> {
  return { kind: 'released' };
}

/** The household typed it. */
export function entered<T>(value: T): TermChange<T> {
  return { kind: 'entered', value };
}

/**
 * The solver fields a terms editor can change.
 *
 * `monthsLeft` and `payoffMonth` are **two faces of one unknown**: entering one clears the other,
 * which the server does on the stored row. The client sends only what it was told, because
 * volunteering a release the household did not ask for is the bug this whole mechanism exists to
 * prevent.
 */
export interface TermChanges {
  apr?: TermChange<string>;
  payment?: TermChange<string>;
  monthsLeft?: TermChange<number>;
  payoffMonth?: TermChange<string>;
}

/**
 * A `PATCH` body carrying the version and exactly the solver instructions it was given.
 *
 * An `unchanged` field adds no key at all. A `released` one adds the key with `null`. Nothing is
 * inferred from emptiness.
 */
export function buildTermsPatch(version: number, changes: TermChanges): UpdateDebtRequest {
  const patch: UpdateDebtRequest = { version };
  // Written out field by field rather than through a loop: the assignment *is* the mechanism.
  // `patch.apr = null` puts the key in the body; not reaching the line leaves it out, and no
  // amount of cleverness makes that clearer than four explicit branches.
  if (changes.apr?.kind === 'released') {
    patch.apr = null;
  } else if (changes.apr?.kind === 'entered') {
    patch.apr = changes.apr.value;
  }
  if (changes.payment?.kind === 'released') {
    patch.payment = null;
  } else if (changes.payment?.kind === 'entered') {
    patch.payment = changes.payment.value;
  }
  if (changes.monthsLeft?.kind === 'released') {
    patch.monthsLeft = null;
  } else if (changes.monthsLeft?.kind === 'entered') {
    patch.monthsLeft = changes.monthsLeft.value;
  }
  if (changes.payoffMonth?.kind === 'released') {
    patch.payoffMonth = null;
  } else if (changes.payoffMonth?.kind === 'entered') {
    patch.payoffMonth = changes.payoffMonth.value;
  }
  return patch;
}

/** What the add / edit dialog holds. Every amount is a string from the first keystroke. */
export interface DebtFormValue {
  name: string;
  kind: DebtKind;
  /** `''` means no servicer, which on an edit is a **clear**. */
  servicer: string;
  /** `''` means "the household's own", and is only offered when creating. */
  currency: string;
  balance: string;
  /** `YYYY-MM-DD` — the date the balance was true. */
  balanceDate: string;
  apr: string;
  /**
   * "Work the rate out for me" — the release, as an interaction.
   *
   * Ticking it on a debt whose rate was entered sends `apr: null`, and the sheet says the field
   * has been released and is now solved. Ticking it on a debt whose rate is *already* solved sends
   * nothing: there is nothing to release.
   */
  aprSolved: boolean;
  minimumPayment: string;
  /** `''` means absent, and progress is then absent rather than rendered as 0%. */
  originalBalance: string;
  /** `''` means no account named. */
  accountId: string;
  /** `''` means no bill carries this minimum — so it counts into Bills &amp; Income. */
  billId: string;
}

/** A blank form for a debt that does not exist yet. */
export function newDebtForm(): DebtFormValue {
  return {
    name: '',
    kind: 'CREDIT_CARD',
    servicer: '',
    currency: '',
    balance: '',
    balanceDate: '',
    apr: '',
    aprSolved: false,
    minimumPayment: '',
    originalBalance: '',
    accountId: '',
    billId: '',
  };
}

/** The server's debt as the form holds it, for an edit. */
export function debtFormFrom(debt: Debt): DebtFormValue {
  return {
    name: debt.name,
    kind: debt.kind,
    servicer: debt.servicer ?? '',
    currency: debt.currency,
    balance: debt.balance,
    balanceDate: debt.balanceAsOf,
    // A solved rate is shown as empty with the box ticked, so the field never presents a figure
    // the household did not assert as though they had.
    apr: debt.aprSource === 'ENTERED' ? (debt.apr ?? '') : '',
    aprSolved: debt.aprSource !== 'ENTERED',
    minimumPayment: debt.minimumPayment,
    originalBalance: debt.originalBalance ?? '',
    accountId: debt.accountId ?? '',
    billId: debt.billId ?? '',
  };
}

/**
 * The body for a new debt.
 *
 * **The rate is required here**, so `aprSolved` plays no part: solving for a rate needs a payment
 * *and* a horizon, and this form collects neither. A debt one minute old is ranked from its
 * balance, its rate and its minimum, which is what makes the ladder possible at all.
 */
export function buildDebtCreate(form: DebtFormValue): CreateDebtRequest {
  const request: CreateDebtRequest = {
    name: form.name.trim(),
    kind: form.kind,
    balance: form.balance.trim(),
    balanceDate: form.balanceDate,
    minimumPayment: form.minimumPayment.trim(),
  };
  const apr = form.apr.trim();
  if (apr !== '') {
    request.apr = apr;
  }
  const servicer = form.servicer.trim();
  if (servicer !== '') {
    request.servicer = servicer;
  }
  if (form.currency !== '') {
    request.currency = form.currency;
  }
  const originalBalance = form.originalBalance.trim();
  if (originalBalance !== '') {
    request.originalBalance = originalBalance;
  }
  if (form.accountId !== '') {
    request.accountId = form.accountId;
  }
  if (form.billId !== '') {
    request.billId = form.billId;
  }
  return request;
}

/**
 * The patch for an edit: only what changed, with the rate's three-way answer made explicit.
 *
 * The balance is **not** here. It is never solved and never patched — it moves through
 * `POST /api/debts/{id}/balance`, which writes the anchor and a correction row when the statement
 * disagrees with the arithmetic. A balance edited as though it were an ordinary field would lose
 * both its date and its provenance.
 */
export function buildDebtPatch(original: Debt, form: DebtFormValue): UpdateDebtRequest {
  const patch = buildTermsPatch(original.version, { apr: aprChange(original, form) });

  const name = form.name.trim();
  if (name !== original.name) {
    patch.name = name;
  }
  if (form.kind !== original.kind) {
    patch.kind = form.kind;
  }

  const minimum = form.minimumPayment.trim();
  if (!sameAmount(minimum, original.minimumPayment)) {
    patch.minimumPayment = minimum;
  }

  // Nullable text and the three nullable links: absent keeps them, null clears them. A client
  // that sent the whole form would clear the servicer of every debt it edited.
  const servicer = form.servicer.trim() === '' ? null : form.servicer.trim();
  if (servicer !== (original.servicer ?? null)) {
    patch.servicer = servicer;
  }

  const originalBalance = form.originalBalance.trim();
  if (originalBalance === '') {
    if (original.originalBalance !== null) {
      patch.originalBalance = null;
    }
  } else if (
    original.originalBalance === null ||
    !sameAmount(originalBalance, original.originalBalance)
  ) {
    patch.originalBalance = originalBalance;
  }

  const accountId = form.accountId === '' ? null : form.accountId;
  if (accountId !== (original.accountId ?? null)) {
    patch.accountId = accountId;
  }

  const billId = form.billId === '' ? null : form.billId;
  if (billId !== (original.billId ?? null)) {
    patch.billId = billId;
  }

  return patch;
}

/**
 * What the form says about the rate.
 *
 * Four cases, and the two in the middle are the ones a careless client gets wrong:
 *
 * - ticked, and the rate was entered → **release it**, explicitly null;
 * - ticked, and it was already solved → **nothing to say**, so the key is absent;
 * - a figure that differs from the stored one → enter it;
 * - the same figure → absent, so an edit of the name does not re-assert the rate.
 */
function aprChange(original: Debt, form: DebtFormValue): TermChange<string> {
  const typed = form.apr.trim();
  if (form.aprSolved) {
    return original.aprSource === 'ENTERED' ? released() : unchanged();
  }
  if (typed === '') {
    // The box is unticked and the field is empty: an incomplete form, not an instruction. The
    // dialog's own validation refuses it, and this refuses to invent a meaning for it.
    return unchanged();
  }
  const stored = original.aprSource === 'ENTERED' ? original.apr : null;
  return stored !== null && sameAmount(typed, stored) ? unchanged() : entered(typed);
}

/**
 * Logging a payment: the date, the amount, and optionally the ledger row it came from.
 *
 * **The split is not sent and is never computed here.** The server works out the interest and the
 * principal under the rate in force and reports them back; a client that calculated them would be
 * sending its own arithmetic to be stored as history (ADR-0006).
 */
export function buildLogPayment(form: {
  paidDate: string;
  amount: string;
  transactionId: string;
  note: string;
}): LogPaymentRequest {
  const request: LogPaymentRequest = {
    paidDate: form.paidDate,
    amount: form.amount.trim(),
  };
  if (form.transactionId !== '') {
    request.transactionId = form.transactionId;
  }
  const note = form.note.trim();
  if (note !== '') {
    request.note = note;
  }
  return request;
}

/** Whether a patch would change anything beyond the version it has to carry. */
export function patchIsEmpty(patch: { version: number }): boolean {
  return Object.keys(patch).length === 1;
}

/**
 * How many months from one `YYYY-MM` to another — **the only delta the what-if states**.
 *
 * Month arithmetic, not money arithmetic: *"moves the date up 3 months"* is what the plan
 * artboards say, and a day-precision delta would be false precision over monthly compounding. A
 * money difference is never computed here; both totals are shown as the server sent them.
 *
 * `null` when either side is absent or malformed — an unknown delta is a sentence the screen
 * leaves out, not a zero.
 */
export function monthsBetween(from: string | null, to: string | null): number | null {
  const start = monthIndex(from);
  const end = monthIndex(to);
  return start === null || end === null ? null : end - start;
}

function monthIndex(month: string | null): number | null {
  if (month === null || !/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) {
    return null;
  }
  const [year, inYear] = month.split('-');
  return Number(year) * 12 + Number(inYear) - 1;
}
