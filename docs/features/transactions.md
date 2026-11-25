# Feature: Transactions

- **Slice:** 5
- **Status:** Shipped
- **Depends on:** accounts (slice 3), categories (slice 4)
- **Depended on by:** envelopes (6), bills (7), transfers (8), import (12), reporting (13)

## Purpose

A transaction is a single movement of money. **This is the slice that makes Budget Owl usable:**
before it, a household could say it had a current account and then do nothing further. Balances are
derived from it, envelopes draw down against it, bills match to it, and reporting sums it.

## User stories

- As a member, I want to record what I just spent in a few seconds, without being made to tidy
  anything up first.
- As a member, I want my account balances to be right because of what I recorded, not because I
  typed a balance in.
- As a member, I want to find a transaction again — by account, by category, by date, or by
  something I half-remember about it.
- As a member, I want to see what still needs a category, and work through it.
- As a member, I want to correct a mistake without losing the rest of what I typed.

## Rules and behavior

1. **The amount is signed; the sign carries the direction.** Negative is money leaving. There is no
   stored income/expense flag, because the sign already says it, and two sources for one fact
   eventually disagree. A positive amount against an expense category is a **refund**, which is
   ordinary and must not be rejected.
2. **The amount is in the account's currency, and nowhere states a currency of its own.** A
   `currency` column on the transaction would be a second copy of a fact the account owns. This is
   also what makes `account-currency-immutable` meaningful: re-denominating an account would
   silently change the meaning of every row under it.
3. **Amounts cross the wire as strings** (ADR-0006). A JSON number is refused with `400` rather
   than converted: by the time it arrives as a number, the sender's double has already rounded it.
   Zero is refused — it moves no money — and so is an amount with more than four decimal places,
   rather than being silently rounded.
4. **A category is optional.** `categoryId: null` means **"Needs a category"** — a real state the
   UI shows and a filter you can stand in, not missing data. Recording what happened is never
   gated on tidying up first; that is the single biggest difference between a budget somebody keeps
   using and one they abandon.
5. **A payee is created on the way past.** The person types a name; it matches one the household
   already has (case-insensitively) or becomes a new one. Nobody is asked to manage a payee list
   before recording a purchase.
6. **An archived account takes no new transactions** (`409 account-archived`). Un-archiving is one
   click and is a deliberate act; an archived account quietly accepting money and then not
   appearing in the list it was recorded against is the bug that would cause.
7. **The account cannot be changed by an edit.** It changes two balances and, when currencies
   differ, the meaning of the amount. `TransactionChanges` cannot express it.
8. **Deleting is a real delete**, not a tombstone. A household that typed the wrong figure needs it
   gone, and every later sum would otherwise have to remember to exclude it. "Append-mostly" in
   `docs/domain/model.md` is about corrections keeping a trail, which `updated_at` and `version`
   provide.
9. **`RECONCILED` is not a status a caller may set.** It asserts that a statement period was
   closed against the row, which is reconciliation's to say. Reconciliation has no slice yet
   (`reporting.md`, decision 12). The request contract refuses it, the same way it refuses an
   account change — rather than accepting it and apologising later.
10. **Everything a person types starts `CLEARED`.** They watched it happen and are the authority on
   whether it did. `PENDING` is for something expected to settle; `RECONCILED` is set by
   reconciliation, which has no slice yet, never by a user toggling it.

## Data model

`payees` and `transactions` (`V12__payees_and_transactions.sql`).

**Payee is a table from the start**, though slice 5 ships only get-or-create. A plain
`payee_name text` would behave identically today and would make normalisation — the whole point,
so "STARBUCKS #1234" and "Starbucks" can reconcile — a migration that extracts names out of every
transaction and rewrites them.

**Scoping is declarative, not a trigger.** The account, category and payee foreign keys are
composite on `(id, household_id)`, so a transaction physically cannot reference another household's
row, whatever a future query forgets to filter (ADR-0017). Two consequences fall out for free:

- `fk_transactions_account` is `ON DELETE RESTRICT`, so deleting an account that still has
  transactions fails **in the database**. The rule accounts.md states is now structural.
- `fk_transactions_category` is `ON DELETE SET NULL (category_id)` — PostgreSQL 15+ column-list
  syntax, because a bare `SET NULL` would try to null `household_id` too and the delete would fail.

`transfer_group_id` is present but unwritten: a transfer is a linked **pair** of equal and opposite
rows sharing it (slice 8), never one row with two account columns.

`external_id` is unique per household where not null, so re-importing a file (slice 12) cannot
duplicate rows.

## API

| Method   | Path                                  | Notes                              |
| -------- | ------------------------------------- | ---------------------------------- |
| `GET`    | `/api/transactions`                   | The ledger, newest first, filtered |
| `POST`   | `/api/transactions`                   | `OWNER` / `MEMBER`                 |
| `GET`    | `/api/transactions/{id}`              |                                    |
| `PATCH`  | `/api/transactions/{id}`              | Requires `version`                 |
| `DELETE` | `/api/transactions/{id}`              | `204`                              |
| `GET`    | `/api/transactions/payee-suggestions` | Names only, at most ten            |

Filters are explicit typed query parameters: `accountId`, `categoryId`, `uncategorised`, `from`,
`to`, `search`. **`uncategorised` is a separate flag** because an omitted `categoryId` already
means "any category", so "has none" would otherwise be unexpressible — and it is the one filter a
person actually acts on.

The ledger's envelope adds `pageTotal` and `pageTotalCurrency` to the usual four paging fields.
**It is `null` when the page mixes currencies**, never a converted figure: conversion is
display-only (ADR-0022) and a cross-currency total is a number nobody could check.

`payeeName`, `categoryId` and `note` are three-way on `PATCH`: absent leaves the value, `null`
clears it, a value sets it. Without that, a client patching an amount would silently clear all
three.

## UI

`/transactions`, and the app's landing page — it is the screen somebody opens Budget Owl to use.

**The amount is typed unsigned and the direction is a separate choice.** Nobody writing down a £42
shop types a minus sign, and asking them to is how a month of income ends up recorded as spending.
"Money out" is preselected because it is the common case.

Payee suggestions come from a native `<datalist>`: keyboard and screen-reader support for free,
and if suggestions never arrive the field is still an ordinary text input rather than a broken
combobox.

"Needs a category" appears three times on purpose — as the first option in the category picker, as
a marked state on a row, and as a filter button — because it is a work queue, and a queue nobody
can see is a queue nobody empties.

Row actions reserve a fixed width so the amounts line up in a column that can be scanned down,
regardless of how many buttons a row carries.

## Security considerations

- Every query is scoped by the household from the caller's verified membership, and the composite
  foreign keys enforce the same thing structurally.
- A `VIEWER` reads the ledger and writes nothing.
- **Nothing is logged but ids.** An amount is somebody's money, a payee says where they shop, and a
  note is whatever they wrote. Request and response DTOs have redacted `toString`s because Spring
  MVC logs both at `DEBUG`.
- `search` and the payee prefix are bound parameters with `like` wildcards escaped — not a
  security hole, since nothing is concatenated, but an unescaped `%` would silently match
  everything.

## Edge cases

- **An account whose transactions net to zero still has history.** It can be neither deleted nor
  re-denominated. `withHistory` is deliberately not derived from the totals.
- **A transfer has no category and never will.** Transfers are excluded from the "Needs a category"
  queue and are not offered a category in the UI; the row says "Between your accounts". Found by
  the browser check, which renders a transfer from the fixtures.
- **A page mixing currencies** carries no total, and the screen says so.
- **Two members editing one transaction**: `409 stale-version`.

## Known gaps

These came out of the security review of this slice and are not done:

- **`ApplicationRoleRestrictionsIT` does not exercise the three new tables.** The grants are
  inherited correctly (`V10` sets `ALTER DEFAULT PRIVILEGES`), but the only test that connects as
  the genuinely restricted role still inserts into the slice-1 and slice-3 tables only. A grant
  table says what was said, not what the database means.
- **No disclosure test for amounts, payees and notes.** `AccountDisclosureIT` proves an account's
  name, digits and balance stay out of the log at `DEBUG`; its journey records no transaction, so
  nothing holds the "nothing is logged but ids" claim above. The control is level-based and
  therefore entity-agnostic, which is why this is a gap in the proof rather than a known leak — but
  that is exactly the argument slice 2 made before the credential-disclosure test found a real one.

## Out of scope

- Transfers as a first-class act (slice 8). The column and the rules are here; the UI is not.
- Splitting one transaction across several categories. A real need, and a schema change.
- Attachments, reconciliation, recurring entry, bulk edit, import (slice 12).
- Renaming or merging payees — it wants reporting to show why it matters.

## Open questions

- **Infinite scroll versus paging.** 50 per page with a pager is honest and cheap; a long ledger
  may want more.
- **Keyboard-first rapid entry.** The dialog is fine for one transaction and tedious for fifteen.
  An inline "add another" row is the obvious answer; not yet designed.
- **Should a refund be visually distinct** from income? Both are positive amounts in an expense
  context. Today only the category tells them apart.

## Testing notes

- `TransactionIT` — the cases that would cost money or data if wrong: an amount keeping every digit
  across the wire, a JSON number refused, a `PATCH` of one field not clearing the others, an
  explicit `null` clearing them, the balance moving, the zero-sum account still having history,
  and every filter exercised **separately**. That last one matters: an unfiltered ledger passed
  while every filtered one 500'd, so a single "the ledger returns rows" test would have shipped a
  broken screen (`docs/memory/gotchas.md`).
- `transactions-data.spec.ts` — `signedAmount` round-tripping, and the amount comparison that
  treats `-42.5` and `-42.5000` as equal without going through a `Number`.
- `transactions.page.spec.ts`, `tools/ui/specs/app/transactions.spec.ts` — the screen in both
  viewports, with axe, including the work-queue filter and the entry form.
