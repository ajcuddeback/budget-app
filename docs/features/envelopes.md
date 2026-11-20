# Feature: Envelopes

- **Status:** Shipped — slice 6
- **Owner:** Repository owner
- **Last updated:** 2026-11-20
- **Related:** ADR-0006 (money), ADR-0017 (households), ADR-0022 (currency), ADR-0023 (i18n),
  ADR-0025 (bills are occurrences), ADR-0031 (periods and rollover)
- **Designs:** `design/canvas/` section `spend` — Quick add, Envelope log, and Envelopes in
  Bills & Income, each at 390 and at desktop width

## Purpose

An envelope is **an amount you decided on, that you spend down**. Groceries, dining, fuel,
"adhoc" — the variable spending that a schedule cannot predict and a bill cannot describe.

This is the feature a spreadsheet does worst. In the spreadsheet that motivated it, a "Food
Expenses" column held 81 hand-entered purchases totalling $3,578, and that total was written back
into the Groceries line **at the end of the month** — by which point the number could no longer
change a decision. An envelope computes the remaining figure *as you add to it*, so the number
exists while you are still standing in the shop.

It is also the first slice that is **useful to somebody on day one**, because it needs nothing
connected to anything.

## Prerequisites

Categories (slice 4) and transactions (slice 5). Not bank connections, not file import, not the
weekly check-in — an envelope must work on an instance that has never talked to a bank.

## Rules and behaviour

### The envelope

- An envelope has a **name**, a **category**, a **period** (`YearMonth`), and a set **amount**.
- **Remaining is computed, never stored.** `amount` minus the sum of its entries. It is the
  headline on every envelope surface because it is the number a person acts on; the running total
  sits underneath it, not above.
- Envelopes are **household-scoped** like everything else (ADR-0017). Every query filters by a
  household the caller is a verified member of, and a `VIEWER` may read but never log an entry.

### Logging an entry

- **An amount alone is a valid entry.** Payee is optional, note is optional, category comes from
  the envelope. Anything that makes payee mandatory has broken the feature — this is the action
  someone takes 81 times a month, most often one-handed, in a queue.
- Quick add **opens on the keypad**, with an envelope pre-selected from the last entry. The guess
  is a convenience and must be changeable in one tap.
- Entries are **append-mostly**, like transactions: correcting one leaves a trail rather than
  silently rewriting it.

### Manual and synced entries are visibly different

Every entry records whether it was typed (`MANUAL`) or arrived from a sync (`SYNCED`), and the
log **says which on every row**. A person needs to know what they told the app from what the app
told itself; a figure you entered and a figure inferred from a bank feed are different kinds of
claim and are never presented identically.

An envelope with no connected account is not a degraded envelope. It is the default.

**Assigning spending to an envelope is user-managed.** A transaction never files itself, however
confident the category match looks. The app may surface a suggestion; the person decides. So
`SYNCED` means *the user attached this entry to a real transaction* — not that the app did it
while they were not looking. An envelope is a record of what someone decided, and an app that
quietly fills it in has taken that away.

### Envelopes are not bills

They live on the same page — Bills & Income, as a third block under bills and income — because
both feed the same left-over figure. They are not the same thing:

| | `Bill` | `Envelope` |
|---|---|---|
| Nature | Owed on a date | Chosen, spent down |
| Amount | Largely not your choice this month | Entirely your choice |
| Signal | Paid / not paid | How much is left |

### Keep envelopes narrow

Groceries and Adhoc are **separate envelopes**, not one mixed column. In the source spreadsheet's
July log, Publix and Wendy's runs sat beside a $299 Home Depot trip and a $278 vacuum — those are
not the same decision, and averaging them into one number destroys exactly the signal the
envelope exists to give. The UI should make splitting easy and merging unattractive.

## Data model

`Envelope` and `EnvelopeEntry` as defined in `../domain/model.md`. In summary:

- `Envelope`: `id`, `householdId`, `name`, `categoryId`, `period`, `amount` (`Money`),
  `rollover`, `archived`
- `EnvelopeEntry`: `id`, `envelopeId`, `date`, `amount` (`Money`), `payeeId` (**nullable**),
  `note`, `source` (`MANUAL` | `SYNCED`), `transactionId` (nullable)

Money is `BigDecimal` / `NUMERIC(19,4)`, never a float, and an envelope's currency is its
household's — cross-currency arithmetic throws rather than coercing (ADR-0006, ADR-0022).

## API

Endpoints are household-scoped and follow `../guides/api-style.md`. Sketch, to be firmed up when
the slice is built:

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/envelopes?period=2026-08` | List with computed `remaining`, plus the period's totals |
| `GET` | `/api/envelopes/periods` | The months that have envelopes, newest first |
| `POST` | `/api/envelopes` | Create |
| `GET` | `/api/envelopes/{id}` | One |
| `PATCH` | `/api/envelopes/{id}` | Rename, re-amount, re-categorise. **No `period`** |
| `POST` | `/api/envelopes/{id}/archive` · `/unarchive` | |
| `DELETE` | `/api/envelopes/{id}` | Takes the log, no transactions |
| `GET` | `/api/envelopes/{id}/entries` | The log |
| `POST` | `/api/envelopes/{id}/entries` | Log spend — **either** an `amount` **or** a `transactionId` |
| `PATCH` | `/api/envelopes/{id}/entries/{entryId}` | Correct |
| `DELETE` | `/api/envelopes/{id}/entries/{entryId}` | Remove |

**The totals exclude archived envelopes**, even when the caller asked for them so it could show
them separately. A retired envelope is not part of the month's plan; counting it would overstate
the plan and understate what is left, and the figure would look perfectly reasonable. A UI test is
what caught that.

An entry's path names its envelope as well as the entry: an entry of the right household but the
wrong envelope is still the wrong entry for the request that asked for it, and the server answers
`404`.

## UI

Three surfaces, each at both viewports (non-negotiable #10):

1. **Quick add** — a sheet whose first field is the amount, `inputmode="decimal"` so a phone opens
   the keypad, and `type="text"` so the figure never passes through a double. Everything else says
   it is optional. The 81-times-a-month action. **Shipped.**
2. **Envelope log** — remaining as the headline, running total beneath, entries listed with their
   source marked. Opened inline from the list; the toggle is hidden for an envelope with nothing
   logged, because a button that opens an empty list wastes a tap. **Shipped.**
3. **Envelopes block** — on Bills & Income, under bills and income. **Not shipped**: that page is
   built in slice 7.

The screen opens on the current month, or — when that month has no envelopes and an earlier one
does — on the most recent month that has any. Landing on an empty screen looks broken to somebody
who knows they set envelopes up, and the header names the month it is showing either way.

Values come from the design tokens, never hard-coded (`design/README.md`). Dark mode is part of
the slice, not a later pass.

## Security considerations

- **Household scoping** is the whole game: an envelope or entry from another household must 404,
  and the household comes from the authenticated session, never from the request body.
- **`VIEWER` may not log entries.** A read-only member logging spend is a role-escalation bug.
- **Validate at the edge**: amount present, scale within the currency's, date sane, note length
  bounded, `envelopeId` in the path belonging to the caller's household before anything is read.
- **No amounts in logs.** An entry is financial data; log ids, not figures.

## Decided

Both questions this doc used to end with are answered in **ADR-0031**, which was written before
the slice was built rather than during it.

1. **The period is a LABEL, not a boundary.** `2026-08-01` means "the envelope for August 2026".
   An entry belongs to an envelope because somebody logged it there, not because its date falls in
   a range — `EnvelopeIT.the_period_is_a_label_not_a_bound_on_entry_dates` holds that. So when
   slice 9 makes a month start on the 25th, it changes what people log where and rewrites nothing.
2. **Rollover is not built, and there is no `rollover` column.** The designs show the affordance
   and say nothing about an *overspent* envelope, which is the half that matters for the households
   this feature is for. Unspent does not carry; an overspend shows as a negative remaining.

A third decision came out of building it: **a transaction may be attached to at most one envelope
entry, ever**, enforced by a unique index. Counting one purchase in two envelopes would overstate
spending in both and understate what is left in each, and both figures would still look plausible.

## Known gaps

- **No rollover**, as above. A household that underspends in August gets no credit for it in
  September. That is the gap the designs gestured at, and closing it needs its own ADR answering
  the overspend question first.
- **No envelopes block on Bills & Income.** The feature doc asks for three surfaces; slice 6 ships
  two — the list with its inline log, and quick add. The third belongs with bills (slice 7), which
  is where that page gets built.
- **No "copy last month's envelopes".** Setting up twelve envelopes again every month is the
  obvious next irritation, and the `/api/envelopes/periods` endpoint already knows what last month
  had. Deliberately not guessed at here.

## Out of scope

- **Zero-based budgeting** ("every dollar assigned"). The designs explore it as *Direction C* and
  the built home screen leads with safe-to-spend instead. Envelopes here sit alongside bills
  rather than consuming the whole income. Revisit deliberately, with an ADR, if ever.
- **Auto-suggesting envelope amounts** from history. Wanted eventually; needs reporting first.
- **Shared vs personal envelopes** within a household. Everything is household-wide for now.
