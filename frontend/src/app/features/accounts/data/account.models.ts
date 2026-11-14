import { MessageKey } from '../../../core/i18n/messages';

/**
 * What kind of place the money sits in. A closed set the server also enforces — an unknown value
 * is a `400` at the edge and a `CHECK` violation in the database (docs/features/accounts.md).
 */
export const ACCOUNT_TYPES = ['CHECKING', 'SAVINGS', 'CREDIT_CARD', 'CASH', 'INVESTMENT'] as const;

export type AccountType = (typeof ACCOUNT_TYPES)[number];

const ACCOUNT_TYPE_KEY: Readonly<Record<AccountType, MessageKey>> = {
  CHECKING: 'accounts.type.CHECKING',
  SAVINGS: 'accounts.type.SAVINGS',
  CREDIT_CARD: 'accounts.type.CREDIT_CARD',
  CASH: 'accounts.type.CASH',
  INVESTMENT: 'accounts.type.INVESTMENT',
};

export function isAccountType(value: string): value is AccountType {
  return (ACCOUNT_TYPES as readonly string[]).includes(value);
}

/**
 * The label for a type, typed so a template cannot ask for one that does not exist.
 *
 * A type this client has never heard of is a newer server, not a bug: it gets a neutral word
 * rather than a `SCREAMING_SNAKE` code or a blank cell.
 */
export function accountTypeKey(type: string): MessageKey {
  return isAccountType(type) ? ACCOUNT_TYPE_KEY[type] : 'accounts.type.unknown';
}

/**
 * One account, exactly as `AccountResponse` sends it.
 *
 * **`openingBalance` and `balance` are strings and stay strings** (ADR-0006). They are also *not
 * the same field*: `balance` is `openingBalance` plus the account's transaction total, computed on
 * the server. They are equal only because no transactions exist yet, and code that assumes they
 * always will breaks silently the moment slice 5 lands.
 *
 * **`name` and `lastFour` arrive separately and are never pre-joined.** The client composes
 * `name •1234` through a translated template, because a joined label cannot be re-ordered for a
 * right-to-left locale (ADR-0023, ADR-0028).
 */
export interface Account {
  id: string;
  name: string;
  /** Four digits, or `null`. Display sugar for telling two cards at one bank apart (ADR-0028). */
  lastFour: string | null;
  type: AccountType;
  /** The account's own ISO-4217 code. Never a converted one (ADR-0022). */
  currency: string;
  openingBalance: string;
  balance: string;
  archived: boolean;
  createdAt: string;
  updatedAt: string;
  /** What a `PATCH` must send back, or the optimistic lock cannot work. */
  version: number;
  /**
   * Whether the account has no transactions — the one fact that decides whether deleting it is
   * allowed and whether its currency is still changeable.
   *
   * **The API does not send this yet.** `AccountResponse` has no such field, so until slice 5
   * gives it one it is absent on every row and {@link isKnownToHaveHistory} answers "no history
   * known", which is true today: there is no transaction table. See the feature doc's UI section —
   * the client must not infer history from `balance === openingBalance`, because those two are
   * deliberately different fields and the comparison would start lying the moment it mattered.
   */
  empty?: boolean;
}

/**
 * True only when the server has *said* the account has transactions.
 *
 * Absent information is not a yes. The server refuses a delete (`409 account-not-empty`) and a
 * currency change (`409 account-currency-immutable`) regardless of what the UI offered, so being
 * wrong here costs a refused request and an explanation, never lost history. Being wrong the other
 * way — hiding Delete from every account forever — would make the feature unreachable.
 */
export function isKnownToHaveHistory(account: Account): boolean {
  return account.empty === false;
}

export interface CreateAccountRequest {
  name: string;
  /** Omitted entirely when there are no digits. */
  lastFour?: string;
  type: AccountType;
  currency: string;
  /** A string. A JSON number here is refused by the server with `400 malformed-request`. */
  openingBalance: string;
}

/**
 * A partial update. **An absent property means "leave it alone"**, which is what `PATCH` means,
 * so every field here is optional and only what changed is sent.
 *
 * `lastFour` is three-way and the three states are not interchangeable (ADR-0028):
 *
 * | property            | meaning                 |
 * | ------------------- | ----------------------- |
 * | absent              | keep the digits it has  |
 * | `null`              | clear the digits        |
 * | `"1234"`            | set the digits          |
 *
 * `JSON.stringify` drops a property whose value is `undefined` and keeps one whose value is
 * `null`, so the distinction survives the wire — but only if the absent case is built by *not
 * adding the key*. {@link buildAccountPatch} is the only place that decides.
 */
export interface UpdateAccountRequest {
  /** Required: optimistic locking only works if the caller says which version it read. */
  version: number;
  name?: string;
  lastFour?: string | null;
  type?: AccountType;
  currency?: string;
  openingBalance?: string;
}
