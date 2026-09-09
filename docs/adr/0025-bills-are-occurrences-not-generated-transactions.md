# ADR-0025: Bills are occurrences with a status, not generated transactions

- **Status:** Accepted
- **Date:** 2026-09-09
- **Deciders:** Repository owner

## Context

A recurring bill has to appear in the app before it is paid — that is the point of listing it.
There are two established ways to make that happen, and they produce very different ledgers.

The first is to treat the bill as a **template that generates transactions**: on a schedule, the
app writes `PENDING` rows into the ledger, and paying the bill flips one to `CLEARED`. An earlier
draft of `docs/domain/model.md` assumed this, under the name `RecurringTransaction`. It is the
conventional answer and it is what most budgeting apps do.

The second is to treat the bill as a **plan with occurrences**: the bill projects a due date and
an expected amount per period, carries a status, and only becomes a ledger entry when real money
actually moves.

The designs point at the second. Bills are shown as their own list with a status — *Cleared*,
*In 3 days*, *Paid*, *Not mapped* — not as transactions sitting in the ledger. And the owner's
description of the intended behaviour is explicit: users define their bills; the app does its
best to match a bill to a connected transaction where a connection exists and the descriptor
looks similar; the user marks a bill paid as the month goes on, or the status changes itself when
a matching charge arrives.

The spreadsheet that motivated the product is built the same way. Its bill table's own columns
are *Bill Name*, *Bill Amount*, *Bill Due*, *Actual*, *Pay Date*, *Pay Amount* — a planned figure
and a due date, then an actual figure and a real pay date filled in as the month progresses. That
is a plan with occurrences, and it is the mental model the product is replacing.

The distinction matters because this app is not, and must never become, the authority on whether
money moved. The bank is. Anything we invent and write into the ledger is a claim we made up.

## Decision

**A bill is a plan. It generates `BillOccurrence` rows, not `Transaction` rows.**

- `Bill` carries the recurring definition: name, category, schedule, expected amount, and whether
  the amount varies (with a tolerance).
- `BillOccurrence` is one instance of it in one period: `dueDate`, `expectedAmount`, `status`,
  and — once paid — `actualAmount`, `paidDate`, and the `transactionId` it was satisfied by.
- **Planned and actual are separate fields.** A bill can be paid for an amount other than the one
  budgeted, and both numbers are kept. Reporting the difference is the point.
- `status` is `DUE` | `PAID` | `SKIPPED`, with overdue derived from `dueDate` rather than stored
  (a status that needs a nightly job to stay true will eventually be wrong).
- **The status can always be set by hand.** Marking a bill paid is a first-class action that
  requires no connection, no import and no match. This is the primary path, not the fallback.
- **Where an account is connected, matching may set it automatically.** A `ChargeRule` maps the
  bill to a real charge; a confident match sets the occurrence to `PAID` and records the actual
  amount and date from the transaction.
- Every occurrence records **how its status was reached** — `statusSource` of `MANUAL` or
  `AUTO_MATCHED` — so a figure the user asserted is never indistinguishable from one the app
  inferred. Same principle as `Debt.balanceSource`.
- A bill occurrence **never becomes income or expense on its own.** Only the real transaction it
  matched does. An unmatched, hand-marked bill contributes to the *plan*, not to the *ledger*.

`RecurringTransaction` is removed from the domain model. Nothing generates speculative ledger
rows.

## Alternatives considered

| Option | Why not |
|---|---|
| Generate `PENDING` transactions from a schedule | Puts money we invented into the ledger. Every unpaid bill becomes a row that has to be reconciled, cancelled or cleaned up if the bill changes, and a user who never connects an account accumulates a ledger of things that never happened. Editing one occurrence without breaking the series is a known source of bugs in every app that does this. |
| Occurrences, but no `actualAmount` — pay for the expected figure | Loses the planned-vs-actual comparison, which is the thing the spreadsheet does and the reason the owner's table has both an *Amount* and an *Actual* column. Variable bills (utilities) become unrepresentable. |
| No occurrences — compute due dates on the fly from the schedule | Nowhere to hang a status, a paid date, or a match. Works until someone marks something paid. |
| Occurrence status derived purely from matched transactions | Breaks the unconnected instance, which is the default one (ADR-0016). A self-hoster with no aggregator would have a bill list that never changes. |

## Consequences

**Good:** the ledger only ever contains money that actually moved, so it can be trusted as a
record. Manual operation is the primary path rather than a degraded mode, which is what a
self-hosted product with no mandatory connections needs. Planned-versus-actual falls out for
free, per bill and in aggregate. Changing a bill's amount or schedule affects future occurrences
without rewriting history or cleaning up stale generated rows.

**Bad / costs:** two concepts where apps usually have one, and the relationship between a
`BillOccurrence` and the `Transaction` that satisfied it has to be maintained in both directions.
Occurrences have to be materialised at some point — lazily on read for a period, which needs care
so that reading a future month does not silently write rows. "Committed out" totals must count a
bill occurrence *or* its matched transaction, never both; double-counting is the obvious bug this
model invites and needs an explicit test.

**Follow-ups:** the bills & income feature doc (slice 7) specifies matching confidence, what
happens when a match is later found to be wrong, and how far ahead occurrences are materialised.
Debt minimums appear as bill rows and are counted once — see the note under `Debt` in
`docs/domain/model.md`.
