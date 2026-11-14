import { addAmounts } from '../../../core/money';
import { Account } from './account.models';

/**
 * One currency's accounts and their subtotal.
 *
 * `subtotal` is `null` when a balance in the group is not a figure this client can read — a
 * missing total is a visible state, never a zero and never a guess (ADR-0022).
 */
export interface CurrencyGroup {
  currency: string;
  accounts: readonly Account[];
  subtotal: string | null;
}

/**
 * The household's accounts, grouped by currency, each group subtotalled in its own currency.
 *
 * **There is deliberately no function here that totals across groups.** Adding GBP to EUR needs a
 * rate, a date and a label saying it is an estimate (ADR-0022); a plain sum of the two would be a
 * number that is simply wrong, and this is the file where someone would be tempted to write it.
 * A cross-currency roll-up arrives with the reporting slice or not at all.
 *
 * Groups are ordered by currency code so the list does not re-order itself between loads; accounts
 * keep the server's order, which is by name, case-insensitively, with a stable tiebreak on id.
 */
/**
 * The server's order — `ORDER BY lower(name), id` — applied to a list a write has just changed.
 *
 * A renamed or newly created account lands where a reload would put it, so the list does not
 * reshuffle itself the next time the screen is opened. Names are not unique (two accounts may both
 * be called "Savings"), which is why the id tiebreak is here and not optional.
 */
export function orderedByName(accounts: readonly Account[]): readonly Account[] {
  return [...accounts].sort((left, right) => {
    const a = left.name.toLowerCase();
    const b = right.name.toLowerCase();
    if (a !== b) {
      return a < b ? -1 : 1;
    }
    return left.id < right.id ? -1 : left.id > right.id ? 1 : 0;
  });
}

export function groupByCurrency(accounts: readonly Account[]): readonly CurrencyGroup[] {
  const byCurrency = new Map<string, Account[]>();
  for (const account of accounts) {
    const group = byCurrency.get(account.currency);
    if (group) {
      group.push(account);
    } else {
      byCurrency.set(account.currency, [account]);
    }
  }
  return [...byCurrency.entries()]
    .sort(([left], [right]) => (left < right ? -1 : left > right ? 1 : 0))
    .map(([currency, members]) => ({
      currency,
      accounts: members,
      subtotal: addAmounts(members.map((account) => account.balance)),
    }));
}
