# Feature: Weekly check-in

- **Slice:** 14
- **Status:** Planned — slice 14. Nothing is built. **All nine decisions are settled** (owner,
  2026-11-26): every recommendation below is taken. See
  [Open questions: decisions needed](#open-questions-decisions-needed).
- **Owner:** Repository owner
- **Last updated:** 2026-11-26
- **Depends on:** transactions (5) and categories (4) for step 2. Bills & income (7) for steps 1
  and 3. Envelopes (6), the debt plan (10) and goals (11) for step 4, because next week's figure is
  built from the same `left` they feed. File import (12) is not required, but it is what makes
  steps 2 and 3 worth having on an instance with nothing connected.
- **Related:** ADR-0006 (money), ADR-0008 as amended by ADR-0017 (scoping), ADR-0016 (nothing
  leaves the box), ADR-0022 and ADR-0039 (per currency), ADR-0023 (i18n), ADR-0025 (bills are
  occurrences), ADR-0026 (one household), ADR-0031 (labels, no rollover), ADR-0032 (occurrences
  are projected), ADR-0035 (a debt payment is a plan record), `bills-and-income.md`,
  `goals.md`, `debt-plan.md`, `transactions.md`, `categories.md`, `envelopes.md`,
  `file-import.md`, `reporting.md`, `../domain/model.md` (`CheckIn`)
- **Designs:** `design/canvas/Budget Owl.dc.html`, around lines 1205–1690 of the current export.
  Six artboards, described in words under [UI](#ui) so nobody needs to open the canvas:
  - **Step map, "Weekly check-in · 4 steps"** (*"Sunday · about 4 minutes"*). For each step, what
    it does, what it **writes**, and when it is **skipped**. This is the most authoritative
    statement of the feature and the table under [The four steps](#the-four-steps) follows it.
  - **"Web — weekly check-in as one page"**, desktop, light: all four steps as sections on one
    page, at `/check-in`.
  - **"Check-in step 1 · what's new"**, **"step 2 · categorize"**, **"step 3 · bills"**,
    **"step 4 · next week"**: mobile, 390 pt, light.
  - The **home card** (*"Check-in · Due Sunday. 3 charges waiting on a category. · Start now"*) on
    the web home, light and dark.

  **There is no dark check-in artboard.** The dark-mode section says it is *"shown on three
  screens; the rest follow the same swaps"*, so dark mode here is the role tokens' dark values and
  nothing drawn.

## Purpose

Once a week, usually on Sunday, a household spends a few minutes making the budget true again:
did the pay arrive, what were those three charges, which bills have gone out, and what should
happen to the money left over. The check-in asks only the questions that week actually raised,
in four short steps, and ends with one number: **what you can spend next week**.

It invents nothing new to track. Every answer becomes an ordinary record somewhere else: a
transaction's category, a bill marked paid, a goal contribution. The check-in is a fast,
ordered way to make decisions the rest of the app already knows how to store. A quiet week
should take about ninety seconds (the step 1 artboard says so).

Nothing it decides takes effect until the household finishes it. Until then it is a draft that
any member can pick up on any device.

## User stories

- As a member, I want to be told only what needs my attention this week, not walk four screens
  of "nothing to do".
- As a member, I want to give this week's uncategorised charges a category with the week's
  spending beside them, and have the app remember the payees I always file the same way.
- As a member, I want to see which bills went out and which are still to come this month, and
  link a repeating charge to the bill it pays so I am not asked again.
- As a member, I want to know whether we came in under or over this week, decide where any
  surplus goes (a debt, a goal, or next week), and see what that leaves us to spend.
- As a member, I want to start the check-in on my phone and finish it on the laptop, or let my
  partner finish it, without losing what I answered.
- As a member, I want to be sure that nothing changes in our budget until one of us presses
  Finish, and that it either all happens or none of it does.
- As a `VIEWER`, I want to see the check-in and its outcome, and change nothing.

## Prerequisites

Everything it reads has shipped: transactions, categories, envelopes, bills and income, the debt
plan, goals, file import and reporting's spending definitions. It needs **no** bank connection,
no Owl and no background job. A household that types every transaction by hand gets the same
check-in as one that imports a statement every Sunday morning. It just has fewer uncategorised
rows.

## Rules and behaviour

Where a rule depends on an open decision, it names it, and it is written as the recommendation.
If the owner decides otherwise, that rule changes with it. Calls made without asking are marked
*Assumed* and can be overruled cheaply.

### What a check-in is

- A check-in belongs to a household and an **ISO week**, Monday to Sunday, written `2026-W34`.
  The canvas's *"week 34 · Aug 17–23"* is exactly ISO week 34 of 2026, which runs from Monday
  17 August to Sunday 23 August. *Assumed:* every household uses ISO weeks. A configurable week
  start is out of scope, as the month start is (`reporting.md`).
- **At most one check-in per household per week** (`UNIQUE (household_id, week_start)`), and
  **at most one draft per household at a time** (decision 8).
- It is **due on the week's Sunday**, which is what the home card's *"Due Sunday"* means. It can
  be finished before then. The desktop artboard is mid-check-in on Friday 21 August (*"Car
  payment Aug 24 · In 3 days"*). A check-in finished early compares the spending recorded
  **so far** and says "so far".
- Status is `DRAFT` or `COMPLETED`. A week nobody has started reads as `NOT_STARTED`. That state
  is computed on read and never stored, just as a projected bill occurrence is computed
  (ADR-0032).
- **Every figure is in the household's base currency.** Accounts, goals and debts in another
  currency are left out of steps 2 to 4, and the step says how many were left out. That is the
  `owed` / `goalsSetAside` rule (ADR-0022, ADR-0039).

### Which weeks, and what a check-in looks at (decision 8)

- A check-in can be **started only for the current ISO week or the one before**, by the
  application `Clock` (`Clock.systemUTC()`, as in reporting), with one day's slack each way. The
  slack means a household east or west of UTC is not refused at the edges of Monday. Anything
  else is `422 check-in-week-out-of-range`. Finishing on Monday counts as checking in for the
  previous week, which is common.
- The check-in's **window** is the span of dates its steps 1 to 3 look at. It runs from the day
  after the household's most recent completed check-in's Sunday to this week's Sunday, and it is
  **never longer than 28 days** (this week plus the three before). It is fixed when the draft is
  started and stored as `window_start`. A household that skipped a week therefore gets asked
  about that week's charges too. One that skipped two months is asked about the last four
  weeks, and older rows stay where they already are: in the ledger's "Needs a category" queue,
  and on Bills & Income.
- Step 4 compares **this week only** against a target. The target exists only when the previous
  week's check-in was completed. Otherwise nobody set one, and there is no surplus to allocate.

### The four steps

From the step map. Each step *"earns its place by writing something"*, and *"steps with nothing to
answer are skipped, so a quiet week is far shorter than the count suggests"*.

| # | Step | Reads | Asks | Writes, at completion | Skipped when |
|---|---|---|---|---|---|
| 1 | **What's new** | Income sources expected in the window, and the positive rows that landed. `DUE` occurrences due in the window or earlier. Outflows in the window, measured against the payee's history | For an overdue bill: paid, skip, or leave it. For an unusual charge on a mapped bill: prepaid, or leave it | An occurrence's status. *Prepaid* sets this occurrence to `PAID` and skips the next *k − 1* | No overdue occurrence, no unusual charge, and every expected income arrival has a row |
| 2 | **Categorize** | Uncategorised, non-transfer rows in the window. Payee rules | A category for each row, and optionally *"always file {payee} here"* | The transaction's category. A payee rule | Every row is categorised, or placed by a payee rule |
| 3 | **Bills** | The month's bills, through the same `BillsForPeriod` set Bills & Income reads. Charge rules. Recurring charges with no rule | *"Is this your Internet bill?"* Attach this charge to that occurrence, and optionally remember the mapping | The occurrence `PAID` with the transaction attached. A charge rule. A debt payment, if the bill is a debt's bill row and the person accepts the prompt (`debt-plan.md` decision 3) | Every charge in the window matched a charge rule within tolerance, and there is no recurring suggestion |
| 4 | **Next week** | The week's unbudgeted spending, last week's target, `left`, the debt plan, goals | Where the surplus goes: a debt, a goal, or next week | A debt payment or a goal contribution, each with provenance `CHECK_IN`, or a rollover. Next week's target | **Never** |

A skipped step is **not shown**, at either viewport. The canvas's "1 of 4" counts become counts of
the steps shown (*"2 of 3 answered"*).

**Informational lines need no answer.** *"Paycheck landed ✓ … Nothing to do"* is shown and never
asked about. The bills table in step 3 (what cleared, what is still due) is the same. A step
counts as **answered** when every question in it has an answer, and *"Leave it"* is an answer.

### Step 1: What's new

From the step map: *"Confirms your paycheck landed and surfaces anything unusual — a charge twice
its normal size, a bill that never appeared. Writes: income confirmed. Skips if nothing odd."*

**Income (decision 7).** For each active base-currency `IncomeSource` whose schedule falls in the
window, the step looks for a positive, non-transfer row in the window. The row must be in the
source's account when the source names one, and in any base-currency account when it does not.
The candidate is the row whose amount is nearest the source's, with ties going to the earlier
date. The step shows *"Paycheck landed · Northwind Systems · Aug 20 · Chase •4412 · $2,620"* with
the comparison against the plan: *"as expected"* or *"$40 less than expected"*. When no candidate
exists it shows *"Expected Aug 20 — not seen yet"*.

Both are **information, not questions, and they write nothing outside the check-in.**
`bills-and-income.md` is explicit that income projects no occurrences (*"Nobody ticks their
salary off; they notice it arrived"*), so "income confirmed" has nowhere to be stored. The
pairing of a source with a row is a display heuristic, and the canvas permits that. The app may
suggest, but it never files a row on its own.

**A bill that never appeared.** An occurrence still `DUE` whose due date is on or before the
window's last day is overdue, derived exactly as Bills & Income derives it. The question offers
**Mark paid** (actual amount defaults to expected, as on Bills & Income), **Skip this month**,
and **Leave it**. When the bill is a debt's bill row, *Mark paid* carries the debt plan's
one-time prompt (*"Also record this against Car loan?"*, `debt-plan.md` decision 3). The prompt
is part of the answer and is never assumed.

**A charge twice its normal size.** An outflow in the window is unusual when:

- its payee has a **charge rule** (step 3) and the amount is at least twice the bill's expected
  amount, or otherwise
- its payee has **at least three earlier outflows in the same account in the 12 months before the
  window**, and the amount is at least **twice their median**.

*Assumed:* the ×2 threshold and the three-row minimum. The canvas gives "twice its normal size"
and no more. Fewer than three earlier rows is not a history.

**Prepaid** is offered only for a mapped bill (one with a charge rule), and only when the amount
is within the bill's tolerance of a whole multiple *k* ≥ 2 of the expected amount. The canvas's
*"Mountain Strength Co · Aug 18 · usually $42 · $126 … Three months at once … treat it as prepaid
and skip the next two?"* is *k* = 3. On completion it attaches the charge to this occurrence as
`PAID` with `actualAmount` = the charge, then records the next *k − 1* projected occurrences as
`SKIPPED`. *k* ≤ 12, so at most 11 skips, and an `IRREGULAR` bill is never offered prepaid
because it projects nothing to skip. When the payee has no charge rule, the only answer is
**Leave it**: the app cannot tell which bill the charge is without guessing.

### Step 2: Categorize (decision 3)

From the step map: *"The only real work: charges Owl could not place. The week's spending sits
alongside so you decide with context. Writes: category, payee rule."*

- The queue is the window's **uncategorised, non-transfer rows**. That is the ledger's "Needs a
  category" predicate (`t.categoryId is null and t.transferGroupId is null`) bounded by date.
  Deposits are included, since an uncategorised salary is the same problem.
- At most **200 rows** are shown, newest first, which is the API's page maximum. When there are
  more, the step says *"and 312 more in the ledger"* and links to the filtered ledger. A
  four-minute pass is not the tool for a ten-thousand-row first import.
- Each row takes a category from the usual picker (archived categories are not offered), or
  **Leave it**.
- **"Always file {payee} here"** is a checkbox on each row that has a payee. It is **off by
  default**. On completion, a ticked row writes a **payee rule**: one per payee, `payee → category`,
  replacing any existing rule for that payee. The row says so before you finish (*"Replaces your
  rule filing it under Shopping"*).
- **A payee rule only pre-answers.** In a later check-in, an uncategorised row whose payee has a
  rule arrives already answered, marked *"your rule"*, and the person can change it. It is
  applied when that check-in is completed. A row placed by a rule is not a question, so a week
  where every row is placed skips step 2. The completion summary still lists *"3 rows filed by
  your rules"*.
- **Rules do not act anywhere else.** Import still never sets a category (`file-import.md`
  rule 8, decision 8), and neither does recording a transaction by hand. A rule whose category
  has been archived offers nothing until the category is restored.
- Rules are listed, and removable, from the categories page. See [UI](#ui).

*"Owl could not place"* is canvas copy, and the assistant is not involved. Placement is a lookup
table, as `../product/vision.md` says it should be (*"transaction categorisation, for one — uses a
lookup table"*). See [Where the designs and the docs disagree](#where-the-designs-and-the-docs-disagree).

### Step 3: Bills (decision 4)

From the step map: *"What cleared, what is still due this month, and any repeating charge that
should map to a bill you have named. Writes: bill ↔ charge mapping. Skips if all mapped."*

**The table** lists the occurrences of the month that contains the week's Sunday, plus any
occurrence from an earlier month that is due inside the window. It is read from `BillsForPeriod`,
so it cannot disagree with Bills & Income: bill, due date, status, amount. Statuses read *Paid*
or *In 3 days*. Below it is the outstanding figure Bills & Income already reports
(*"Still to leave this month · $706"*). The table is information.

**A charge rule** maps a `(payee, account)` pair to one bill. Where `../domain/model.md` describes
`ChargeRule`, this doc drops two of its fields:

- `standing` is dropped. Under this design every rule is standing, and a column that is always
  one value is refused (ADR-0031).
- `matchKind` is dropped. It is derived from the bill's own `amountVaries` and `tolerance`, which
  is what the canvas's Bills & Income rows already print (*"exact match"*, *"amount varies
  ±$4"*).

**What step 3 asks**, per outflow in the window that is not already attached to anything:

1. **A rule match within tolerance.** The payee and account have a rule, and the amount equals
   the bill's expected amount, or falls within tolerance when the bill varies. This is
   **pre-answered**. The row attaches to that bill's unattached `DUE` occurrence nearest the
   transaction's date in the period. An `IRREGULAR` bill records against the transaction's date,
   which brings that occurrence into being (`bills-and-income.md`). It is not a question.
2. **A rule match outside tolerance.** This is asked: *"COMCAST XFINITY charged $89.99 — your
   Internet bill is $71.99. Attach it?"*, with **Attach** and **Leave it**.
3. **A recurring charge with no rule.** The canvas's *"A repeating $71.99 from COMCAST XFINITY
   has hit Chase •4412 three months running. Map it to your Internet bill? · Map it · Different
   bill"*. "Recurring" means one outflow from the same payee and account in each of the three
   calendar months ending with the month of the window's end, each within 10% of the latest,
   with the latest falling inside the window. *Assumed:* the three months and the 10%. The
   suggested bill is preselected **only when exactly one** active, unmapped base-currency bill
   has an expected amount matching the latest charge within its tolerance. Otherwise the
   question opens on a bill picker. **Map it** attaches the charge and creates the rule, and
   **Different bill** opens the picker. **Not a bill** is the third answer and writes nothing.

On completion, an attach records the occurrence `PAID`, with `actualAmount` = |amount|, `paidDate`
= the transaction's date, and `transactionId` set, through the same service the occurrence `PUT`
uses. `statusSource` is **`AUTO_MATCHED` for a pre-answered rule match** and **`MANUAL` for one
the person answered**. That uses the value bills reserved for exactly this
(`bills-and-income.md`), so a status the app inferred from a rule never reads as one a person
asserted.

A bill that is a debt's bill row carries the debt plan's one-time prompt on attach, as in step 1.

**Recurring detection runs on read**, inside the request, over a bounded window. There is no
`recurring.detect` job. The admin Jobs artboard draws one, and this slice does not add it, for
the reason ADR-0032 gives: no scheduler means nothing falls behind while an instance is
switched off.

**Matching at import or sync is not built.** A rule acts only through a check-in someone
completes. Automatic matching for a connected account is still slice 16's, and that is what
`AUTO_MATCHED` was originally reserved for.

### Step 4: Next week (decisions 1, 2, 5, 6)

From the step map: *"Where the surplus goes — debt, a goal, or roll it over — and what that makes
next week's safe-to-spend. Writes: allocation, weekly target. Never skipped."*

The canvas figures reconcile, and they are a good worked example. The week's target was $550,
$487 was spent, so the week came in $63 under. Next week's figure is $480, and rolling the
surplus over makes it $543. The emergency fund goes from $2,340 to $2,403.

#### The week's spending (decision 2)

`weekSpending` is the week's **unbudgeted** spending: money that left for things the month's
plan had not already set aside.

```
unbudgeted(from, to) = −Σ t.amount   over transactions t where
    from ≤ t.date ≤ to
    and t.transfer_group_id IS NULL                       -- transfers are never spending
    and t's account is in the household's base currency
    and ( t's category has kind EXPENSE                   -- net of refunds, as reporting nets them
          or (t is uncategorised and t.amount < 0) )
    and t is attached to no bill occurrence, debt payment, goal contribution or envelope entry
    and t's category, and that category's parent, is not the category of
          an unarchived envelope for t's month, or
          an active bill with an unskipped occurrence in t's month
```

- It is computed **with the draft's answers applied**: a row answered "Dining" in step 2 counts
  as Dining, and a row attached in step 1 or 3 drops out. That is why the desktop page *"watches
  the total at the bottom settle as you go"*.
- **Refunds net**, and a week whose refunds exceed its spending reads negative. Nothing is
  clamped, following reporting.
- The category exclusions are the point. Rent paid by direct debit is already in `left` as a bill,
  and groceries are already there as the envelope's amount. Counting either against the week
  as well would count them twice, and every week containing the 1st would read as $1,650 over.

`weekTarget` is the previous week's completed check-in's `next_week_target`, or `null`.
`surplus = weekTarget − weekSpending` when there is a target. It is positive when the week came
in under.

#### Free money and next week's figure (decision 1)

Next week's figure comes from the plan the household already has: the shipped **What's left**
figure from Bills & Income.

```
free(M)  = left(M)                                   -- GET /api/periods/{M}/summary, all five terms
         − unbudgeted(first day of M, min(week's Sunday, last day of M))
         − Σ debt payments with provenance CHECK_IN dated in M

daily(d) = (free(M) − held) ÷ (days from next Monday, or the 1st, to the end of M)   for M = month(d)
                                                       when M has started by next Monday
         = left(M) ÷ days in M                                for a month that has not

base     = Σ daily(d) over the seven days d of next week
held     = surplus when surplus > 0 and free(M) ≥ surplus, else 0
```

- **`held`** sets the surplus aside, so all three choices start from the same `base`. Debt and
  goal take it out of the month. *Roll over* gives the whole of it to next week:
  `next_week_target = base + surplus`. That is the canvas's $480 and $543.
- **A week that crosses a month end** takes each day from its own month. *"Next week"* from
  Monday 31 August is one August day plus six September days, and September's days come from
  September's `left`, with nothing spent yet.
- **An overspent week** has nothing to allocate. `free(M)` is already lower by the overspend, so
  next week's figure falls by construction. There is no separate penalty and no carried
  negative. That answers, for weekly targets, the overspend question ADR-0031 raised about
  envelope rollover.
- **A negative figure is shown negative**, never clamped (the `left` precedent).
- **Per day** is `base ÷ 7` (or the rolled figure ÷ 7), rounded **down** to the currency's minor
  unit and labelled *"≈"*. The canvas's *"$480 · ≈ $68/day"*.
- *"After the car payment on the 24th and the Visa on the 28th"* names the `DUE` occurrences
  that fall due next week. It is an explanation only, because `left` already subtracts every
  bill of the month whatever its date.
- `left` is read through `PeriodSummaryService`, and the step runs no query of its own for it,
  the rule `bills-and-income.md` holds for the five terms. One figure, one definition (the
  `../memory/gotchas.md` lesson from slice 10).

#### Where the surplus goes (decisions 5 and 6)

Offered only when `surplus > 0`. The person picks one of three, and **nothing is preselected**.
*Assumed:* the canvas draws a tick on *Visa payoff*, but preselecting would make the step's only
decision for the person. The whole surplus goes to one place. Splitting it is out of scope.

| Choice | Offered when | Shows | Writes, at completion |
|---|---|---|---|
| **A debt** | The plan's rung-1 debt (`debt-plan.md`, Strategies) in the base currency and not paid off. With no plan, a picker of active base-currency debts. Only when `free(M) ≥ surplus` | The payoff month now and with the payment, in **months**: *"Moves Visa •8823 from Apr 2027 to Mar 2027"*, or *"Doesn't move the month"* | A `DebtPayment` of `surplus`, dated the completion day, provenance `CHECK_IN`, through the same logging path as `POST /api/debts/{id}/payments` (interest split, balance after). That path charges a debt's interest **once per calendar month** (ADR-0040), so a surplus payment after the month's payment is all principal. Its preview passes the completion day as `paidDate`. **No `CHECK_IN` special case is needed, and none should be written** |
| **A goal** | Unarchived base-currency goals that are not yet reached, in `/goals` order. Only when `free(M) ≥ surplus` | *"Emergency fund · $2,340 → $2,403 of $6,000"* | A goal contribution of `surplus`, dated the completion day, provenance `CHECK_IN`, through the `POST /api/goals/{id}/contributions` path |
| **Roll into next week** | Always | *"Weekly target becomes $543"* | Nothing outside the check-in. `rolled_over = surplus` and `next_week_target = base + surplus` on the check-in row |

- **The figures for each choice come from the server**, computed by running the real write path
  with a hypothetical record and discarding it. This is the debt payment preview's and the goal
  preview's pattern, so a preview cannot disagree with what completing records. The browser does
  no money arithmetic.
- **When `free(M) < surplus`**, the month is already short even though the week came in under,
  and the step says so. Only *Roll into next week* is offered.
- **The debt and goal rows say plainly that nothing moves.** *"Records a $63 payment to Visa
  •8823 today. Make the payment at your bank — Budget Owl never moves money."* A payment or
  contribution is a plan record (ADR-0035, `goals.md` decision 1), and so is this.
- **Decision 6: a goal contribution with provenance `CHECK_IN` counts on top of the month's
  plan.** The fifth term becomes
  `Σ ( max(planned(g, P), recordedOther(g, P)) + recordedCheckIn(g, P) )`. With the shipped rule,
  `$63` against a `$110` plan with nothing yet recorded still counts `$110`, so the surplus would
  not leave the figure until the rest of the plan was recorded. A household with no `CHECK_IN`
  contributions gets a fifth term unchanged to the cent. That is the property slices 10 and 11
  relied on when they changed a shipped figure.
- **There is no separate term for a debt allocation in `left`.** A hand-logged debt payment does
  not touch `left` today, and this slice does not change that. The `CHECK_IN` debt payments are
  taken off `free(M)` above instead, which is the only figure they affect.

**The streak line.** The canvas shows *"Four weeks under target in a row — that's what moved the
debt-free date from July to March."* The count is consecutive completed check-ins in adjacent
weeks with `surplus ≥ 0`. The date half compares this check-in's stored `debt_free_month` with
the one stored on the first check-in of the streak. It is shown only when both exist and
differ. It is a sentence about the household's own records and makes no statistical claim.

### The draft

The canvas's *"2 of 4 answered"* beside *"Nothing is saved until you finish at the bottom"* is the
reason a check-in is persisted (`../domain/model.md`): answers are kept, and their effects wait.

- **Answers are saved as they are given**, one request per answer, to the server. The draft
  lives in the database and **nowhere in the browser**. Resuming on another device is reading
  `GET /api/check-ins/{week}`. Nothing financial goes into `localStorage` or `sessionStorage`.
- **Items are derived, answers are stored.** The questions are recomputed from the ledger on every
  read. The draft stores only answers, each keyed by the item it answers (`itemKey`, e.g.
  `cat.<transactionId>`). An answer stores the **version** of the row it was given against
  (`subject_version`): the transaction's version for steps 1 and 2, and the occurrence row's
  version for step 3 (`null` when the occurrence was only projected).
- **Rule pre-answers are derived too**, never written into the draft by a read. `GET` has no side
  effects (`../guides/api-style.md`).
- **Two members editing one draft.** Each answer carries its own `version`. Two people answering
  *different* rows both succeed. Two answering the *same* row are an ordinary
  `409 stale-version` for the second, whose screen re-reads and shows the other's answer and
  who gave it (`answered_by`). Every answer write and delete also bumps the **draft's**
  `version`, which completing requires. That guarantees whoever presses Finish has seen every
  answer.
- **The basis.** Every read returns a `basis`: a SHA-256 digest of the derived items, the rule
  pre-answers and the step 4 figures, all as the server computed them. Completing sends back the
  `basis` the person last saw. If the ledger, a rule, a bill or a goal has changed since, the
  digest differs and completion is refused (see below). It is **not** an authorization token
  and protects against nothing an attacker does. A client can always just re-read. It exists so
  that a draft cannot be completed against a picture of the week that is no longer true.
- **Discarding** a draft deletes it and its answers and writes nothing else. A `COMPLETED`
  check-in cannot be discarded.
- **A draft for a week that has left the range** (decision 8) can still be read and discarded,
  but not completed (`422 check-in-week-out-of-range`). Starting the next week while it exists
  is `409 check-in-draft-open`, carrying the old week, and the screen offers to discard it.

### When the data changes underneath a draft (decision 9)

Between drafting and finishing, another member may edit the ledger, someone may import a
statement, a bill may be retired. **Completion re-derives everything and refuses the whole
check-in if anything the person answered, or saw, has changed.** It writes nothing in that case.

| What happened | What the draft sees |
|---|---|
| An answered transaction was **deleted** | The answer goes with it (`ON DELETE CASCADE`). The item is gone, so `basis` differs and the re-read shows one fewer row |
| An answered transaction was **edited** (categorised in the ledger, amount or date changed) | `subject_version` differs. Refused with reason `subject-changed` for that item |
| The chosen **category** was archived or deleted | Archived: `target-archived`. Deleted: the answer cascades and the row is a question again |
| An answered **occurrence** was recorded on Bills & Income in the meantime | `already-recorded` |
| The **bill** was retired, or its schedule no longer projects that date | `bill-inactive`, or `subject-gone` |
| The attached transaction is now **attached elsewhere** (an envelope entry, a debt payment) | `transaction-attached-elsewhere` |
| The **goal** was archived, or the **debt** paid off, or either deleted | `goal-archived` / `debt-paid-off`. A deleted one cascades and step 4 is unanswered again |
| New rows were imported into the window, or a rule was created or removed | `basis-changed` |
| The surplus is no longer the amount allocated | `surplus-changed` |

The refusal is `409 check-in-stale`. Its `params.items` lists each affected `itemKey` with its
reason. The screen re-reads, marks those items, and asks again. *"Sam filed this under Shopping
in the ledger after you answered Dining — keep Shopping, or use Dining?"*

### Completing a check-in

`POST /api/check-ins/{week}/complete` with `{version, basis}`. In **one database transaction**:

1. Check the caller's role (`OWNER` / `MEMBER`), **before** anything is looked up.
2. Lock the check-in row (`SELECT … FOR UPDATE`). Refuse if it is not `DRAFT`
   (`409 check-in-completed`), if the version is not the one sent (`409 stale-version`), or if
   the week is out of range (`422`).
3. Re-derive every item, pre-answer and figure, and compare the digest with `basis`.
4. Re-check every answer: is its subject still there, at the same version, still in the household,
   and is its target still writable? Collect every failure, not only the first.
5. Refuse with `409 check-in-stale` and the full list if steps 3 or 4 found anything. Refuse with
   `422 check-in-incomplete` if a surplus is offered and step 4 is unanswered.
6. Apply, **through the owning services** (never by writing their tables directly), in this order:
   step 1 occurrence statuses, step 2 categories then payee rules, step 3 attaches then charge
   rules then any accepted debt-plan prompts, step 4's payment or contribution.
7. Write the snapshot onto the check-in row: `currency`, `week_target`, `week_spending`,
   `rolled_over`, `next_week_target` and `debt_free_month`, plus `status = COMPLETED`,
   `completed_at` and `completed_by`.

Any refusal from an owning service rolls the whole transaction back and is reported as
`409 check-in-stale` for that item. A goal's contribution cap and a debt's payment cap are among
these refusals, with reason `limit-reached`. A deadlock against a concurrent ledger edit, which
PostgreSQL detects and aborts, is reported the same way. A re-read and a retry then succeed.

**Unanswered questions in steps 1 to 3 are left exactly as they are.** A row with no answer
stays uncategorised, and an overdue bill with no answer stays `DUE`. Finishing with nothing
answered is allowed: a quiet week is one tap. The summary says what was left (*"1 charge left
uncategorised"*).

**A completed check-in is read-only.** There is no reopen and no undo. Each record it wrote lives
where it always lives and is corrected there: a category in the ledger, a payment in the debt's
history, a contribution in the goal's. The summary links to each one.

### What a check-in never does

- **It never moves money**, and never contacts a bank, a provider or anything off the instance
  (ADR-0016, `../product/vision.md`).
- **It never creates, deletes or splits a transaction**, and never changes a transaction's
  amount, date, account, payee, note or status. The only change it makes to a transaction is the
  category, and only for a row the person answered or a payee rule placed. It never creates or
  splits a transfer.
- **It never files a transaction into an envelope.** That stays the person's act
  (`envelopes.md`).
- **It never changes a plan.** That covers the debt plan's monthly amount and strategy, a goal's
  monthly contribution, an envelope's amount, and a bill's expected amount or schedule. A one-off
  surplus is a one-off record.
- **It never writes anything before completion**, and never writes part of a completion.
- **It never writes a record the person was not shown.** The `basis` and `subject_version`
  checks are what make that true when the data moves.

### Writes and roles

- **`OWNER` and `MEMBER`** may start, answer, discard and complete a check-in, and remove payee
  and charge rules.
- **`VIEWER`** reads every check-in, draft or completed, with every item, answer and figure, and
  writes nothing. The role is checked **before** the week or item is looked up, so a `VIEWER`
  gets `403` rather than a `404` that would say what exists (the accounts and goals rule).
- **Any member may finish a draft another member started.** Completion applies every answer in
  the draft on the authority of the person who presses Finish. The draft version check means that
  person has seen all of them.
- **The role is re-checked on every request.** A member demoted to `VIEWER` mid-draft cannot
  answer or finish. Their answers stay in the draft and say who gave them.
- **Who did what is recorded here**, unlike bills, transfers and debt payments
  (`bills-and-income.md`, Known gaps): `started_by`, `completed_by`, and `answered_by` on each
  answer, all nullable `users` references, `ON DELETE SET NULL`. Removing a user anonymises them
  and deletes nothing (`../guides/database-style.md`, Deletes).

### Money rules

`BigDecimal` in Java, `NUMERIC(19,4)` in Postgres, **strings over the wire** (ADR-0006).

- A JSON number where an amount belongs is a `400` (`MoneyJsonModule`, `CoreConfig`).
- The only amount a client ever sends is the allocation, and it must **equal** the server's
  surplus (`compareTo`, not `equals`). It is sent so the server can tell the person saw the same
  figure, not so the client can choose one. Any other amount is `409 check-in-stale`,
  `surplus-changed`.
- Every amount in a response carries its currency. Every figure is in the household's base
  currency. Cross-currency arithmetic throws.
- Per-day figures round down to the currency's minor unit (`Currency.getDefaultFractionDigits()`).
  Every other amount is at storage scale, as elsewhere.

## Data model

New tables in **`V20__weekly_check_in.sql`**, the next number after `V19__file_import.sql`. They
replace the `CheckIn` paragraph of `../domain/model.md` (*"`period` (week), `status`, and per-step
outcomes"*). That paragraph, and `ChargeRule`'s, are updated in the same change that builds this,
not now.

`check_ins`
: `id`, `household_id`, `week_start` (date, a Monday), `status` (`DRAFT` | `COMPLETED`),
`window_start` (date), `started_by` (nullable), `completed_by` (nullable), `completed_at`
(nullable `timestamptz`). Then the completion snapshot, all nullable while `DRAFT`: `currency`,
`week_target`, `week_spending`, `rolled_over`, `next_week_target`, `debt_free_month` (date, the
first of the month). Then `created_at`, `updated_at`, `version`.

`check_in_answers`
: `id`, `household_id`, `check_in_id`, `item_kind` (`OVERDUE_BILL` | `UNUSUAL_CHARGE` |
`CATEGORIZE` | `BILL_CHARGE` | `ALLOCATION`), `choice` (`MARK_PAID` | `SKIP` | `PREPAID` |
`CATEGORY` | `ATTACH` | `NOT_A_BILL` | `LEAVE` | `DEBT` | `GOAL` | `ROLL_OVER`), then the typed
references, each nullable: `transaction_id`, `category_id`, `bill_id`, `due_date`, `goal_id`,
`debt_id`. Then `amount` (nullable), `remember` (boolean, default false: the payee rule on
`CATEGORIZE`, the charge rule on `BILL_CHARGE`), `record_debt_payment` (boolean, default false:
the debt-plan prompt), `subject_version` (nullable `bigint`), `answered_by` (nullable),
`created_at`, `updated_at`, `version`.

`payee_rules`
: `id`, `household_id`, `payee_id`, `category_id`, `created_by` (nullable), `created_at`,
`updated_at`, `version`.

`charge_rules`
: `id`, `household_id`, `bill_id`, `payee_id`, `account_id`, `created_by` (nullable),
`created_at`, `updated_at`, `version`.

Changes to existing tables:

- `goal_contributions` gains **`provenance`** `NOT NULL DEFAULT 'USER_LOGGED'`, with a check
  allowing `USER_LOGGED` and `CHECK_IN`. This is what `goals.md` (Data model) said slice 14 would
  add. Every existing row *was* user-logged, so the default loses nothing.
- `debt_payments.provenance`'s check gains **`CHECK_IN`**. The constraint is dropped and re-added
  in V20, with the reason beside it.

Constraints that carry a rule:

- **Scoping is declarative**, as in slices 5 to 13. Every reference is a composite foreign key on
  `(id, household_id)`: `check_in_answers` to `check_ins`, `transactions`, `categories`, `bills`,
  `goals` and `debts`. `payee_rules` to `payees` and `categories`. `charge_rules` to `bills`,
  `payees` and `accounts`. Each referenced table already has its `UNIQUE (id, household_id)`
  (V12, V13, V14, V17, V18). `check_ins` gets its own in this migration, for its answers to
  reference.
- `UNIQUE (household_id, week_start)` on `check_ins`, plus a partial unique index on
  `(household_id) WHERE status = 'DRAFT'`: one draft at a time is a database fact, not a
  look-before-you-leap in the service.
- `CHECK (extract(isodow from week_start) = 1)`.
  `CHECK (window_start <= week_start AND window_start >= week_start - 21)`.
- `CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))`. A `COMPLETED` row has
  `currency`, `week_spending` and `next_week_target` set, and a `DRAFT` has none of the snapshot
  set. `CHECK (currency ~ '^[A-Z]{3}$')`. `CHECK (rolled_over > 0)` where not null.
  `CHECK (extract(day from debt_free_month) = 1)`.
- On answers, `UNIQUE NULLS NOT DISTINCT (check_in_id, item_kind, transaction_id, bill_id,
  due_date)`: one answer per item. `NULLS NOT DISTINCT` needs PostgreSQL 15, which the
  column-list `SET NULL` form already requires. One `CHECK` per `item_kind` names exactly which
  references and choices it may carry. A `CATEGORIZE` answer with choice `CATEGORY` has a
  `transaction_id` and a `category_id` and nothing else, an `ALLOCATION` has `amount > 0`, and
  so on. That way a mis-shaped answer is unwritable, not merely unvalidated.
- `UNIQUE (household_id, payee_id)` on `payee_rules` and `UNIQUE (household_id, payee_id,
  account_id)` on `charge_rules`. A payee files one way, and a charge pays one bill.
- **Deletes.** `check_in_answers` references cascade: transaction, category, bill, goal and debt
  `ON DELETE CASCADE`. An answer about a row that no longer exists means nothing, and the `basis`
  is what tells the draft. Rules cascade from their payee, category, bill and account. User
  references are `ON DELETE SET NULL (column)` in the column-list form, because a bare `SET NULL`
  would null `household_id` too. `check_ins` cascades from `households`, and answers cascade from
  `check_ins`.
- Money columns are `NUMERIC(19,4)` with `scale(x) <= 4`.
- Indexes: `(household_id, week_start DESC)` serves the history. `(check_in_id)` on answers.
  `(household_id, payee_id)` on both rule tables already exists as the unique index. **The
  spending query** filters `transactions` by `(household_id, date)`, which
  `ix_transactions_household_date` serves. Measure it with `EXPLAIN` the way `reporting.md`
  recorded its query plans, rather than adding an index on speculation.

No `item_key` column: the key is derived from the typed references, so it cannot disagree with
them.

## API

All routes are authenticated, household-scoped from the caller's verified membership and never
from the request (ADR-0008 as amended by ADR-0017), identical under session and bearer
(ADR-0018), and CSRF-protected on the session transport for every non-`GET`. `{week}` is
`^\d{4}-W\d{2}$` and must be a real ISO week (a `W53` only in a year that has one), or it is
`400`.

| Method | Path | Notes | Auth |
|---|---|---|---|
| `GET` | `/api/check-ins` | History, paginated, newest week first. Each row is a summary: week, status, `completedAt`, the snapshot figures, the allocation's kind and target id | any member |
| `GET` | `/api/check-ins/{week}` | The week: `status` (`NOT_STARTED` \| `DRAFT` \| `COMPLETED`), the shown steps with their items, answers and pre-answers, the step 4 figures for each choice, `basis`, `version`. `NOT_STARTED` is computed, and only for a week in range (otherwise `422`). A completed week returns its snapshot, not a recomputation | any member |
| `PUT` | `/api/check-ins/{week}` | Start the draft. No body. `201` + `Location` the first time, `200` with the same draft after that. Idempotent, so two members pressing *Start* get one draft. `409 check-in-draft-open` while another week's draft exists | `OWNER` / `MEMBER` |
| `PUT` | `/api/check-ins/{week}/answers/{itemKey}` | Record or replace one answer. The body is shaped by the item's kind (below). Omit `version` to create, and send the answer's `version` to replace. `200` with the answer and the draft's new `version` | `OWNER` / `MEMBER` |
| `DELETE` | `/api/check-ins/{week}/answers/{itemKey}` | Clear an answer. `204` | `OWNER` / `MEMBER` |
| `POST` | `/api/check-ins/{week}/complete` | `{"version": 17, "basis": "…"}`. Everything, atomically. `200` with the completed check-in and what each answer wrote | `OWNER` / `MEMBER` |
| `DELETE` | `/api/check-ins/{week}` | Discard a draft. `204`. `409 check-in-completed` on a completed one | `OWNER` / `MEMBER` |
| `GET` | `/api/payee-rules` | Paginated, by payee name | any member |
| `DELETE` | `/api/payee-rules/{id}` | `204` | `OWNER` / `MEMBER` |
| `GET` | `/api/charge-rules` | Paginated, by bill name | any member |
| `DELETE` | `/api/charge-rules/{id}` | `204` | `OWNER` / `MEMBER` |

Rules are **created only by completing a check-in** (decisions 3 and 4). There is no `POST` and no
`PATCH`: a rule is replaced by answering differently, or removed.

`GET /api/bills?period=` gains, per bill, its charge rules' `payeeName` and `accountId`. That is
enough for the *"Mapped · COMCAST XFINITY · Chase •4412"* label without a request per bill.
`GET /api/periods/{period}/summary`'s `goalsSetAside` follows decision 6.

**Item keys** are issued by the server in every `GET` and parsed strictly, at most 100
characters: `odd.<transactionId>`, `bill.<billId>.<yyyy-mm-dd>`, `cat.<transactionId>`,
`chg.<transactionId>`, `alloc`. An `itemKey` that does not derive to an item in **this**
household's **this** week is `404 not-found`. That holds whether the id is foreign, deleted,
outside the window or nonsense, so a key can never be used to ask about a row.

**Answer bodies**, by key:

| Key | Body |
|---|---|
| `odd.…` | `{"choice": "PREPAID" \| "LEAVE"}`. The bill and *k* are the server's, never sent |
| `bill.…` | `{"choice": "MARK_PAID" \| "SKIP" \| "LEAVE", "recordDebtPayment"?: false}` |
| `cat.…` | `{"choice": "CATEGORY", "categoryId": "…", "remember": false}` or `{"choice": "LEAVE"}` |
| `chg.…` | `{"choice": "ATTACH", "billId": "…", "dueDate": "2026-08-24", "remember": true, "recordDebtPayment"?: false}`, or `{"choice": "NOT_A_BILL" \| "LEAVE"}` |
| `alloc` | `{"choice": "DEBT", "debtId": "…", "amount": "63.00"}`, `{"choice": "GOAL", "goalId": "…", "amount": "63.00"}` or `{"choice": "ROLL_OVER", "amount": "63.00"}` |

Every id in a body is resolved through the owning feature's household-scoped lookup at answer
time and again at completion. Another household's id, or one that is not a valid target for that
item, is `404`. That includes an archived category, a bill that is not active, and a debt that is
not the rung-1 debt when a plan exists. The composite foreign key is the backstop. Unknown
fields are `400` (`FAIL_ON_UNKNOWN_PROPERTIES`). In particular there is no `householdId`, no
user and no free text anywhere in this API.

**Response sketch** for `GET /api/check-ins/2026-W34`, abbreviated:

```json
{
  "week": "2026-W34",
  "weekStart": "2026-08-17",
  "windowStart": "2026-08-17",
  "status": "DRAFT",
  "version": 17,
  "basis": "…",
  "startedBy": "…",
  "steps": [
    { "step": "WHATS_NEW", "answered": true,
      "info": [{ "kind": "INCOME_LANDED", "incomeSourceId": "…", "transactionId": "…",
                 "expected": "2620.0000", "actual": "2620.0000", "currency": "USD" }],
      "items": [{ "itemKey": "odd.…", "kind": "UNUSUAL_CHARGE", "transactionId": "…",
                  "billId": "…", "usual": "42.0000", "multiple": 3,
                  "answer": { "choice": "PREPAID", "answeredBy": "…", "version": 1 } }] },
    { "step": "CATEGORIZE", "answered": false, "more": 0, "items": ["…"] },
    { "step": "NEXT_WEEK", "answered": false,
      "figures": { "currency": "USD", "weekTarget": "550.0000", "weekSpending": "487.0000",
                   "surplus": "63.0000", "base": "480.0000", "rolledOver": "543.0000",
                   "basePerDay": "68.00", "dueNextWeek": ["…"], "streak": 4 },
      "choices": [{ "choice": "DEBT", "debtId": "…", "payoffMonthNow": "2027-04",
                    "payoffMonthWith": "2027-03" },
                  { "choice": "GOAL", "goalId": "…", "savedNow": "2340.0000",
                    "savedWith": "2403.0000" }] }
  ]
}
```

Names (payee, category, bill, goal, debt, account) are returned beside their ids for display, as
the ledger returns `accountName`. Amounts are at storage scale except `basePerDay`. **No sentence
is pre-composed**: *"A repeating $71.99 from COMCAST XFINITY…"* is an i18n message with
parameters, built by the client (ADR-0023).

**Error codes**, new to `ErrorCode`, since a code is part of the contract:

- `check-in-week-out-of-range` (422)
- `check-in-draft-open` (409, `params.week`)
- `check-in-completed` (409)
- `check-in-stale` (409, `params.items: [{itemKey, reason}]`. The reasons are
  `subject-changed`, `subject-gone`, `target-archived`, `already-recorded`, `bill-inactive`,
  `transaction-attached-elsewhere`, `goal-archived`, `debt-paid-off`, `limit-reached`,
  `surplus-changed`, `basis-changed`)
- `check-in-incomplete` (422)
- `payee-rule-limit-reached`, `charge-rule-limit-reached` (409)

Existing codes are used as elsewhere: `validation-failed`, `malformed-request`, `read-only-role`,
`not-a-member`, `csrf-token-required`, `not-found`, `stale-version`.

**No `Idempotency-Key`.** Nothing here moves money (`../guides/api-style.md`). Unlike the goal
and debt endpoints, though, completion is **safe to retry without one**. A retry after a lost
response finds the row `COMPLETED` and gets `409 check-in-completed`, and the client re-reads
and shows the summary. The `PUT`s are idempotent by construction.

## UI

One route, **`/check-in`**, which opens the current week, and `/check-in/2026-W34` for any other.
It is built at 390 and desktop width, light and dark (non-negotiable #10, `design/README.md`).
Every string comes from `en.json`, and every amount, date and month goes through the i18n
formatters. Payee names arrive from bank files, so each one renders in its own `dir="auto"`
element (the `file-import.md` bidi control).

**Where it is reached.** The canvas reaches it only from the home card, and the home screen is not
a slice. *Assumed:* a **Check-in** item in the main nav, and the home card's content as a prompt at
the top of the landing page (Transactions, `transactions.md`): *"Check-in · Due Sunday. 3 charges
waiting on a category. · Start now"*. For a draft the prompt reads *"Continue"*, and on a
completed week it is hidden. The count comes from `GET /api/check-ins/{week}`.

**Every answer saves as it is given**, and the screen says so: *"Your answers are kept as you go,
on any device. Nothing changes in your budget until you finish."* This replaces the canvas's
*"Nothing is saved until you finish"*, which is untrue of a persisted draft and would teach
people to fear closing the tab.

### Desktop: one page

From *"Web — weekly check-in as one page"*: *"On a wide screen the steps become sections you can
see at once — same four decisions, same order, but you can answer them in any order and watch the
total at the bottom settle as you go."*

- **Header.** A status chip (*In progress*), *"Check-in for week 34 · 2 of 3 answered"*, the
  eyebrow *"Sunday check-in · week 34 · Aug 17–23"*, a heading in the display face, and the
  saved-as-you-go line. A row of step chips follows, one per **shown** step, each reading *done*
  or its name (*"1 · done"*, *"3 · bills"*). Each chip links to its section.
- **1 · What's new**, with a status (*"Both handled"*). The income lines, then each question as a
  row: payee, date, the reason (*"usually $42"*), the amount, then the choices as buttons.
- **2 · Categorize**, with *"3 of 3 placed"*. One row per charge (payee, date, amount, category
  picker) and the *Always file … here* checkbox under the picker. Rows placed by a rule say
  *your rule* and are changeable. Beneath the rows, the week's spending in one line (*"Spent
  $487 so far this week"*).
- **3 · Bills**, with *"1 waiting on you"*. The mapping question as a card (*Map it* /
  *Different bill* / *Not a bill*), then the table: **Bill · Date · Status · Amount**, the
  canvas's four columns, and the still-to-pay line.
- **4 · Next week**, with *"You came in $63 under target"*. The choices as a radio group with no
  default, each with its effect line. Then the next-week figure, large (*"Next week you can spend
  $480 · ≈ $68/day"*), the due-next-week sentence, the streak line, and **Finish check-in**.
- The figure **updates as answers are saved**, from the server's response and never computed in
  the browser. It sits in an `aria-live="polite"` region, so a change is announced.

### Mobile: one step per screen

From *"Weekly check-in · steps"*: *"Each step ends by naming the next one, so the progress bar is
never the only thing telling you where you are."*

- A progress bar over the **shown** steps. Each screen has the eyebrow *"Sunday check-in · week
  34"* and its own heading. The canvas's are *"Two things to look at"*, *"Four minutes, then
  you're done"*, *"Bills this month"* and *"You came in $63 under"*. Each heading is a message
  with parameters, never a fixed string.
- **Step 1**: cards for *Paycheck landed ✓* and *Bigger than usual · 1 charge* (*Yes, prepaid* /
  *Leave it*), then *"Quiet week otherwise…"*. The footer is *Next: categorize*.
- **Step 2**: *"3 charges need a category"*, with rows showing a chosen category (*"Dining ✓"*)
  or **Choose**, which opens the category picker as a sheet. A *"Week in one line"* card follows
  (*"You spent $487 — $63 under your weekly pace"*), then *Next: bills*.
- **Step 3**: the *One to map* card (*Yes, map it* / *Different bill*), the bill rows with *Paid*
  or *In 3 days*, and the still-to-pay card. Then *Next: next week*.
- **Step 4**: *"Where should it go?"* with the three choices as full-width selectable rows, then
  *"Next week you can spend · $480 · ≈ $68/day"*, the due-next-week line, the streak line, and
  **Done for the week**.
- Back and forward never lose an answer, because each one is already on the server.

### Both viewports

- **Same fields, same order, same numbers** (`design/README.md` rule 1). Only the arrangement
  differs: a page of sections, or a screen per step.
- **A stale refusal** re-reads, scrolls to or opens the first affected item, and marks every one
  with its reason in a sentence. Nothing that was still valid is cleared.
- **Completed**: a summary of what was written, each with a link to where it lives. *"Filed 3
  charges. Marked Internet paid and remembered COMCAST XFINITY. Recorded $63 to Emergency fund.
  Next week: $480."*
- **`VIEWER`**: every section, item, answer and figure, read-only. No Start, no choice controls,
  no Finish, no Discard. A not-started week reads *"Not started yet"* over the computed items.
- **Empty and quiet states.** With nothing to ask and no surplus, the page is the step 4 figure
  and **Finish**. A household with no bills, no envelopes and no income has `left` ≤ 0 for the
  month, and the figure says so, negative if it is negative. It is never presented as $0
  available.
- **Rules.** The categories page gains a **Payee rules** disclosure (*"SQ \*BLUE BOTTLE →
  Dining · Remove"*). On Bills & Income, each bill row shows its mapping (*"COMCAST XFINITY ·
  Chase •4412 · exact match"*, or *"amount varies ±$4"*) with **Unmap**. These are the canvas's
  own Bills & Income rows. A `VIEWER` sees both with no Remove and no Unmap.

**Dark mode.** There is no dark check-in artboard, so the screens use the role tokens, which step
to their dark values (warm near-black grounds, accents at their 400 tints, per `design/README.md`).
No hex or px is written in a component. Where a value seems missing, it is a design-system
question (`design/README.md`, Known gaps).

**A user guide page ships with the slice** (non-negotiable #11). It is captured from the running
UI against `tools/ui/fixtures/demo-data.ts`, which needs a check-in week. Take the fixture from
the canvas's own figures, which reconcile (550 − 487 = 63, 480 + 63 = 543, 2,340 + 63 = 2,403),
unlike goals' (`goals.md`).

## Security considerations

Threat-modelled before implementation (`/threat-model`, 2026-11-26), against
`../architecture/security-model.md`. This touches **authorization and role enforcement, input
validation, logging, and the integrity of writes made on a person's behalf**. It does not touch
authentication, sessions, tokens or CSRF configuration. Slice 2 owns those, and these routes
inherit them unchanged.

### Assets

- **A completed check-in's snapshot** is a household's weekly disposable income, week after
  week: what it was allowed, what it spent, and whether it came in under. Over a year that is a
  behavioural record finer than any monthly report.
- **The draft** shows what a household is deciding right now, and who is deciding it
  (`answered_by`).
- **Payee rules** are a standing map of where the household shops and how it classifies each
  place, for example *"CLINIC NAME → Therapy"*. They are sharper than the ledger, because the
  household has done the classifying (the `reporting.md` argument about category names).
- **Charge rules** say which payee settles which named bill, on which account (*"Bail bonds"*,
  *"Child support"*).
- **Unusual-charge and missed-income lines** point straight at the moments a household's finances
  went off pattern.
- **The writes themselves.** A check-in changes categories, bill statuses, goal and debt records
  in one action. Its integrity is the asset: the household must get exactly what it was shown
  and confirmed.

### Actors

Per the skill: an unauthenticated stranger; an authenticated user with no membership of this
household (for example the instance administrator with no membership, or a removed member whose
session survives); a `VIEWER`; a `MEMBER` or `OWNER` working on a draft another member is also
using, or planting content for them; a hostile page in a member's browser; a stolen session or
bearer token; and **the bank file**. A merchant descriptor nobody in the household wrote is
rendered in sentences here.

### Entry points

The eleven routes in [API](#api). The new field on `GET /api/bills`. The changed fifth term on
`GET /api/periods/{period}/summary` (decision 6). Path parameters are `{week}`, `{itemKey}` and
two UUIDs. Query parameters are `page` and `size`. Request bodies are the answer bodies and the
completion body. There is **no background job, no scheduled task, no file upload, no new cookie or
header, and no outbound call**. Recurring and unusual-charge detection run inside the request
that reads the week.

### Attacks considered

**Live, and controlled:**

1. *An attacker sends `PUT /api/check-ins/{week}/answers/cat.<tx>` with a `categoryId` from
   another household and gets* a transaction filed under a foreign category, and that category's
   name echoed back in the response. **Control:** `categoryId` is resolved through
   `CategoryService`'s membership-scoped lookup at answer time and again at completion, answering
   `404`. `fk_check_in_answers_category` on `(category_id, household_id)` makes the row
   unwritable regardless. The same holds for `billId`, `goalId` and `debtId`.
2. *An attacker puts another household's transaction id in an `itemKey` and gets* that
   transaction categorised, or its payee and amount returned. **Control:** an `itemKey` is
   looked up only among the items derived from **this** household's window, so a foreign,
   deleted or out-of-window id is the same `404` as a malformed one. The composite FK on
   `transaction_id` is the backstop. There is no route that takes a transaction id and reads it.
3. *A `VIEWER` sends `POST /api/check-ins/{week}/complete`, or starts, answers or discards a
   draft, or removes a rule, and gets* the household's categories, bills, goals and debts changed
   on read-only access. **Control:** `memberships.requireWriteAccess` first in every write in
   `CheckInService`, `PayeeRuleService` and `ChargeRuleService`, **before** the week or item is
   looked up, so a `VIEWER` also learns nothing about what exists.
4. *A member is demoted to `VIEWER` while their draft is open, keeps the tab, and presses Finish,
   and gets* writes on a role they no longer hold. **Control:** the role is resolved from the
   database on every request, never cached on the draft or the session.
5. *A member double-taps Finish, or the client retries after a timeout, or two members press it at
   once, and gets* two goal contributions, two debt payments, or every category applied twice
   with the second overwriting a correction made in between. **Control:** `SELECT … FOR UPDATE`
   on the check-in row, then `status = DRAFT` and `version` checked under the lock. The loser
   gets `409 check-in-completed` and writes nothing. The partial unique index means a second
   draft for the week cannot exist to complete.
6. *A member answers "Dining" on Friday. On Saturday another member re-files the row as
   "Medical" in the ledger, and an import adds three rows to the window. On Sunday the first
   member presses Finish, and gets* their stale answer written over a newer decision, plus rule
   pre-answers on rows they never saw. **Control:** `subject_version` per answer and the `basis`
   digest over everything derived, both re-checked under the lock. The whole completion is
   refused with every affected item listed (decision 9).
7. *An answered transaction, category, goal or debt is deleted between drafting and completion,
   and the attacker gets* a write against a row that no longer exists, or a dangling reference.
   **Control:** the composite FKs `ON DELETE CASCADE` remove the answer with its subject, so
   there is nothing to apply. The `basis` changes, so the person is shown the new picture before
   anything is written.
8. *A member crafts `alloc` with `"amount": "5000.00"`, or with `"debtId"` of a debt that is not
   rung 1, and gets* a check-in that records a figure the week never produced, under the
   check-in's provenance. **Control:** the allocation amount must equal the server's surplus, and
   the target must be one of the server's offered choices. Otherwise it is `409` or `404`. A
   member can still log any payment by hand on the debt's own page, which records it as
   `USER_LOGGED`. So this is an integrity control on what `CHECK_IN` means, not a privilege
   boundary.
9. *A member starts a check-in for `2019-W02`, or `2099-W50`, and gets* a contribution or payment
   back- or forward-dated, or a fake streak. **Control:** only the current and previous ISO week
   can be started or completed, by the server clock with one day of slack. Records are dated the
   **completion day**, never the week.
10. *An attacker sends `{week}` as `+10000-W01`, `2026-W99` or a 5 KB string, or an `itemKey` of
    any length, and gets* a `500` from date arithmetic, logged at `ERROR` and loopable (slice 10's
    `payoffMonth` shape, and reporting's attack 5). **Control:** the edge patterns (exactly four
    digits; a key of at most 100 characters in one of five fixed shapes), then a real-week check,
    all `400`.
11. *A member with a 10,000-row first import loops `GET /api/check-ins/{week}` and gets* recurring
    and unusual-charge detection over the whole ledger on every read. **Control:** the window is
    at most 28 days. Detection reads at most 12 months of history for at most the payees that
    appear in the window, in one grouped query. Each step lists at most 200 items. Answers can
    only exist for derived items, so they are bounded too. Payee rules are capped at 1,000 and
    charge rules at 200 per household (`409 …-limit-reached`).
12. *A member names a payee in a typed transaction, or a bank file carries a descriptor such as
    `‮…` or `<img onerror>`, and another member opening the check-in gets* a reordered amount
    or script. **Control:** Angular interpolation only, with no `innerHTML` or
    `bypassSecurityTrust*`. Each payee is in its own `dir="auto"` element, and bidi controls are
    already stripped at import normalisation (`file-import.md`). Sentences are i18n messages with
    the payee as a parameter, never a server-built string.
13. *An operator turns on `DEBUG` to chase a bug and gets* payee names, categories, amounts and the
    weekly figures in the log. That is the leak this codebase has had twice. **Control:** a
    redacted `toString` on every request and response DTO, entity, view and derived-item record.
    The `org.hibernate.orm.core` and `ExceptionHandlerExceptionResolver` loggers stay pinned to
    `INFO`. **`CheckInDisclosureIT`** captures the log at `DEBUG` across a full journey (start,
    answer, a stale refusal, a `CHECK` violation written straight to the table, completion),
    because PostgreSQL puts the failing row in the error `DETAIL` (`../memory/gotchas.md`).
    Audit lines carry ids and item kinds only.
14. *A future change adds a convenience "and record the transfer" or an HTTP call, and the check-in
    gets* a path to moving money or to sending figures off the box. **Control:** **ArchUnit**
    rules (ADR-0024). The check-in package depends on no HTTP client (`RestClient`,
    `WebClient`, `java.net.http`), the reporting precedent. It may call only the transaction
    service's re-categorise method, never one that creates, deletes, pairs or splits a
    transaction or a transfer.

**Ruled out, with reasons:**

- **Reading another household's check-in by week.** The week is not an identifier of another
  household's data: it is resolved against the caller's household, and there is only one per
  instance (ADR-0026). The scoped query is written anyway, and `MoneyCrossHouseholdIT` gains the
  check-in.
- **A row moved to another household mid-draft.** No row in this schema ever changes
  `household_id`, and no route offers it. If one did, the composite FKs would refuse the answer
  that pointed at it. There is no sentence to finish here beyond the cascade case in attack 7.
- **CSRF.** Every write is a non-`GET` and needs the token on the session transport. `GET`s have no
  side effects, including rule pre-answers, which are derived and never written on read. A
  hostile page can navigate to `/check-in` and read nothing (CORS allowlist, never `*`).
- **A member planting a payee rule to mis-file the household's spending.** Not an escalation. A
  member can already re-file any row in the ledger. A rule only ever pre-answers inside a check-in
  that a person reviews and completes, every rule is listed on the categories page, and it is
  removable.
- **Disclosure through `basis`.** It is a digest of data the caller can already read in the same
  response. It is never logged.
- **Timing or count oracles.** Every item a caller can count is one they can read.
- **Owl writing a check-in.** Not in this slice. If slice 17 ever proposes an answer, it is a
  proposal until a human confirms it (`security-model.md`, ADR-0027), and it reaches the same
  role-checked routes a person uses.
- **Moving money.** Nothing here talks to a bank or writes a transaction. Attack 14 is the
  structural control.

### Controls, and where each is enforced

| Control | Layer | Proved by |
|---|---|---|
| Household from verified membership, never the request | Service (`MembershipService`) | `CheckInIT`, `MoneyCrossHouseholdIT` |
| `VIEWER` reads all, writes nothing, `403` before any lookup | Service (`requireWriteAccess` first) | `CheckInIT.viewer_*` per write route, `AuthorizationIT.protectedEndpoints()` |
| Role re-read per request | Service | `CheckInIT.a_member_demoted_mid_draft_cannot_finish` |
| Ids in bodies resolved in the household, at answer and at completion | Service, backed by composite FKs | `CheckInIT`, plus a constraint test inserting a foreign reference directly |
| `itemKey` resolves only to a derived item in this week | Service | `CheckInIT.an_item_key_for_a_row_outside_the_window_is_404` |
| One check-in per week, one draft per household | `UNIQUE` + partial unique index | `CheckInConstraintsIT` |
| Completion single-shot | Row lock + status + version, service | `CheckInCompletionIT.two_concurrent_completions_write_once` (two threads) |
| All or nothing | One transaction; owning services throw | `CheckInCompletionIT.a_refusal_in_the_last_write_rolls_back_the_first` |
| Nothing unseen is written | `subject_version`, `basis`, service | `CheckInStaleIT`, one case per row of the stale table |
| Allocation equals the surplus, target is an offered choice | Service | `CheckInIT.an_allocation_amount_other_than_the_surplus_is_refused` |
| Week range | Edge pattern + service clock check | `CheckInIT` with a fixed `Clock` |
| Answer shapes | Bean Validation + one `CHECK` per `item_kind` | `CheckInConstraintsIT` |
| Bounds: 28-day window, 200 items per step, rule caps, *k* ≤ 12 | Service, `CHECK` | `CheckInLimitsIT` (disabling each check fails it) |
| Amounts are strings at storage scale | `MoneyJsonModule`, `@Pattern`, `NUMERIC(19,4)` | `CheckInIT` |
| No names, amounts or figures in logs | Redacted `toString` + pinned loggers | `CheckInDisclosureIT` at `DEBUG` |
| No outbound dependency; no transaction create/delete/pair/split | ArchUnit | `ArchitectureTest` |
| Payee text rendered inert | Angular interpolation, `dir="auto"` | `check-in.page.spec.ts` with a bidi and an HTML payee |

### Accepted risks

- **Every member sees every check-in, rule and answer**, including who gave it. Roles are
  household-wide and the operator sees everything regardless (ADR-0026), and the invitation says
  so before anyone joins.
- **A removed member's answers can be applied by whoever finishes.** The finisher has seen them,
  and the version check guarantees it. They are recorded with `answered_by`, set to null if the
  user is deleted.
- **The rule caps can be overshot by one or two under concurrent completions** (count, then
  insert). They bound cost and police no invariant, as with goals and debts. Only one draft
  exists at a time, which narrows the window to almost nothing.
- **"Now" is the server's UTC clock** for which weeks are in range. That moves a boundary by a
  few hours for a household far from UTC and never moves a transaction between weeks: dates are
  calendar dates (`reporting.md`, decision 10).
- **The figures are only as good as the ledger.** Unbudgeted spending depends on categories and
  attachments, and an uncategorised refund is not netted. The app reports what it is told, and
  step 2 is how the household makes it truer.
- **A busy household's draft is refused more often.** Every import or ledger edit to the window
  changes the `basis`. That is the price of decision 9, and it is a re-read and a tap.

## Edge cases

- **The first check-in ever.** There is no target, so there is no surplus line and nothing to
  allocate. Step 4 is the next-week figure and **Finish**, and the completion sets the first
  target.
- **A week after a gap.** If the previous week's check-in was not completed, this week has no
  target. The window still reaches back up to 28 days.
- **Overspent.** *"You went $40 over"*, with no choices. The figure is lower by construction and
  the streak resets.
- **A surplus of exactly zero** counts as under for the streak, with nothing to allocate.
- **The month is short although the week came in under** (`free(M) < surplus`): only *roll over*
  is offered, with a sentence saying why.
- **Next week crosses a month end, or lies wholly in the next month.** Each day comes from its own
  month. A month with no envelopes or bills set up yet gives `left` = its expected income, and
  the figure is labelled as coming from next month's plan.
- **No base-currency accounts**, or only foreign ones: nothing is spending and steps 2 and 3 are
  empty. Step 4 says that accounts in other currencies are not part of the weekly figure.
- **A transfer** is never an item and never spending, in any step, including after it has been
  paired from two imported rows (`file-import.md` rule 12).
- **`PENDING` rows** count, as reporting counts them. **Future-dated rows** count when they fall
  inside the window.
- **A refund larger than the week's spending** makes the week's spending negative, shown as
  negative.
- **A weekly bill** has four or five occurrences a month. A rule match attaches to the nearest
  unattached `DUE` one, and two charges in one week attach to two occurrences, never one.
- **The same transaction offered twice** (as unusual in step 1 and as a rule match in step 3) is
  one item. Step 1's *prepaid* is the answer, and step 3 shows it as handled.
- **A transaction already attached** to an occurrence, a debt payment, a goal contribution or an
  envelope entry is never offered for attaching and never counts as unbudgeted.
- **A charge rule's account is archived.** The rule stays and matches nothing new, because an
  archived account takes no transactions.
- **A charge rule's bill is retired.** It matches nothing, and a match against it is `bill-inactive`.
- **"Not a bill" is not remembered.** The same COMCAST suggestion comes back next month. That is
  an accepted cost of having no negative-rule table, listed under [Out of scope](#out-of-scope).
- **A row with no payee** gets no *always file here*, no charge rule, and no unusual-charge check
  (there is no history to compare).
- **A debt with no plan.** The debt choice is a picker over active base-currency debts. A payment
  that leaves a debt overpaid is not clamped (`debt-plan.md`).
- **A goal reached by the allocation** stays listed, and its plan stops counting from the next
  month (`goals.md` decision 7).
- **Prepaid on a bill whose schedule has fewer than *k − 1* occurrences left in the next twelve
  months** (a yearly bill) skips the ones that project and says how many.
- **Two members, two devices, the same answer at the same moment**: one wins and the other gets
  `409 stale-version`, then sees the winner's answer and name.
- **A draft discarded while another member is answering**: their next `PUT` is `404`, and the
  screen says the draft was discarded.
- **A JPY household**: per-day figures have no decimals, and an allocation at scale 2 is `400`.

## Out of scope

- **Moving money, or writing, deleting, splitting or pairing a transaction or transfer.** The
  vision and ADR-0035. The only ledger change is a category.
- **Filing a transaction into an envelope** from the check-in. Assigning spending to an envelope
  is the person's act (`envelopes.md`), and the canvas does not draw it here.
- **Envelope rollover.** *Roll into next week* moves a surplus into next week's **target**. It
  carries nothing between envelope periods, and ADR-0031 still stands.
- **Changing a plan**: the debt plan's monthly amount, a goal's monthly contribution, an
  envelope's amount, or a bill's figure. A one-off allocation stays one-off.
- **Splitting the surplus** across several destinations. The canvas offers one choice.
- **Reopening or undoing a completed check-in.** Each record it wrote is corrected where it lives.
- **Applying payee or charge rules anywhere but a completed check-in**: not at import, not on
  manual entry, not on sync. Doing it would change `file-import.md` rules 8 and 10, which shipped,
  and needs its own decision and an ADR. Automatic matching for connected accounts is slice 16.
- **A negative rule** ("COMCAST is not a bill, stop asking").
- **A category-source flag on transactions.** A category set by a rule is indistinguishable in the
  ledger from one typed. Bills have `statusSource`, and transactions have no such column. That is
  worth adding with rule-at-import, not before.
- **Reminders and notifications** (*"Due Sunday"* as a push). Nothing in the core may need a service
  we operate (non-negotiable #9).
- **The home screen.** It is not a slice. Its check-in card reads `GET /api/check-ins/{week}`.
- **The Flutter screens.** Slice 15 builds them against this API.
- **Owl.** No model is involved anywhere in the check-in (slice 17, ADR-0027).
- **Statistical attributions** such as *"Dining was the whole difference"* (see below), and
  *"usual pace"* per category. Reporting decision 7 refused both until a baseline is defined.
- **A configurable week start or household time zone.**
- **Catch-up beyond four weeks.** Older rows stay in the ledger's queue and on Bills & Income.
- **Payee merging and normalisation** beyond case-insensitive get-or-create. A rule keys on the
  payee as stored, so "STARBUCKS #1234" and "Starbucks" need a rule each until normalisation
  exists.

## Where the designs and the docs disagree

- **`../domain/model.md`'s `CheckIn` and `ChargeRule` paragraphs are superseded by this doc**, in
  the change that builds it. `period (week)` becomes `week_start`, an ISO Monday. The per-step
  outcomes become typed answer rows. `ChargeRule` loses `standing` and `matchKind` (both
  explained under [Step 3](#step-3-bills-decision-4)).
- **"Nothing is saved until you finish"** describes the effects and not the draft. The copy
  changes (see [UI](#ui)).
- **"Pulls Apr 2027 in by 4 days"** and *"Apr 2027 comes in 4 days"* are day-precision deltas that
  monthly compounding cannot produce. `debt-plan.md` already flagged this for slice 14, and payoff
  dates are months everywhere. The copy becomes *"Moves Visa •8823 from Apr 2027 to Mar 2027"*
  or *"Doesn't move the month"*.
- **"Charges Owl could not place."** No model places anything. The copy becomes *"3 charges need a
  category"*, which the mobile artboard already uses.
- **"Same as the last six deposits."** The comparison is with the income source's own amount
  (*"as expected"*), because that is the plan figure the household entered. Counting past
  deposits would be a second definition of "expected".
- **"Covered by what's in Chase"** under the bills step is not built. A bill has no account
  (`bills-and-income.md`, Data model), so which balance "covers" it would be a guess.
- **"Dining was the whole difference."** Not built. Attributing a week's variance to a category
  needs a per-category weekly baseline that nothing defines. That is the claim reporting
  decision 7 refused.
- **"Writes: income confirmed."** Under decision 7, step 1 writes nothing for income.
- **The glossary's *Safe to spend*** (*"Money on hand minus bills still due minus what envelopes
  still hold, over the days remaining"*) is balance-based, and next week's figure here is
  plan-based (decision 1). One of them changes. The recommendation is that the glossary entry and
  the home screen adopt this definition when the home screen is built, so the product never shows
  two different "you can spend" numbers.
- **`bills-and-income.md` (Out of scope) and the roadmap's slice 7 row** say charge mapping
  *"needs a connected account (slice 16)"*. File import made real charges available without one.
  Under decision 4 both change in the same change that builds this. Automatic matching for a
  connection stays slice 16.
- **The roadmap's slice 14 row** lists dependencies 6, 7 and 10. It also depends on 11, for goal
  contributions.
- **The canvas preselects *Visa payoff*** in step 4. Here nothing is preselected (see
  [Where the surplus goes](#where-the-surplus-goes-decisions-5-and-6)).

## Open questions: decisions needed

**Decided 2026-11-26 by the owner: every recommendation below is taken.** Asked directly:
1 (the plan, not balances), 3 and 4 (build payee rules and bill ↔ charge matching in this slice),
and "accept the rest" for 2, 5, 7, 8 and 9. On 6 the owner stated no preference, so the
recommendation stands (a check-in contribution counts on top of the plan). Decision 1 is recorded
as ADR-0040 and "rules act only through a completed check-in" (3 with 4) as ADR-0041, at the
start of implementation, as the entries below say.

For the product owner. Each question changes what gets built. Each has a recommendation, and the
rules above are written as if it were taken.

1. **What is next week's figure built from?** Recommended: **the plan**. It is the shipped
   **What's left** figure for the month, minus unbudgeted spending so far and any check-in debt
   payments, spread over the days left. A rolled-over surplus goes on top (see
   [Free money](#free-money-and-next-weeks-figure-decision-1)).
   *Why:* it reuses a figure that is shipped, tested and already on Bills & Income, with its five
   terms, so the app has one "what's left" and not two that disagree. It works for a household
   that never entered an opening balance. An overspend lowers next week by construction, which
   also answers the overspend question ADR-0031 raised. *Rejected:* the glossary's balance-based
   *Safe to spend*. It needs a "money on hand" definition nobody has made: which account types,
   whether card balances count, and whether savings that hold goals count. It is wrong for any
   household whose opening balances are not exact, and it would be a second "what's left"
   figure. *Consequence:* the glossary entry changes when the home screen is built. Because this
   defines a headline figure and rejects a real alternative, record it as **ADR-0040** at the
   start of implementation.
2. **What counts as the week's spending?** Recommended: base-currency, non-transfer outflows in
   `EXPENSE` categories or uncategorised, net of refunds. Excluded are rows attached to a plan
   record and rows in a category that an envelope or a bill of the month already budgets. The
   formula is under [The week's spending](#the-weeks-spending-decision-2).
   *Why:* rent and groceries are already in `left`, and counting them against the week as well
   counts them twice. Every week containing the 1st would read as $1,650 over. *Rejected:*
   reporting's `spending` unchanged, for exactly that reason. Also rejected: excluding only
   attached rows, because nothing outside the check-in can attach a row today, so almost nothing
   would be excluded.
3. **Payee → category rules: build them in slice 14, and where do they act?** Recommended: build
   them. Make *"Always file {payee} here"* **opt-in per row, off by default**. A rule only
   pre-answers inside later check-ins and is applied when one is completed. Import and manual
   entry stay as they are (`file-import.md` decision 8 stands).
   *Why:* the step map says step 2 writes a payee rule, and the vision wants categorisation by
   lookup table. Off by default because a mixed payee (Amazon) filed one way forever is a silent
   error. Check-in only because applying a rule at import changes a shipped rule and writes
   categories nobody saw, which no column could later tell apart. *Rejected:* on by default;
   applying at import and create. If taken as recommended together with 4, record **"rules act
   only through a completed check-in"** as an ADR (the next free number after 1's).
4. **Bill ↔ charge mapping: pull `ChargeRule` forward from slice 16?** Recommended: **yes**,
   applied only inside the check-in. A rule match within tolerance is pre-answered as
   `AUTO_MATCHED`, the recurring-charge suggestion is computed on read, and there is no job.
   Automatic matching for connected accounts stays slice 16.
   *Why:* step 3's only design is the mapping (*"Map it to your Internet bill?"*), and step 1's
   *prepaid* stands on it. The reason it was deferred, *"needs a connected account"*, stopped
   being true when import shipped. *Rejected:* keeping it in 16 and shipping step 3 as one-off
   attaches only. That is smaller, but it asks the same COMCAST question every month, has no
   *prepaid*, and leaves the "Skips if all mapped" behaviour unbuildable.
5. **What does allocating the surplus to a debt or a goal write?** Recommended: a debt payment or
   a goal contribution of the surplus, dated the completion day, with a new provenance `CHECK_IN`.
   It is written through the existing endpoints' service paths, and the screen says
   *"Make the payment at your bank — Budget Owl never moves money."*
   *Why:* `goals.md` and the domain model already say the check-in will record a contribution,
   and the debt side should match. A recorded allocation is what takes the money out of next
   week's figure. *Rejected:* recording nothing, which makes the allocation a note and counts
   the money twice next week. Also rejected: raising the plan's monthly amount, which turns a
   one-off into a standing commitment.
6. **Does a check-in goal contribution count on top of the month's plan in "What's left"?**
   Recommended: **yes**. The fifth term becomes `Σ (max(planned, recordedOther) +
   recordedCheckIn)`. For a household with no check-in contributions it is unchanged to the cent.
   *Why:* under the shipped `max(planned, recorded)`, `$63` against a `$110` plan with nothing yet
   recorded still counts `$110`, so the surplus would not leave the figure, and the weekly target
   would offer the same money again. *Rejected:* leaving the fifth term alone and accepting the
   lag. *Consequence:* this changes a shipped figure for the third slice running, so
   `bills-and-income.md`, the glossary's **Left over** and the user guide move in the same change.
7. **Step 1's "income confirmed": does it write anything?** Recommended: **no**. Step 1 compares
   the income sources expected in the window with the positive rows that landed, as information.
   *Why:* income projects no occurrences, by design (`bills-and-income.md`), so a confirmation has
   nowhere to live, and nothing downstream reads one. *Rejected:* an income-arrivals table. That
   reverses a shipped design and is the start of `AUTO` income, which belongs with a bills change.
8. **Missed and late weeks.** Recommended: a check-in can be started only for the current or
   previous ISO week, with one draft at a time. The window reaches back to the last completed
   check-in, at most 28 days, and there is no surplus without a target set for this week.
   *Why:* a skipped week's charges still get asked about, and a two-month gap stays a few-minute
   job instead of an archaeology session. *Rejected:* the check-in's own week only (skipped
   weeks are never looked at); unbounded catch-up; several open drafts (the chain of weekly
   targets becomes order-dependent).
9. **What happens when the data changes underneath a draft?** Recommended: **refuse the whole
   completion** (`409 check-in-stale`), list every affected item with its reason, and write
   nothing until the person re-confirms.
   *Why:* the person approves a set. Applying part of it is exactly what *"nothing takes effect
   until it is finished"* rules out, and it is the one shape that can never write something they
   were not shown. *Rejected:* applying what is still valid and silently dropping the rest.

## Testing notes

Every route gets the mandatory set in `../guides/testing-style.md`: `401`, `404` for not-yours,
`400` naming the field, the happy path, both transports, CSRF `403`, a `VIEWER` `403` on every
write, and the money cases. Beyond that, what must be proven for this to be trusted:

- **`CheckInIT`**: the canvas's week end to end, asserted as numbers: target 550, spending 487,
  surplus 63, base 480, rolled 543, contribution 2,340 → 2,403. Each step is skipped when it has
  nothing to ask. Each write route refuses a `VIEWER` before any lookup, including with an
  unknown week. A member demoted mid-draft cannot finish. Ids from another entity's table are
  `404` in every body field and every `itemKey` shape. Week range is tested with a fixed `Clock`
  at both edges of the slack. An allocation amount other than the surplus is refused.
- **`CheckInCompletionIT`**: **atomicity** with a refusal injected into the *last* write (a goal at
  its contribution cap), proving the categories and occurrences written before it are rolled
  back. **Two threads completing at once** write each record exactly once. A retry after
  completion is `409 check-in-completed` and writes nothing. Unanswered items are untouched.
- **`CheckInStaleIT`**: one case per row of the stale table, each asserting that **nothing** was
  written and that `params.items` names exactly the affected keys.
- **`CheckInNeverIT`**: completes a check-in exercising every answer kind, then asserts that the
  transaction count is unchanged and that every transaction's amount, date, account, payee, note,
  status and `transfer_group_id` is unchanged. Only the category may differ, and only on answered
  or rule-placed rows. It also asserts that no envelope entry was written and that no plan
  (debt-plan amount, goal steps, envelope amounts, bill figures) changed.
- **`WeeklyFigureTest`** (unit): the free-money formula; a week crossing a month end; a week
  wholly in the next month; overspent; zero; `free(M) < surplus`; rollover adds exactly the
  surplus; per-day rounds down at the minor unit for USD and JPY.
- **`UnbudgetedSpendingIT`**: every exclusion **separately**. That covers transfers, foreign
  currency, an `INCOME` category, an uncategorised positive, each of the four attachments, an
  envelope's category, a child of an envelope's category, and a bill's category. It also covers
  refunds netting and the draft's answers being applied. Each case is separate because one test
  that "spending returns a number" would pass while one exclusion was missing (the
  `transactions.md` lesson about filters).
- **`GoalPeriodSummaryIT`**, extended: decision 6's fifth term with `CHECK_IN` contributions on
  top of the plan, and every existing case green unchanged. Those existing cases are the proof
  that a household without check-ins sees no difference.
- **`RecurringChargeTest`**, **`UnusualChargeTest`** (unit): three months exactly, the 10% band,
  ×2 against the median, fewer than three rows giving nothing, and *k* for prepaid including the
  tolerance and the 12 cap.
- **`PayeeRuleIT`** and **`ChargeRuleIT`**: created only by completion, a rule replaced on a
  different answer, an archived category offering nothing, cascades from payee, category, bill and
  account, the caps, and pre-answers applied with `AUTO_MATCHED` for charge rules and never at
  import (an import into the window leaves the row uncategorised and unattached).
- **`CheckInConstraintsIT`**: the database refuses a second draft, a second check-in for a week,
  a non-Monday `week_start`, a window over 21 days back, a mis-shaped answer for each
  `item_kind`, and a foreign reference inserted straight into each composite FK.
- **`CheckInLimitsIT`**: each bound, and that removing each check fails it.
- **`CheckInDisclosureIT`**: the full journey at `DEBUG`, with no payee, category name, amount or
  figure in the log.
- **`ArchitectureTest`**: the two check-in rules (no HTTP client, and no transaction
  create/delete/pair/split).
- **Migration**: `V20` in the migration list test, and `ApplicationRoleRestrictionsIT` exercising
  the four new tables as the restricted role (the gap `transactions.md` recorded for its own
  tables).
- **Frontend**: `check-in-data.spec.ts` (bodies by kind, amounts as strings, `version` only when
  replacing, `basis` sent on complete). `check-in.page.spec.ts` (skipped steps absent, *Next: …*
  naming the next shown step, nothing preselected in step 4, a stale refusal marking exactly the
  listed items, the `aria-live` figure, a bidi and an HTML payee rendered inert, and a `VIEWER`
  with every figure and no control). `tools/ui/specs/app/check-in.spec.ts` covers both
  viewports, light and dark, with axe, and the screenshots are read (non-negotiable #8).
