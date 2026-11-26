# Feature: Bills and income

- **Slice:** 7
- **Status:** Shipped. **Amended by slice 10** — `left` lost a fourth term — and **by slice 11**,
  which took a fifth, the goals; see [What is left to spend](#what-is-left-to-spend)
- **Depends on:** categories (4), transactions (5), envelopes (6)
- **Related:** ADR-0025 (bills are occurrences), ADR-0032 (occurrences are projected), ADR-0006,
  `debt-plan.md` (slice 10) and `goals.md` (slice 11), which each changed this page's headline
  figure
- **Designs:** `design/canvas/` — the Bills & Income page, bills and income blocks with envelopes
  beneath them

## Purpose

Bills and income are the **predictable** half of a household's money: what arrives on a schedule,
and what leaves on a date. Together with envelopes — the unpredictable half, slice 6 — they answer
the only question the page exists for:

> **What is left to spend this month?**

That figure is income, minus the month's bills, minus what is set aside in envelopes, minus the
debt minimums that are not already a bill row, minus what the month's goals set aside. It is one
subtraction and the page leads with it.

**Slice 10 added the fourth term**, which changed a figure that had already shipped — the owner's
decision, recorded in `debt-plan.md`. See [What is left to spend](#what-is-left-to-spend) for why
it is the *not-already-a-bill* minimums and not all of them.

**Slice 11 added the fifth**, the goals (`goals.md`, decision 2), and changed the figure again. Each
goal counts the greater of what its plan asks of the month and what was recorded against it, so
setting money aside never makes the figure go up.

**Bills count whether or not they are paid.** Rent that has already gone is not money still
available, so subtracting only the unpaid ones would make "what's left" _rise_ every time somebody
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

### What is left to spend

```
left = income − bills − envelopesAllocated − debtMinimumsNotInBills − goalsSetAside
```

**The fifth term arrived with slice 11** (`goals.md`, decision 2):

```
goalsSetAside(P) = Σ max(planned(g, P), recorded(g, P))   over unarchived, base-currency goals
```

- **Planned £110 and recorded £50 still counts £110.** The month is not over and the other £60 is
  spoken for. This is the same reason bills count whether or not they are paid.
- **A £200 top-up above a £110 plan counts £200.** Money moved towards a goal is not free to spend.
- **A withdrawal never raises it.** `max(110, −500)` is 110. The spending a withdrawal pays for is
  not in this figure either, so raising `left` by it would report money as free that has gone.
- **A goal's starting amount is in no month**, because it was set aside before tracking began.
- **A goal that began the month already reached** counts only what was recorded, from the month
  after the one it was reached in.
- **Archived goals and goals in another currency count nothing**, the rules envelopes and `owed`
  already follow. Archiving a goal therefore makes `left` rise, and the goals page says so.
- **A household with no goals has a fifth term of zero**, so every figure from before slice 11 is
  unchanged for it. That is the property that made this a safe change to a shipped figure, as it was
  for slice 10.

**Slice 14 changed the fifth term again** (`weekly-check-in.md`, decision 6). A contribution a
completed weekly check-in records carries provenance `CHECK_IN` and counts **on top of** the plan:

```
goalsSetAside(P) = Σ ( max(planned(g, P), recordedOther(g, P)) + recordedCheckIn(g, P) )
```

With the slice 11 rule, a $63 surplus against a $110 plan with nothing yet recorded would still
count $110, so the surplus would not leave the figure until the rest of the plan was recorded.
**A household with no `CHECK_IN` contributions gets a fifth term unchanged to the cent**:
`GoalProjectionTest` proves it for every recorded figure, withdrawals included, and the whole
slice 11 suite passes unchanged. A `CHECK_IN` debt payment does not touch `left`; it comes off the
check-in's own `free(M)` instead.

**Bill rows show their mappings.** `GET /api/bills` gains `mappings` per bill (a charge rule's
`ruleId`, `payeeName`, `accountId`, `accountName`, `accountLastFour`), and the page shows each as
*"Mapped · COMCAST XFINITY · Chase •4412 · exact match"* with **Unmap** for an `OWNER` or
`MEMBER`.

The two halves read one object. `PeriodSummaryService` asks `GoalService.setAsideFor` once, and the
`goalsSetAside` it reports is the same `Money` it subtracts. `GoalService` computes each goal's
share with `GoalLedger.monthContribution`, which is also what `/goals` reads for "this month". So
the term on this page, the term taken off `left`, and the line on the goal's own page cannot be
three readings of two definitions. That is the `docs/memory/gotchas.md` lesson slice 10 paid for.

**The debt plan's extra is reported beside `left` and never subtracted** (`goals.md`, decision 3):
`debtPlanExtra` is `monthlyAmount − Σ minimums` for the plan's own debts in the base currency, or
`null` with no plan or one below its minimums. `debtPlanExtraExceedsLeft` says whether it is more
than `left`. The comparison is the server's, so the client compares no amounts. When it is, this
page and `/debt-plan` both say so in words, and nothing changes automatically.

The fourth term arrived with slice 10 (`debt-plan.md`, decision 2) and is the part of the debt
minimums **not already carried by a bill row**. A card's or a car loan's minimum is frequently also
a bill — *"the car payment is its bill row"* — and subtracting all of them would count that one
twice. The designs' own figures are minimums of 185, 342 and 118: 645 in all, of which only
185 + 118 = **303** is new, because the 342 is already one of the bill rows.

The link is **explicit, never inferred**: `debts.bill_id` names the bill, set by the household.
Matching a debt to a bill by name, amount or category would be guessing — the same refusal this
page already makes about matching bills to envelopes.

The term asks whether **this month's `bills` figure already carries that bill**, not merely whether
the link exists. The set is read off the same `BillsForPeriod` the `bills` term is summed from, so
the two cannot disagree. Three ways they used to: a **retired** bill projects no occurrences; a
**yearly** bill projects none in eleven months out of twelve; a **skipped** occurrence is excluded
from the total by name. In each case the debt's link survives, so treating it as covered would
count that minimum in neither term and this page's headline would rise by it.

So retiring, skipping, or simply not being due this month all move the car payment's 342 out of
`bills` and into `debtMinimumsNotInBills`, and `left` does not move. Deleting the bill does the
same thing by a different route — the link is nulled. And in June, when the yearly bill *is* due,
the 342 moves back the other way, and `left` still does not move.

Three consequences, all of them visible rather than hidden:

- **The figure fell when slice 10 shipped**, for any household with a debt whose minimum is not a
  bill. That is the point: the money was already committed and the page was not saying so.
- **Deleting a bill that a debt is linked to makes `left` fall further.** The link is nulled
  (`ON DELETE SET NULL (bill_id)`), so that minimum starts counting here instead. Correct and
  surprising, which is why the page says why.
- **`owed` is not part of the subtraction.** It is the total debt balance — a balance, not a
  monthly commitment — and taking it off would tell a household owing £26,000 that it has nothing
  to spend this month. It is reported for the **Owed** card and nothing else.

`owed` and `debtMinimums` cover the household's base currency only; a combined figure across
currencies would need a rate the instance may not have (ADR-0022). `debtCount` spans every
currency, because a count needs none.

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

| Method                              | Path                                 | Notes                                         |
| ----------------------------------- | ------------------------------------ | --------------------------------------------- |
| `GET`                               | `/api/bills?period=2026-08`          | Bills with this period's projected occurrence |
| `POST` · `PATCH` · `DELETE`         | `/api/bills[/{id}]`                  |                                               |
| `PUT`                               | `/api/bills/{id}/occurrences/{date}` | Record a status. Idempotent; creates the row  |
| `DELETE`                            | `/api/bills/{id}/occurrences/{date}` | Back to projected — forget what was recorded  |
| `GET` · `POST` · `PATCH` · `DELETE` | `/api/income[/{id}]`                 |                                               |
| `GET`                               | `/api/periods/{period}/summary`      | Income, bills, envelopes, debts and what is left |

`PUT` rather than `POST` for an occurrence: the URL is valid before anything exists at it, and
recording the same status twice is the same request twice. `{date}` is the occurrence's due date,
as `2026-08-15` — see ADR-0032 for why that, and not the month.

A date the bill's schedule does not imply is a `404`: the URL names an occurrence that does not and
cannot exist. The exception is an `IRREGULAR` bill, which projects nothing by definition — there,
recording against a date is what brings the occurrence into being.

A retired bill refuses new records (`bill-inactive`) and keeps the ones it has. It is retired, not
erased.

## UI

`/bills` — headed **Bills & income**, reached from **Bills** in the nav, with four blocks in the order the designs show them, under a month
picker (previous / next) that every block follows at once:

1. **What's left** — the one figure the page is for, with its three terms beneath it, and a
   secondary line reading "£266.00 of £1,200.00 still to pay" — a sentence rather than the two
   figures with a slash between them, which reads as a ratio. The card also says in so many words
   that bills count whether or not they are paid: the headline not moving when you tick one off
   is correct, and is the thing people misread. It comes from
   `GET /api/periods/{period}/summary` rather than from adding the other blocks up, so the
   headline cannot disagree with them. **It is rendered negative when it is negative** — never
   clamped — and a negative figure is coloured with `--color-accent-text`, because the olive
   accent reads as success.
2. **Income** — sources and the expected total. A source shows what arrives each time, the
   month's figure, and **how many arrivals the month has**: a fortnightly wage lands two or three
   times, and that is the month the household has rather than a figure averaged flat.
3. **Bills** — each bill with **one row per occurrence**, and each row its own due date, status
   and one-tap **Mark paid** (`PUT` to that row's date, no amount and no date in the body). A
   weekly bill is four or five rows; a bill with no occurrence this month is still listed and
   says why. **Mark paid is the only control in the open**, and only on a row that is actually
   `DUE`: a weekly bill is five rows a month, and five buttons on each buries the one anybody
   taps — and on a skipped row it contradicted the tag beside it. The corrections live behind a
   **Correct this** disclosure: **Paid a different amount…**, **Skip**, **Mark as due**,
   **Forget** (only on a `recorded` occurrence, with a confirmation that says it is not the same
   as marking it due), and **Mark paid** when the row is skipped. An `IRREGULAR` bill gets
   **Record a payment**, which chooses the date, because recording against one is what brings its
   occurrence into being — and its amount field opens empty with a hint that says so, rather than
   promising a budgeted figure it has not got.
4. **Envelopes** — a summary block using `envelopesAllocated`, linking to `/envelopes`. The
   block slice 6 deliberately did not build, because this page did not exist; it is a link and a
   figure, not a second envelopes screen.
5. **Owed** — the fourth block, added by slice 10 and linking to `/debt-plan`: `owed`, `debtCount`,
   and the counted-once sentence in the household's own language (*"Minimums · each counted once
   here (the car payment is its bill row) — £645"*). The **What's left** card shows
   `debtMinimumsNotInBills` as its fourth term beside the other three, so the subtraction is
   visible rather than mysterious. The card and the headline both come from
   `GET /api/periods/{period}/summary`, which composes them from the debt service — so the page
   cannot show an Owed card its own headline disagrees with.
6. **Goals** — slice 11 adds no block, only the fifth term. The **What's left** card shows
   `goalsSetAside` as **Goals** beside the other four, with a one-line note on how a goal counts.
   When `debtPlanExtraExceedsLeft` is set, the card says so in a sentence. The goals themselves
   are on `/goals`.

A bill that is overdue says so. A bill paid for other than its expected amount shows both figures,
labelled, and is tagged as not what was budgeted — and **one** figure when they agree, because the
difference is the useful number and there is no difference to report. A skipped row keeps its
figure struck through and labelled "not counted this month", so it is visible and visibly not part
of what the month costs.

A recorded occurrence says **how** it was recorded — "You recorded this" — so a figure somebody
asserted is never shown as one the app inferred. It does **not** say **which** person: no user is
stored against an occurrence. See Known gaps.

The bill and income dialogs share one schedule editor: a kind, and only the parameters that kind
uses. It takes its heading from the caller — "When it is due" for a bill, **"When it arrives"** for
income, because money coming in is not due. **Changing the kind clears the parameters that stopped applying**, and the request is built
from the same table the editor reads, because sending a parameter a kind has no use for is a
`400`. `amountVaries` and `tolerance` move together — the tolerance field appears only when the
box is ticked, and a patch sends both or neither.

A `VIEWER` sees every figure, both totals, every occurrence row, and no write control at all.
Retiring a bill or a source is `PATCH active: false` and is offered as **Retire** with
**Restore** beside the retired list; deleting is separate, destructive, and says what it keeps.

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
- **A bill and an envelope can double-count the same plan.** Rent recorded as a bill _and_ given
  an envelope is subtracted twice from what's left. Nothing stops it, because nothing can tell the
  two apart, and the alternative — matching bills to envelopes by name or category — would be
  guessing. The page shows the three terms, so the double is visible rather than hidden.
- **An occurrence does not record who settled it.** `statusSource` says a person did it rather
  than a rule, which is what it is for, but not which person — so a household of two cannot tell
  who ticked a bill off. The column does not exist yet; nothing is lost by adding it later.
- An occurrence cannot yet be attached to a transaction by hand. The column exists and the
  uniqueness rule is enforced; the UI for choosing the transaction arrives with matching.

## Testing notes

- `BillIT` — projection across all five schedules, the clamped month end, overdue derivation,
  idempotent `PUT`, and that a projected period and an untouched one read identically.
- `BillProjectionTest` — the schedule arithmetic as a unit, including February and leap years.
- `IncomeIT`, `PeriodSummaryIT` — the figure the page leads with.
- `DebtPeriodSummaryIT` (slice 10) — the fourth term: the counted-once arithmetic on the designs'
  own 185 / 342 / 118, that `left` subtracts 303 and **not** 645, and that deleting the linked bill
  makes it 645. `PeriodSummaryIT`'s own cases stay green because a household with no debts has a
  fourth term of zero, which is the property that made this a safe change to a shipped figure.
- `GoalPeriodSummaryIT` (slice 11) — the fifth term, asserted as numbers: a plan of 110 with 0, 50
  and 200 recorded gives 110, 110 and 200; a withdrawal never raises it; a month that began reached
  counts only what was recorded; archived, foreign-currency and starting amounts count nothing; a
  plan step set for next month leaves this month unmoved; and `debtPlanExtra` is reported and not
  subtracted. `PeriodSummaryIT` and `DebtPeriodSummaryIT` stay green unchanged.
- `bills-data.spec.ts` — the request builders: a schedule sends only its own parameters, a kind
  change clears and re-requires them, `amountVaries` and `tolerance` travel together, an amount
  stays a string, and recording a status is a `PUT` to a date with no version.
- `bills.page.spec.ts` — what a person sees: `left` rendered negative and styled as bad, a weekly
  bill as four independently markable rows, Mark paid reaching the tapped date and no other,
  overdue and surprising, Forget offered only on a recorded occurrence, and a `VIEWER` with every
  figure and no button.
