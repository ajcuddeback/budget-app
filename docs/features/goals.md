# Feature: Goals

- **Slice:** 11
- **Status:** Done (slice 11). The schema (`V18__goals.sql`), the fifteen endpoints, the fifth
  term and `debtPlanExtra` on the period summary, both screens, the dialogs, the generated demo
  fixtures and the user guide have shipped. See
  [What slice 11 has built](#what-slice-11-has-built), the implementation decisions at the end of
  [Decisions](#decisions), and [Known gaps](#known-gaps). The open questions were answered on
  2026-11-25.
- **Owner:** Repository owner
- **Last updated:** 2026-11-25
- **Depends on:** transactions (5) for the optional link from a contribution to a ledger row.
  Accounts (3) for the optional "where it is kept". Bills & income (7) and the debt plan (10),
  because goals feed the same left-over figure they do.
- **Related:** ADR-0006 (money), ADR-0008 as amended by ADR-0017 (household scoping), ADR-0022
  (currency), ADR-0023 (i18n), ADR-0025 (a plan record is not a transaction), ADR-0026 (one
  household), ADR-0031 (period labels, no rollover), ADR-0035 (a debt payment is a plan record),
  ADR-0036 (the ladder's flow-down), `bills-and-income.md`, `debt-plan.md`, `envelopes.md`,
  `../domain/model.md`
- **Designs:** **There is no Goals artboard.** `design/README.md` lists none, and the canvas has
  only these:
  - **Goals** in the desktop nav, between *Debt plan* and *Insights*.
  - A **Goals card** on the home screen, light and dark, with two rows: *Emergency fund
    $2,340 / $6,000*, and *Japan, Nov 2027 $890 / $3,500*. Each row has a progress bar.
  - Owl's **proposed change**: *"Japan trip: $110 → $165 per month, starting Sep 1"*.
  - Check-in step 4, *"Where the surplus goes — debt, a goal, or roll it over"*.
  - Onboarding step 5, *"a suggested first goal"*.
  - The debt plan's subtitle, *"the extra comes from what's left after goals"*.

  The screens below are therefore described in terms of existing tokens and patterns, not drawn
  values. See [UI](#ui).

## Purpose

A goal is **money you are putting aside for something**, with a target and, if you have one, a
month you want it by: an emergency fund of £6,000, or a trip to Japan by November 2027. Bills say
what you owe, envelopes say what you chose to spend, and the debt plan says what you are paying
down. A goal is the only one of the four where the number you care about is going **up**.

The page answers two questions for each goal:

> **How far along am I?** and **At this rate, when do I get there — or what would it take to get
> there by the month I picked?**

It works with nothing connected and no account recorded at all, the same as envelopes and debts.
The home card's two rows are a fair statement of the feature: a name, an optional month, a figure
saved out of a figure wanted, and a bar.

## User stories

- As a member, I want to name something I am saving for and how much it needs, with or without a
  date, so I can see it filling up.
- As a member, I want to say how much I put towards it each month, and have that taken out of
  what's left to spend, because I already decided that money is not for spending.
- As a member, I want to record money I set aside, including a one-off top-up when a month comes in
  under, and see the total move.
- As a member, I want to take money back out of the emergency fund when the emergency happens,
  without deleting history to make the figure true.
- As a member, I want to know when I will get there at my current pace, and what I would need each
  month to get there by the month I picked.
- As a member, I want to raise my contribution from next month without rewriting what last month
  said.
- As a `VIEWER`, I want to see every goal and its progress, and change nothing.

## Prerequisites

Nothing beyond what has shipped. Goals need **no** bank connection, file import, check-in or Owl.
The optional link from a contribution to a transaction uses slice 5. The optional "where it is
kept" account uses slice 3.

## Rules and behaviour

Where a rule follows from one of the [decisions](#decisions), the rule names it.

### What a goal is

- A **name** of 1–80 characters after trimming, the same rule and `CHECK` as accounts.
  - Names are **unique per household, case-insensitively, among unarchived goals**: `409
    goal-name-taken`. Assumed, not decided. This follows bills and envelopes rather than accounts,
    because the check-in's *"put the surplus in Holiday"* and Owl's *"Japan trip"* both name a goal
    and could not tell two apart. Accounts allow duplicate names only because `lastFour` tells them
    apart, and goals have no such field.
- A **target amount**, greater than zero, in the goal's currency.
- An **optional target month**, a `YearMonth` serialised `"2027-11"` (decision 5). A goal with
  no month is normal. The designs' emergency fund has none.
- A **currency**. It defaults to the household's base currency. When the goal is linked to an
  account, the currency is the account's and cannot be set separately, the same rule as a debt
  (`debt-plan.md`). It is **fixed at creation**: a goal with contributions in pounds cannot become
  a goal in euros without every figure being reinterpreted. This is the reasoning behind
  `category-kind-immutable` and `account-currency-immutable`.
- An **optional linked account**: where the household keeps the money. **It is informational and is
  not the source of progress** (decision 1). The goal page may show the account's balance
  beside the goal's saved figure, labelled as two different things, and nothing reconciles them.
- An optional **starting amount**: what was already set aside when the household started tracking
  the goal, `≥ 0`, default `0`. It is the goal's equivalent of an account's `openingBalance`. It
  counts towards `saved`. **It never counts towards any month's left-over figure**, because that
  money was set aside before this month. Recording it as a contribution dated today would subtract
  £2,000 from this month's headline. See [Edge cases](#edge-cases).

### How money gets toward it (decision 1)

**Progress comes from recorded contributions.** A contribution is a dated, signed amount the
household logs against a goal. It is a **plan record, not a transaction**, for the reasons
ADR-0035 gives for debt payments, which carry over unchanged:

- Logging a contribution **writes no transaction and no transfer, and moves no account balance.**
  The household's accounts are the record of what happened. A goal screen that writes ledger rows
  edits another part of the app in response to a figure typed about something else.
- If logging wrote a transfer into savings, every contribution would turn into two rows the moment
  file import (12) or bank connections (16) brought the real transfer in. That is ADR-0035's
  double-counting argument, word for word.
- A contribution carries a **nullable `transactionId`** so the household can attach the real
  movement, usually one leg of a transfer into savings. The link is **evidence, not the source of
  truth**. It is unique where not null, and deleting the transaction leaves the contribution
  standing with the link cleared: `ON DELETE SET NULL (transaction_id)`, in the column-list form
  `transactions.md` established.
- **Nothing is recorded on its own.** No job posts the monthly contribution when a month turns.
  Every figure in `saved` traces to something a person recorded. This is the line envelopes
  (ADR-0025's principle) and debt payments (ADR-0035) already hold, and it is why there is no
  scheduler to attack or to fall behind while an instance is switched off.

`saved = startingAmount + Σ contributions`, **derived on read and never stored**. `remaining =
targetAmount − saved`, also derived, and it may be zero or negative.

A contribution is **signed and non-zero** (decision 6). A negative contribution is a
**withdrawal** and is shown as one. `saved` is **not clamped**: an over-funded goal reads over
100%, and a withdrawal larger than what was saved reads negative. This follows the debt plan's
refusal to clamp an overpayment and Bills & Income's refusal to clamp a negative `left`.

**A contribution's month is a label** (ADR-0031). It is stored as `period`, the first day of the
month it counts towards, and set from the calendar month of its `date`. In slice 11 the API does
not accept a separate `period`. Storing the label now means a configurable month start, which
`envelopes.md` and ADR-0031 still anticipate, changes where new contributions land and rewrites
nothing.

### The monthly contribution (decision 4)

The household plans a **monthly contribution**, and the plan is **effective-dated**: a list of
`(fromPeriod, monthlyAmount)` steps. The amount in force for a period is the latest step whose
`fromPeriod` is at or before it, and before the first step it is zero. Zero is a valid step and
means paused.

- **A change applies from the current period forwards, never backwards.** Setting a step for a past
  month is `422 goal-plan-period-past`. To correct what happened in a past month, record or edit a
  contribution. The plan is not the place to do it.
- Setting a step **replaces every step at or after its `fromPeriod`**, so the request is
  idempotent: *"from September, $165"* means the same thing however many times it is sent.
- This is the designs' only drawn interaction with a goal: *"$110 → $165 per month, starting Sep
  1"*. With a single stored figure instead, raising it in September would rewrite August's
  left-over figure. ADR-0032 accepts that for bills, and envelopes avoid it by being per-period
  rows.

### One rule for "this month's contribution"

**A goal's contribution to a month is the greater of what was planned for that month and what was
recorded against it.**

`monthContribution(g, P) = max(planned(g, P), recorded(g, P))`

Here `planned` is the step in force, and `recorded` is the sum of the contributions labelled `P`,
withdrawals included. `planned` is treated as **zero** for any month that began with the goal
already reached, that is, when `startingAmount + Σ contributions labelled before P ≥ targetAmount`.

The projection and the left-over figure both use this rule, and it has three consequences:

- **The figure does not move as you set money aside.** Planning £110 and then recording £50 still
  counts £110. The month is not over, and the other £60 is still spoken for. This is the reason
  bills count whether or not they are paid (`bills-and-income.md`): a headline that rises each time
  somebody does the responsible thing is most optimistic exactly when least is left.
- **A top-up above the plan counts in full.** Recording £200 against a £110 plan counts £200. Money
  moved towards a goal is not free to spend, and the check-in's surplus allocation (slice 14) needs
  this to be true.
- **A withdrawal never raises the figure.** `max(110, −500)` is 110, and `max(0, −500)` is 0. Left
  over is a plan figure: income, bills, envelopes and minimums, with no ad-hoc spending in it. The
  spending a withdrawal pays for is not in it either, so raising `left` by the withdrawal would
  report money as free that has already gone.

### What's left this month (decision 2)

```
left = income − bills − envelopesAllocated − debtMinimumsNotInBills − goalsSetAside
```

`goalsSetAside(P) = Σ monthContribution(g, P)` over goals that are **unarchived** and in the
**household's base currency**.

- **Archived goals are excluded**, the same rule envelopes follow: a retired goal is not part of the
  month's plan. Archiving a goal that had a contribution recorded this month makes `left` rise by
  that amount. That is correct and surprising, and the page says why.
- **Goals in another currency are excluded**, the same reason `owed` and `debtMinimums` are
  base-currency only (ADR-0022): a combined figure needs a rate the instance may not have. They
  still appear on `/goals`, grouped by currency.
- **A household with no goals has a fifth term of zero**, so every existing `PeriodSummaryIT` case
  stays green. That is the property slice 10 relied on to change a shipped figure safely, and it
  holds again here.

**This changes a figure that has shipped, for the second slice running.** As with `debt-plan.md`
decision 2, `bills-and-income.md`, the glossary's **Left over** entry and the user guide's *"See
what's left this month"* all move **in the same change**, and the user guide says what changed and
why. This doc does not edit them.

### The debt plan's extra (decision 3)

**Goals and the debt plan are not coupled by arithmetic.**

- `payoff_plans.monthly_amount` stays the household's own figure, set on `/debt-plan` (`debt-plan.md`
  decision 8). Slice 11 does **not** compute it from what's left after goals. That makes decision 8's
  field what it was always going to be if this slice chose not to compute it: the only control.
- **Goals are not rungs.** They never enter the ladder, and a cleared debt's minimum and surplus
  never roll down into a goal. ADR-0036's flow-down is right for a ladder the household asked to
  have allocated. Nobody has ranked goals against debts, and an app that did it for them would be
  making a financial-priority call nobody asked for.
- `left`, with goals subtracted, **is** the pool the debt plan's extra comes from, which is what the
  designs' copy already says. So the only interaction is a disclosure. When the plan's extra
  (`monthlyAmount − Σ minimums`, base currency) is more than this month's `left`, both Bills &
  Income and `/debt-plan` say so in words, and nothing changes automatically. The summary reports
  the extra beside `left` (see [API](#api)). Comparing them needs no arithmetic in the browser, but
  the sentence needs both figures.

**Rejected: deriving `monthlyAmount` from `left` after goals.** `left` varies from month to month,
and a fortnightly wage arrives two or three times. So every payoff date on `/debt-plan` would move
whenever an envelope changed, and a tight month would hand the ladder zero or a negative amount.
It would also break the invariant ADR-0036 asserts, *"the monthly amount is spent exactly every
month but the last"*, which presumes one figure. And the plan snapshot behind *"4 months earlier
than last month's plan"* would churn for reasons unrelated to the plan.

### Projection

Computed on read and never stored. "Now" is the current period: the calendar month of today on the
server's clock, as the debt plan's projection uses it. Call that period `C`.

**Projected month, at the plan in force.**

- Start from `saved`.
- For `C`, add what is still to come this month: `max(planned(C) − recorded(C), 0)`.
- For each later month `Q`, add `planned(Q)`.
- The projected month is the first month at which the running figure reaches `targetAmount`.
- If no step from `C` onwards is positive, `projectedMonth` is `null` with reason
  `no-contribution`.
- **The walk is bounded at 600 months**, the debt solver's horizon. Past that, the month is `null`
  with reason `beyond-horizon`. See [Security](#attacks-considered), attack 5.
- A goal already reached gets reason `reached`.

**Required monthly, to reach the target month.** Only when a target month `T ≥ C` is set. It is the
smallest amount `a`, at the currency's minor-unit scale, such that a plan of `a` from `C` onwards
reaches `targetAmount` in or before `T` under the walk above. In closed form:
`a = ⌈(targetAmount − savedBeforeC) ÷ n⌉`, where `savedBeforeC` excludes contributions labelled `C`
and `n` is the number of months from `C` to `T` inclusive.

- When that `a` is not more than what is already recorded this month, this month's slot is already
  filled, and `a = ⌈remaining ÷ (n − 1)⌉` over the months after `C`.
- **It is computed by the projection code and verified against it, not by a second formula.** This
  is the debt-preview lesson: two implementations of one rule drift.
- **Rounded up, explicitly, at the currency's minor-unit scale**: two places for GBP, none for JPY,
  three for KWD. This is not `HALF_EVEN`. Rounding to nearest sometimes leaves the household a cent
  short in the final month, and the projection would then put the goal a month after the month they
  asked for.
- If `T` has passed and the goal is not reached: `requiredMonthly` is `null` with reason
  `target-month-passed`, and the goal is **overdue**, derived and never stored, like a bill's. The
  projected month is still shown.
- With no target month: `null`, reason `no-target-month`.

**No interest, no growth.** Savings interest and investment returns are not modelled (see
[Out of scope](#out-of-scope)), and the page says the projection assumes none.

**Payoff precision is a month.** `projectedMonth` is a `YearMonth` everywhere, for the reason
`debt-plan.md` gives for `payoffMonth`. "Behind by 4 months" is date arithmetic and the client may
do it. "£53 a month short" is a subtraction of two amounts and the client may not, so the server
returns `requiredMonthly` and `monthlyContribution` and the screen states both.

### Reached (decision 7)

- `reached` is `saved ≥ targetAmount`, **derived and never stored**. A stored flag would need
  something to keep it true when a contribution is edited or a withdrawal recorded.
- A reached goal stays in the list, marked reached. Contributions are still accepted and show as
  over 100%.
- Its planned contribution stops counting towards `left` from the month **after** the month it began
  reached, per the rule above. Nothing flows to another goal automatically. The page offers two
  explicit actions: **Archive**, and **Use this £110 for another goal**, which opens that goal's
  plan prefilled. The second action is optional and can ship later without any API change.
- A withdrawal that takes it back below the target makes it unreached again, and its plan counts
  again from the next month. Nothing has to be reset by hand.

### Writes and roles

- **`OWNER` and `MEMBER`** may create, edit, archive and delete goals, set the plan, and record,
  correct and remove contributions.
- **`VIEWER`** reads every goal, every figure, the plan, every contribution and every projection,
  and writes nothing. That includes both projection previews: they are `POST`s and are refused like
  any non-`GET`. See [API](#api).
- **The role is checked before the goal is looked up**, so a `VIEWER` sending an unknown id gets
  `403`, not `404`. This is the accounts rule: the other order lets a `VIEWER` tell which ids exist.
- An **archived goal** takes no new contributions and no plan changes (`409 goal-archived`). Its
  history still reads. This is the envelope rule.
- **Deleting a goal** takes its plan and contributions and no transactions, as deleting an envelope
  or a debt does. It is permitted with history, because archive is the normal path and deletion is
  the deliberate one, and the confirmation says "permanently".

### Money rules

`BigDecimal` in Java, `NUMERIC(19,4)` in Postgres, **strings over the wire** (ADR-0006).

- A JSON number where an amount belongs is a `400` (`MoneyJsonModule`, and `CoreConfig`'s refusal
  to coerce a number into a textual field; see `../memory/gotchas.md`).
- Every amount response carries its currency.
- A contribution's currency is its goal's and is **not** a request field.
- A request whose scale exceeds the currency's minor-unit scale is a `400`.
- Cross-currency arithmetic throws. Totals group by currency first.

## Data model

New tables in `V18__goals.sql`, the next free number after V17. This replaces the `Goal` paragraph
in `../domain/model.md`, which was written from the canvas before this doc existed. The corrections
are listed under [Where the designs and the docs disagree](#where-the-designs-and-the-docs-disagree).

`goals`
: `id`, `household_id`, `name`, `currency`, `target_amount`, `target_month` (nullable date, the
first of the month), `starting_amount` (default 0), `account_id` (nullable), `archived`,
`created_at`, `updated_at`, `version`.

`goal_contribution_steps`
: `id`, `household_id`, `goal_id`, `from_period` (date, the first of the month), `monthly_amount`,
`created_at`. `UNIQUE (goal_id, from_period)`.

`goal_contributions`
: `id`, `household_id`, `goal_id`, `contributed_on` (date), `period` (date, the first of the month,
a label), `amount` (signed), `note` (nullable), `transaction_id` (nullable, unique where not null),
`created_at`, `updated_at`, `version`.

Constraints that carry a rule:

- **Scoping is declarative**, as in slices 5–10. Every foreign key is composite on
  `(id, household_id)`, so a goal physically cannot reference another household's account and a
  contribution cannot reference another household's transaction (ADR-0017). `goals` gets
  `UNIQUE (id, household_id)` in the same migration for its children to reference. V17 had to
  `ALTER` one onto `bill_occurrences` after the fact, and the migration fails to apply without it.
- `CHECK (target_amount > 0)`. `CHECK (starting_amount >= 0)`. `CHECK (monthly_amount >= 0)`.
  `CHECK (amount <> 0)` on a contribution. A zero contribution records nothing, and a negative one
  is a withdrawal (decision 6).
- `CHECK (extract(day from target_month) = 1)`, and the same on `from_period` and `period`. A label
  is the first of its month, as `envelopes.period` is.
- `name` is bounded raw as well as trimmed, the V8 / accounts lesson. A partial unique index on
  `(household_id, lower(name)) WHERE NOT archived` backs `goal-name-taken`.
- `CHECK (currency ~ '^[A-Z]{3}$')`. That `currency` matches the linked account's cannot be
  expressed in SQL, so the service enforces it. See [Edge cases](#edge-cases) for the hole this
  leaves, which the debt plan already has.
- `fk_goals_account … ON DELETE SET NULL (account_id)` and
  `fk_goal_contributions_transaction … ON DELETE SET NULL (transaction_id)`, both in the
  column-list form. A bare `SET NULL` would try to null `household_id` and the delete would fail.
- `ON DELETE CASCADE` from `goals` to both child tables, and to nothing else.
- Index `(household_id, goal_id, period)` on contributions, which serves `recorded(g, P)` and
  `savedBefore(P)`.

No `provenance` column. Every contribution in slice 11 is typed by a person. When the check-in
(slice 14) starts writing them, it adds the column with existing rows defaulted to `USER_LOGGED`.
That loses nothing, because every existing row *was* user-logged. This is ADR-0031's refusal of a
column that is always one value, and it is safe here as it was not for the debt plan's
`paid_from_account_id`, whose value could not be reconstructed later.

No `created_by` either. See [Known gaps](#known-gaps).

## API

All routes are authenticated, household-scoped from the caller's verified membership and never
from the request (ADR-0008 as amended by ADR-0017), identical under session and bearer (ADR-0018),
and CSRF-protected on the session transport for every non-`GET`. A collection is paginated with the
page envelope from `../guides/api-style.md`.

| Method | Path | Notes | Auth |
|---|---|---|---|
| `GET` | `/api/goals` | Paginated. `?includeArchived=true`. Each goal with `saved`, `remaining`, `reached`, this month's contribution, and its projection. Order: target month ascending with none last, then `lower(name)`, then `id` | any member |
| `POST` | `/api/goals` | `201` + `Location` (id only). `409 goal-limit-reached` past the cap. `409 goal-name-taken` | `OWNER` / `MEMBER` |
| `GET` | `/api/goals/{id}` | One goal, the same shape as a list row | any member |
| `PATCH` | `/api/goals/{id}` | `name`, `targetAmount`, `targetMonth`, `startingAmount`, `accountId`, plus a required `version`. Three-way on `targetMonth` and `accountId`: an absent field is unchanged, and `null` clears it. `currency` is not accepted | `OWNER` / `MEMBER` |
| `DELETE` | `/api/goals/{id}` | `204`. Takes its plan and contributions and no transactions | `OWNER` / `MEMBER` |
| `POST` | `/api/goals/{id}/archive` · `/unarchive` | Retire without deleting, as accounts and debts do. `200` with the goal | `OWNER` / `MEMBER` |
| `GET` | `/api/goals/{id}/contribution-plan` | The steps, paginated, oldest first | any member |
| `PUT` | `/api/goals/{id}/contribution-plan/{fromPeriod}` | `{"monthlyAmount": "165.00", "version": 4}`. From that month on, the plan is this amount, replacing later steps. Idempotent. `422 goal-plan-period-past` before the current period. Uses the goal's `version` | `OWNER` / `MEMBER` |
| `GET` | `/api/goals/{id}/contributions` | Paginated, newest first, ties broken by `created_at` then `id`. The order is the server's, as the debt payment history is | any member |
| `POST` | `/api/goals/{id}/contributions` | `{"amount": "110.00", "date": "2026-11-25", "note"?, "transactionId"?}`. `201` | `OWNER` / `MEMBER` |
| `PATCH` · `DELETE` | `/api/goals/{id}/contributions/{contributionId}` | Correct · remove. `PATCH` needs the contribution's `version`. Three-way on `note` and `transactionId`. A contribution of the right household but the wrong goal is `404`, the envelope-entry rule | `OWNER` / `MEMBER` |
| `POST` | `/api/goals/{id}/projection-preview` | `{"monthlyAmount": "165.00"}`. What the projection and required-monthly **would** be with that plan from the current period, computed from this goal's stored figures. Writes nothing | `OWNER` / `MEMBER` |
| `POST` | `/api/goals/projection-preview` | Pure arithmetic over `{currency, targetAmount, startingAmount?, targetMonth?, monthlyAmount?}`, for the add form, before a goal exists. Reads no household data and writes nothing | `OWNER` / `MEMBER` |

**Why the previews are `POST`s.** Both carry an amount, and a URL is the one part of a request every
layer logs. A reverse proxy's access log does by default, and so does Spring's `DispatcherServlet`
line at `DEBUG`. `debt-plan.md` moved its payment preview from `GET ?amount=` to a body after review
for exactly this. Goals start there. The consequence is the same one the debt plan accepted: a
non-`GET` is a write here, so a `VIEWER` is refused both previews. Previews serve only the add and
edit dialogs, which a `VIEWER` never sees.

**Both previews use the same projection code as `GET`**. The goal-bound one builds the goal's view
with the supplied plan substituted and discards it, so a preview cannot disagree with what saving
then reports. Assert this the way `DebtIT.the_preview_is_exactly_what_logging_then_records` does.

**`POST /api/goals` body.**

- Required: `name`, `targetAmount`.
- Optional: `targetMonth`, `startingAmount`, `monthlyContribution`, and either `currency` or
  `accountId`.
- When `accountId` is present, **`currency` is refused** (`400` naming it). The account owns that
  fact.
- A `monthlyContribution` creates the first step at the current period.
- No `householdId` and no user: both come from the caller's membership.

**Contribution `date`** must be no later than tomorrow on the server's clock. The one day of slack
is so a household east of the server is not refused at half past midnight. This is an assumption,
not a decision. A contribution is a record of something done, and intent belongs in the plan.

**Linking an account whose currency differs from the goal's** is `422 goal-currency-mismatch`.

**Response shape**, on every route that returns a goal:

```json
{
  "id": "…",
  "name": "Japan",
  "currency": "USD",
  "targetAmount": "3500.0000",
  "targetMonth": "2027-11",
  "startingAmount": "0.0000",
  "saved": "890.0000",
  "remaining": "2610.0000",
  "reached": false,
  "overdue": false,
  "accountId": null,
  "thisPeriod": {
    "period": "2026-08",
    "planned": "110.0000",
    "recorded": "0.0000",
    "stillToCome": "110.0000"
  },
  "monthlyContribution": "110.0000",
  "projection": {
    "projectedMonth": "2028-07",
    "projectedReason": null,
    "requiredMonthly": "163.13",
    "requiredReason": null
  },
  "archived": false,
  "createdAt": "…",
  "updatedAt": "…",
  "version": 4
}
```

`requiredMonthly` is at the currency's minor-unit scale, because it is an amount somebody will
type. Every other amount is at storage scale, as elsewhere. The name and the month are **separate
fields**. The canvas's *"Japan, Nov 2027"* is composed by the client from an i18n message, because a
pre-joined label cannot be reordered for a right-to-left locale (ADR-0023, the accounts `•1234`
rule).

**Bills & Income** (decisions 2 and 3). `GET /api/periods/{period}/summary` gains:

- **`goalsSetAside`**, the fifth term. `left` subtracts it. `PeriodSummaryService` composes it from
  `GoalService`, as it composes the other four, and runs no query of its own.
- **`debtPlanExtra`**: `monthlyAmount − Σ minimums` in the base currency, `null` with no plan or a
  plan below its minimums. It is **reported and not subtracted**, so the card can say when the
  extra exceeds what is left.

Both are base-currency figures, like `owed`.

**Error codes**, new to `ErrorCode`, since a code is part of the contract:

- `goal-limit-reached` (409)
- `goal-name-taken` (409)
- `goal-archived` (409)
- `goal-plan-period-past` (422)
- `goal-currency-mismatch` (422)

**Reason codes** travel beside a `null` figure and are **not** errors: `no-contribution`,
`beyond-horizon`, `reached`, `no-target-month`, `target-month-passed`.

Existing codes are used as elsewhere: `validation-failed`, `malformed-request`, `read-only-role`,
`not-a-member`, `csrf-token-required`, `not-found`, `stale-version`.

**No `Idempotency-Key`.** `api-style.md` asks for one on endpoints that move money, and this one
moves none. No plan-record endpoint in the codebase takes one yet: not envelope entries, not
occurrences, not debt payments. See [Accepted risks](#accepted-risks).

## UI

Two routes, both in the main nav as **Goals**, in the canvas's position between *Debt plan* and
*Insights*. At the narrow viewport the web app's nav carries Goals the same way it carries Debt plan
(decision 8). The Flutter tab bar is slice 15's call. Both ship at 390 and desktop width, light and dark (non-negotiable #10,
`design/README.md`'s two rules). Every string comes from `en.json`, and every amount and month
goes through the i18n formatters.

**There is no Goals artboard**, so this section builds from what exists and invents no values:

- **The goal row is the home card's row.** The name, the optional month, and *saved of target* sit
  on one line, with a progress bar under it. In light mode the canvas draws the bar's track in the
  colour of `--color-neutral-300` and its fill in `--color-accent-2-500`. The app's role token
  `--color-accent-2` already steps to its 400 tint in dark mode, which is rule 2. **No token carries
  a track colour on a dark ground.** Settle that in the design project before the bar is built
  (`design/README.md`: a missing value is a design-system question, not a licence to write a hex).
  Build the row as a shared component, so that when the home screen is built its Goals card reuses
  it rather than redrawing it.
- **"$890 / $3,500" becomes "$890.00 of $3,500.00"**, a sentence rather than a slash, the same
  change Bills & Income made for "still to pay". A slash reads as a ratio.
- **The bar's geometry is the one place an amount becomes a `number`**, through
  `core/money.ts#amountFraction`. That is the debt chart's precedent: a dimensionless ratio
  computed in exact `bigint` minor units. The bar is `aria-hidden`, and the *saved of target* text
  is its accessible equivalent. It is deliberately not a `<meter>`, whose `value` would need the
  amount itself as a JS number. Over 100% fills the bar and the text says the real figure. A
  negative `saved` shows an empty bar and the negative figure, coloured with `--color-accent-text`
  as a negative `left` is.
- **Cards, dialogs and the empty state** follow the patterns Bills & Income and `/debt-plan` already
  use: the card surface, the native `<dialog>` with trapped and restored focus and `aria-labelledby`,
  and amounts as `type="text"` with `inputmode="decimal"`, never `type="number"`.

### `/goals`, the list

Goals are grouped by currency only when there is more than one currency, the accounts rule, and
are never totalled across goals. There is no "total saved" figure (see
[Out of scope](#out-of-scope)). Each row shows the goal row above, plus a line from its projection
in words:

- *"At $110 a month you reach it in Jul 2028 — 8 months after Nov 2027. $163.13 a month gets you
  there by Nov 2027."*
- *"No monthly amount set."*
- *"Reached."*
- *"Overdue since Nov 2027 — at $110 a month you reach it in Jul 2028."*

Each reason code has its own sentence and is never shown as a blank. Then **Add a goal**, and
**Open →** on each row. Archived goals sit behind a toggle, muted, in their own section, as on
accounts.

**Empty state:** one line on what a goal is for, and **Add a goal**. Never a $0 of $0 bar.

### `/goals/{id}`, the detail

The route takes the **id**, never a slug, for the reason `debt-plan.md` gives: a slug leaks the
name into browser history and server logs, and goal names are the most disclosing field this
feature has. Regions, top to bottom and the same at both viewports:

1. **Header**: the name, the month, the linked account if any, and **Set money aside** and
   **Edit**.
2. **Progress**: the goal row, larger, with `remaining`, plus the linked account's balance where
   there is one, labelled as the account's and not the goal's.
3. **The plan**: the monthly amount in force, any future step (*"$165 from Sep 2026"*), and
   **Change** to set one. The required monthly sits beside it, stated rather than subtracted.
4. **History**: date, amount, note, and *withdrawal* on negative rows. It is paginated, in the
   server's order. The starting amount is the first line, labelled as what was already saved, and
   is not a contribution row.

**Set money aside** opens prefilled with this month's `stillToCome`, for example *"Set aside $110
for November"*. One tap records the plan's amount. Change the amount for a top-up. **Take money
out** is the same dialog for a withdrawal, not a minus sign typed into the first one.

**Add a goal / Edit / Change the plan** show the server's preview as the amount settles. A late
answer is dropped, a half-typed amount asks nothing, and a failed preview never blocks saving.
These are the three rules the debt payment sheet keeps. The browser computes no projection.

**A `VIEWER`** sees every figure and region and no control: no Add, no Set aside, no Edit, no
Change, no Archive.

**A user guide page ships with the slice** (non-negotiable #11). It is captured from the running UI
against `tools/ui/fixtures/demo-data.ts`, which needs goal fixtures. Take those from the arithmetic,
not from the canvas (see below).

## Security considerations

Threat-modelled before implementation (`/threat-model`, 2026-11-25), against the baseline in
`../architecture/security-model.md`. This touches **authorization and role enforcement, input
validation, and logging**. It does not touch authentication, sessions, tokens or CSRF
configuration. Slice 2 owns those, and these routes inherit them unchanged.

### Assets

- **The goal's name** is the sharpest asset here, as `servicer` was for debts. *IVF*, *Divorce
  lawyer*, *Bail*, *Move out*, *Mum's care home*: one string with no amount attached, and it still
  discloses a household's plans, health or relationships.
- **The target and the month** state what somebody intends to spend and when.
- **The contribution history** is a record of disposable income month by month: who saves, how
  much, and when it stopped.
- **The linked account** says where a household keeps its savings.
- **The notes** are free text, and may hold anything.
- **The fifth term on the period summary** is a household's total monthly saving in one figure.

### Entry points

The fifteen endpoints in [API](#api), plus the two new fields on
`GET /api/periods/{period}/summary`. There is **no background job, no scheduled task and no file
upload**. Path parameters are two UUIDs and one `YearMonth`. Query parameters are `page`, `size` and
`includeArchived`. There is no client-supplied sort field.

### Attacks considered

**Live, and controlled:**

1. *An attacker sends `POST /api/goals` with an `accountId` from another household and gets* a goal
   bound to a foreign account. The goal page would then disclose that account's balance and
   currency.
   **Control:** `accountId` is resolved through the membership-scoped account query before
   anything is written, and the composite foreign key makes the row unwritable otherwise. The same
   applies on `PATCH`.
2. *An attacker sends `POST /api/goals/{id}/contributions` with another household's
   `transactionId` and gets* a contribution that discloses that transaction's amount and date.
   **Control:** the transaction is resolved through the same scoped path a ledger read uses, the
   composite FK backs it, and a transaction already attached to a contribution is refused.
3. *A `VIEWER` sends `PUT /api/goals/{id}/contribution-plan/{fromPeriod}`, or records a
   contribution, and gets* the household's plan, and from the fifth term its left-over figure,
   changed on read-only access.
   **Control:** `memberships.requireWriteAccess` on every write in `GoalService`, **checked before
   the goal is looked up**, so a `VIEWER` also learns nothing about which ids exist.
4. *An attacker sends `PATCH /api/goals/{A}/contributions/{c}` where `c` belongs to goal `B` of the
   same household and gets* a contribution moved between goals or edited through the wrong parent.
   **Control:** the contribution is looked up by `(id, goal_id, household_id)`. The wrong parent is
   `404`. The envelope-entry rule.
5. *An attacker sets a monthly contribution of `0.0001` against a target of `999999999999999` and
   calls `GET /api/goals` and gets* a projection walk of about 10^19 months per goal on every list
   read.
   **Control:** the walk is capped at 600 months and answers `beyond-horizon`, asserted with a
   timing test so that removing the cap fails rather than merely slowing down. A walk after the last
   step is a constant tail and may be closed-form. The cap still binds.
6. *An attacker creates ten thousand goals, or a hundred thousand contributions on one goal, and
   then loops `GET /api/goals` and the period summary, and gets* O(goals × months) and an unbounded
   `SUM` from cheap writes.
   **Control:** per-household and per-goal caps, enforced with `countBy…` before insert the way
   `DebtService` does. Proposed: **50 goals** (`409 goal-limit-reached`) and **2,000 contributions
   per goal**, mirroring the debt caps. The plan steps are bounded by `UNIQUE (goal_id,
   from_period)` and the ban on past periods, so they grow by at most one a month. The 600-month
   horizon caps that too. Collections are paginated.
7. *An operator turns on `DEBUG` to chase a bug and gets* goal names, amounts and notes in the log.
   This is not an attack by a stranger, but it is the leak this codebase has had twice.
   **Control:** a redacted `toString` on every request and response DTO, entity, view and projection
   record. `org.hibernate.orm.core` and `ExceptionHandlerExceptionResolver` are already pinned to
   `INFO`. And **`GoalDisclosureIT`** captures the log at `DEBUG` across the whole journey,
   including a `CHECK` violation written straight to the table, because PostgreSQL puts the failing
   row in the error `DETAIL` (`../memory/gotchas.md`). A redacted `toString` is not a logging
   control by itself. Audit lines carry ids only.
8. *An attacker sends an amount as a JSON number, or at scale 9, or with a `currency` or
   `householdId` field, and gets* a double-rounded figure stored, or a field set that the API never
   offered.
   **Control:** `MoneyJsonModule` and `CoreConfig` refuse a number for an amount (`400`).
   `@Pattern` on every amount, with scale checked against the currency in the service.
   `FAIL_ON_UNKNOWN_PROPERTIES` refuses the extra fields. A blank `name` is
   `@Pattern(NOT_BLANK)`, Unicode-aware, so `"   "` is a `400` and not the `500` that slices 3 and
   10 both shipped once.

**Ruled out, with reasons:**

- **Reading another household's goal by id.** `404` for missing and for not-yours, as everywhere
  since ADR-0008. There is no oracle. The test still exists (`MoneyCrossHouseholdIT` gains goals).
- **Disclosure through the pure preview.** `POST /api/goals/projection-preview` reads no stored
  data. It discloses nothing because it knows nothing. The goal-bound preview reads only the
  caller's goal, through the scoped lookup.
- **Cost through the previews.** Each is one bounded walk with no retained rows. Unlike
  `/api/debt-terms/solve` there is no bisection and nothing multiplies. Not rate-limited, for that
  reason. If a future change adds interest, this needs revisiting.
- **A background job posting contributions.** There is none. No scheduler means no scheduler to
  attack, and no figure that moves while nobody is looking.
- **Moving money.** Nothing here talks to a bank or writes a transaction. Budget Owl never moves
  money (`../product/vision.md`).
- **Owl writing to goals.** Not in this slice. When slice 17 lands, its *"$110 → $165"* proposal is
  a proposal until a human confirms it (`security-model.md`, ADR-0027). It reaches the same
  role-checked `PUT` a person uses, and gets no route of its own.
- **XSS through a goal name or note.** Angular interpolation escapes, and nothing here needs
  `bypassSecurityTrustHtml`.
- **Sort or filter injection.** There is no client-supplied sort. The order is fixed by the server.

### Controls, and where each is enforced

| Control | Layer |
|---|---|
| Household scoping on every read and write | Service (membership-scoped queries) **and** composite FKs |
| `VIEWER` reads all, writes nothing, and gets `403` before any lookup | Service (`requireWriteAccess` first) |
| Foreign ids (`accountId`, `transactionId`) resolved in the household | Service, backed by composite FKs |
| A contribution belongs to the goal in its path | Repository finder on `(id, goal_id, household_id)` |
| Projection walk ≤ 600 months | Service, with `beyond-horizon` |
| Goals ≤ 50, contributions ≤ 2,000 per goal | Service, `409` |
| Paginated collections | Controller, page envelope |
| Amounts are strings, at the currency's scale | `MoneyJsonModule`, `@Pattern`, service scale check, `NUMERIC(19,4)` |
| Positive target, non-negative start and plan, non-zero contribution | Bean Validation **and** `CHECK` |
| Plan changes only from the current period | Service, `422 goal-plan-period-past` |
| Goal currency equals the linked account's | Service, `422 goal-currency-mismatch` |
| Amounts kept out of URLs | Previews are `POST` bodies |
| No names, amounts or notes in logs | Redacted `toString` everywhere, plus the pinned loggers, proved by `GoalDisclosureIT` |

### Accepted risks

- **Every member sees every goal**, including one named for something they would not want another
  member to see. Roles are household-wide and the operator sees everything regardless (ADR-0026).
  The invitation already says so before anyone joins. Per-member goals are out of scope.
- **A double-tapped "Set money aside" records two contributions.** There is no `Idempotency-Key` on
  any plan-record endpoint yet. The duplicate is visible in the history and removable, and the
  dialog disables its button while the request is in flight. That is a courtesy, not a control.
- **The caps can be exceeded by one under concurrent inserts** (count, then insert). The cap bounds
  cost and does not police an invariant, so one over is harmless.
- **A household can tell itself a comfortable lie**: contributions that never happened. The app
  records what it is told, and the optional `transactionId` is how a household shows its working.
  It is their plan, not ours.

## Edge cases

- **A goal already part-funded on day one.** Use `startingAmount`, never a contribution dated
  today. The latter would subtract the whole sum from this month's `left`. The add dialog asks
  *"Already saved"* for exactly this.
- **A back-dated contribution** into a past month raises that month's `goalsSetAside` when its
  recorded total passes the plan. That is correct, and the same as a back-dated bill payment.
- **The month a goal is reached.** Its plan still counts that month, and stops from the next.
  Reaching it mid-month never refunds the month.
- **A plan of zero** pauses the goal. The projection says `no-contribution` and the term counts
  only what is recorded.
- **A target month in the past at creation** is accepted, and the goal is simply overdue. It is not
  worth an error code.
- **A target month equal to the current month**: `n = 1`, and the required figure is everything
  still remaining this month.
- **Concurrent edits.** The goal and each contribution carry `version`, so `409 stale-version`, and
  the page re-reads and says what changed, the accounts pattern. Two members recording
  contributions at once both succeed, as envelope entries do.
- **Deleting the linked account** nulls `account_id`, and the goal is untouched. Archiving it
  changes nothing.
- **Deleting the linked transaction** nulls `transaction_id`, and the contribution and `saved` stand.
- **A linked account that has no transactions can still change currency.** `AccountService` refuses
  a re-denomination only once an account has transactions, so an empty account linked to a goal
  could move from GBP to EUR and leave the goal disagreeing with it. **The debt plan has the same
  hole today.** `debt-plan.md` and `DebtLinks` say `account_currency_immutable` keeps the debt's
  currency true, and it does not for an empty account. Recommended fix, for whoever owns accounts:
  refuse a currency change while any debt or goal links the account (`409
  account-currency-immutable`, the existing code). Not something this slice should change silently.
- **Mixed currencies.** Grouped per currency on `/goals`, excluded from `goalsSetAside` when not the
  base currency, and never converted.
- **Archived goal with a contribution this month.** `left` rises when it is archived. The page says
  so.
- **A JPY goal**: zero minor-unit digits, so `requiredMonthly` has no decimals, and a scale-2 amount
  is a `400`.

## Out of scope

- **Interest, returns and growth on savings.** A goal is a sum of what was put in. Modelling a
  savings rate needs a rate per account and a compounding rule, and an investment goal needs
  market values, which `accounts.md` already defers.
- **Moving money, or writing a transfer when a contribution is logged.** ADR-0035's reasoning, and
  the vision: we never move money.
- **Allocating the surplus.** Check-in step 4 (slice 14) will call `POST …/contributions`. This slice
  builds the endpoint, not the step.
- **Owl's proposed change** (slice 17, optional, ADR-0027).
- **The home screen's Goals card.** The home screen is not built and is not a roadmap slice. The
  goal row component is built to be reused there.
- **Onboarding's "suggested first goal".** Onboarding is not a slice. `POST /api/goals` is all it
  will need.
- **Ranking goals, or rolling one goal's contribution into the next.** No design asks for it, and
  ADR-0036's flow-down belongs to a ladder somebody chose to have allocated.
- **Rolling unspent envelope money into a goal.** Rollover is not built (ADR-0031), and the
  check-in's *"roll it over"* is slice 14's to define.
- **Per-member or private goals.** Everything is household-wide, as envelopes are.
- **A "total saved" across goals.** It is a currency sum with nothing to act on, and the designs do
  not show one.
- **Icons, colours, images per goal.** An image is a file upload surface, and an icon set fetched
  from anywhere is non-negotiable #9.
- **Reminders** ("you haven't set aside this month"). Nothing in the core may need a service we
  run. That is a later, opt-in decision, as on Bills & Income.
- **Debt-payoff goals.** Paying something down is the debt plan's job.

## Known gaps

- **Nothing records who logged a contribution or changed the plan.** This is the same gap bills,
  transfers, occurrences and debt payments have. The column can be added later without losing
  anything that exists.
- **A transaction may be attached to a goal contribution and also to an envelope entry, a debt
  payment or a bill occurrence.** Uniqueness is per table, as it already is between those three.
  In practice a transfer leg into savings is not an envelope expense, but nothing enforces that.
- **`goalCount`-style figures are not on the summary.** If one is added, it will have the same
  every-currency-versus-base-only mismatch `debtCount` has (`debt-plan.md`, Known gaps).
- **The linked account's balance and the goal's `saved` can disagree**, and the page shows both
  rather than reconciling them. This is the same latitude ADR-0035 gives debt and account
  balances.
- **The UI corrects a contribution by removing it and recording it again.** `PATCH
  /api/goals/{id}/contributions/{contributionId}` is built and tested, three-way on `note` and
  `transactionId`, but the history has a **Remove** action and no **Edit** one. The UI section asks
  for no per-row edit, and a correction dialog is a contained follow-up.
- **There is no UI for attaching a transaction to a contribution.** The API accepts and resolves
  `transactionId`, scoped and unique, as the debt payment API does. The picker arrives with
  matching, as it will for debt payments and envelope entries.
- **"Use this £110 for another goal" is not built.** The doc already calls it optional and says it
  can ship later without any API change. A reached goal shows **Reached.** and the Archive action.
- **The same linked-account currency hole the debt plan has** (see [Edge cases](#edge-cases)) is
  still open, as the doc recommended: an empty account linked to a goal can change currency
  underneath it. Not changed silently here.
- **The dark-mode track of the progress bar is a role alias, not a design-system value.** No token
  carried a track on a dark ground, so `--color-goal-track` is `--color-neutral-300` in light and
  `--color-divider` in dark. It composes existing tokens and invents no value. It still belongs in
  the design project (`design/README.md`, Known gaps).
- **A list row never names the monthly amount in its projection sentence** (decision 20). It says
  *"Keeping to its plan, you reach it in…"* for every goal, because the list response carries no
  steps. A `planChangesLater` flag on the goal response would restore *"At $200.00 a month…"* for
  the goals with one amount.
- **The set-money-aside date defaults to the browser's today.** In the guide captures, the demo
  fixtures stand in August 2026 while the browser's clock does not, so the date field shows the
  machine's real date beside an August "still to come". Against a real server, the two agree.

## Where the designs and the docs disagree

- **`../domain/model.md`'s `Goal` paragraph is superseded by this doc, on four points**:
  - `targetDate` becomes `targetMonth` (decision 5).
  - `monthlyContribution` becomes an effective-dated plan (decision 4).
  - The linked `accountId` is informational, not the source of "computed progress" (decision 1).
  - The paragraph gains `startingAmount`.

  The domain model should be updated in the same change that builds this, not now.
- **The canvas's figures do not reconcile, and are not test fixtures.** *"At your current pace
  you'll have $1,240 in the Japan goal by Nov 1"* does not follow from $890 and $110 a month:
  three more months gives $1,220. The $165 Owl proposes is a rounding-up of the $163.13 that
  reaches $3,500 by Nov 2027 from August 2026, which is the right shape. The persona is invented
  (`design/README.md`), so compute the fixtures.
- **"Japan, Nov 2027" is one string on the canvas** and two fields here (ADR-0023).
- **"$890 / $3,500"** becomes *"$890.00 of $3,500.00"*.
- **The mobile tab bar has no Goals** (Home · Money · Bills · Insights). The desktop nav does. The
  web app's narrow nav carries it anyway (decision 8).
- **`../domain/model.md` says goals absorb surplus from the check-in.** They will, in slice 14,
  through the contribution endpoint built here.

## Decisions

Decided 2026-11-25 by the product owner. Each one took the doc's recommendation.

1. **Where does a goal's progress come from?** Recorded contributions (option a). Each is a plan
   record, like a debt payment, and never writes a transaction or a transfer. The linked account is
   optional and informational.
   *Reason:* one savings account often holds several goals, so its balance cannot say how much
   belongs to each. Rejected: (b) the linked account's balance, and (c) counting the planned amount
   as saved each month, which reports money nobody confirmed moving.
2. **Does "What's left this month" lose a fifth term for goals?** Yes.
   `left = income − bills − envelopesAllocated − debtMinimumsNotInBills − goalsSetAside`, where each
   unarchived, base-currency goal counts the greater of its plan and what was recorded for the
   month.
   *Reason:* a planned commitment counts whether or not it has been done, as bills and envelopes
   already do. Rejected: leaving `left` alone with an "after goals" secondary line, which overstates
   the headline.
3. **How do goals interact with the debt plan's monthly extra?** No automatic coupling.
   `monthlyAmount` stays the household's own figure, goals never become rungs, and the summary
   reports `debtPlanExtra` beside `left` so both pages can say when the extra exceeds it.
   *Reason:* deriving the extra from `left` would move every payoff date whenever an envelope
   changed and would break ADR-0036's "spent exactly every month" invariant.
   **Copy:** keep the designs' *"the extra comes from what is left after your goals"*. It is
   simply true once goals come off `left` (decision 2), and the debt plan page already says it.
   It describes the arithmetic and ranks nothing.
4. **One monthly figure, or a plan with effective months?** Effective months:
   `(fromPeriod, amount)` steps, changed only from the current period forwards.
   *Reason:* with one figure, raising it in September rewrites August's headline. Rejected: a single
   `monthlyContribution` with retroactive re-projection accepted as a known gap.
5. **Is the target a month or a day?** A month, `targetMonth`, `"2027-11"`.
   *Reason:* contributions are monthly, so a day is false precision, and the designs label the goal
   *"Nov 2027"*.
6. **Can money come out of a goal?** Yes. A contribution is signed and non-zero, a negative one is a
   withdrawal, and `saved` is never clamped.
   *Reason:* an emergency fund exists to be spent. Rejected: refusing anything that takes `saved`
   below zero (`422`), which needs the check on every edit and delete too.
7. **What happens when a goal is reached?** `reached` is derived. The goal stays listed, takes
   further contributions, and its plan stops counting towards `left` from the month after. Nothing
   flows to another goal automatically.
   *Reason:* nobody ranked the goals, so ADR-0036's flow-down does not apply, and a stored flag
   would need something to keep it true.
8. **Where is Goals on a phone?** The web app's narrow-viewport nav carries Goals as it carries
   Debt plan. The Flutter tab bar (slice 15) decides separately.
   *Reason:* a screen a phone user cannot reach fails rule 1 of `design/README.md`.

### Implementation decisions (slice 11)

Made while building, where the spec left a gap or where following it literally gave a wrong
answer. Each is the smallest change that fixed it, and each is tested.

9. **A net withdrawal counts as nothing towards this month's plan in `stillToCome`.** The spec
   reads `stillToCome = max(planned − recorded, 0)`. Taken literally, a £500 withdrawal against a
   £110 plan makes £610 "still to come", so the projection would assume the withdrawal is put back
   this month while `goalsSetAside` reserves only £110. The two halves of one page would disagree
   about one month. It is now `max(planned − max(recorded, 0), 0)`, which gives the spec's own
   110 / 60 / 0 / 0 for recorded 0 / 50 / 110 / 200, and 110 for −500. The closed-form candidate for
   `requiredMonthly` uses the same rule. Found by `GoalProjectionTest`, which still tests the spec's
   four cases and now a fifth.
10. **A new reason code, `not-reachable-by-target-month`.** When the target month is this month,
    the month began with the goal reached, and a withdrawal has since taken it below, this month's
    plan does not count, so no monthly amount reaches the target by the month named. The spec's
    reason codes have no word for that, and leaving it `null` would break "never a blank". It has
    its own sentence: record the rest directly.
11. **`requiredMonthly` answers `beyond-horizon` for a target month more than 600 months away.**
    The search uses the same capped walk, so it cannot verify an answer past the cap. The spec
    names the code and does not assign it to this case.
12. **A target month must fall in 1900–9999** (`400 validation-failed`, naming the field), as must
    a plan's `fromPeriod`, and a contribution may not be dated before 1900. The spec bounds none of
    them. An unbounded `YearMonth` reaches the database's date range and the month arithmetic, and
    `Horizon`'s overflow in slice 10 was exactly this.
13. **The goal-bound preview also takes `targetAmount`, `startingAmount` and `targetMonth`**
    (three-way), each optional, beside `monthlyAmount`. The spec gives it `monthlyAmount` only, but
    says the **Edit** dialog shows the server's preview, and an edit changes those three. Absent
    fields keep the goal's own values, so `{"monthlyAmount": …}` behaves exactly as specified.
14. **The plan is changed from this month or next month, not from an arbitrary month**, in the UI.
    The API accepts any month from the current one on. The plan dialog's live preview projects a
    plan from this month, which is what the goal-bound preview computes, and says in words that
    this month is unchanged when "from next month" is chosen.
15. **A plan `PUT` that changes nothing writes nothing** and does not bump the goal's `version`. A
    step equal to the amount already in force is not stored either. Replaying the same instruction
    with the current version is then a no-op, which is what "idempotent" can mean for a request
    guarded by an optimistic lock.
16. **A transaction already attached to a contribution is `409 conflict`**, the code debt
    payments use for the same case. The spec says it is refused and names no code.
17. **The summary carries `goalsCounted` and `debtPlanExtraExceedsLeft`** beside the two fields the
    spec names. The first is the card's own line. The second keeps the comparison on the server, so
    the browser compares no amounts to decide whether to say the sentence.
18. **A `POST /api/goals` Location names the id**, and the body is the created goal, as for every
    other create. That is how "id only" is read: the name, the most disclosing field, never appears
    in a URL.
19. **The cap counts archived goals**, as the debt cap counts archived debts: the rows are still
    there to be projected the moment anything un-archives them.
20. **The projection names this month's amount only when no later step changes it.** The UI
    section's example, *"At $110 a month you reach it in Jul 2028"*, assumes one amount
    throughout. The walk uses every step, so with $165 from September, Japan reaches its target in
    December 2027 on $110 and then $165, and *"At $110.00 a month you reach it in December 2027"*
    was false. The first capture for the user guide showed exactly that sentence. Where a later step
    exists, the sentence is *"Keeping to its plan, you reach it in December 2027 — 1 month after
    November 2027."* The detail page and the edit preview know the steps and choose. The add and
    change-the-plan previews are always one amount, because a plan change replaces every later step.
    **A list row always uses the plan wording**, because `GET /api/goals` does not return the steps.
    A boolean on the goal response would let the list name the amount again. It was left out to keep
    the API as the spec states it; see Known gaps.

## What slice 11 has built

**Schema.** `V18__goals.sql`: `goals`, `goal_contribution_steps` and `goal_contributions`, with
every foreign key composite on `(id, household_id)`, `UNIQUE (id, household_id)` on `goals` in the
same migration, the `SET NULL (column)` form for the account and the transaction, every `CHECK` the
data model lists, the partial unique index behind `goal-name-taken`, and the
`(household_id, goal_id, period)` index.

**Backend**, `com.budgetowl.goal`:

- `GoalLedger` — one goal's target, starting amount, plan and per-month totals, and **the one
  definition** of `planned`, `stillToCome` and `monthContribution`. The list, the detail page, both
  previews and the period summary all read it.
- `GoalProjection` — the walk, capped at 600 months, and the required-monthly search. The closed
  form proposes a candidate, and the walk accepts it only if it arrives on time and one minor unit
  less does not. Otherwise a bisection over the walk decides. The walk is authoritative:
  `HALF_EVEN` in the candidate is caught by the walk, not shipped.
- `GoalService` — every write checks the role **before** the goal is looked up. Every read is
  membership-scoped. Accounts and transactions are resolved through the household. Caps are 50
  goals per household and 2,000 contributions per goal. Amounts are refused when more precise than
  the currency's minor unit.
- `GoalController` — the fifteen routes, not `@Validated`. Both previews are `POST`s.
- `PeriodSummaryService` — `goalsSetAside` is one `SetAside` object, both reported and subtracted.
  `debtPlanExtra` comes from `PayoffPlanService.extraFor`, computed from the facts the debt
  figures already read, over the plan's own debts.
- Five error codes in `ErrorCode`, each with an English sentence in `en.json`.

**Frontend**, `features/goals` plus the shared `GoalProgressComponent` (the home card's row, built
to be reused):

- `/goals` — grouped by currency only when there are several, never totalled. Each row has its
  projection in words. There is an empty state, and an archived section behind a toggle.
- `/goals/{id}` — header, progress (with the linked account's balance labelled as the
  account's), the plan with scheduled steps and the required figure, and the history. The starting
  amount is its first line and not a row, and a withdrawal says so in words.
- Dialogs: add and edit (live server preview), change the plan (this month or next, live preview),
  set money aside (prefilled with `stillToCome`), take money out (its own dialog, sent negative).
  Archive, restore and delete use the shared confirm dialog, whose text says what happens to
  "left".
- Bills & Income shows **Goals** as the fifth term with a note, and both it and `/debt-plan` say
  in words when the plan's extra exceeds `left`.
- Nav: **Goals**, between Debt plan and Envelopes, at both viewports. A `period` pipe was added to
  the core formatters.

**Tests.** `GoalProjectionTest` (33), `GoalTest` (10), `GoalIT` (87, including the fifteen-route
sweep), `GoalPeriodSummaryIT` (16), `GoalLimitsIT` (6), `GoalDisclosureIT` (3), the goal block of
`MoneyCrossHouseholdIT` (27 more), and `DemoFixtureIT`'s third scenario. Frontend:
`goal-data.spec.ts`, `goals.page.spec.ts` and `goal-detail.page.spec.ts` (40), plus new cases in
`bills.page.spec.ts` and `debt-plan.page.spec.ts`. In the browser: `tools/ui/specs/app/goals.spec.ts`,
at both viewports and in the dark theme. `PeriodSummaryIT` and `DebtPeriodSummaryIT` pass unchanged.

**Proved to fail.** Each of these was broken on purpose, the named test failed, and the code was
restored:

| Broken | Caught by |
|---|---|
| The 600-month cap raised to 10^8 | `GoalProjectionTest` — both timed horizon tests time out |
| The required candidate returned unverified, rounded `HALF_EVEN` | `GoalProjectionTest` — the 163.13, yen and boundary cases |
| `contribute` looked the goal up before checking the role | `GoalIT.a_viewer_gets_403_for_an_id_that_does_not_exist_too` |
| `left` reported the fifth term and did not subtract it | Five `GoalPeriodSummaryIT` cases |
| `CreateGoalRequest` printed every component | `GoalDisclosureIT` at `DEBUG` |
| The goal-bound preview built its ledger without the contributions | `GoalIT.the_goal_preview_is_exactly_what_setting_that_plan_then_reports` |
| `contribute` trusted `transactionId` instead of resolving it in the household | `MoneyCrossHouseholdIT.cannotBindOneOfOurGoalsToAnotherHouseholdsAccountOrTransaction` |
| A contribution was found by household alone, not by its goal | `GoalIT.a_contribution_of_the_right_household_but_another_goal_is_not_found` |
| The bar was not clamped | `goals.page.spec.ts` — over 100% |
| A late preview answer was not dropped | `goals.page.spec.ts` — the late-answer case |
| The detail page named this month's amount even with a later step | `goal-detail.page.spec.ts` — names the amount only when no later step changes it |

**Demo fixtures.** `DemoFixtureIT.the_committed_goal_fixtures_are_what_the_application_returns`
seeds four goals, with the demo debts, through the real endpoints, with the clock at 2026-08-20,
and writes `tools/ui/fixtures/goal-api.generated.ts`. That file holds every goal, plan and history,
every preview the dialogs ask, and August's summary. `demo-data.ts` takes `goalsSetAside`,
`goalsCounted` and `debtPlanExtra` from it and subtracts the fifth term from its own `left`. The
canvas's figures do not reconcile, so the goals keep its shape and the arithmetic is the
application's: Japan, $890 of $3,500 by November 2027 at $110 and then $165 from September,
reaches it in December 2027 and needs $163.13 a month.

## Testing notes

Beyond the mandatory list in `../guides/testing-style.md`: `401`, `404` for not-yours, `400` on bad
input, the happy path, identical under session and bearer, CSRF `403`, and a `VIEWER` `403` on every
write. The arithmetic is the part that will be wrong silently.

- **`GoalProjectionTest`**, the arithmetic as a unit with no database:
  - `saved = startingAmount + Σ contributions` exactly, withdrawals included.
  - The projected month is the first month at which the walk reaches the target, and the month
    before falls short.
  - **The required-monthly boundary.** At `requiredMonthly` the target is reached in or before `T`.
    At one minor unit less, it is not. Assert this in GBP (2), JPY (0) and KWD (3). This is the
    debt solver's boundary test, and it is the test that catches `HALF_EVEN` sneaking in.
  - **The this-month rule.** With a plan of 110, recorded amounts of 0, 50, 110 and 200 give a still
    to come of 110, 60, 0 and 0. The required figure switches to the months after `C` once this
    month's slot is filled.
  - Effective-dated steps: a step in a future month changes the walk from that month and not
    before.
  - A zero plan gives `no-contribution`. A reached goal gives `reached`. A past `T` gives
    `target-month-passed`, with the projected month still present.
  - **The 600-month cap answers `beyond-horizon`, timed**, so removing it fails rather than slowing
    down. The input is the attack 5 case: a plan of `0.0001` against `999999999999999`.
- **`GoalPeriodSummaryIT`**, the fifth term, using the designs' Japan figures (plan 110):
  - Recorded 0 → 110. Recorded 50 → 110. Recorded 200 → 200. A withdrawal of −500 → 110. A
    paused plan with a withdrawal → 0.
  - A goal that began the month reached → only what was recorded.
  - An archived goal → 0, and archiving moves `left`.
  - A non-base-currency goal → 0.
  - `startingAmount` is never counted in any month.
  - **A plan step set for next month leaves this month's `left` unmoved**, which is the property
    decision 4 exists for.
  - `left` subtracts `goalsSetAside` and not, say, the recorded figure alone: assert the number,
    not "it went down".
  - `PeriodSummaryIT`'s existing cases stay green with no goals.
  - `debtPlanExtra` is reported and **not** subtracted.
- **`GoalIT`**:
  - Create, read, list order (month ascending with none last, then name, then id), and three-way
    `PATCH` on `targetMonth` and `accountId`. An absent field and a `null` are different requests
    with different outcomes.
  - `currency` refused alongside `accountId`, and `goal-currency-mismatch` on linking.
  - The plan `PUT` is idempotent, replaces later steps, and refuses a past month.
  - An archived goal refuses contributions and plan changes.
  - Deleting a goal takes its steps and contributions and **no transactions**.
  - Deleting a linked transaction leaves the contribution and `saved`.
  - A transaction cannot be attached to two contributions.
  - Amounts keep every digit across the wire, and a JSON number is a `400`.
  - **Each preview returns exactly what saving the same plan then reports.**
- **`GoalIT.everyRoute()`** sweeps all fifteen routes for `401`, CSRF, `VIEWER` `403` (with an
  unknown id too, proving role-before-lookup), and both transports.
  **`MoneyCrossHouseholdIT`** gains another household's goal, contribution, account and transaction
  as `404` on every route, plus the wrong-parent contribution.
- **`GoalLimitsIT`**: past 50 goals, `409 goal-limit-reached` writes nothing. Past 2,000
  contributions, refused. Collections honour the page envelope with more rows than a page holds.
- **`GoalDisclosureIT`**: at `INFO` and at `DEBUG`, across create, list, update, contribute, a
  refused `VIEWER` write, a `404`, a `409 stale-version`, an unparseable body and a `CHECK`
  violation written straight to the table. No goal name, amount or note appears in the captured
  log.
- **`GoalTest`, `GoalContributionTest`**, entities with no database:
  - A blank name is refused.
  - A zero contribution is refused.
  - Currency cannot change.
  - No `toString` prints a name, amount or note.
- **`goal-data.spec.ts`**:
  - Amounts leave as strings.
  - Three-way fields send `null` and omission differently.
  - The previews are `POST`s with the amount in the body and never in a URL.
  - A late preview answer is dropped.
  - Nothing computes a projection client-side.
- **`goals.page.spec.ts`, `goal-detail.page.spec.ts`**:
  - Every reason code renders a sentence and never a blank.
  - Over 100% fills the bar and states the real figure. A negative `saved` renders negative.
  - No target month means no month in the label.
  - The starting amount is labelled and is not a contribution row.
  - The empty state offers **Add a goal** and no $0 bar.
  - A `VIEWER` has every figure and not one control.
- **`tools/ui-check.sh`**: both routes, both viewports, light and dark, with axe, and the
  screenshots read rather than trusted (non-negotiable #8). The bar's dark-mode track is the thing
  most likely to be wrong.
