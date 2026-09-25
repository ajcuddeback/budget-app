# Feature: Envelopes

- **Status:** Planned — this is slice 6
- **Owner:** Repository owner
- **Last updated:** 2026-09-09
- **Related:** ADR-0006 (money), ADR-0017 (households), ADR-0022 (currency), ADR-0023 (i18n),
  ADR-0025 (bills are occurrences)
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
| `GET` | `/api/envelopes?period=2026-08` | List with computed `remaining` |
| `POST` | `/api/envelopes` | Create |
| `PATCH` | `/api/envelopes/{id}` | Rename, re-amount, archive |
| `GET` | `/api/envelopes/{id}/entries` | The log |
| `POST` | `/api/envelopes/{id}/entries` | Log spend — amount required, the rest optional |
| `PATCH` | `/api/envelopes/{id}/entries/{entryId}` | Correct |
| `DELETE` | `/api/envelopes/{id}/entries/{entryId}` | Remove |

## UI

Three surfaces, each at both viewports (non-negotiable #10):

1. **Quick add** — keypad-first sheet. Amount, envelope, optional payee. The 81-times-a-month
   action.
2. **Envelope log** — remaining as the headline, running total beneath, entries listed with their
   source marked.
3. **Envelopes block** — on Bills & Income, under bills and income.

Values come from the design tokens, never hard-coded (`design/README.md`). Dark mode is part of
the slice, not a later pass.

## Security considerations

- **Household scoping** is the whole game: an envelope or entry from another household must 404,
  and the household comes from the authenticated session, never from the request body.
- **`VIEWER` may not log entries.** A read-only member logging spend is a role-escalation bug.
- **Validate at the edge**: amount present, scale within the currency's, date sane, note length
  bounded, `envelopeId` in the path belonging to the caller's household before anything is read.
- **No amounts in logs.** An entry is financial data; log ids, not figures.

## Open questions

These are genuinely undecided. Answer them before building, not during.

1. **Rollover.** The designs show a rollover affordance in the weekly check-in but never say what
   an envelope does with an unspent amount at period end — carry forward automatically, offer to,
   or reset. And an *overspent* envelope is the harder half: does the overspend come out of next
   period, out of the left-over figure, or just sit as a negative?
2. **Period boundary.** Envelopes are per-`YearMonth`, but the instance has a configurable period
   start (admin Settings — "it decides what a month means everywhere in the app"). Envelopes have
   to honour that, which means "August" is not necessarily the 1st to the 31st.

## Out of scope

- **Zero-based budgeting** ("every dollar assigned"). The designs explore it as *Direction C* and
  the built home screen leads with safe-to-spend instead. Envelopes here sit alongside bills
  rather than consuming the whole income. Revisit deliberately, with an ADR, if ever.
- **Auto-suggesting envelope amounts** from history. Wanted eventually; needs reporting first.
- **Shared vs personal envelopes** within a household. Everything is household-wide for now.
