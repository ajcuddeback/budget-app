# Feature: Transfers

- **Status:** In progress — slice 8
- **Owner:** Repository owner
- **Last updated:** 2026-11-21
- **Related:** ADR-0006 (money), ADR-0017 (households), ADR-0022 (currency),
  ADR-0033 (a transfer is a linked pair), `transactions.md` (slice 5)
- **Designs:** `design/canvas/` — the transfer sheet, reached from the ledger

## Purpose

Moving money between two of your own accounts is **not income and not spending**, and an app that
records it as either will tell you that you earned your savings twice and spent your rent money
on nothing.

The column and the rules have been in place since slice 5. This slice is the act: creating a
transfer, correcting one, and removing one — as one thing, not as two transactions somebody has
to remember to keep in step.

## Prerequisites

Accounts (slice 3) and transactions (slice 5). Nothing else: a transfer needs no category, no
payee, no connection and no rate source.

## Rules and behaviour

### A transfer is two rows, not one

ADR-0033. Two transactions sharing a `transfer_group_id`, one in each account, with opposite
signs. Never one row with two account columns — an account's balance is the sum of its own
transactions, and that stays true only if every movement is a row in the account it moved.

Both legs appear in the ledger. An account whose balance changed with nothing to show for it is a
bug report, and a household filtering its ledger to one account must see that account's side.

### Across currencies, the household supplies both amounts

ADR-0033 and ADR-0022. Two accounts in the same currency need one figure; the legs are equal and
opposite. Two accounts in **different** currencies need both, because what left and what arrived
are different numbers and only the statement knows the second one. No rate is fetched, inferred
or stored — the bank's rate had a fee inside it and ours would not.

An implied rate may be **shown**. It is a division, labelled as derived, and nothing reads it.

### A transfer has no category and no payee

It is not spending, and the other side is not a third party. Both are refused rather than ignored:
a request that sets them is a `400`, so a client cannot believe it filed a transfer under
"Savings" and find later that it did not.

### Neither half stands alone

`PATCH` and `DELETE` on a transaction with a `transfer_group_id` are already refused (slice 5).
This slice adds the endpoints that act on the pair, inside one database transaction, so there is
no moment at which money has left one account and arrived nowhere.

Editing a transfer rewrites both legs: the date, the figures, the note.

**Not the accounts.** `Transaction` already fixes an account at creation — moving a row between
accounts changes two balances and, where the currencies differ, what the amount means — and a
transfer is two such rows, so it would change up to four. Picking the wrong savings account is
corrected by deleting the transfer and making it again, which costs exactly what a mis-keyed
transaction costs today. It also keeps the pair's version honest: a leg is only ever corrected,
never replaced, so the version moves forward and nothing else.

### The two accounts must differ

A transfer from an account to itself is two rows that cancel out and mean nothing. Refused at the
edge and again in the database.

### An archived account takes no new transfers

The same rule as a transaction (`account-archived`). Either side being archived refuses the
transfer; an **existing** transfer involving one is untouched, because it already happened.

## Data model

No new table. `V15__transfer_constraints.sql` adds what the pair rule needs and the existing
schema cannot express:

- `ck_transactions_transfer_has_no_category` — a row with a `transfer_group_id` has no
  `category_id`. The API refuses it; this is the control.
- A **deferrable constraint trigger** asserting that a `transfer_group_id` names exactly two rows,
  in two different accounts, with opposite signs. Deferred, because the first leg is written
  before the second one exists; `DEFERRABLE INITIALLY DEFERRED` is the same shape the
  at-least-one-owner rule uses (V3) and the categories two-level rule uses (V11).

`transfer_group_id` itself, its index and the comment are already in `V12`.

## API

| Method   | Path                    | Notes                                              |
| -------- | ----------------------- | -------------------------------------------------- |
| `POST`   | `/api/transfers`        | Creates the pair. `OWNER` / `MEMBER`               |
| `GET`    | `/api/transfers/{id}`   | `{id}` is the `transferGroupId`                    |
| `PATCH`  | `/api/transfers/{id}`   | Rewrites both legs. Requires `version`             |
| `DELETE` | `/api/transfers/{id}`   | Removes both legs. `204`                           |

A transfer is addressed by its **group id**, not by either leg's id: the pair is the thing, and
naming one leg would make the other one's id a second name for the same object.

`version` is the **pair's** version: the sum of the two legs'. They are written together and
bumped together, so the sum moves by two on every edit, is stale exactly when either leg is, and
only ever moves forward. A stale one is a `409`, as everywhere else. `PATCH` carries no account
ids.

The ledger (`GET /api/transactions`) is unchanged: both legs appear, already marked.

## UI

A **Move money** action on the ledger, opening a sheet:

1. **From** and **To** — account pickers. Choosing the same account in both is refused inline
   rather than on submit.
2. **Amount** — one field when the two accounts share a currency.
3. **Amount received** — a second field, appearing only when they do not, labelled with the
   receiving account's currency and explaining why it is being asked for.

Editing offers the date, the figures and the note. To move a transfer to different accounts you
delete it and make it again, and the sheet says so rather than leaving somebody hunting for a
picker that is not there.
4. **Date**, and an optional **note**.

A transfer in the ledger reads as a transfer, with the other account named, and offers **Edit**
and **Delete** that act on the pair. There is no category picker on it at all.

## Security considerations

- Household-scoped throughout. **Both** account ids are resolved against the caller's household;
  an id from the body is a lookup key and another household's is a `404`.
- A `VIEWER` may read a transfer and create none.
- The group id in a path is parsed as a UUID, so a malformed one is a `400` before any query.
- **No amounts in logs.** Audit lines carry ids, account ids and the group id.
- A transfer must not become a way to write a transaction into an account the caller cannot
  otherwise write to — which is why both sides go through the same account resolution a plain
  transaction does, rather than one side being taken on trust from the other.

## Out of scope

- **Matching imported pairs** (slice 12). An import sees two rows and cannot know they are one
  transfer; that is its problem to solve.
- **Scheduled or recurring transfers.** A standing order between your own accounts is a real
  thing, but it is a bill-shaped idea and belongs with schedules, not here.
- **Netting a transfer out of a household roll-up.** Across currencies that needs conversion,
  which may be impossible offline (ADR-0022).

## Known gaps

_To be filled in as the slice lands._

## Testing notes

- `TransferIT` — the pair is written atomically; neither leg is editable or deletable alone; a
  same-account transfer is refused; an archived account on either side is refused; a cross-
  currency pair keeps both figures; both accounts' balances move by their own leg.
- `TransferCrossHouseholdIT` coverage folded into `MoneyCrossHouseholdIT` — another household's
  account cannot be either side, and its transfer is a `404` on every route.
- A constraint test proving the deferred trigger refuses a one-legged group, a two-legged group
  in one account, and two legs with the same sign.
