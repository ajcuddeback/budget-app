# Feature: Transfers

- **Status:** In progress — slice 8 (API and UI landed)
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

The ledger (`GET /api/transactions`) carries both legs, each marked `transfer: true` and each
carrying the pair's `transferGroupId` — **which is what lets a ledger row act on the transfer**.
Without it a row could show a transfer and offer nothing to do with it, because a leg's own id
addresses nothing and a guessed group id would edit somebody else's pair.

## UI

A **Move money** button on the ledger, beside *Record a transaction*, opening a sheet. It is
offered only to an `OWNER` or `MEMBER`, and only when the household has **two** accounts that can
still take a transaction — with one, the sheet could offer nothing but an account to itself.

1. **Out of** and **Into** — account pickers. Archived accounts are not listed, because either
   side being archived is a `409`. Choosing the same account in both is refused **inline, as it is
   chosen**, not on submit.
2. **Amount that left** — one field when the two accounts share a currency. `type="text"` with
   `inputmode="decimal"`, never `type="number"` (ADR-0006), and unsigned: the direction is the two
   pickers.
3. **Amount that arrived** — a second field, appearing only when the currencies differ, labelled
   with the receiving account's currency and explaining why it is being asked for. It lives in an
   `aria-live` region, so appearing is announced rather than silent. Within one currency the field
   is **not on screen and is never sent**; across two it is required. Both mistakes are a `400`.
4. **Date**, and an optional **note** (the note goes on both legs).

There is **no category picker and no payee field anywhere on the sheet**, and it says why in a
sentence rather than leaving the absence to be noticed.

**Editing** offers the date, the figures and the note. It has **no account pickers at all**: the
two accounts are named, and the sheet says that moving a transfer to different accounts means
deleting it and making it again — rather than leaving somebody hunting for a picker that is not
there. The patch carries **only what changed**: an absent `amountReceived` means "leave the
receiving leg alone", so a note-only edit of a cross-currency transfer is a note-only edit. Within
one currency it is never sent at all — there it would be a second name for `amount`, and the server
refuses it.

The **implied rate** is shown beside a cross-currency transfer in the sheet, formatted through
`Intl` and labelled as worked out from the two figures — never as the rate the bank gave, which had
a fee inside it (ADR-0022).

A transfer in the ledger **reads as a transfer**: "Transfer out" / "Transfer in" where a payee's
name would go (a transfer has none, and a blank there reads as missing data), the "Between your
accounts" label instead of a category, and a *Transfer* badge. Its **Edit** and **Delete** act on
the pair, through `/api/transfers/{transferGroupId}` — never on the leg, which the server refuses
with `409 transfer-leg-not-separate`. Deleting says that **both halves go** before it happens. A
leg that somehow arrives with no `transferGroupId` offers neither button and says that both halves
change together, rather than offering a write that could only be refused or aimed by guesswork.

A `VIEWER` sees every transfer, both legs and every figure, and no write control.

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

- **A leg does not name the account on the other side.** The ledger row carries its own
  `accountName` and the pair's `transferGroupId`, so it can say which way the money went and act
  on the pair, but not where it went without fetching the transfer. Naming it would mean either a
  second account name on every transaction row or a client-side fetch per transfer; neither is
  worth it until a screen needs it.
- **A household roll-up cannot net a cross-currency transfer out.** The two legs are in different
  currencies, so subtracting one from the other needs a rate the instance may not have. Reporting
  (slice 13) must exclude both legs rather than cancel them.
- **A transfer's accounts cannot be edited.** Moving one to a different account is a delete and a
  re-create. See the rule above for why; it is a real cost and it is the right one.
- **Nothing records who made a transfer.** The log line carries the acting user; the row does not.
  The same gap bills have.
- **The implied rate is not the rate anybody got.** It is what the two figures divide to. The
  bank's fee is usually inside its rate, so the two differ and the screen must not call ours "the
  rate you got".
- **There is no transfers list.** `GET /api/transfers/{id}` takes one group id and there is no
  collection route, so nothing can show "all transfers" — the ledger, with both legs in it, is the
  only view. That matches the data model (a transfer *is* two transactions) and is only a gap if a
  screen ever wants the pairs on their own.

## Testing notes

- `TransferIT` — the pair is written atomically; neither leg is editable or deletable alone; a
  same-account transfer is refused; an archived account on either side is refused; a cross-
  currency pair keeps both figures; both accounts' balances move by their own leg.
- `TransferCrossHouseholdIT` coverage folded into `MoneyCrossHouseholdIT` — another household's
  account cannot be either side, and its transfer is a `404` on every route.
- A constraint test proving the deferred trigger refuses a one-legged group, a two-legged group
  in one account, and two legs with the same sign.
- `transfers-data.spec.ts` — the second figure is sent across currencies and omitted within one;
  amounts leave as strings, unsigned; a patch carries only what changed, so a note-only edit of a
  cross-currency pair leaves the receiving leg alone; an unchanged form patches nothing; a group id
  in a path is encoded; a leg with no `transferGroupId`, or a group id on a row not marked a
  transfer, yields none.
- `transfers.page.spec.ts` — the second field appears only for a cross-currency pair and
  disappears again when the pair stops being one; the same account twice is refused inline and
  sends nothing; an archived account is not offered; there is no category picker; a transfer row's
  Edit and Delete call `/api/transfers`, never the leg's own endpoints; an edit has no account
  pickers; the implied rate is labelled as derived; a `VIEWER` gets no write control.
