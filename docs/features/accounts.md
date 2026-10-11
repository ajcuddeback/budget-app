# Feature: Accounts

- **Status:** API complete (service + web). UI not started.
- **Owner:** Repository owner
- **Last updated:** 2026-10-11
- **Related:** [ADR-0006](../adr/0006-money-representation.md) (money),
  [ADR-0017](../adr/0017-households-own-financial-data.md) (ownership),
  [ADR-0022](../adr/0022-multi-currency-display.md) (currency),
  [ADR-0028](../adr/0028-account-last-four-digits.md) (the last-four field),
  [authentication-and-households](authentication-and-households.md)

## Purpose

An account is a place money sits — a current account, a savings pot, a credit card, cash in a
tin, an investment. Budget Owl needs them before it needs anything else, because every
transaction happens _in_ one and every total is a sum _across_ them.

For the person using it the job is small and boring, which is correct: tell the app where your
money lives, once, and then mostly never think about it again. The screen earns its place by
staying out of the way.

## User stories

- As an **owner or member**, I want to add the accounts I actually have, so that transactions
  can be recorded against the right one.
- As an **owner or member**, I want to set what an account held when I started tracking, so my
  balances are right from day one rather than off by a constant forever.
- As an **owner or member**, I want to hide an account I have closed without losing its history,
  so that old transactions still make sense.
- As a **viewer**, I want to see the household's accounts and balances without being able to
  change them.
- As someone with **two currencies**, I want each account in its own currency, so that nothing
  is silently converted behind my back.

## Prerequisites

Slice 2 (authentication and households). Every account belongs to the single household on the
instance (ADR-0026), and the member's role decides whether they may write.

## Rules and behaviour

### Identity and naming

- An account has a **name**, required, 1–80 characters after trimming. Names are _not_ unique:
  two accounts may both be called "Savings", because real households have exactly that.
- An account has an optional **last four digits** (`lastFour`): either absent, or **exactly four
  ASCII digits**. Anything else is rejected at the edge. It is display sugar for telling two
  similar accounts apart — see [ADR-0028](../adr/0028-account-last-four-digits.md) for why a
  partial account number is stored at all and what that costs.
- Display form is `name` then, when `lastFour` is present, `name •1234`. **The server does not
  assemble that string**; it returns the two fields and the client composes them, because a
  pre-joined label cannot be re-ordered for a right-to-left locale (ADR-0023).

### Type and currency

- **Type** is one of `CHECKING`, `SAVINGS`, `CREDIT_CARD`, `CASH`, `INVESTMENT`. Required, and a
  closed set — an unknown value is a `400`, never silently coerced.
- **Currency** is a required ISO-4217 alphabetic code, validated against the codes the server
  knows. It defaults, in the UI only, to the household's base currency.
- **Currency is immutable once the account has any transaction.** Changing it would silently
  reinterpret every amount already stored — the figures do not move, so £100 becomes €100 and
  nothing in the UI admits it happened. Attempting it is a `409`, not a silent no-op. Before the
  first transaction it may be changed freely.
- Type is always mutable. Nothing about stored money depends on it.

### Opening balance

- **`openingBalance`** is what the account held at the moment the household started tracking it.
  Required, `NUMERIC(19,4)`, **signed**, and in the account's own currency.
- Negative is normal and must not be warned about: a credit card you owe £400 on opens at
  `-400.00`. The UI says "what it held when you started" rather than asking for a positive
  number and guessing a sign from the type.
- It is mutable. Correcting a mistyped opening balance is routine bookkeeping, not a rewrite of
  history: it changes the derived balance and nothing else.

### Balance

- **Balance is derived, never stored** (`../domain/model.md`): `openingBalance` plus the sum of
  the account's transactions.
- **Until slice 5 there are no transactions, so balance equals `openingBalance`.** The API
  returns a `balance` field from day one, computed by the same code path that will later add the
  transaction sum. A client must never compute a balance itself, and must never assume the two
  fields are equal — that assumption would break silently the moment transactions land.
- Balances are **never summed across currencies** (ADR-0022). A household total is out of scope
  here; the accounts screen groups by currency and subtotals within each group.

### Archiving and deletion

- **Archiving is the normal way to retire an account.** An archived account keeps its history,
  disappears from pickers and from the default list, and cannot receive new transactions. It can
  be un-archived.
- **Deletion is permitted only while the account has no transactions.** With transactions it is a
  `409` naming archiving as the alternative — deleting would orphan or silently destroy
  financial history, and a budgeting app that loses your records is not one anybody keeps using.
- Deleting an empty account is a hard delete. There is nothing to preserve and a graveyard of
  tombstoned empties is worse than the row being gone.

### Listing

- Default list is **unarchived only**, ordered by name, case-insensitive, with a stable tiebreak
  on id so paging cannot repeat or skip a row.
- `?includeArchived=true` returns both, with `archived` on each row so the client can group them.
- The list is paginated like every other collection in this API (`../guides/api-style.md`).

## Data model

Adds one table. See `../domain/model.md` for where `Account` sits in the domain.

Migration: `V9__accounts.sql`

| Column                      | Type            | Notes                                                                                                                                           |
| --------------------------- | --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| `id`                        | `uuid`          | primary key                                                                                                                                     |
| `household_id`              | `uuid`          | FK → `households`, `NOT NULL`, indexed. The ownership root (ADR-0017)                                                                           |
| `name`                      | `text`          | `NOT NULL`, `CHECK (length(btrim(name)) BETWEEN 1 AND 80 AND length(name) <= 80)`                                                                |
| `last_four`                 | `text`          | nullable, `CHECK (last_four ~ '^[0-9]{4}$')`                                                                                                    |
| `type`                      | `text`          | `NOT NULL`, `CHECK (type IN (...))` — a check constraint, not a Postgres enum, so adding a type later is a migration rather than a type rewrite |
| `currency`                  | `text`          | `NOT NULL`, `CHECK (currency ~ '^[A-Z]{3}$')`                                                                                                   |
| `opening_balance`           | `NUMERIC(19,4)` | `NOT NULL`. Signed (ADR-0006)                                                                                                                   |
| `archived`                  | `boolean`       | `NOT NULL DEFAULT false`                                                                                                                        |
| `created_at` / `updated_at` | `timestamptz`   | `NOT NULL DEFAULT now()`                                                                                                                        |
| `version`                   | `bigint`        | `NOT NULL DEFAULT 0` — optimistic locking                                                                                                       |

Index on `(household_id, archived, lower(name), id)` to serve the default listing without a sort.
The trailing `id` is the listing's own tiebreak: without it in the index PostgreSQL answers the
filter from the index and still adds a `Sort` node for `ORDER BY lower(name), id`.

The `name` check bounds the **raw** value as well as the trimmed one. `length(btrim(name)) <= 80`
on its own is satisfied by any amount of surrounding whitespace, and `text` in PostgreSQL has no
ceiling of its own — the same gap V8 section 4 closed on every other user-supplied text column.
The application stores the trimmed value, so the two halves agree on every row it writes.

**The database enforces the shape, not just the application.** Bean Validation produces the good
error message; the `CHECK` constraints are what remain true when a future import path, CLI or
migration writes rows without going through the API.

## API

All routes are authenticated. The household is resolved from the authenticated member's
membership and is **never** read from the request (ADR-0017 — non-negotiable #2).

| Method   | Path                           | Purpose                                               | Auth                |
| -------- | ------------------------------ | ----------------------------------------------------- | ------------------- |
| `GET`    | `/api/accounts`                | List, paginated. `?includeArchived=true`              | any member          |
| `POST`   | `/api/accounts`                | Create                                                | `OWNER` or `MEMBER` |
| `GET`    | `/api/accounts/{id}`           | One account                                           | any member          |
| `PATCH`  | `/api/accounts/{id}`           | Update name, lastFour, type, openingBalance, currency | `OWNER` or `MEMBER` |
| `POST`   | `/api/accounts/{id}/archive`   | Archive                                               | `OWNER` or `MEMBER` |
| `POST`   | `/api/accounts/{id}/unarchive` | Un-archive                                            | `OWNER` or `MEMBER` |
| `DELETE` | `/api/accounts/{id}`           | Delete, only while empty                              | `OWNER` or `MEMBER` |

Archive and un-archive are **explicit sub-resources rather than a field on `PATCH`**, because
retiring an account is a different act from correcting its name and reads differently in an audit
log.

Amounts cross the wire as **strings** (`"1234.56"`), never JSON numbers — a double cannot hold
every `NUMERIC(19,4)` and the one that gets rounded will be somebody's money (ADR-0006). A JSON
number where an amount belongs is **refused**, not converted: by the time it arrives its digits are
already whatever the sender's double rounded to. `MoneyJsonModule` writes every `BigDecimal` as a
string and refuses to read one from a number; `CoreConfig` additionally refuses coercion of any JSON
number into any textual field, which is what closes the same hole for the `String`-typed amounts on
the request DTOs (see `../memory/gotchas.md`).

### Bodies

`AccountResponse`, on every route that returns an account:

```json
{
  "id": "…", "name": "Chase", "lastFour": "4412", "type": "CREDIT_CARD",
  "currency": "GBP", "openingBalance": "-400.0000", "balance": "-425.5000",
  "archived": false, "createdAt": "…", "updatedAt": "…", "version": 3
}
```

`balance` is computed by the service as `openingBalance` plus the account's transaction total, and
is **not** the same field as `openingBalance`. They are equal only because there are no
transactions yet.

`POST` takes `name`, `lastFour` (optional), `type`, `currency`, `openingBalance`. No `householdId`
and no user: both come from the authenticated caller's verified membership, and a field for either
would be a field an attacker could set.

`PATCH` is a **partial** update and takes the same five fields plus a required `version`:

- **An absent field is unchanged.** A client may patch one field and nothing else.
- **`version` is required**, because optimistic locking only works if the caller says which version
  it read. Without it the second of two concurrent editors silently overwrites the first instead of
  getting `409 stale-version`. The version in every response is the version in the row — a write
  flushes before it answers, or it would hand back the version the caller already had.
- **`"lastFour": null` clears the digits; omitting `lastFour` leaves them alone.** The two have to
  be distinguishable or a client that patches only the name silently erases the digits that tell two
  cards at one bank apart. JSON cannot carry that distinction in a record component — an absent
  property and an explicit `null` both arrive as `null`, and as `Optional.empty()` with Jackson's
  `Optional` support — so `UpdateAccountRequest` is the one request body in the codebase that is a
  mutable class rather than a record: a setter is called only for a property that is present.
- **Changing the currency to the one the account already has is not a change** and is never refused,
  so a client may patch the whole form back without being told the account is immutable.

Error codes follow `../guides/api-style.md`:

| Code                         | Status | When                                                                  |
| ---------------------------- | ------ | --------------------------------------------------------------------- |
| `validation-failed`          | 400    | Bad name, `lastFour`, type, currency or amount                        |
| `malformed-request`          | 400    | Unparseable body, unknown property, or a JSON number for an amount    |
| `read-only-role`             | 403    | A `VIEWER` attempting any write                                       |
| `not-a-member`               | 403    | Authenticated, but not a member of the household                      |
| `csrf-token-required`        | 403    | Session transport, missing or wrong CSRF token                        |
| `not-found`                  | 404    | Unknown id, or one belonging to another household                     |
| `account-currency-immutable` | 409    | Currency change on an account with transactions                       |
| `account-not-empty`          | 409    | Delete on an account with transactions                                |
| `stale-version`              | 409    | Optimistic-lock conflict                                              |

Two corrections to an earlier draft of this table, both deliberate:

- The `403` is `read-only-role`, not a generic `forbidden`. The codes already in `ErrorCode` are
  finer-grained than `forbidden`, a code is part of the API contract, and the client renders the
  sentence from it (ADR-0023) — "this role may not write" and "you are not a member of this
  household" are different sentences.
- `malformed-request` is reachable and was missing. A body Jackson cannot bind cannot be answered
  with a field-level `validation-failed`, because there is no bound object to validate.

The **role is checked before the account is looked up**, so a `VIEWER` sending an id that does not
exist gets `403` rather than `404`. That ordering is deliberate: the alternative lets a `VIEWER`
distinguish ids that exist from ids that do not by the code they get back.

## UI

Route `/accounts`, reachable from the main navigation.

**The designs do not contain an accounts screen.** The canvas shows accounts only as a filter row
("All accounts", "Chase •4412") and as labels on transaction rows in the _Money_ screen. This
screen is therefore built from the design system's existing patterns — the household settings and
members screens are the closest analogues and should be matched, not improvised against. When the
canvas gains real accounts artboards, this section is what gets reconciled.

- **List.** Grouped by currency when more than one is present, subtotalled per group, never
  totalled across. Each row: name, `•1234` when set, type, balance. Archived accounts are behind
  a toggle, visually muted.
- **Empty state.** First-run is the common case, not an error: a short line explaining what an
  account is for and a single primary action. No illustration of a sad wallet.
- **Add / edit** in a modal dialog, matching the invitation dialog's structure: focus trapped,
  focus restored to the trigger on close, `Escape` closes, the heading labelled by
  `aria-labelledby`.
- **Archive** asks for confirmation and says what it does ("keeps its history, hides it from
  lists"). **Delete** appears only when the account is empty, and its confirmation says the word
  "permanently".
- Currency is a `<select>`; when it is locked, the control is disabled with the reason beside it
  rather than vanishing, so the user learns why rather than wondering where it went.
- Both viewports, light and dark, from tokens only. Every string from `en.json`; every amount and
  date through the i18n formatters (ADR-0023).

## Security considerations

Touches `../architecture/security-model.md` on data ownership and role enforcement. It does
**not** touch authentication, sessions, CSRF or tokens — slice 2 owns those and nothing here
changes them.

- **Who may see this.** Any authenticated member of the household, and nobody else. The operator
  can read the database directly, which ADR-0026 already states plainly and the join screen
  discloses before anyone accepts an invitation.
- **Ownership check, and where.** Every query filters by the `household_id` resolved from the
  authenticated member's own membership. An id from the path is **only ever** a lookup key, never
  evidence of ownership. Another household's account id returns `404`, not `403` — a `403` would
  confirm the row exists.
- **Role enforcement is server-side.** A `VIEWER` gets `403` on every write. The UI also hides
  the buttons, which is a courtesy and not a control.
- **Untrusted input.** `name`, `lastFour`, `type`, `currency`, `openingBalance` are all attacker
  controlled. Bean Validation on the request DTO at the edge; `CHECK` constraints in the database
  as the thing that stays true when something bypasses the edge. `openingBalance` arrives as a
  string and is parsed to `BigDecimal` — a malformed amount is a `400`, never a silent `0`.
- **`lastFour` is a partial financial identifier and is treated as one.** It is never written to
  a log, never placed in an error message or a problem-detail `detail` field, and never used in a
  URL or query string where a proxy or access log would capture it — including the `Location`
  header on `POST`, which carries the id alone. See ADR-0028.
- **Two framework loggers had to be pinned to make that true**, and this is part of the feature
  rather than an aside. `AccountDisclosureIT` runs the whole journey with the root logger at
  `DEBUG` and found `lastFour`, the account name and the opening balance in two places nothing in
  our own code could reach: `org.hibernate.orm.core`, which prints every column of every flushed
  entity through Hibernate's `EntityPrinter` (and ignores the entity's own redacted `toString`),
  and `ExceptionHandlerExceptionResolver`, which logs a Bean Validation failure's `rejected
  value [98765]`. Both are pinned to `INFO` in `application.yml`. A root logger set to `TRACE`
  still binds JDBC parameters into the log; that is a deliberate act rather than a default.
- **Every request body's `toString` is redacted.** Spring MVC logs the deserialized body at
  `DEBUG`, and a record's generated `toString` prints every component — which here means the
  digits, the name and the amount. Same shape as the bug slice 2's credential-disclosure test
  caught.
- **Nothing here is logged with a value attached.** Audit lines name the account by id and the
  actor by user id. An account's name can contain anything the user typed and belongs in the
  database, not in the log stream.

## Edge cases

- **No accounts yet.** The empty state, not a zero-row table.
- **Two accounts, same name.** Allowed, and the reason `lastFour` exists. Nothing may assume
  name uniqueness — not a lookup, not a test fixture, not an import.
- **Opening balance of exactly zero.** Valid and common for a new account. Must not be treated as
  "unset" anywhere.
- **Negative opening balance.** Normal for a credit card. No warning, no sign coercion.
- **Currency change before any transaction.** Allowed. **After.** `409`.
- **Archiving an account that a picker currently has selected** elsewhere in the UI: the picker
  keeps showing it for the open form so the user is not silently rebound to a different account,
  and refuses to accept a new one.
- **Concurrent edits.** Optimistic locking on `version`; the loser gets `409 stale-version` and
  the UI re-reads and says what changed rather than overwriting.
- **Deleting an account with transactions.** `409 account-not-empty`. Only reachable before slice
  5 lands, but the rule exists now so the behaviour is not invented later under pressure.
- **An archived account's balance** is still correct and still shown when archived rows are
  revealed. Archiving hides, it does not zero.

## Out of scope

- **Reconciliation and statement matching.** Needs transactions (slice 5) and belongs with them.
- **Transfers between accounts.** Slice 8. A transfer is a linked pair of transactions
  (`../domain/model.md`), so it cannot exist before transactions do.
- **A cached or materialised balance.** Derived only. If a real performance problem appears, that
  is a projection with a rebuild path and it needs an ADR — `../domain/model.md` already says so.
- **Cross-currency household totals.** ADR-0022's read-time conversion, rates, and the
  "rate missing" state are a piece of work in their own right. Here: subtotal per currency, never
  a combined figure.
- **Bank connections and automatic balances.** Slice 16, gated on the admin console's first-run
  acknowledgement (ADR-0020).
- **Account-level permissions.** Roles are household-wide. Per-account visibility is a different
  product, and ADR-0026 already says the operator sees everything regardless.
- **Institution names, logos, colours, icons.** A name and four digits identify an account well
  enough; fetching a logo means talking to somebody else's server (non-negotiable #9).

## Open questions

- **Should the accounts screen show a household total when every account shares one currency?**
  It is unambiguous in that case and genuinely useful. Deferred until the Money screen lands,
  because that is where a total would actually be read, and inventing one here first risks two
  different totals computed two different ways.
- **Does `INVESTMENT` need a separate "current value" distinct from a transaction sum?** A
  holding changes value without a transaction. Treated as an ordinary account for now; revisit
  when somebody actually tracks investments in it rather than guessing at the shape.

## Testing notes

Beyond the mandatory list in `../guides/testing-style.md` (401 unauthenticated, 404 for another
household, 400 on bad input, 403 for a `VIEWER` write, identical under session and bearer):

- **Balance is computed, not echoed.** Assert through the API that `balance` equals
  `openingBalance` _by computation_ — a test that passes because both fields happen to be read
  from the same column proves nothing, and will keep passing after transactions make it wrong.
  **Done, in `AccountBalanceIT`**, which replaces the `AccountTransactions` bean with one that
  reports real totals and real history and then asserts `balance == openingBalance + total`
  through HTTP, on the single-account route and on every row of the list. That is also where the
  two transaction-dependent `409`s are proven reachable rather than merely written.
- **Currency immutability** proven with a real transaction present once slice 5 exists; until
  then, prove the check is reached and that the pre-transaction path still allows the change.
  **Done**, both halves, plus the two cases that are easy to get wrong in the other direction:
  patching the account's existing currency back is still allowed once it has transactions, and
  type and opening balance stay mutable.
- **`lastFour` rejects** empty string, three digits, five digits, letters, Unicode digits
  (`١٢٣٤`), a leading `+`, and whitespace padding. Accepts `0000`.
- **`openingBalance` round-trips exactly.** A value like `-1234.5678` comes back identical —
  string in, string out, no float anywhere. A property test over generated amounts is cheap here.
- **The ownership test is the valuable one.** ADR-0026 removed the cross-household case from
  slice 2 by making the instance single-household; here it returns. Seed a second household, and
  assert `404` on every route.
  **Seeding one takes more than an `INSERT`.** `uq_households_singleton` (`V3__households.sql`) is
  a unique index on a constant expression, so it permits exactly one household row whoever is
  inserting — API, migration, CLI or `psql`. The second household has to be created with the index
  dropped and the index restored afterwards, extra household deleted first. `AccountHouseholdScopingIT`
  does exactly that and explains itself at length; copy its shape rather than rediscovering why a
  plain insert fails.
- **`lastFour` never reaches a log.** Assert against captured log output on create, update and
  error paths — the same shape as slice 2's credential-disclosure test, which is what caught a
  password reaching the log. **Done, in `AccountDisclosureIT`**, at `INFO` and again at `DEBUG`,
  covering create, read, list, update, a rejected `lastFour`, a `VIEWER`'s refused write, a `404`,
  a `409 stale-version`, an unparseable body and a `CHECK` violation written straight to the table.
  It found two real leaks; see the Security section.
- **The seam, not a stub.** `AccountTransactions` has two methods because "the total is zero" and
  "there are no transactions" are different facts — a credit of 50 against a debit of 50 is an
  account with history whose currency must not be reinterpreted and whose rows must not be
  deleted. There is a test for exactly that case.

## Implementation notes

- `AccountService` owns the derived balance, both `409`s and all authorization.
  `AccountTransactions` is the slice-5 seam: a batched `totalsByAccount` and an
  `existForAccount`, both taking the `householdId` resolved from the caller's membership.
  `NoTransactionsRecorded` is its truthful implementation for a schema whose only money table is
  `accounts` — slice 5 replaces that bean and **adds a term**; it does not rewrite a call site.
- `AccountRepository.saveAndFlush` was added for one reason: `@Version` is incremented at flush, so
  a response built straight after mutating the entity reports the version the caller already had
  and their next `PATCH` is refused as a conflict that never happened.
- The listing pages **in the query**, not in memory. `PageResponse.of` materialises a list and
  slices it, which is fine for a household's members and is not fine for a financial collection.
