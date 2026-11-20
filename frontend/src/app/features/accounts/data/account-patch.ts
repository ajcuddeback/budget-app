import { sameAmount } from '../../../core/money';
import { Account, AccountType, CreateAccountRequest, UpdateAccountRequest } from './account.models';

/**
 * What the add/edit form holds. Every value is a string, including the amount — the form is the
 * one place a `number` would be easiest and the one place it would be worst (ADR-0006).
 *
 * `lastFour` is `''` when the box is empty, which the form cannot tell apart from "never had
 * any" on its own. {@link buildAccountPatch} is what resolves that against the saved account.
 */
export interface AccountFormValue {
  name: string;
  lastFour: string;
  type: AccountType;
  currency: string;
  openingBalance: string;
}

export function buildAccountCreate(form: AccountFormValue): CreateAccountRequest {
  const digits = form.lastFour.trim();
  return {
    name: form.name.trim(),
    // Absent, not empty: the server's pattern rejects `""`, and an account with no number is
    // entirely ordinary — cash in a tin has no number.
    ...(digits === '' ? {} : { lastFour: digits }),
    type: form.type,
    currency: form.currency.toUpperCase(),
    openingBalance: form.openingBalance.trim(),
  };
}

/**
 * The smallest `PATCH` that expresses what the person changed.
 *
 * Only changed fields are sent, for two reasons beyond tidiness. A currency the user never touched
 * is never sent, so an account with transactions is not refused for a change nobody made. And the
 * three-way `lastFour` becomes decidable: the form's empty box means *cleared* when the account had
 * digits and *absent* when it did not, which is the distinction that stops a rename from silently
 * erasing the digits (ADR-0028).
 *
 * The amount is compared as a decimal, not as text: `-400` and `-400.0000` are the same money, and
 * reporting them as a change would patch for no reason.
 */
export function buildAccountPatch(previous: Account, form: AccountFormValue): UpdateAccountRequest {
  const patch: UpdateAccountRequest = { version: previous.version };

  const name = form.name.trim();
  if (name !== previous.name) {
    patch.name = name;
  }

  const digits = form.lastFour.trim();
  if (digits !== (previous.lastFour ?? '')) {
    // '' with digits on the account is a deliberate clear; '' with none is nothing to say.
    patch.lastFour = digits === '' ? null : digits;
  }

  if (form.type !== previous.type) {
    patch.type = form.type;
  }

  const currency = form.currency.toUpperCase();
  if (currency !== previous.currency) {
    patch.currency = currency;
  }

  const openingBalance = form.openingBalance.trim();
  if (!sameAmount(openingBalance, previous.openingBalance)) {
    patch.openingBalance = openingBalance;
  }

  return patch;
}

/** The fields a conflict can be about. `balance` is derived, so it is never one of them. */
export const ACCOUNT_FIELDS = [
  'name',
  'lastFour',
  'type',
  'currency',
  'openingBalance',
  'archived',
] as const;

export type AccountField = (typeof ACCOUNT_FIELDS)[number];

/**
 * Which fields differ between the copy a form was opened with and the copy the server now holds.
 *
 * This is what turns `409 stale-version` into a sentence naming what somebody else changed, rather
 * than a shrug. Nothing is overwritten on the strength of it.
 */
export function differingFields(mine: Account, theirs: Account): readonly AccountField[] {
  return ACCOUNT_FIELDS.filter((field) => {
    if (field === 'openingBalance') {
      return !sameAmount(mine.openingBalance, theirs.openingBalance);
    }
    return mine[field] !== theirs[field];
  });
}
