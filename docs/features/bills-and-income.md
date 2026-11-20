# Feature: Bills and income

- **Slice:** 7
- **Status:** Shipped
- **Depends on:** categories (4), transactions (5), envelopes (6)
- **Related:** ADR-0025 (bills are occurrences), ADR-0032 (occurrences are projected), ADR-0006
- **Designs:** `design/canvas/` — the Bills & Income page, bills and income blocks with envelopes
  beneath them

## Purpose

Bills and income are the **predictable** half of a household's money: what arrives on a schedule,
and what leaves on a date. Together with envelopes — the unpredictable half, slice 6 — they answer
the only question the page exists for:

> **What is left to spend this month?**

That figure is income, minus the month's bills, minus what is set aside in envelopes. It is one
subtraction and the page leads with it.

**Bills count whether or not they are paid.** Rent that has already gone is not money still
available, so subtracting only the unpaid ones would make "what's left" *rise* every time somebody
ticked a bill off — the figure would be most optimistic on the day the household had least left.
A paid occurrence contributes what was really paid; an unpaid one contributes what is budgeted; a
skipped one contributes nothing. The outstanding figure is reported alongside, so the page can say
"£340 of £1,200 still to pay" without that being the figure it leads with.

## User stories

- As a member, I want my rent and my phone bill listed before they are due, so nothing surprises
  me.
- As a member, I want to tick a bill off as I pay it, without connecting anything to a bank.
- As a member, I want to record that I paid **more** than I budgeted, and keep both numbers.
- As a member, I want to skip a bill in a month it does not apply, without deleting it.
- As a member, I want to see what is left after the predictable things are accounted for.

## Rules and behaviour

### A bill is a plan, not a transaction

ADR-0025. A bill never writes a `Transaction`. Marking one paid records a **plan** fact; only a
real transaction is ever income or expense. The app is not, and must not become, the authority on
whether money moved.

### Occurrences are projected, not generated

ADR-0032. Reading a period computes each active bill's occurrences from its schedule; nothing is
written. Recording a status writes a row, keyed on `(billId, dueDate)`. There is **no job and no
scheduler**, so an instance that was switched off for a month is not wrong when it comes back.

A period nobody has touched reads exactly like one where every occurrence is still `DUE`.

The key is the **due date**, not the month: a weekly bill falls four or five times in a month and
each payment is ticked off separately. ADR-0032 also sets out how a period that was settled before
the schedule changed is read back.

### Planned and actual are both kept

A bill carries an `expectedAmount`. An occurrence marked paid carries an `actualAmount` as well,
defaulting to the expected one. The difference is the useful number, and a bill whose amount
varies is unrepresentable without it.

`amountVaries` with a `tolerance` lets a bill say "about £120, give or take £22", so that a £134
payment is not flagged as a surprise and a £300 one is.

### Overdue is derived, never stored

`status` is `DUE` | `PAID` | `SKIPPED`. "Overdue" is `dueDate` in the past and `status` still
`DUE`, computed per request. A stored overdue flag would need a job to stay true, and would
therefore eventually be false.

### Every status says how it was reached

`statusSource` is `MANUAL` or `AUTO_MATCHED`. Slice 7 only ever writes `MANUAL` — auto-matching
needs a connected account (slice 16). The field exists now so that a figure somebody asserted
never becomes indistinguishable from one the app inferred, which is the whole reason for it.

### Income is a plan too

An `IncomeSource` is money arriving on a schedule: a name, an amount, a schedule, and the account
it lands in. It carries a `sourceOfTruth` of `MANUAL_ESTIMATE` — `AUTO`, derived from history,
needs reporting (slice 13) and is not built.

Income does **not** project occurrences. Nobody ticks their salary off; they notice it arrived.
The page sums the active sources that fall in the period and uses that as the expected figure.

## Data model

`bills`, `bill_occurrences` and `income_sources` (`V14__bills_and_income.sql`).

- `bills`: `name`, `categoryId` (nullable), `expectedAmount`, `schedule` + its parameters,
  `amountVaries`, `tolerance`, `active`.
- `bill_occurrences`: `(billId, dueDate)` unique, `period` as a month label, `expectedAmount`,
  `status`, `actualAmount`, `paidDate`, `transactionId`, `statusSource`.
- `income_sources`: `name`, `amount`, `schedule` + parameters, `accountId` (nullable),
  `sourceOfTruth`, `active`.

Scoping is declarative throughout, as in slices 5 and 6: every foreign key is composite on
`(id, household_id)`.

A transaction attached to an occurrence is unique across occurrences — the same purchase cannot
settle two bills, for the reason ADR-0031 gives about envelope entries.

## API

| Method                              | Path                                   | Notes                                         |
| ----------------------------------- | -------------------------------------- | --------------------------------------------- |
| `GET`                               | `/api/bills?period=2026-08`            | Bills with this period's projected occurrence |
| `POST` · `PATCH` · `DELETE`         | `/api/bills[/{id}]`                    |                                               |
| `PUT`                               | `/api/bills/{id}/occurrences/{date}`   | Record a status. Idempotent; creates the row  |
| `DELETE`                            | `/api/bills/{id}/occurrences/{date}`   | Back to projected — forget what was recorded  |
| `GET` · `POST` · `PATCH` · `DELETE` | `/api/income[/{id}]`                   |                                               |
| `GET`                               | `/api/periods/{period}/summary`        | Income, bills, envelopes and what is left     |

`PUT` rather than `POST` for an occurrence: the URL is valid before anything exists at it, and
recording the same status twice is the same request twice. `{date}` is the occurrence's due date,
as `2026-08-15` — see ADR-0032 for why that, and not the month.

A date the bill's schedule does not imply is a `404`: the URL names an occurrence that does not and
cannot exist. The exception is an `IRREGULAR` bill, which projects nothing by definition — there,
recording against a date is what brings the occurrence into being.

A retired bill refuses new records (`bill-inactive`) and keeps the ones it has. It is retired, not
erased.

## UI

`/bills` — Bills & Income, with three blocks in the order the designs show them:

1. **What's left** — the one figure the page is for, with its three terms beneath it, and the
   outstanding bills figure as a secondary line.
2. **Income** — sources and the expected total.
3. **Bills** — each with its due date, status, and a one-tap **Mark paid**.
4. **Envelopes** — the block slice 6 deliberately did not build, because this page did not exist.

A bill that is overdue says so. A bill paid for other than its expected amount shows both figures.

## Security considerations

- Household-scoped throughout; an id from a path is a lookup key and another household's is `404`.
- A `VIEWER` reads everything and marks nothing paid.
- **No amounts in logs.** A bill's amount is somebody's rent.
- The `period` in a path is parsed, not interpolated: a malformed one is a `400`, never a query.

## Out of scope

- **`ChargeRule` and auto-matching** — needs a connected account (slice 16).
- **`AUTO` income from history** — needs reporting (slice 13).
- **Reminders and notifications.** Nothing in the core may require a service we operate
  (non-negotiable #9), so this is a later, opt-in decision.

## Known gaps

- Changing a bill's schedule re-projects **past** periods that nobody recorded anything against.
  ADR-0032 explains why that is the right trade; it is still a surprise worth knowing about.
- **A bill and an envelope can double-count the same plan.** Rent recorded as a bill *and* given
  an envelope is subtracted twice from what's left. Nothing stops it, because nothing can tell the
  two apart, and the alternative — matching bills to envelopes by name or category — would be
  guessing. The page shows the three terms, so the double is visible rather than hidden.
- An occurrence cannot yet be attached to a transaction by hand. The column exists and the
  uniqueness rule is enforced; the UI for choosing the transaction arrives with matching.

## Testing notes

- `BillIT` — projection across all five schedules, the clamped month end, overdue derivation,
  idempotent `PUT`, and that a projected period and an untouched one read identically.
- `BillProjectionTest` — the schedule arithmetic as a unit, including February and leap years.
- `IncomeIT`, `PeriodSummaryIT` — the figure the page leads with.
