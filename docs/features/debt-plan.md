# Feature: Debt plan and debt detail

- **Slice:** 10
- **Status:** Done (slice 10). The API, schema, both screens, the log-a-payment and confirm sheets,
  the **Owed** card, the demo fixtures and the user guide have all shipped. See
  [What slice 10 has built](#what-slice-10-has-built) and [Known gaps](#known-gaps)
- **Owner:** Repository owner
- **Last updated:** 2026-11-27 (ADR-0043: interest is charged once per debt per month)
- **Depends on:** accounts (3), transactions (5), bills & income (7)
- **Related:** ADR-0006 (money), ADR-0017 (households), ADR-0022 (currency), ADR-0025 (a plan
  record is not a transaction), ADR-0031 (one transaction, one entry), ADR-0032 (projected, not
  generated), ADR-0033 (a linked pair), ADR-0043 (interest once per debt per month),
  `bills-and-income.md`, `transfers.md`,
  `../domain/model.md`
- **Designs:** `design/canvas/` section `v1` — six artboards: *Debt plan, two structures* ·
  *Debt plan absorbs the debt table* · *Debt detail — terms in, payments out* · *Debt detail on
  mobile* · *The three ways a balance moves* · *The same two actions on desktop*. Onboarding step
  2 (*What the steps are*) and the Bills & Income **Owed** card are the two places outside those
  artboards that this slice touches.

## Purpose

A debt is **a balance you are reducing, with terms**. Bills say what leaves on a date and
envelopes say what you chose to spend; a debt is the only one of the three where the number you
care about is going *down*, and where arithmetic — not a schedule — decides when it reaches zero.

Two screens, one question each:

> **Debt plan:** in what order do I attack these, and when am I free?
> **Debt detail:** what are this one's terms, and what has actually been paid against it?

The plan answers the first by ranking the household's debts and projecting both strategies side by
side. The designs refuse to pick for them — *"Avalanche costs $780 less. Snowball clears the Visa
three months sooner, which some people need to keep going. Either is a good answer."* That
sentence is the feature's posture: compute both, show both, let the household choose.

The detail page answers the second with a **terms solver** — the household supplies the numbers it
can read off a statement and Budget Owl derives the rest, labelling every derived field `Solved` so
a figure the app worked out is never mistaken for one the household asserted. It is the same
discipline `statusSource` gives a bill occurrence and `balanceSource` gives a balance, applied to
arithmetic.

## User stories

- As a member, I want my debts in one place with their balances and rates, so I can see what I owe
  without opening three lender portals.
- As a member, I want to be told which order to pay them in, and why that order and not the other
  one.
- As a member, I want to know what paying £50 more a month would buy me, before I commit to it.
- As a member, I want to type the four numbers my statement gives me and have the app work out when
  this is gone — not be asked for the one number I do not have.
- As a member, I want to log a payment I made and see the balance move, with the interest and
  principal split shown, so the balance has an audit trail rather than being a figure I typed.
- As a member, I want my card's minimum counted **once** — it is already a line on Bills & Income
  and I do not want to be told I owe it twice.
- As a `VIEWER`, I want to see the plan and every balance, and change nothing.

## Prerequisites

Accounts (3) for the optional link to a credit-card account, transactions (5) for the optional link
from a payment to the real movement, and bills & income (7) for the minimum-counted-once rule and
the **Owed** card. Not connections, not import, not the check-in: a debt plan must be fully usable
on an instance that has never spoken to a bank. The designs say so in as many words — the student
loan in every artboard is *"Nelnet · manual entry"*.

## Rules and behaviour

### The terms solver

This is the hardest part of the slice and the reason this doc exists before any code.

**Four quantities are mutually constrained by one equation:**

| Quantity | In the API | Notes |
|---|---|---|
| Current balance | `balance` | **Always supplied. Never solved** — see below |
| Annual rate | `apr` | A percentage, e.g. `"22.900"`. Nullable: null means solved |
| The payment being made | `payment` | Not the minimum. Normally **supplied by the plan** |
| The horizon | `monthsLeft` / `payoffMonth` | **One unknown with two faces**, not two fields |

One equation and four quantities means **three degrees of freedom**: give any three and the fourth
follows.

**The solve target is whichever one slot of `apr`, `payment` and the horizon is not supplied.**
"Supplied" means entered by the household — or, for `payment` only, assigned by the payoff plan.

- With a plan in place, `payment` is the plan's allocation for that debt (its minimum, plus the
  surplus if it is rung 1), so **the horizon is the default solve target**. With no plan yet — a
  debt one minute old, created from onboarding's balance, APR and minimum — `payment` falls back to
  `minimumPayment`, which is what makes *"enough to rank them"* true.
- Entering a horizon (*"I want this gone by Dec 2027"*) makes **`payment`** the solve target. The
  plan then shows that debt's required payment instead of its allocation, and says so when its
  monthly amount cannot cover it.
- Entering both a horizon and a payment makes **`apr`** the solve target — the installment-loan case
  where the statement gives a balance, a payment and a term but never names the rate.
- An entered `payment` overrides the plan's allocation and becomes that debt's floor in the plan.
- Entering all three is `422 terms-over-determined`.

Three fields in the designs' *Loan terms* panel look like solver inputs and are not:

- **`minimumPayment`** is always entered. It never participates in this debt's own projection; it
  is the floor the plan allocates to every debt below rung 1, and the second input to the *"if
  you'd paid the minimum"* comparison line on the chart.
- **`originalBalance`** is a progress baseline — *"$4,470 paid off · 42% of $10,650"* — and
  nothing else. It is optional, and progress is simply absent when it is absent.
- **`totalInterestLeft`** is always derived. It is a sum over the projection.

**Every cell therefore carries one of three labels, not two:** entered, `Solved`, or *from your
plan*. That is what makes the artboard's own count — *"4 entered · 3 solved"* over eight cells —
add up: entered are original balance, APR, current balance and minimum; solved are months left,
payoff date and total interest left; and *"Payment I'm making $445.00 / mo"* is the eighth, which
comes from the plan and is neither.

**Why the balance is never solved.** It is the one number a household can read off a statement, and
it is the number `balanceSource` exists to qualify. An app that *derives* what you owe from a rate
and a payment schedule has replaced a fact with an inference and then shown it in the headline
position. Everything else on the page can be an inference as long as it says so; this one may not
be.

### How a projection is computed

Monthly compounding on the balance, interest first, payment at the end of the period:

> `interest(m) = round(balance(m-1) × apr ÷ 100 ÷ 12, minor unit, HALF_EVEN)`
> `balance(m)  = balance(m-1) + interest(m) − payment`

The debt clears in the first month `m` where `balance(m) ≤ 0`. *Minor unit* is the currency's own
(`Currency.getDefaultFractionDigits()`: two places for USD, none for JPY), and four places for a
currency that defines none; the result is still stored at `NUMERIC(19,4)` (decision 9).

Three things follow, and all three are deliberate:

1. **The projection is computed by iterating that recurrence, not by the closed-form annuity
   formula.** The closed form disagrees with a row-by-row ledger by a few cents, and a few cents
   compounded eventually disagrees by a whole month — so the payment table on screen and the payoff
   date above it would contradict each other. The iteration is the definition.
2. **Solving for a non-horizon field is a bounded search, not algebra.** `payment` and `apr` are
   each monotonic in the number of months to clear, so each is found by bisection over the
   iteration, seeded from the closed form, to a stated tolerance: `payment` to the currency's scale
   and `apr` to `0.001` percentage points. A solved `apr` is an approximation of a real rate and the
   UI says `Solved`, which is exactly what that label is for.
3. **Projections are approximate, and the product says so out loud.** Real lenders accrue daily,
   bill on statement cycles, and change rates. Monthly-on-the-balance is a simplification chosen
   because it is the one a household can check with a calculator, and because the designs already
   state it as the rule — *"Interest is balance × APR ÷ 12 until a statement says otherwise — then
   the statement wins and the gap is logged as a correction."* It must never be presented as what
   the lender will charge.

`apr ÷ 100 ÷ 12` is the **nominal** monthly rate, not the effective `(1 + apr)^(1/12) − 1`. That
is what
card issuers quote, and it is what the sentence above promises.

### Payoff dates are months, not days

A payoff date derived from monthly arithmetic cannot be day-accurate. `payoffMonth` is a
`YearMonth` (`"2027-04"`) everywhere in the API, and every screen renders it as *Apr 2027*, which
is what the designs show. Day-precision deltas are false precision — see the note on the check-in
copy under [Where the designs and the docs disagree](#where-the-designs-and-the-docs-disagree).

The projection's first period starts at the debt's **next payment date**: the linked bill's next
occurrence if the debt is linked to one, otherwise the day-of-month of the most recent recorded
payment, otherwise today. Month-end clamps the same way a bill schedule does — the 31st becomes the
28th in February (`bills-and-income.md`).

### When the numbers do not work

Three failure shapes, all of them real and none of them an error page.

**A payment that never clears the balance.** When `payment ≤ interest(1)` the balance grows every
month and there is no horizon to solve for. This is not a user mistake — a card's real minimum is a
percentage of the balance, and a household entering a flat minimum from last month's statement will
hit it legitimately. So:

- The terms are **accepted and stored**. Refusing to record a debt because its minimum is too small
  tells somebody in trouble that their situation is invalid input.
- `monthsLeft`, `payoffMonth` and `totalInterestLeft` come back `null` with a reason code of
  `never-clears`, and the screen says it in the arithmetic's own terms: *"At 22.9%, $118 a month is
  less than the $118.44 of interest this balance accrues — the balance grows."*
- The plan still ranks the debt and still produces dates for the debts that do clear. A plan that
  refuses to render because one debt is underwater is a plan nobody can use to fix it.

**An over-determined set.** If the request marks all three of `apr`, `payment` and the horizon as
entered, the server answers `422 terms-over-determined` naming the fields. It does **not** silently
drop one: a number the household typed must never be quietly replaced by a computed one. The
*client* is what releases a field — the designs make this the interaction (*"Change a solved field
and it becomes yours — I'll re-solve around it"*) — and the sheet says which field it released and
that it is now solved.

**A set with no solution at all** (a horizon the balance cannot reach at any non-negative rate, a
negative payment, a payoff month in the past): `422 terms-unsolvable`, with the field at fault
named. The bisection is **iteration-bounded**, never run to convergence-or-forever — see
[Security considerations](#security-considerations).

### What is stored and what is recomputed

| | Stored | Derived on read |
|---|---|---|
| Terms | Only the **entered** values. A solved field is `NULL` in the row | Every solved field, every time. `payment` when the plan supplies it |
| Balance | An **anchor**: `anchorBalance` + `anchorDate` + `balanceSource` | `balance` = anchor rolled forward by the payments recorded after `anchorDate` |
| A payment's split | `interest`, `principal`, `balanceAfter` as at the time | — |
| Rung | Only for `CUSTOM` (`customOrder`) | Derived from the strategy for `AVALANCHE` and `SNOWBALL` |
| Plan | `strategy`, `monthlyAmount` | Order, per-debt allocation, per-debt payoff month, total interest, debt-free month, both strategies' comparison |
| The previous plan's projection | A **snapshot** per plan change | — |

**A solved field is not stored, and nullability is the `entered` / `solved` flag.**
`../domain/model.md` asks for such a flag on every field; this is it, without a parallel set of
boolean columns that could disagree with the values they describe. A stored solved value is a
second copy of a derivable fact — which is what "overdue is derived, never stored" and "remaining
is computed, never stored" already refuse elsewhere.

**A payment's interest and principal *are* stored**, which looks like the opposite call and is not:
they are what was true under the rate in force on the day, and a statement may have corrected them.
Recomputing a payment from 2024 against today's APR would rewrite history to make it agree with
the present, which is the one thing an audit trail must not do.

**The plan's projection is snapshotted when the plan changes** (strategy, monthly amount, or custom
order), carrying the strategy, the monthly amount, the debt-free month and the total interest.
Without it the home screen's *"4 months earlier than last month's plan"* has nothing to compare
against, and a snapshot cannot be backfilled. Slice 10 writes the snapshot; the screen that renders
the delta is not in this slice.

### Strategies

Three: `AVALANCHE` (highest APR first), `SNOWBALL` (smallest balance first), `CUSTOM` (the
household's own order). **Both named strategies are always computed, whichever one is selected** —
the comparison card is a product requirement, not a view of the selection.

**Ordering must be deterministic**, including on ties, or the ladder reshuffles between two reads of
the same data:

- `AVALANCHE`: APR descending, then balance ascending, then name, then id.
- `SNOWBALL`: balance ascending, then APR descending, then name, then id.
- `CUSTOM`: `customOrder` ascending, then the `AVALANCHE` ordering for anything unordered.

**How a household sets that order.** Under `CUSTOM`, each rung in **Your debts** carries *Move up*
and *Move down*, in the table and in the ladder alike — **buttons, not drag and drop**: a drag
handle is unreachable from a keyboard by default, and this control decides every date on the page.
The first rung has no up and the last no down, each button's accessible name says which debt as
well as which way, and a polite live region announces where the moved rung landed.

Each move sends the **whole** order, because that is how the server reads it: a debt `customOrder`
omits has its position cleared. With debts in more than one currency the list therefore spans every
group, even though a move only ever happens within one ladder — sending a single group's ids would
silently unplace the other's. **Choosing *Your own order* sends the order already on screen**, so
switching changes nothing until something is moved; otherwise every debt would arrive unplaced, the
ladder would fall back to the avalanche ordering, and the first move would be made against an order
nobody had seen. The plan is **read back from the response**, never reordered locally: every date,
every allocation and the debt-free month follow from the order, and only the server computes them.

**The page names what the household's own order costs.** The two comparison cards are always
avalanche and snowball — that is the product requirement — so under `CUSTOM` neither of them is
the plan in force, and without a line for it the page would show two totals nobody is paying and
never name the one they are. Rearranging the demo ladder moves the interest from $2,880.41 to
$4,568.81 (ADR-0043 figures); both cards carry on reading as before. The line is omitted under the two named
strategies, where the matching card already says it.

**Choosing it from the avalanche order makes all three chart lines identical**, which is the first
thing a household sees after choosing it. The chart therefore collapses **any** number of
coincident lines to one key naming all of them, not just two — keying a subset together and
leaving the rest keyed separately over the same pixels is the original confusion rearranged.

**Allocation is the ladder.** Every active debt gets its minimum. The surplus
(`monthlyAmount − Σ minimums`) goes entirely to rung 1. When rung 1 clears, its minimum **and** the
surplus roll down to rung 2, and so on — *"Pay the top one hard. Everything below gets its minimum
until the rung above clears."* Both strategies use the same roll-down; the only difference between
them is the order.

**A plan never projects an overpayment.** No rung is allocated more than it owes that month; what a
rung does not need flows down to the next one **in the same month**, so `monthlyAmount` is spent
exactly until the month the last debt clears, and the roll-down starts in the month a rung clears
rather than the month after. This is deliberately *not* the single-debt recurrence, where the last
payment overshoots and is not clamped: a single debt's projection says what the household will
actually pay a lender, while a plan allocates one monthly figure across several debts, and
projecting that somebody hands a lender £175 more than the payoff figure while the next debt
accrues interest is a fiction. It is also what makes avalanche's headline property true — see
Testing notes.

**What the plan outputs**, per read:

- an **order** — the rung of each active debt;
- a **monthly amount per debt**, split as minimum plus extra (*"$185 minimum + $260 extra"*);
- a **projected payoff month per debt** (*"Clears Apr 2027"*);
- a **debt-free month** for the plan: the latest of the per-debt payoff months;
- a **total interest** figure for the plan;
- the **first win** — when rung 1 clears, because that is snowball's whole argument;
- the same five figures for **the other strategy**, so the comparison card can be rendered without
  a second request;
- a **balance-to-zero series** for the chart, one point per month per strategy.

**The what-if is computed, never stored.** *"Adding $50/mo to rung 1 moves the whole ladder up by 3
months and saves $780 in interest"* is the plan re-run with `monthlyAmount + 50`. It is a `GET`
with query parameters and it writes nothing.

**A monthly amount below the sum of the minimums is accepted and produces no projection.** It is
refused neither at the edge nor in the database, because a household that genuinely cannot meet its
minimums exists and `422` is the wrong thing to say to them. The plan says plainly that the amount
is less than the minimums and that it cannot build an order from it. Every projection computed from
such an amount would be a fiction.

### Payments

**A `DebtPayment` is a plan record, not a transaction.** ADR-0025's reasoning carries over
unchanged: only a real `Transaction` is ever income or expense, and the app must not become the
authority on whether money moved. So logging a payment **writes no transaction and moves no account
balance**, and it carries a nullable `transactionId` naming the real movement when one is known —
the same shape, and the same optionality, as `bill_occurrences.transaction_id`.

This is a deliberate departure from `transfers.md`, where creating a transfer *does* write two
transactions (ADR-0033). The difference is what the household asked for: a transfer is an
instruction to record a movement between two accounts it owns, and both legs are rows in its own
ledger. A debt payment is a statement about a balance held by a third party, of which the money
leaving the account is only one half — and that half is very often already in the ledger, or about
to arrive by import or sync. Writing it would duplicate it. This is the kind of call that deserves
its own ADR; see [Open questions](#open-questions).

Consequences, stated so nobody rediscovers them:

- **The debt's balance derives from its payments; the account's balance does not.** A household that
  logs a debt payment and records no transaction has a correct debt balance and an account balance
  that is too high. The same gap bills have, for the same reason.
- **A transaction settles at most one debt payment, ever**, enforced by a unique index — ADR-0031's
  argument about one transaction and one envelope entry, and the same rule bills apply across
  occurrences. Counting one charge against two debts would understate both balances plausibly.
- **Deleting the linked transaction does not delete the payment and does not move the balance.** The
  foreign key is `ON DELETE SET NULL (transaction_id)` — the column-list form, because a bare `SET
  NULL` would try to null `household_id` too and the delete would fail (`transactions.md` learned
  this on `category_id`). The payment stands with its link cleared, its provenance unchanged, and a
  row that says the charge it came from is gone. The alternative — removing the payment with the
  transaction — would mean tidying up a mistyped ledger row silently changed what the household
  owes.
- **`provenance` says how the row got here**, exactly as `statusSource` does for a bill:
  `USER_LOGGED`, `AUTO_MATCHED`, `STATEMENT_CORRECTION`. Slice 10 writes `USER_LOGGED` and
  `STATEMENT_CORRECTION` only. `AUTO_MATCHED` needs a connected account (slice 16); the column
  exists now so that a figure somebody asserted never becomes indistinguishable from one the app
  inferred.

**The split: interest is charged once per debt per calendar month** (ADR-0043, decided by the
owner on 2026-11-27). The **first** payment recorded for a debt in a calendar month, by the
payment's date, carries that month's interest, `round(balance × apr ÷ 100 ÷ 12, minor unit,
HALF_EVEN)` on the balance at the time. **Every further payment for that debt dated in the same
month has interest 0** and is all principal. `principal` is `amount − interest` and `balanceAfter`
is the previous balance minus `principal`. `interest + principal = amount` exactly, by construction:
the interest is rounded and the principal takes the remainder, so no payment ever loses or invents
a cent. Because the interest is a whole number of cents, a payment in cents splits into two cent
figures that each display exactly and still add up, and the balance it leaves is on the cent grid
too.

Until this rule, every payment was charged a whole month of interest, so two payments in one month
paid two months. Slice 14's check-in found it: a $63 surplus on the canvas Visa (22.9%) was charged
$117.94, its balance went up, and the payoff moved later. Slice 14 patched only the check-in, with
a special case on `CHECK_IN`. That special case is gone. **One rule applies to every way a payment
is recorded**: by hand, a past payment, the check-in's bill prompt (_"Also record this against
…"_), and its surplus. All of them build the row through `DebtPaymentService.draft`, which asks
`MonthInterest.decide`, the only place the decision is taken. A statement correction is not a
payment: it carries no interest and never books a month.

The canvas Visa, $6,180.00 at 22.9%, accrues `6180 × 22.9 ÷ 1200 = 117.935 → $117.94` a month:

| Recorded | Date   | Interest | Principal | Balance after | Why                                 |
| -------- | ------ | -------- | --------- | ------------- | ----------------------------------- |
| $445.00  | Aug 5  | $117.94  | $327.06   | $5,852.94     | August's first payment              |
| $50.00   | Aug 20 | $0.00    | $50.00    | $5,802.94     | August's interest is already booked |
| $445.00  | Sep 2  | $110.74  | $334.26   | $5,468.68     | September's first payment           |

How the edge cases are decided, so nobody re-derives them:

1. **"First" is read off the stored rows, not the order things arrive in.** A month is _booked_ when
   a stored payment for the same debt, dated in that month, carries non-zero interest. A payment
   back-dated to earlier in a booked month (a $100 payment dated Aug 3, logged after the Aug 20
   one above) is principal only. The month's interest is already in the history, and the stored
   split of the payment that carries it is **not rewritten**. Splits are stored as at the time,
   because they record what was true then and a statement may have corrected them since (_What is
   stored and what is recomputed_, above). A month whose only rows carry no interest (a statement
   correction, a payment made while nothing was owed, a slice-14 check-in row stored
   principal-only) is not booked. Its next payment carries the interest, so the month never loses
   it.
2. **Deleting the payment that carried a month's interest un-books that month.** The API allows it
   (`DELETE /api/debts/{id}/payments/{paymentId}`). The other payments in that month keep their
   stored principal-only splits, because history is not re-split. **The next payment recorded in
   that month carries the interest again**, against the balance at that time. If none is, the month
   stands uncharged until a statement says otherwise, and the confirmation's correction row is
   where the difference shows. Never lost silently, never charged twice. For example: delete the
   Aug 5 carrier above and the $50 stays $0.00 / $50.00. The balance is $6,130.00, so a $100
   payment logged on Aug 20 carries `6130 × 22.9 ÷ 1200 = $116.98`, its principal is −$16.98, and
   it leaves $6,146.98. August is charged $116.98, once. **A correction (`PATCH`) that moves a
   payment carrying interest into another month that is already booked makes it principal only**,
   as a payment logged there would have been, and un-books the month it left. Moving a
   principal-only payment never gives it interest, because that would be a split against a balance
   from another day.
3. **A check-in surplus that is the month's first payment carries the month's interest.** In the
   canvas week the regular Visa payment is due on the 28th and the check-in is on the 21st, so the
   surplus comes first. At 22.9%, $63 carries $117.94: principal −$54.94, balance after $6,234.94,
   **higher than before**. The regular $185 later in August is then all principal, so August ends
   at $6,049.94, exactly $63 below the $6,112.94 the regular payment alone would leave. The month is
   charged once. Three consequences are built in:
   - **(a) No projection charges the booked month again.** The plan's month 1 is the current month
     (`PayoffPlanService.firstMonth`), and a debt's own projection starts at its next payment
     month, often this one. Each charged month 1 its interest on today's balance. That is right
     when the month is unbooked and a double count when it is booked. Now `DebtFacts` carries
     whether the current month is booked, and a projection whose month 1 is that month charges it
     nothing: `FirstMonth.interestBooked`, carried on `LadderDebt` in the ladder and on `DebtTerms`
     in `DebtTermsSolver`'s projection, payment search and rate search. `monthsLeft`, `payoffMonth` and `totalInterestLeft` all follow from those. The
     check-in's what-if (`PayoffPlanService.oneOffPayment`) is told whether the surplus would book
     the month, so the projection after it does not charge the month a second time.
   - **(b) The guarantee holds: a surplus never moves the payoff later, and never makes total
     interest higher than not paying it.** The plan after the surplus starts $63 lower than the
     plan without it, once the booked interest is counted on both sides. On the demo ladder ($905 a
     month, avalanche) the plan projects $2,847.88 of interest without the surplus. With it, the
     plan projects $2,701.45 plus the $117.94 already booked, $2,819.39 in all, $28.49 less. The
     Visa clears in Dec 2027 either way. Without (a) it would have been $2,874.29 + $117.94 =
     $2,992.23. The canvas test's "never later" assertion fails if (a) is removed: the canvas's
     24.99% Visa then moves later. `PayoffLadderTest` asserts the property over 200 random
     surpluses, booked and unbooked.
   - **(c) The copy explains a balance that rises.** The preview carries `monthInterest`
     (`CARRIED` · `ALREADY_CARRIED` · `NONE`) and `interestMonth`, and the check-in's debt choice
     carries `monthInterest` (the interest the surplus would carry, or null). The web and the phone
     word them: _"This includes August 2026's interest of $117.94. Interest is charged once a month,
     on the first payment dated in that month…"_, _"No interest on this one: August 2026's interest
     was charged on an earlier payment…"_, and on the check-in, _"It would be Visa's first payment
     this month, so it carries this month's interest of $117.94…"_. Neither client computes
     anything.
4. **Confirmed estimates and the check-in's matched charges take the same path.** There is one
   split function, `DebtPaymentService.draft` → `MonthInterest.decide`, and every writer of a
   payment row calls it: `log` (by hand, a bill occurrence's _"also record this"_, and the
   check-in's bill prompt for a marked or attached charge), `logFromCheckIn` (the surplus), and both
   previews. The two rows that are not payments do not split: a balance confirmation's
   `STATEMENT_CORRECTION`, which carries interest 0, and confirming an estimate, which writes only an
   anchor. `AUTO_MATCHED` has no writer yet (slice 16). When it gets one, it must call `draft`.
5. **Two payments at once cannot both take the interest.** Every payment write (`log`,
   `logFromCheckIn`, `correct`, `delete`, and `DebtService.confirmBalance`) first locks the debt
   row `FOR UPDATE` through `DebtLocks`: a household-scoped find, then `EntityManager.refresh` under
   `PESSIMISTIC_WRITE`. The second writer waits, then sees the first one's row. **The lock is a
   refresh, not a query**, because a query hands back an entity the transaction already loaded
   exactly as it was loaded. A check-in completion reads its picture (and so its debts) before it
   writes, so a locking query would have let it decide against a paid-off flag or an anchor from
   before the wait (`DebtLockingIT.a_completion_decides_against_the_debt_as_it_is_once_locked…`).
   **Every wait is bounded**: `DebtLocks` sets `SET LOCAL lock_timeout = '3s'` before it locks, and
   running out, like losing a deadlock, is `PessimisticLockingFailureException`, which
   `ApiExceptionHandler` answers with a retryable **`409 record-busy`** in the normal problem shape
   (_"Someone else is changing this right now. Nothing has been saved — try again in a moment."_ on
   both clients). Without the bound, a transaction that hung holding a debt would hold every writer
   to it, and the request thread with them. The two writers that take debt rows without
   `DebtLocks.lock`, your own order and a debt's delete, set the same bound
   (`DebtLocks.boundWaits`, `DebtLockingIT.your_own_order_and_a_delete_on_a_held_debt…`), and the
   handler logs which lock failure it was at `DEBUG`, the exception's class only. **A debt deleted
   while a writer waits for it is `404`, not `500`.** `DebtLocks` locks the row by id (`SELECT id …
   FOR UPDATE`, household-scoped) and only then refreshes the entity. PostgreSQL re-checks a row it
   waited on and returns nothing when it has gone, so the writer finds no debt: `404` for a payment,
   and for a completion a skip, after which the write that names the debt answers `409
   check-in-stale`, subject gone. Refreshing a row that no longer exists throws
   `EntityNotFoundException` and marks the transaction for rollback, which surfaced as a `500`
   (`DebtLockingIT.a_debt_deleted_while_a_payment_waits…`, `…_a_completion_waits…`). `@Version` could not do this, because a payment does not touch
   the debt row unless it settles it. Without the lock, six concurrent $100 payments produced three
   or four interest-carrying rows. With it, exactly one (`MonthInterestIT`). The preview reads
   without the lock: it writes nothing, and a stale preview is corrected by the recorded split.
   **A transaction that writes to two debts takes both locks first, in one global order** (the id's
   text, which is PostgreSQL's `uuid` order): `DebtPaymentService.lockInOrder`, which a check-in's
   completion calls before it records a bill prompt's payment and the surplus. Each write would
   otherwise lock its own debt in item order, and two transactions taking the same two debts the
   other way round deadlock. **Setting your own order** (`PayoffPlanService.applyCustomOrder`), the
   one other write that touches several debt rows, writes them in the same order
   (`DebtLocks.lockOrder`). Two writes outside this rule are recorded rather than reordered:
   deleting an account or a bill nulls `debts.account_id` / `debts.bill_id` (`ON DELETE SET NULL`)
   in whatever order PostgreSQL visits the rows. A bill links at most one debt (`bill_id` is
   unique), so it takes one debt row and cannot deadlock on debts alone. An account can link
   several. If such a delete does deadlock with a completion, PostgreSQL aborts one of the two and
   that one is answered `409 record-busy`, with nothing half-written. `DebtLockingIT` proves the
   completion's order deterministically, and proves the locks on
   `correct` (six concurrent moves into one month leave one carrier, not six), `delete` (a delete
   racing a payment never leaves a debt settled with a balance) and `confirmBalance` (a statement
   racing a payment still chains in the history).
6. **Existing data is not rewritten.** No migration touches a stored row. Slice-14 `CHECK_IN` rows
   keep their principal-only split, and because they carry no interest they do not book their month
   (rule 1). Months that were charged twice before this change stay charged twice in the history,
   and the next statement confirmation reconciles the balance. No schema change was needed:
   `ix_debt_payments_debt_paid_date` already serves the month look-up, and "this row carries the
   month" is `interest <> 0`, not a second column that could disagree with it.
7. **A month's interest that rounds to $0.00 books nothing, and that is the right answer.** What the
   rule guarantees, stated exactly, is **at most one non-zero interest charge per debt per month**.
   `round(balance × apr ÷ 1200)` is zero only on a dust balance, `balance × apr < 6` in dollars:
   under $6 at 1%, under 27 cents at 22.9% (`MonthInterestTest`). Such a first payment charged the
   month nothing. A later payment in the same month then carries interest only if the balance
   grew in between, which only a statement correction does, and that is the month's one real
   charge, on the balance the statement gave. A marker column to treat the zero row as the carrier
   was considered and rejected: it needs a migration and a three-state rule for old rows (slice-14
   check-in rows carry interest 0 and must not book their month), to prevent a case that charges
   at most one real month of interest anyway.
8. **The month comes from the household's date, so the server accepts it** (`HouseholdDates`,
   formerly `DebtDates`; bills share it, `bills-and-income.md`, The paid date). There is
   no household time zone, and the server's clock is UTC. A household in Sydney paying on the
   morning of Sept 1 is still on Aug 31 by the server. Refusing Sept 1 as "in the future" would
   push them to date it Aug 31, into a month already booked, and September would never be charged.
   So a `paidDate` on log, preview and correct, and a `balanceDate` on a debt or a statement
   confirmation, may be **as late as today in UTC+14**, the latest date anywhere on Earth: the
   device's own date is always accepted, and the interest month follows it
   (`MonthInterestIT.a_payment_dated_the_first_by_a_household_east_of_utc…`: at 23:00 UTC on Aug 31
   a payment dated Sept 1 carries September's interest, `111.69 / 333.31 → 5,519.63`, and a Sept 2
   payment is principal only). The slack is the same as the check-in's week range and reporting's
   months. The same dates may be **no earlier than 1900-01-01**: `-5000-01-01` parsed and reached
   PostgreSQL as a `500`, and is now `400 validation-failed` naming the field. The web and phone
   date pickers stop at the device's own today, so the slack is never offered, only accepted.
   **The check-in's bill payments take the household's date too.** The completion carries the
   device's own date (`today`, `weekly-check-in.md`, Completing a check-in), within today somewhere
   on Earth. A bill marked paid, and its debt payment, are dated that day
   (`MonthInterestIT.a_bill_marked_paid_on_the_first_east_of_utc…`: at 23:00 UTC on Aug 31 a store
   card marked paid with `today` Sept 1 records `2026-09-01 · 3.08 / 21.92`, September's first;
   dated by the server it was Aug 31, all principal, and September went uncharged). Taking the
   occurrence's due date instead was rejected: it invents a payment date, and a late payment would
   land in the month it was owed, not the month it was paid. A matched charge's payment keeps the
   charge's own date, capped at the device's day, or, from a client too old to send one, at the
   latest date on Earth, so a true date is never moved into the month before (`HouseholdDay`).
   **The check-in's surplus keeps the server's date** (`balances.today()`, UTC): its preview, the
   debt choice's _"carries this month's interest"_, is computed by the server before the
   completion, and a payment dated differently from its preview would record a split the household
   was not shown. Near a month boundary that can put the surplus in the household's previous month.
   It is still charged by the one rule there, so no month is charged twice. The month it lands in
   may be a day off the household's calendar, which is the same accepted risk as reporting's months.

**A statement wins.** When the household confirms a balance that disagrees with the arithmetic, the
difference is recorded as a `STATEMENT_CORRECTION` payment row — *"Statement corrected +$4.10"* —
and the anchor moves. History is never silently adjusted to agree with the present.

**Overpaying is not clamped.** A payment whose principal exceeds the balance takes it below zero and
the screen says the debt is overpaid by that amount. Clamping would hide a real error in somebody's
favour, and bills already set the precedent that a figure is rendered negative when it is negative.

### Balance sources

`CONFIRMED` · `ESTIMATED` · `LINKED_STATEMENT`, and **the UI never renders two of them
identically**.

- `CONFIRMED` — an anchor the household entered or confirmed, with the date it was true.
- `ESTIMATED` — the anchor rolled forward over months with no logged payment, assuming the minimum
  was paid. The designs give it its own screen and its own words: *"This one's an estimate. I've
  assumed three $118 payments since June — if that's right, tap confirm and it becomes the real
  balance."* Confirming it writes a `CONFIRMED` anchor at today's date.
- `LINKED_STATEMENT` — from a connected account. **Not reachable in slice 10** (slice 16), like
  `AUTO_MATCHED` and like a bill's `ChargeRule`.

Estimation rules: never estimate past today; never estimate a month in which a payment is logged;
estimate whole months only, from the anchor date; and apply the same interest formula as everything
else, which means an estimate is *not* the anchor minus the payments (see the design-arithmetic note
below). After **twelve** estimated months the balance is reported as stale rather than estimated,
and the screen asks for a statement figure instead of extrapolating a year of guesses.

### The minimum is counted once

A debt's minimum payment is frequently *also* a bill — *"the car payment is its bill row"*. Counting
it in both places is the obvious bug in this slice, and the designs' own figures show the rule
working: three debts with minimums of $185, $342 and $118 total $645, and the Bills & Income
committed figure adds only **$303** of them — $185 + $118 — because the car loan's $342 is already
one of the seven bill rows.

**The link is explicit, never inferred.** `debts.bill_id` names the bill whose occurrence is this
debt's minimum, set by the household when it creates or edits the debt. Matching a debt to a bill by
name, amount or category would be guessing, which is precisely why `bills-and-income.md` refuses to
match bills to envelopes that way.

So: `debtMinimumsNotInBills = Σ minimumPayment over active debts whose minimum **this month's
bills term does not already carry**`, and that is the only debt figure the Bills & Income period
summary adds to anything.

**"Already a bill" means the month's bills term actually carried it** — not that the debt names a
bill. `PeriodSummaryService` reads the set of such bills off the very `BillsForPeriod` the bills
term is summed from, and passes it in; nothing in the debt code re-derives it. That is what makes
counted-once true **by construction** rather than by two services happening to agree, and it was
not true before: asking the debt *"do you have a `bill_id`?"* left the minimum counted in
**neither** term in three separate cases, each of which raises the headline by a figure the
household still owes.

| The link survives, but…                      | …the bills term carries nothing, because          |
| -------------------------------------------- | ------------------------------------------------- |
| the bill was **retired** (not deleted)       | an inactive bill projects no occurrences at all   |
| the bill is **yearly** and this is not its month | it projects none in eleven months out of twelve |
| the occurrence was **skipped**               | `SKIPPED` is excluded from the total by name      |

For the designs' figures, all three must leave `left` at 1,755 rather than 2,097 — and the yearly
case must also move the same 342 *back* into the bills term in June, so the headline does not move
either way. Asserted by `DebtPeriodSummaryIT.retiring_the_bill_…`,
`…a_minimum_whose_bill_is_not_due_this_month_…` and `…skipping_the_bill_…`, all three distinct from
the delete case: deleting nulls the link (`ON DELETE SET NULL (bill_id)`), none of these does.

The skipped case is the one worth saying out loud, because it reads like a bug and is not: the
household said it is not paying that bill this month, so that money **is** free, and the minimum
counts. `left` does not move, because the same figure simply changed which term carries it.

**The split lives on the period summary alone.** `GET /api/debts` returns `owed`, `debtMinimums`
and `debtCount` and **not** `debtMinimumsNotInBills`, because the split depends on which bills a
given month carries and that request names no month — it could only ever have answered by
guessing. The plan page's footer shows the **total** (*"Minimums · each counted once in Bills &
Income — $645"*), which is `debtMinimums`.

### Currencies

A debt is denominated in one currency, and **the plan's totals are per currency**. A household with
debts in two currencies gets no single debt-free date and no combined total interest — those would
need a rate the instance may not have, and a converted figure is never the input to stored
arithmetic (ADR-0022). The plan groups by currency and says so, the same way the ledger returns a
`null` page total when a page mixes currencies rather than inventing one (`transactions.md`).

A debt carries its own `currency`, defaulting to the household's. Where a debt is linked to an
account, its currency **is** that account's and is not independently settable — the account owns
that fact, exactly as it does for a transaction (`transactions.md`).

### Money rules

`BigDecimal` in Java, `NUMERIC(19,4)` in Postgres, **strings over the wire** — amounts and `apr`
both (ADR-0006). A JSON number is refused with `400`: `22.9` is not exactly representable as a
double either, and it multiplies a balance. Rounding is `HALF_EVEN` to the currency's minor unit
(four places where it has none), applied at each monthly step and nowhere else, so the figure on screen and the figure in the chain of
`balanceAfter` values are the same figure. `apr` is a **percentage**, because that is what a
statement prints; the monthly rate is `apr ÷ 100 ÷ 12` and no stored column holds it.

## Data model

New tables, `V<n>__debts_and_payments.sql` — next free number after slice 9's migrations. Extends
`Debt`, `DebtPayment` and `PayoffPlan` in `../domain/model.md`; read that first, and note the two
corrections to it below.

`debts`
: `name`, `kind` (`CREDIT_CARD` | `INSTALLMENT`), `servicer` (nullable text — *Chase*, *Alpine Auto
Finance*, *Nelnet*), `currency`, `apr` `NUMERIC(6,3)` (**nullable** — null means solved),
`minimum_payment`, `original_balance` (nullable), `anchor_balance`, `anchor_date`,
`balance_source`, `payment` (nullable — entered only), `months_left` (nullable — entered only),
`payoff_month` (nullable — entered only), `custom_order` (nullable), `account_id` (nullable),
`bill_id` (nullable, unique where not null), `paid_off_at` (nullable), `archived`, `version`.

`debt_payments`
: `debt_id`, `paid_date`, `amount`, `interest`, `principal`, `balance_after`, `provenance`,
`transaction_id` (nullable, unique where not null), `bill_occurrence_id` (nullable, unique where
not null), `note`, `version`.

`payoff_plans`
: one row per household (`household_id` unique — ADR-0026). `strategy`, `monthly_amount`,
`version`.

`payoff_plan_snapshots`
: `taken_at`, `strategy`, `monthly_amount`, `currency`, `debt_free_month`, `total_interest`.
Plan-level only: that is what *"4 months earlier"* compares. Per-debt snapshot rows can be added
later without rewriting these.

Constraints that carry a rule rather than decorating one:

- **Scoping is declarative**, as in slices 5–8: every foreign key is composite on
  `(id, household_id)`, so a debt physically cannot reference another household's account, bill,
  transaction or occurrence (ADR-0017).
- `CHECK` that **at most two** of `apr`, `payment`, and the horizon are non-null — the
  degrees-of-freedom rule, in the database as well as at the edge. `months_left` and `payoff_month`
  are two faces of one unknown, so both being set is also refused.
- `CHECK (apr IS NULL OR (apr >= 0 AND apr < 1000))`. Zero is a real promotional rate and must not
  be refused; so is a payday rate above 100%, and a household that cannot record its worst debt is
  worse off than one looking at an alarming number.
- `CHECK (minimum_payment >= 0)`. There is deliberately **no** check against a zero or negative
  `anchor_balance`: a confirmed zero is how a debt is paid off, and a negative one is an
  overpayment the screen must be able to show. A debt *created* at zero is a `422` at the edge,
  which is a different claim.
- `CHECK (currency = account.currency)` cannot be expressed in SQL, so the service enforces it and
  `account_currency_immutable` (slice 3) is what keeps it true afterwards.
- `fk_debt_payments_transaction … ON DELETE SET NULL (transaction_id)` and
  `fk_debts_bill … ON DELETE SET NULL (bill_id)`, both in the column-list form.
- `ON DELETE CASCADE` from `debts` to `debt_payments`: deleting a debt takes its own history and
  nothing else, exactly as deleting an envelope takes its log and no transactions.

**V17 also alters a slice-7 table.** `debt_payments.bill_occurrence_id` needs a composite foreign
key on `(id, household_id)`, and `bill_occurrences` had no `UNIQUE (id, household_id)` for it to
reference — V14 gave one to `bills` and `income_sources` but not to occurrences. V17 adds it by
`ALTER`, the way V13 did for `transactions`, because migrations are append-only and V14 is frozen
(ADR-0007). Without it the migration does not merely skip the scoping: it **fails to apply**, with
`there is no unique constraint matching given keys for referenced table "bill_occurrences"` — which
is the declarative-scoping rule refusing to be half-applied.

Three corrections to `../domain/model.md`, which was written from the canvas before this doc
existed:

1. **`rung` is not a stored field on `Debt`.** It is derived from the strategy for `AVALANCHE` and
   `SNOWBALL`; only `CUSTOM` stores an order. Storing it would give the plan two sources of truth
   for the same ordering.
2. **The solver constrains four quantities, not seven.** See
   [Where the designs and the docs disagree](#where-the-designs-and-the-docs-disagree).
3. **`currentBalance` is not a stored field.** What is stored is the anchor — `anchor_balance`,
   `anchor_date`, `balance_source` — and today's balance is derived from it and the payments
   since. A stored current balance would be a fourth figure to keep in step with the anchor, the
   payments and the linked account, and the first of them to drift.

## API

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/debts` | List with derived balance, rung, what it is paying, when it clears, progress |
| `POST` | `/api/debts` | `OWNER` / `MEMBER`. Requires `name`, `kind`, `minimumPayment`, `balance`, `balanceDate`, and `apr` unless a payment and a horizon are both given. `409 debt-limit-reached` past the per-household cap |
| `GET` | `/api/debts/{id}` | Terms (each field flagged `ENTERED`, `SOLVED` or `FROM_PLAN`), progress, projection, chart series |
| `PATCH` | `/api/debts/{id}` | The terms editor. Carries the entered set and `version` |
| `DELETE` | `/api/debts/{id}` | `204`. Takes its payments, no transactions |
| `POST` | `/api/debts/{id}/archive` · `/unarchive` | Retire without deleting, as accounts do |
| `GET` | `/api/debts/{id}/payments` | The history, **paginated** (page envelope, `api-style.md`) |
| `POST` | `/api/debts/{id}/payments` | Log one. Accepts an optional `transactionId` |
| `POST` | `/api/debts/{id}/payments/preview` | What logging `{"amount", "paidDate"?}` **would** record — the split, the resulting balance, and `monthInterest` / `interestMonth` (ADR-0043) — writing nothing. Built through the same code path as logging, so it cannot disagree with it. A `POST` to keep the amount out of URLs; a `VIEWER` is refused it, as for any non-`GET` |
| `PATCH` · `DELETE` | `/api/debts/{id}/payments/{paymentId}` | Correct · remove |
| `POST` | `/api/debts/{id}/balance` | Confirm or correct the balance. Writes the anchor, and a `STATEMENT_CORRECTION` row when it disagrees with the arithmetic |
| `GET` | `/api/debt-plan` | Strategy, amount, order, **both** projections and the comparison |
| `PUT` | `/api/debt-plan` | Set strategy, `monthlyAmount`, custom order. Requires `version` |
| `GET` | `/api/debt-plan/projection?strategy=&monthlyAmount=` | The what-if. Writes nothing |
| `POST` | `/api/debt-terms/solve` | Solve a term set without saving it, for the editor and the add form |

`/api/debt-plan` is **singular** because there is exactly one per household (ADR-0026), like
`/api/periods/{period}/summary`.

`POST /api/debt-terms/solve` is a **pure calculation** — it reads no household data. It is still
authenticated, still rate-limited, and still iteration-bounded; see security.

**The entered set is explicit in the request.** `PATCH /api/debts/{id}` carries, for each solver
field, either a value marked entered or an instruction to release it to solved. It never infers
"absent means release" — `transactions.md`'s three-way `PATCH` lesson applies with more force here,
because a field dropped by accident would silently change what the household believes about its own
payoff date.

**Bills & Income gains debt figures.** `GET /api/periods/{period}/summary` adds `owed` (the total
balance), `debtCount`, `debtMinimums` and `debtMinimumsNotInBills`, and **`left` now subtracts
`debtMinimumsNotInBills`** — decision 2 below, which changed a figure that had already shipped.
`owed` is **not** subtracted: it is a balance, not a monthly commitment, and taking it off would
tell a household owing £26,000 that it has nothing to spend this month. The fourth figure is on
**this endpoint only**: `GET /api/debts` returns `owed`, `debtMinimums` and `debtCount` and not the
split, because the split depends on which bills a given month carries and that request names no
month. `owed` and `debtMinimums`
are per currency — the household's base currency only — because a combined figure would need a rate
the instance may not have (ADR-0022); `debtCount` spans them all, because a count needs no
currency.

Error codes: `terms-over-determined` · `terms-unsolvable` · `never-clears` (a reason on a null
projection, not an error) · `plan-below-minimums` (a flag on the plan, not an error) ·
`debt-paid-off` on a write to a cleared debt · `debt-limit-reached` · `stale-version` as
everywhere.

## UI

Two routes, both in the main nav under **Debt plan**, both at 390 and desktop width, both light and
dark (non-negotiable #10, `design/README.md`'s two rules). Dark mode steps the accents to their 400
tints over the warm near-black grounds, like every other screen; no debt artboard invents a colour.

### `/debt-plan` — the plan

Desktop (*Debt plan absorbs the debt table*), top to bottom:

1. A header strip: **the monthly commitment and how much of it is strategic** — *"$645/mo committed
   · $560 of it strategic"*.
2. **Debt plan**, with the subtitle that says where the money comes from: *"Three balances, one
   order of attack. Minimums come from your bills; the extra comes from what's left after goals."*
3. **Strategy tabs** — Avalanche · Snowball · Custom.
4. **Balance to zero** — a chart with one line per strategy, labelled with the assumption (*"Both
   lines assume $560/mo"*), and the headline underneath: *"$26,480 → $0 · 31 months on the current
   plan"*.
5. **Two comparison cards**, side by side, each with its one-line rationale, its debt-free month and
   its total interest; then the sentence that compares them and the what-if (*"adding $50/mo to
   whichever you pick moves the date up 3 months"*). Neither card is pre-blessed beyond showing
   which one is selected.
6. **Your debts** — the table that used to live on the bills page: rung, name, `kind · servicer`,
   balance, APR, progress, clears, paying (with the `min + extra` breakdown), and **Open →**.
   Headed by the order it is in (*"Avalanche order"*) and by *"Open any debt for its terms and
   payments"*, with **Add a debt** beside it.
7. A footer line carrying the counted-once rule in the household's own language: *"Minimums · each
   counted once in Bills & Income (the car payment is its bill row) — $645"*.

Mobile (*Debt plan, two structures*) renders the same data as **the ladder**: one card per rung
carrying its APR, its clear date, its balance and what it is paying — rung 1 showing *"$185 minimum
+ $260 extra"* and the rungs below it *"Minimum $342/mo until rung 1 clears"*, which is the
allocation rule said in words rather than left to be inferred from numbers. Under it, the
**what-if** sentence; under that, the same two-strategy comparison with **Use avalanche** as its
action.

The two artboards are **alternative structures for the same plan**, and the canvas resolves half of
it: *"Strategy comparison follows the side-by-side structure."* So the comparison is side-by-side
everywhere; the ranked ladder is how the phone shows the order and the table is how the desktop
shows it. The remaining ambiguity is an open question.

**Empty state:** no debts means the page explains what it is for and offers **Add a debt**. It does
not render a $0 balance, a chart of nothing, or a debt-free date of today — a household with no debt
being told it is debt-free *this month* reads as a bug, not a congratulation.

**What the built page does with the parts the designs leave open**, recorded so the next session
does not re-derive them:

- **The strategy control is a group of buttons carrying `aria-pressed`, not ARIA tabs.** They are
  not tabs over panels — pressing one *saves* a choice to the server and changes every date on the
  page — and tab semantics without tabpanels is a lie a screen reader acts on.
- **Both structures are rendered and one is hidden with `display: none`** at a 48rem breakpoint,
  which is the width at which the table's columns stop fitting. `display: none` rather than an
  off-screen class so only one is ever in the accessibility tree: nobody hears the ladder read out
  after the table.
- **The chart is a shared component** (`shared/components/line-chart.component.ts`): inline SVG,
  no library and no CDN, drawing any number of labelled series plus an optional marker, so the
  detail page's *Balance over time* reuses it. It **plots what it is given and never resamples** —
  the server has already thinned the line and reported `seriesMonthStride`. Its geometry is the
  one place an amount becomes a `number`, and what is converted is a dimensionless ratio computed
  in exact `bigint` minor units (`core/money.ts#amountFraction`); the figures it *states* are the
  server's own strings.
- **A `VIEWER` gets no control at all**: no strategy group, no *Add a debt*, no *Use avalanche*, no
  *Edit terms*. The selected order is reported as a sentence instead.
- **Four backend facts are given their own wording** rather than rendered as ordinary: a negative
  `extra` reads *"£120.00 — all that is left to clear it"*; `aprDerivable: false` reads *"Rate not
  available"* and never 0%; a stale balance asks for a statement; and `never-clears` is a reason in
  the Clears cell rather than a blank.

**Add a debt / Edit terms** is not drawn as its own artboard. Its fields are the table's columns
plus onboarding step 2's three: name, kind, servicer, balance (and the date it was true), APR,
minimum, optionally the account it is held at, optionally the bill that pays its minimum. Amounts
are `type="text"` with `inputmode="decimal"`, never `type="number"` (ADR-0006).

### `/debt-plan/{id}` — the detail

The canvas shows a slug (`/debt-plan/visa-8823`); the route takes the debt's **id**, like every
other detail route in the app. A slug would need a second unique key per household and would leak
the debt's name into browser history and server logs.

Desktop (*Debt detail — terms in, payments out*): a back link to the plan, the debt's name, the
`kind · servicer · link` line, a **rung switcher** across the household's debts (`1 Visa · 2 Car ·
3 Student`), and **Edit terms** / **Log a payment**. Then four regions:

1. **Loan terms** — the solver panel, headed with its own count (*"4 entered · 3 solved"*) and its
   promise. Every derived cell carries a `Solved` tag, entered cells carry none, and the payment
   the plan assigned says *from your plan* — three labels, as above. Beneath it, the
   statement line and the balance source, named: *"Statement balance from Chase on Sep 1 was
   $6,180.00 — matches my math to the cent. Balance source: linked statement."*
2. **Balance now**, with when it was confirmed and from what, and the progress figure
   (*"$4,470 paid off · 42% of $10,650"*) — absent when there is no original balance rather than
   rendered as 0%.
3. **Balance over time** — actual pace against the schedule at minimum, with the two lines labelled
   and the payoff marker; and the sentence that makes the chart mean something: *"Paying $260 extra
   is buying you 27 months and $2,890."*
4. **Payment history** — date, paid, interest, principal, balance after, and **how it got here**
   (*Auto · Chase*, *You logged it*, *Statement corrected +$4.10*, *Waiting on you*) on every row,
   so an inferred split is never shown as a confirmed one. Headed with its provenance counts
   (*"6 auto-labeled · 1 manual"*) and **Add a past payment**. Under it, the compounding rule in
   plain words — the sentence quoted at the top of this doc.

Mobile (*Debt detail on mobile*) is **the same four regions as one scroll**: balance → terms →
chart → history, with the debt stepper (`‹ ›`) in the header instead of the rung switcher, a
condensed terms grid (Original · APR · Minimum · You pay · Months left · solved · Payoff · solved),
interest and principal as a subtitle under each payment rather than as columns, **Show all 7
payments** instead of the full list, and **Edit terms** / **Log payment** as the sticky actions.
Same fields, same order, same numbers as desktop — `design/README.md` rule 1.

**What the built detail page does with the parts the designs leave open**, recorded so the next
session does not re-derive them:

- **One region order at both viewports: terms → balance → chart → history.** The desktop artboard
  leads with the terms and the mobile one with the balance; `design/README.md`'s rule 1 asks for
  the same order in both, and reordering visually with CSS while leaving the DOM alone makes
  reading order disagree with visual order. The desktop order is the one kept.
- **The chart plots every line on a shared axis of months**, not on each line's own count of
  points. A debt's pace over 18 months and the same debt at its minimum over 55 drawn "index out
  of count" each fill the width, so the short line appears to reach zero at the far right — the
  chart saying 2031 beside a panel saying 2027. Found in a screenshot, not in the suite.
- **A series point is what is owed at the *start* of a month**, so the first point at zero is the
  month *after* the debt cleared. Both ends of the *"buying you 37 months"* sentence read the
  month before that first zero, which reproduces `payoffMonth` exactly for the line the household
  is paying — that agreement is what makes it a reading of the series rather than a guess about it.
- **The at-minimum line is named as the comparison** and carries the note that a real card minimum
  falls as the balance falls, so the comparison is kinder to the minimum than reality is.
- **A form field shows an amount without the wire's trailing zeros** (`core/money.ts#trimAmount`):
  the same figure, as somebody about to edit it would write it. `445.0000` in a text box reads as
  a glitch. It is string surgery, never a parse, and assumes nothing about a currency's scale.
- **The monthly amount is editable beside the figure it changes**, on `/debt-plan`.
- **A stale balance is read from the flag, never from the source.** Past twelve months the server
  stops extrapolating: the figure reverts to the anchor and comes back `CONFIRMED` with
  `balanceStale: true`. A screen that read `balanceSource` alone would present a fourteen-month-old
  figure as a fresh confirmation, so every stale branch is checked before the source is looked at.
- **An estimate is not described as something that only grows.** It is the anchor rolled forward
  with the interest *and* the minimum taken off, so it is frequently **lower** than the figure it
  started from — $1,196.64 from an anchor of $1,240.00 in the balance-state fixtures.
- **A debt with no projection gets a sentence where the picture would be.** A payment that never
  covers its interest has an empty series; the card says why, and the legend goes with the lines
  it was describing rather than keying lines nobody drew.
- **The payment history is rendered in the order the server sent it.** Two rows can share a date —
  a payment and the statement correction that followed it — and only the server knows which came
  first; re-sorting by date in the client would put a same-day pair back into whatever order the
  response happened to arrive in, and "Balance after" would stop reconciling down from the
  headline.

### Payment actions

*The three ways a balance moves* (mobile) and *The same two actions on desktop* give three sheets,
which become dialogs on desktop with identical fields in identical order:

1. **Confirm a matched charge** — a found charge, its interest/principal split, the balance after,
   and three answers: *Yes — and always* · *Just this once* · *This isn't a Visa payment*. **Needs a
   connected account; not in slice 10** (slice 16). The *"and always"* answer is a standing rule,
   which is a `ChargeRule`-shaped idea and lands with the same slice.
2. **Log it by hand** — prefilled with what is scheduled (*"Change the amount if you paid something
   else"*), with `+$50` (rung 1 only, decision 10) and `Other` as one-tap alternatives, a date
   (no later than the device's today), the account it was paid from,
   the live split (*"$33.38 interest · $84.62 principal"*) and the resulting balance **shown before
   it is recorded**. This is the slice-10 path.
3. **Confirm the estimate** — the estimate, what it assumed, what it was last confirmed at, and
   **Confirm $8,900** / **Correct it**, with *Stop estimating* and *Log a payment instead* beneath.

**One deviation from the sheets as drawn, and one that has been closed:**

1. **The split is shown before the payment is recorded, and it is the server's.** The designs ask
   for *"$33.38 interest · $84.62 principal"* and the resulting balance in the sheet. Producing
   those in the browser would need `round(balance × apr ÷ 1200, minor unit, HALF_EVEN)` — a second
   implementation of the one formula this feature turns on, in the one place it could drift. So
   the sheet asks the server instead, on open and whenever the amount settles, and **computes
   nothing itself, not even as a fallback**.

   Three rules the sheet keeps, because each is a way the figure could be false:

   - **A late answer is dropped.** Typing produces a stream of questions whose answers can arrive
     in any order; without a sequence check a slow reply about `44` lands after a quick one about
     `445` and the sheet shows a split that is true of nothing on screen.
   - **An amount the field cannot carry asks nothing.** Empty, or a half-typed `4.`, would be
     refused and reported as a failure.
   - **A failed or refused preview never blocks recording.** The sheet says the split will be
     shown on the payment once it is recorded, and goes on accepting it. The reply is checked
     against the shape it should have before it is rendered, so a body that is merely *shaped*
     like JSON produces that same message rather than a split with three blanks in it.

   `POST
   /api/debts/{id}/payments/preview` with `{"amount": "445.00", "paidDate": "2026-08-20"}` (the
   date the sheet will record, which both clients send and re-ask on; today when absent) returns the interest, the principal, the balance the split is taken against,
   the balance it would leave, and **why the interest is what it is**: `monthInterest` (`CARRIED`,
   the month's first payment; `ALREADY_CARRIED`, an earlier payment in `interestMonth` carried it;
   `NONE`) and `interestMonth`. The sheet words the first two under the split, so a first payment
   that leaves the balance higher, or a second one with no interest, explains itself (ADR-0043). It **cannot
   disagree** with logging: `DebtPaymentService.preview` and `.log` both build the row through one
   private `draft` method — `log` saves it, `preview` reads four figures off it and discards it —
   and `DebtIT.the_preview_is_exactly_what_logging_then_records` asserts the two match, so an edit
   that routes either around `draft` fails rather than drifts.

   **A `POST` that writes nothing, and a `VIEWER` is refused it.** It began as `GET
   ?amount=`, and a URL is the one part of a request every layer logs — a reverse proxy's access
   log by default, and Spring's `DispatcherServlet` line at `DEBUG`, which masks the decoded
   parameters but prints the raw query string anyway. The security review flagged it. Moving the
   amount into a body made the route a `POST`, and this repository treats a non-`GET` as a write a
   `VIEWER` is refused; the preview serves only the log-a-payment sheet, which a `VIEWER` never
   sees, so least privilege costs nobody anything. It sits in `DebtIT.everyRoute()` with every
   other route, so the 401, CSRF, `VIEWER` and cross-household sweeps cover it on both
   transports.

   **The plan's what-if still takes its amount in the query string**
   (`GET /api/debt-plan/projection?monthlyAmount=`), and that is an accepted risk rather than an
   oversight: it is the household's monthly figure, already shown on the page, and moving a
   shipped read to a `POST` would refuse it to the `VIEWER` who may currently run it.

2. **"Stop estimating" re-anchors on the last confirmed figure.** There is no endpoint that turns
   estimation off, and inventing one in the client would mean storing a figure the app extrapolated
   as though the household had asserted it. So the action confirms `anchorBalance` at today's date:
   it stops the extrapolation using the number they themselves last gave, and says so.

**"Paid from" is not a transaction.** It records which account the household paid from, which is
where a matching charge will be looked for later. Logging a payment writes no ledger row — see the
payments rule above, and the open question.

It is `debt_payments.paid_from_account_id`, nullable, resolved through the household like every
other id in a request body, and `ON DELETE SET NULL` in the column-list form so an empty account
can still be deleted without unpicking a payment history. **Distinct from `debts.account_id`**,
which is where the debt is *held*: a card is at Chase and paid from checking, and conflating the
two would send slice 16's matching to the wrong ledger.

The first implementation of the API dropped this field, and it was worth adding rather than
deferring to slice 16 — not because anything reads it yet, but because nothing will be able to
match a charge against a payment logged before the column existed. A field whose only value is
historical has to be collected from the first payment onwards or not at all. Nothing is written to
the named account (ADR-0035), and `DebtIT` asserts that: the account's balance after logging a
payment from it is still its opening balance, and the ledger has no rows.

A `VIEWER` sees the plan, both strategies, the table, every balance, every term, the chart and the
full payment history, and **no write control anywhere** — no strategy tab that saves, no
*Use avalanche*, no *Log a payment*, no *Confirm*.

A **user guide page ships with the slice** (non-negotiable #11), captured from the running UI
against `tools/ui/fixtures/demo-data.ts`, which needs debt fixtures adding.

## Security considerations

Threat-modelled before implementation (`/threat-model`, 2026-11-23). Baseline assumptions are in
`../architecture/security-model.md`; this section records what is specific to debts, **including
the attacks ruled out**, so the analysis is not redone from scratch.

### Assets

Debts are the most disclosing records this app holds, and more disclosing than transactions.

- **The balance** is what somebody owes. **The APR** is a statement about their credit rating.
- **The servicer name** is the sharpest of the three: *Nelnet* says this household has student
  debt, a payday lender says something else again, and a medical financing company says something
  about somebody's health. One string, no amount attached, and it still discloses.
- **The payment history** is a behavioural record — who pays late, who pays extra, and when money
  appeared.
- **The plan** (`strategy`, `monthlyAmount`) is a statement of disposable income.
- **The chart series** is nothing but balances over time, so it carries everything above in a form
  that is easy to forget is sensitive because it renders as a line.

### Entry points

The seventeen endpoints in [API](#api) — the sixteen the threat model was written against, plus the
payment preview added after it, which writes nothing and is covered by the same route sweeps —
plus the four new fields on `GET /api/periods/{period}/summary`. Every one is authenticated and
household-scoped by default;
public routes remain the explicit, reviewed exception they are everywhere else.

### Attacks considered

Written as sentences, as the threat-model skill requires. An attack that cannot be finished as a
sentence is not real and is listed under *ruled out*.

**Live, and controlled:**

1. *An attacker sends `POST /api/debts` with an `accountId` belonging to another household and
   gets* a debt in their own household bound to a foreign account, which then leaks that account's
   currency and existence through their own debt screen.
   **Control:** `accountId` is resolved through the membership-scoped query before anything is
   written, and the composite foreign key `(id, household_id)` makes the row physically
   unwritable otherwise (ADR-0017). Two layers, and the second cannot be forgotten by a future
   caller.
2. *An attacker sends `POST /api/debts/{id}/payments` with a `transactionId` belonging to another
   household and gets* a payment row that discloses that transaction's amount and date.
   **Control:** same resolution path a plain transaction read uses — never taken on trust because
   the debt happens to reference the account. Identical shape for `billId` and
   `billOccurrenceId`.
3. *A `VIEWER` sends `PUT /api/debt-plan` and gets* every projection the household acts on
   changed, having been granted read-only access.
   **Control:** `requireWriteAccess` on the service, as everywhere. Setting a strategy is a write
   even though it stores two values.
4. *An attacker sends `POST /api/debt-terms/solve` repeatedly with values chosen to maximise
   bisection depth and gets* sustained CPU burn from `BigDecimal` arithmetic.
   **Control:** hard caps — 600 months of iteration, 64 bisection steps — so one call is bounded
   at ~38,400 arithmetic steps, `terms-unsolvable` when a cap is hit, plus rate limiting. It is
   called out because it is the first endpoint whose cost depends on the numbers in the request
   rather than on how much the household has stored.

   **This threat model was wrong about which endpoint is the expensive one, and the security
   review that followed implementation said so.** `GET /api/debt-plan/projection` also takes
   `monthlyAmount` from the request, and that parameter drives the iteration count from one month
   to the full cap — so it is strictly more expensive than the endpoint this control protects: the
   plan runs the ladder two or three times (avalanche, snowball, and the selection when it is
   `CUSTOM`), and the ladder **retains** a `ProjectionMonth` per rung per month. At the 50-debt
   cap that is 50 × 600 × 3 retained rows, against the solver's 38,400 allocation-free steps.
   What was done about it, and what was not, is in **Known gaps** below.

**Found by this threat model and not previously specified** — the feature doc had the solver
bounded and these unbounded:

5. *An attacker creates ten thousand debts and then calls `GET /api/debt-plan`, and gets* a
   projection over both strategies — the plan computes both so it can show the comparison — at
   O(debts × months) each, from a single cheap request.
   **Control:** a per-household cap on debts, enforced the way `CategoryService` enforces its 200
   (`countByHouseholdId` before insert, `409 debt-limit-reached`). The number belongs in the
   implementation; what matters here is that an unbounded one is a remote amplification of a
   `POST` into an expensive `GET`.
6. *A household with years of history calls `GET /api/debts/{id}/payments` and gets* every payment
   ever recorded in one response.
   **Control:** the page envelope `api-style.md` requires of every collection. Noted because the
   admin console already has the one unpaginated collection in the API and it should stay the
   only one.
7. *A caller requests `GET /api/debt-plan` (or the what-if, with any `monthlyAmount`) and gets* a
   response carrying two strategies × every debt × up to 600 months of series data.
   **Control:** the series returned is bounded independently of the iteration bound — the
   projection may iterate 600 months internally while the response carries a capped series. A
   number a human reads off a chart does not need monthly resolution to year 50.

### Ruled out, with reasons

- **Reading another household's debt by id.** `GET /api/debts/{id}` answers `404` for both a
  missing row and one belonging to somebody else, as everywhere since ADR-0008. There is no
  oracle to build.
- **Information disclosure through the solver.** `POST /api/debt-terms/solve` reads no stored
  data at all — it is arithmetic over the request body. It discloses nothing because it knows
  nothing. It is still authenticated and rate-limited, for the cost reason above rather than a
  confidentiality one.
- **A background job to roll balances forward.** There is none: `ESTIMATED` is computed on read
  and capped at twelve months. No scheduled task means no scheduled task to attack, and no
  balance that moves while nobody is looking.
- **Tampering with the interest/principal split to misstate history.** The split is stored rather
  than derived precisely so a later APR change cannot rewrite it, and `interest + principal =
  amount` holds by construction. A household editing their own payment record is not an attack;
  it is the feature.
- **Cross-currency confusion as an attack.** `CHECK (currency = account.currency)` cannot be
  expressed in SQL, so the service enforces it — but a household mis-stating its own currency
  harms only itself, and `account_currency_immutable` (slice 3) stops the account moving
  underneath it afterwards.

### Controls, and where each is enforced

| Control | Layer |
|---|---|
| Household scoping on every read and write | Service (`requireWriteAccess` / membership query) **and** composite FKs in the schema |
| `VIEWER` may read everything, write nothing | Service |
| Degrees-of-freedom rule (at most two of `apr`, `payment`, horizon) | **Service** and a `CHECK` constraint. Not Bean Validation, which this table used to claim: validation can only answer `400`, the contract here is `422 terms-over-determined`, and a `PATCH` is over-determined only once the three-way changes are merged into the stored row. Shape constraints stay at the edge |
| Solver iteration and bisection caps | Service, with `terms-unsolvable` as the refusal |
| Solver rate limiting | The existing `RateLimitedException` path |
| Per-household debt cap | Service, `409 debt-limit-reached` |
| Paginated payment history | Controller, page envelope |
| Bounded chart series | Service, independent of the iteration bound |
| No amounts, names or servicers in logs | Redacted `toString` on every DTO and entity; audit lines carry ids only |
| Amounts as strings, scale within the currency's | Bean Validation, `NUMERIC(19,4)`, ADR-0006 |
| A blank `name` is refused, not a `500` | `@Pattern(NOT_BLANK)` at the edge, Unicode-aware. Added after review: `@Size(max)` alone passes `"   "`, which reached the entity and threw `IllegalArgumentException` — a `500` with a stack trace at `ERROR`, loopable by any authenticated caller. Slice 3 found this on `/api/accounts`; this DTO reintroduced it |
| The payment cap binds **every** writer of a payment row | One shared `requireRoomForAPayment`, called by `log` and by `confirmBalance`. Added after review: the cap lived in `log` alone while `POST /{id}/balance` also inserts a `STATEMENT_CORRECTION` row, so the documented bound could be walked past one request at a time |
| `customOrder` is bounded | `@Size(max = 50)` at the edge — the only collection-valued field in the API, and there is no server-wide body limit |
| A horizon outside what a projection covers is refused in **both** directions | `Horizon.isSolvableSpan`, shared by the two callers that build one from a request. Added after review: `monthsLeft` was bounded both ways and `payoffMonth` only from below, so a wide enough year overflowed the month arithmetic |

### Accepted risks

- **Rate limiting is in-memory and per-instance**, as `AuthRateLimiter` already documents. Adequate
  for one household on one box; it would not survive a multi-instance deployment, which this
  product does not have (ADR-0026).
- **A household can tell itself a comfortable lie.** Nothing stops somebody entering a balance
  lower than reality, and the app will faithfully project a payoff date from it. `balanceSource`
  and the confirm/correct flow exist to make the provenance visible, not to police it. The
  operator owns their data (ADR-0026) and this is their plan, not ours.

## Edge cases

- **A minimum that never clears the balance** — accepted, projection `null` with `never-clears`, the
  plan still renders. The common real case, not an exotic one.
- **0% APR** — a real promotional and car-loan rate. Interest is zero, the recurrence still
  terminates, and nothing divides by a rate.
- **A payment larger than the balance** — clears the debt and shows the overpayment. Not clamped.
- **A debt reaching zero** — `paid_off_at` is set, it leaves the ladder, the debt-free month becomes
  the latest of the *remaining* debts, and it is listed under paid-off with its full history intact.
  Writes to it are `409 debt-paid-off` until it is reopened by a correction.
- **Deleting the bill that a debt is linked to** — the link is nulled, so that minimum starts
  counting into the Bills & Income committed figure. **The committed total therefore rises when a
  bill is deleted**, which is correct and surprising; the page says why.
- **A debt with no payments at all** — projects from its anchor. Nothing about the chart or the
  history requires a payment to exist, only to render differently.
- **Month-end** — a payment anchored on the 31st clamps in February, the same way a bill schedule
  does.
- **Two members editing terms at once** — `409 stale-version`. The plan has its own version, so two
  members changing strategy collide too.
- **Mixed currencies** — grouped per currency with no combined total. Never a converted sum.
- **An estimate that has run for a year** — reported as stale rather than estimated; the screen asks
  for a figure instead of extrapolating further.
- **A linked account that is archived** — the debt is untouched. An archived account is a retired
  container, not a settled debt.

## Out of scope

- **Auto-matched payments and the "and always" standing rule** — needs a connected account
  (slice 16). The `AUTO_MATCHED` provenance and the `transaction_id` column exist from day one so
  that nothing has to be backfilled; the matching, the confirm sheet and the rule do not.
- **`LINKED_STATEMENT` balances** — same slice, same reason.
- **The by-hand transaction picker.** `transactionId` is accepted by the API and uniqueness is
  enforced; the UI for choosing a transaction from the ledger arrives with import or matching,
  exactly as it does for bill occurrences.
- **Promotional and variable APR windows.** The canvas's own instance-console changelog lists
  *"Debt payoff projections account for promotional APR windows"* as a **future** item, which is as
  clear a scope signal as it gets. A debt has one rate in slice 10.
- **Percentage-based minimums.** A card's real minimum is a percentage of the balance; slice 10
  stores the flat figure the statement printed. The consequence is stated under Known gaps.
- **Onboarding step 2 (*What you owe*).** Onboarding is not a roadmap slice; the three fields it
  captures — balance, APR and minimum — are exactly the required fields of `POST /api/debts`, so
  building it later needs no new API.
- **The weekly check-in's surplus allocation** (*"You came in $63 under → Visa payoff · Pulls Apr
  2027 in by 4 days"*) — slice 14. Slice 10 provides the projection it will call; it does not build
  the step.
- **The home screen's debt-free widget and its *"4 months earlier than last month's plan"* delta.**
  Slice 10 writes the snapshot that makes the comparison possible and renders no delta beyond the
  plan page's own live what-if.
- **Owl's *"snowball vs avalanche"* suggestion chip** — slice 17, and optional (ADR-0027).
- **Refinancing, consolidation, extra one-off lump sums as a plan input, and "what if I stopped
  paying".** None are designed.

## Known gaps

- **Small rough edges from the slice-10 user-guide pass (2026-11-25), none blocking:**
  - **Viewer wording:** a `VIEWER`'s *Order of attack* card still says *"so you can compare before
    you choose"*, though a Viewer can't choose.
  - **Lost arrangement:** switching from *Your own order* to Avalanche and back starts again from
    the on-screen avalanche order, so the household's earlier arrangement is lost. Read from the
    code, not confirmed on screen.
  - **No visible confirmation after a move:** a move only redraws the table. The *"X is now rung 2
    of 3"* announcement is a screen-reader live region.
  - **Guide capture helper:** `tools/ui/helpers/doc-capture.ts` draws the highlight ring in the
    wrong place when a capture is both cropped and highlighted.
  - **Unreproducible guide captures:** they don't run on a fixed clock, so date fields show
    today's date.
  - **Recorded banner is hand-built:** the user-guide spec answers **Record it** with a response
    built from the generated preview. `DemoFixtureIT` should generate a real logged-payment
    response instead.

- **A plan read retains a row per rung per month, and is not rate-limited.** `PayoffLadder.run`
  keeps a `ProjectionMonth` for every rung in every month, so a plan read at the 50-debt cap over
  a ladder that never clears retains 50 × 600 × 3 rows — roughly 25 MB, transient, from an
  unmetered `GET` that any member or `VIEWER` can loop. Two things were done and one was not:

  - **Done.** A plan that allocates nothing is no plan. A household whose minimums are all zero
    has `minimums == 0`, so a monthly amount of zero was *not* "below the minimums" — every rung
    was paid nothing, every balance grew, nothing ever cleared, and all 600 months were iterated
    for all 50 rungs. `PayoffLadder.project` now answers `plan-below-minimums` for a non-positive
    monthly amount, which removes the pathological case.
  - **Done.** The 50-debt cap bounds the rest, and the response series is bounded independently
    (60 points per line).
  - **Not done, deliberately.** The obvious structural fix — accumulate the series as the ladder
    runs and stop retaining the rows — was rejected because those rows are **load-bearing for the
    property tests**: the invariants in `PayoffLadderTest` that "the monthly amount is spent
    exactly every month but the last" and that avalanche never costs more interest than snowball
    beyond rounding (ADR-0036, found by a counterexample search) are asserted *from* the per-rung per-month
    allocations. Trading a proven property for a memory bound is the wrong trade. The remaining
    cost is proportional to real data under a cap, so it is recorded rather than metered: the
    alternative was rate-limiting the household's own plan page, which for a two-person
    self-hosted instance costs more than it buys.
- **The debt figures on the period summary are not period-scoped.** `GET
  /api/periods/{period}/summary` passes the period to income, bills and envelopes, and asks the
  debt service for *today's* figures. For `debtMinimums` that is consistent with how income
  already works — today's recurring commitments applied to the month asked for — but `owed` and
  `debtCount` are point-in-time facts about now, and a historical month's heading over today's
  balance is the wrong one. There is no fix available in this slice: a balance as at a past month
  needs a per-day ledger the schema does not keep (see the back-dated payment gap below). **The
  Owed card must therefore label itself as of today rather than appearing to belong to the
  month**, and a past period is where that matters.
- **`debtCount` and the two totals beside it do not describe the same debts.** On both `GET
  /api/debts` and `GET /api/periods/{period}/summary`, the count spans **every** currency while
  `owed`, `debtMinimums` and `debtMinimumsNotInBills` are the household's base currency only
  (ADR-0022) — a combined figure would need a rate the instance may not have. A household with one
  foreign-currency debt therefore sees "3 debts" over a total covering two of them. Not in the doc
  before the Angular work; found while reading the backend.
  **How it is handled rather than hidden:** the **Owed** card states it in words — *"The count
  covers debts in every currency; the two totals are &lt;base&gt; only. They do not describe the same
  set of debts…"* — and `/debt-plan` groups per currency, naming each group, with no combined
  debt-free date or interest total. The alternatives were both worse: dropping the count loses the
  only figure that *is* complete, and scoping the count to the base currency hides that the other
  debts exist. A real fix needs per-currency totals on the summary, which is a wire change.
- **An absurd magnitude is accepted and then fails as a `409`.** `AMOUNT` allows 15 integer
  digits, the entire width of `NUMERIC(19,4)`, leaving no headroom for the interest computed on
  it. A balance of `999999999999999.9999` at 999.999% overflows the column on the next payment and
  surfaces as the handler's generic conflict, which is both wrong and unexplainable; the estimator
  reports balances around 10^173 in the meantime. This matches every other slice's pattern, so it
  is a house-wide note rather than a slice-10 regression — but this is the first slice that
  multiplies a balance by a rate six hundred times, so it is the first where it shows.
- **`POST /api/debt-terms/solve` has a typed service and a test and no caller.** The solver panel
  renders what `GET /api/debts/{id}` already solved; a terms editor that re-solves as you type is
  what the endpoint is for, and that is the field-level editor the detail page does not yet have.
- **The *"buying you"* sentence names months and no money.** The designs also claim a saving
  (*"27 months and $2,890"*), which is the difference between two interest totals; the API carries
  the total for the payment being made and not for the minimum, and subtracting two figures in the
  browser is arithmetic on money whichever two they are.
- **The what-if adds its £50 in the browser.** `GET /api/debt-plan/projection` takes an absolute
  `monthlyAmount`, so *"what would £50 more do"* can only be asked by computing `monthlyAmount +
  50` client-side. It is done through `core/money.ts`'s exact `bigint` path and sent to a read-only
  endpoint that writes nothing, and every figure in the answer comes back from the server — but it
  is still the client doing arithmetic on money, which the style guide otherwise forbids. An
  `extra=` parameter on that endpoint would remove it.
- **The chart headline names the debt-free month, not a count of months.** The designs say
  *"$26,480 → $0 · 31 months on the current plan"*; no response field carries a month count, and
  deriving one from the series length is wrong once the line is thinned (`seriesMonthStride`). The
  page says *"£26,480.00 to clear · debt-free June 2029 on this plan"* instead, which is the same
  fact at the precision the arithmetic supports.
- **The comparison sentence states no difference figure.** *"Avalanche costs $780 less"* is a
  subtraction of two amounts, which the browser may not do; the page says which order costs less
  and which clears the first debt sooner, with both totals on the cards beside it. The delta it
  *does* give is in **months**, which is date arithmetic rather than money arithmetic.
- **A flat minimum makes the "at minimum" line optimistic.** A real card minimum falls as the
  balance falls, which stretches the payoff far beyond what a flat figure projects. The comparison
  line is therefore kinder to the minimum than reality is, in the one direction that matters.
- **The debt balance and the account balance can disagree.** Logging a debt payment without
  recording the transaction leaves the account too high. Bills have the same gap for the same
  reason; both are visible rather than hidden.
- **A `CREDIT_CARD` debt linked to a `CREDIT_CARD` account has two balances** — the account's,
  derived from transactions, and the debt's, derived from the anchor and its payments. They are
  deliberately not reconciled: the debt's is what the lender says and the account's is what the
  household recorded. The detail page shows the comparison (*"matches my math to the cent"*) and
  nothing automatically adjusts either.
- **Nothing records who logged a payment or changed the strategy.** The same gap bills, transfers
  and occurrences have.
- **Three bounds came from the threat model, not the designs**, and are recorded there rather than
  here because they are controls: a per-household debt cap, a paginated payment history, and a
  chart series bounded independently of the solver's iteration limit. Without them a cheap `POST`
  loop amplifies into an expensive `GET`.
- **A rate that can be neither entered nor derived is ranked as 0%.** A debt with an entered
  payment and horizon and no rate implies one — until its balance falls far enough that the payment
  clears it before the horizon at *every* rate under 1000%, at which point no rate follows from the
  three figures. The debt still gets its minimum and its place in the ladder, with
  `aprDerivable: false` and `apr: null` on the wire, and the detail page says the rate is not
  available rather than showing a figure nobody can check. Ranking it at 0% puts it last under
  avalanche, which is the wrong end for a card: the alternative — dropping it from the plan — would
  understate the household's commitments, which is worse. Fixed by the household entering the rate.
- **A back-dated payment's split is taken against today's balance.** The interest on a payment the
  household forgot last month is `round(balance_now × apr ÷ 1200)`, not the balance as it stood on
  that date: reconstructing a balance as at an arbitrary past day needs a per-day ledger this slice
  does not keep. The stored split is still what it always is — what was true when it was recorded —
  and `interest + principal = amount` holds. *Whether* it carries interest is decided for its own
  month (ADR-0043), so a payment back-dated into a month with no payment yet carries that month's
  interest, taken against today's balance.
- **The projection is monthly and nominal.** It will differ from a lender's daily-accrual figure,
  typically by a few pounds a month and occasionally by a whole month of payoff date.
- **The projection's month 1 is the server's UTC month.** A payment's own month follows the date
  the household gave it (The split, rule 8), but which month a projection starts in, and so which
  payments count as this month's (decision 10), comes from the application `Clock`. Near a month
  boundary, a household well east or west of UTC will disagree with the server for up to about
  fourteen hours about which month is current. For those hours the plan may count a payment dated
  the 1st as next month's, or plan a month the household has already left. That is an accepted
  risk and a house-wide fact (`reporting.md`, decision 10), and it moves a projection, never a
  stored split.
- **A payment back-dated before the anchor can say it carries interest that moves nothing.** The
  balance is the anchor plus the payments dated on or after it (_The anchor is the figure as at
  the start of its date_). A payment dated earlier is in the history but not in the balance, and
  if its month has no other carrier its split takes that month's interest (`CARRIED` in the
  preview). Both the interest and the principal are then outside the balance, so the balance is
  unchanged either way; only the split on that history row reads as charged. Not changed, because
  the alternative is a second rule for whether a month can be charged, keyed on the anchor, for a
  row that affects no figure. A statement confirmation is the real answer to an old payment.

## Decisions

These were open forks the designs do not settle. The repository owner ruled on all seven on
2026-11-23, before any code was written, which is the point of writing this doc first. Each one
below is settled; the reasoning that was weighed is kept so it is not re-argued from scratch.

**An eighth, decided during implementation rather than before it, and flagged for reversal.**
Building the page surfaced a gap this doc had: its UI section gives **no control that sets
`monthlyAmount`**. Every date on `/debt-plan` — the debt-free month, each rung's clear date, the
whole chart — derives from that one figure, and there was nowhere in the application to enter it.
The three options were a field on `/debt-plan` beside the header strip that already displays the
figure; a field in the Add-a-debt dialog, which is wrong because it is plan-level rather than
per-debt; and deferring to slice 11, since this doc says the extra *"comes from what's left after
your goals"* and goals may want to compute it.

It is built as a plain editable amount on `/debt-plan`, because the alternative ships a page whose
every number comes from a value the household can neither see the source of nor change. **If
slice 11 computes it from goals, this becomes the manual override rather than dead work** — which
is the reason this was a safe call to make without waiting: the shapes do not conflict.

1. **A debt payment does not write a transaction.** It is a plan record with a nullable
   `transactionId`, so a household can attach the ledger row they already recorded. Deleting that
   transaction leaves the payment standing and **does not move the debt balance** — the link is
   evidence, not the source of truth. Recorded as **ADR-0035**, because this sits precisely between
   ADR-0025 (envelopes never auto-file a transaction) and ADR-0033 (a transfer *is* two linked
   rows), and without an ADR it will be reopened. The rejected alternative is an opt-in "also record
   this in my ledger" checkbox, which is defensible today and double-counts the moment file import
   (12) or bank connections (16) arrive.
2. **Bills & Income's "what's left" subtracts debt minimums.** The page gains an **Owed** card as a
   fourth block, and `left` loses `debtMinimumsNotInBills` — only the minimums not already carried
   by a bill row, so nothing is counted twice. The fourth term is shown beside the other three so
   the subtraction is visible rather than mysterious.
   **This changes a figure that has already shipped**, so it is not a quiet edit:
   `docs/features/bills-and-income.md`, the glossary's **Left over** entry, and the user guide's
   "See what's left this month" all move in the same change, and the user guide says what changed
   and why.
3. **Marking a linked bill's occurrence paid prompts once.** *"Also record this against Car loan?"*
   — and on acceptance writes a `USER_LOGGED` payment carrying the occurrence's actual amount and
   its `bill_occurrence_id`. Never silently: a balance that moves on its own cannot be audited, and
   this app's whole claim is that every number traces to something a person recorded.
4. **Balance estimation ships, capped at twelve months.** `ESTIMATED` is the one place the app puts
   a number it invented in the headline position, so it is marked as an estimate on screen wherever
   it appears, and the confirm/correct actions ship with it. The cap is a refusal to extrapolate
   indefinitely: past twelve months with no confirmation the debt shows its last confirmed balance
   and that date instead.
5. **The mobile plan is the ladder**, not a condensed table — stacked cards in payoff order, each
   carrying its amount and projected payoff month. It states the allocation rule in words, which a
   table conveys only through two numbers in a cell, and it is how envelopes and bills already
   reflow.
6. **`originalBalance` is an optional progress baseline**, defaulting to the balance at creation,
   with progress simply absent when it is absent. It is **not** labelled "original balance" on a
   revolving card, where the phrase means nothing.
7. **One `PayoffPlan` per household** (ADR-0026). The consequence is recorded rather than hidden: a
   household with debts in two currencies gets one strategy and two ungrouped projections, which is
   the mixed-currency gap in *Known gaps* seen from another angle.

Decided on 2026-11-25, during implementation, and numbered after the eighth above:

9. **Interest is rounded to the currency's minor unit, not to four places.** `interestFor` rounds
   `balance × apr ÷ 1200` half-even to `Currency.getDefaultFractionDigits()` — cents for USD, whole
   yen for JPY, four places for a currency that defines no minor unit — and the figure is stored at
   `NUMERIC(19,4)` as before. Four places made a split impossible to show honestly: the designs'
   Visa at $6,180.00 and 22.9% accrues $117.935, and a $495 payment read *"$117.94 interest ·
   $377.07 principal"*, which is $495.01, while displayed balances in the history drifted by a cent
   from row to row. Rounded to the cent, a payment in cents splits into two cent figures that add up
   exactly, and every balance stays on the cent grid, as on a lender's statement. Reconciling on
   display instead was rejected because it fixes the split and not the balance column. **This
   replaced scale-4 rounding before slice 10 ever merged to main**, so no stored row predates it
   and there is nothing to migrate. One consequence: avalanche never costing more than snowball now
   holds to within rounding rather than exactly (*Testing notes*, `PayoffLadderTest`).

10. **A projection counts this month's payments as paid** (owner, 2026-11-27, with ADR-0043). A
    projection made mid-month plans only what is still to come. Month 1 is the current month, and:
    - its interest is not charged when a payment recorded this month already carried it;
    - **the month's amount loses everything already paid** towards the debts this month, and each
      debt's minimum loses what was paid towards that debt. Minimums are still met (they are owed
      whatever was paid elsewhere), only the extra goes, nothing is ever planned negative, and an
      overpayment is not carried into later months: the balance already reflects it;
    - statement corrections are not payments and do not count; **neither does a check-in's
      surplus**, which the household pays *on top of* the plan (`weekly-check-in.md`, decision 5).
      Counting it would take it off the plan's own payment, so paying the surplus could never move
      a payoff date, and the check-in's debt choice would always say *"Doesn't move the month"*;
    - both strategies get the **same** month-1 cash, because the month's amount is netted rather
      than each debt's allocation. Netting each debt's own share would hand an order more to spend
      this month whenever the paid debt sat lower in it, and the comparison would stop being like
      for like (`PayoffLadderTest.bothStrategiesPutTheSameCashIntoAPartlyPaidMonth`);
    - the **standing allocation** the page shows (*"$185 minimum + $260 extra"*) is unchanged: it
      is read off month 1 before anything paid is counted.

    It applies wherever month 1 is projected: the ladder, the +$50 what-if (the same ladder), a
    debt's own projection and the solver's payment and rate searches (`FirstMonth`, carried on
    `LadderDebt` and `DebtTerms`), and the check-in's what-if (`DebtFacts.afterCheckInPayment`).
    The demo Visa ($6,180.00 at 22.9%, its $445 for August logged on Aug 20, read on Aug 20):

    | | Before ADR-0043 | ADR-0043, month 1 still planned | Counted as paid (now) |
    |---|---|---|---|
    | Months left | 17 | 16 | 18 (August included, with nothing left to pay) |
    | Gone by | Dec 2027 | Nov 2027 | **Jan 2028** |
    | Interest still to come | $1,069.36 | $910.35 | **$1,069.36** |
    | Plan interest, avalanche / snowball | $2,847.88 / $2,896.49 | $2,677.02 / $2,727.28 | **$2,880.41 / $2,923.93** |
    | First debt gone | Dec 2027 | Nov 2027 | **Jan 2028** |
    | Debt-free | Apr 2029 | Apr 2029 | Apr 2029 |

    The "now" column is the true one. August is paid; from September the Visa takes 17 more
    payments and $1,069.36 of interest, exactly the old figures a month later. The old column was a
    month early because it planned a second August payment and charged August's interest again,
    and the middle column was two months early because it kept the payment and dropped only the
    interest. The plan's total rises, because August's $260 of extra was already spent on the Visa
    and nothing more reaches the other debts this month.

    **A debt paid off this month still counts.** It leaves the ladder's rungs, but what was paid to
    it this month was spent from this month's amount. `PayoffPlanService.paidOutsideTheLadder`
    sums it (unarchived, same currency, not in the plan) and the ladder takes it off month 1 apart
    from the rungs (`PayoffLadder.project(…, paidOutsideTheRungs)`). Forgetting it handed the other
    debts the whole month again (`MonthInterestIT.paying_a_debt_off_to_the_cent_still_counts…`,
    `PayoffLadderTest.whatWasPaidToADebtThatLeftTheLadder…`). The check-in's what-if adds the
    surplus's own debt to it when the surplus would clear that debt. That only moves the
    debt-free month, which no screen shows yet, so it is covered by the same code path and not by
    a test of its own.

    **The consequence the owner accepted: an extra paid to the wrong debt makes the plan worse.**
    Counted as paid, an extra logged to a debt below rung 1 is money the plan would have sent to
    rung 1 this month, so the dearest debt gets less. Two debts on $500 a month, avalanche:

    | $500/mo, 25% $5,000 (min $100) and 3% $5,000 (min $100) | 25% debt clears | Debt-free | Plan interest |
    |---|---|---|---|
    | Nothing extra this month | Oct 2027 | Jun 2028 | $1,053.16 |
    | $300 extra logged to the 3% debt | **Nov 2027** | Jun 2028 | **$1,136.26** |
    | $300 extra logged to the 25% debt | Oct 2027 | May 2028 | $911.56 |

    That is true arithmetic about where the money went, so the plan says it rather than hiding it.
    The opposite holds for the debt the plan would pay anyway: **an extra logged to rung 1 never
    makes the plan later or dearer** (`PayoffLadderTest.anExtraLoggedToTheFirstRungNeverMakesThePlanLaterOrDearer`,
    over random ladders and extras). So **the payment form's _"+$50 more"_ shortcut is offered on
    rung 1 only**, on the web and the phone (`debt-detail.page.spec.ts`: "offers fifty more only on
    the plan's first rung…", `debt_screens_test.dart`: "fifty more is offered on the plan's first
    rung only"). Offering it on a lower rung would be the app suggesting a move that can make the
    plan worse. Any amount can still be typed for any debt.

## Where the designs and the docs disagree

Recorded rather than smoothed over, as the designs are the source of visual intent and these are
places where it cannot be taken literally.

- **The canvas's plan figures do not agree with each other, and the demo fixtures follow the ones
  that do.** The artboard carries four numbers for one plan: *"$645/mo committed · $560 of it
  strategic"*, *"Both lines assume $560/mo"*, *"$185 minimum + $260 extra"* on rung 1, and
  *"31 months"*. A surplus of $560 and a rung-1 extra of $260 cannot both be true with one debt
  taking the whole surplus. Running the real solver over the designs' own three balances settles
  which set is coherent:

  | Monthly amount | Surplus | Rung 1 pays | Debt-free |
  | -------------- | ------- | ----------- | --------- |
  | $1,205 (645 + 560) | $560 | $745 | 24 months |
  | **$905 (645 + 260)** | **$260** | **$445** | **33 months** |

  The second row makes *"$185 minimum + $260 extra"* exactly right, and $445 is the figure this
  document's own worked example uses. Neither row gives 31 months. So the fixtures use **$905**,
  and *"$560 of it strategic"* and *"31 months"* are the canvas's errors — the second one in the
  same family as the $118.35-versus-$117.94 discrepancy already recorded below.

- **Avalanche and snowball barely differ for the designs' own debts**, which the comparison
  artboard does not prepare you for. Over $6,180 at 22.9%, $10,650 at 6.4% and $9,650 at 4.5%, the
  two orders clear in the **same month** and differ by **$48.61** of interest in total. The screen
  is honest about it — *"Avalanche costs less in interest, and both clear your first debt in the
  same month. Either is a good answer."* — but it means the strategy picker's own demo data makes
  the weakest possible case for it, and the balance-to-zero chart draws two lines that coincide.
  That is a true fact about three debts of these sizes rather than a defect, and the page says so
  rather than choosing flattering numbers.

- **"Give me any four and I'll solve the rest" overstates the arithmetic.** The canvas's panel
  shows eight cells and `../domain/model.md` reads it as *seven mutually constrained fields*. There
  is one equation among **four** quantities, and `originalBalance`, `minimumPayment` and
  `totalInterestLeft` are not in it. The panel's own *"4 entered · 3 solved"* count does close —
  but only under the reading above, where the eighth cell comes from the plan rather than from the
  solver. The honest copy is *"any three of these four"*; worth a change in the design project
  rather than a local fudge.
- **The canvas's figures do not reconcile, and are not test fixtures.** At 22.9% on $6,180 the
  monthly interest is $117.94, not the $118.35 the payment table shows — and the page claims it
  *"matches my math to the cent"*. The estimate screen is clearer still: $9,254 less three $118
  payments is exactly $8,900, which is the figure shown, so the estimate ignored three months of
  interest (the correct figure is about $9,003). The numbers are an invented persona
  (`design/README.md`); take fixtures from `tools/ui/fixtures/demo-data.ts` and compute them.
  **Implementation follows the arithmetic, and the estimate's copy should say it assumes the
  payments *and the interest in between*.**
- **The plan header's totals do not add up either.** *"$645/mo committed · $560 of it strategic"*
  sits above a ladder paying $445 + $342 + $118 = $905/mo, and $645 is the sum of the three
  minimums. Define the two terms in the API — `monthlyAmount` and `Σ minimums` — and do not copy the
  figures.
- **The check-in implies day-precision payoff dates** — *"Pulls Apr 2027 in by 4 days"*, *"Apr 2027
  comes in 4 days"*. Monthly compounding cannot produce that. Express what-if deltas in **months**
  (*"moves the date up 3 months"*, which the debt plan artboards themselves use) and flag the
  check-in copy for slice 14.
- **The canvas's Bills & Income page is not the page slice 7 shipped.** The canvas shows three
  summary cards — *Income · monthly*, *Committed out · monthly*, *Owed · lives on the debt plan* —
  where the shipped page leads with **What's left** and its three terms. Slice 10 should add the
  **Owed** card without rebuilding the headline; open question 2 is the part that needs deciding.
- **`servicer` is a field the domain model does not have.** The designs show *Credit card · Chase*,
  *Installment · Alpine Auto Finance* and *Installment · Nelnet · manual entry*; neither `Debt` nor
  `Account` carries an institution name today. Added above as nullable text.

## What slice 10 has built

The API, the services, the entities and the repositories, plus the four new fields on the Bills &
Income summary. **Not** the two Angular routes, the user-guide page or the debt fixtures in
`tools/ui/fixtures/demo-data.ts` — those are the rest of the slice, and non-negotiables #8 and #11
still apply to them.

**A `security-auditor` pass ran against the finished backend** (CLAUDE.md requires one on anything
touching money movement or a data boundary, including our own work). It could not construct a
cross-household read or write, a `VIEWER` write, or a `403`-versus-`404` existence oracle anywhere
in the slice — the authorization surface, the composite foreign keys and the role matrix all held.
Everything it did find was input validation, a resource bound, or a comment asserting a rule
nothing enforced. Six things changed as a result, each with a test that was proved to fail first:

| Found | Now |
|---|---|
| A blank `name` on `PATCH /api/debts/{id}` was a `500` with a stack trace, loopable — slice 3's audit finding, reintroduced on a new DTO | `@Pattern(NOT_BLANK)`, Unicode-aware, `400` |
| `POST /{id}/balance` wrote payment rows without consulting the payment cap, so the documented bound could be walked past | One shared `requireRoomForAPayment`, called by both writers |
| `customOrder` was an unbounded collection with no server body limit | `@Size(max = 50)` |
| `payoffMonth` was bounded only from below, so a wide enough year overflowed the month arithmetic into a `500` | `Horizon.isSolvableSpan`, both directions, one definition, plus a window on `firstPaymentMonth` |
| Five projection and plan records had no redacted `toString`, against the control this doc asserts | All five redacted |
| An unpaged `findAllForDebt` with a javadoc inviting the next author to call it, and two comments describing code that did something else | Deleted; the other two corrected, and the "before anything is committed" one made true by construction |

It also found the counted-once rule was **still** wrong after the first fix — see the three-case
table above — and that this doc's own threat model named the wrong endpoint as the expensive one.
Both are recorded where they were wrong rather than only where they were fixed.

**The numbers the threat model left to the implementation**, each a constant with its reasoning
beside it in code:

| Bound | Value | Where |
|---|---|---|
| Debts per household | 50 | `DebtService.MAXIMUM_PER_HOUSEHOLD`, `409 debt-limit-reached` |
| Payments per debt | 2,000 | `DebtService.MAXIMUM_PAYMENTS_PER_DEBT`, `409 conflict` |
| Points in one chart line | 60 | `PayoffPlanService.MAXIMUM_SERIES_POINTS` |
| Estimated months | 12 | `DebtBalanceService.MAXIMUM_ESTIMATED_MONTHS`, then **stale** |
| Solves per caller per minute | 60 | `TermsSolveRateLimiter`, `429` with `Retry-After` |

Past the series bound the line is **thinned, not truncated** — every *n*th month, with the first
and last points always kept — and `seriesMonthStride` travels with it, because a truncated line
would show a fifty-year plan clearing in five years and a client that assumed one point per month
would mislabel the axis of exactly the long plan the cap exists for.

**The rate limiter is new rather than reused.** `AuthRateLimiter` counts *failures* and backs off
exponentially from the fifth, which is right for a login and wrong for a terms editor where a run
of successful calls is the normal case. `TermsSolveRateLimiter` is a fixed window per caller, with
the same refusal (`RateLimitedException` → `429`, finite `Retry-After`) and the same accepted risk:
in memory, per instance (ADR-0026).

**Two rules the doc above could not state precisely enough to implement**, recorded here because
they decide what a balance reads as:

1. **The anchor is the figure as at the *start* of its date.** The balance is the anchor minus the
   principal of every payment dated **on or after** `anchorDate` — not strictly after. Strictly
   after means a household that records a debt from today's statement and then logs the payment it
   made today watches nothing happen, which is the first thing anybody does. The exception is a
   `STATEMENT_CORRECTION` dated exactly on `anchorDate`: that is the row which *explains* where the
   anchor came from, so applying it again would count the statement's disagreement twice. It still
   stands in the history, which is the point of recording it rather than adjusting silently.
2. **Confirming a balance writes an anchor that absorbs that date's payments.** `anchorBalance`
   becomes the confirmed figure *plus* the principal already recorded on or after that date, so the
   derivation above returns the statement figure exactly. In the ordinary case — a statement dated
   after the last payment — that sum is zero and the anchor is the statement figure unchanged.

**A rate is solved against the anchored balance**, before estimation: estimating needs a rate and
solving a rate needs a balance, and taking the pre-estimate figure is the one order that
terminates.

**Not built, and deliberately:** decision 3's prompt on marking a linked bill's occurrence paid.
That is a change to `PUT /api/bills/{id}/occurrences/{date}` and a UI prompt, neither of which is
one of the sixteen endpoints here. The column, the uniqueness rule and the scoped resolution all
exist — `POST /api/debts/{id}/payments` accepts a `billOccurrenceId` and refuses another
household's or a second payment against the same occurrence — so the prompt is the only part left.

**Deviation from the controls table:** the degrees-of-freedom rule is enforced in the **service**
(`422 terms-over-determined`, naming the fields) and by `ck_debts_degrees_of_freedom`, rather than
by Bean Validation at the edge. Bean Validation can only answer `400 validation-failed`, and the
API table above names a `422` with its own code — and a `PATCH` is only over-determined once the
three-way changes have been merged into the stored row, which is a judgement the edge cannot make.
The request DTOs still carry the shape constraints (`@Pattern` on every amount and the rate), which
is what keeps a malformed figure a `400` naming its field.

## Testing notes

Beyond `../guides/testing-style.md`. The solver is the part that will be wrong silently, so most of
this is about it.

- **Interest once per debt per month (ADR-0043).** `MonthInterestTest` is the rule as a unit:
  first, second, back-dated, another month, another debt, a month whose rows carry no interest,
  the payment being corrected, a zero rate, nothing owed, a payment smaller than the interest.
  `DebtLockingIT` covers the locks on the other writers, the order a completion and your own order
  take debts in, the fresh read under the lock and the bounded wait. Its waits are scoped to the
  test's own connections (`pg_blocking_pids` of the holder's `pg_backend_pid`), so another test's
  backend cannot satisfy them. `MonthInterestIT` takes it through the API on a stopped clock: a
  second hand-logged payment, a back-dated one, the next month, another debt, the preview (both
  answers, `paidDate`, a future date refused), the 1st of a month dated east of UTC, a date before
  1900, a debt paid off this month, delete and re-book, a `PATCH` into a booked month, the check-in's bill prompt
  after a hand-logged payment, the surplus after and before the regular payment (with "never
  later" and "no more interest in total"), and six concurrent writers. `PayoffLadderTest` and
  `DebtTermsSolverTest` cover a booked month 1, including the never-later property over random
  surpluses and the same property for an extra logged to rung 1. Every rule-carrying line was broken and seen to fail a named test, then restored from
  git:

  | Broken | Failed |
  | ------ | ------ |
  | `isBooked` ignored (always charge interest) | `MonthInterestTest` (2), `MonthInterestIT` (8) |
  | Booking judged by date order, not stored rows (the latest takes it) | `MonthInterestTest`, `MonthInterestIT.a_payment_back_dated…` |
  | Debt id not checked | `MonthInterestTest.another_debts_interest…` |
  | Month not checked | `MonthInterestTest.a_payment_in_another_month…` |
  | A zero-interest row books the month | `MonthInterestTest.a_month_whose_only_rows…`, `MonthInterestIT.deleting_…` |
  | The corrected payment books its own month | `MonthInterestTest.the_payment_being_corrected…` |
  | The debt row lock dropped | `MonthInterestIT.payments_logged_at_the_same_moment…`: 3 and 4 carriers on two runs |
  | `CHECK_IN` special case restored | `CheckInIT.a_surplus_that_is_the_months_first_payment…`, `MonthInterestIT.a_surplus_that_is…` |
  | A move into a booked month keeps its interest | `MonthInterestIT.moving_…` |
  | The preview ignores `paidDate` | `MonthInterestIT.the_preview_says…`, `…a_preview_dated_in_the_future…` |
  | The ladder charges a booked month 1 | three `PayoffLadderTest` tests, `CheckInIT.the_canvas_week_reconciles…` ("never later"), `MonthInterestIT.a_surplus_that_is…` |
  | The check-in's what-if not told the surplus books the month | `CheckInIT.the_canvas_week_reconciles…`, `MonthInterestIT.a_surplus_that_is…` |
  | The solver charges a booked month 1 | two `DebtTermsSolverTest` tests |
  | `DebtFacts` never sees a booked month · a debt's own projection ignores it | `DemoFixtureIT.the_committed_debt_fixtures…` (the generated figures move) |
  | Month 1 plans the whole month's amount despite what was paid | three `PayoffLadderTest` tests (already paid, overpaid, both strategies the same cash) |
  | The page's standing allocation read off the netted month 1 | `PayoffLadderTest.aMonthAlreadyPaidPlansOnlyWhatIsStillToCome` |
  | Minimums rationed by what is left of the month | `PayoffLadderTest.overpayingTheMonth…` |
  | The solver plans the whole payment in month 1 | two `DebtTermsSolverTest` tests |
  | A check-in surplus, or a statement correction, counted as paid | `MonthInterestTest.a_statement_correction_and_a_check_in_surplus…` |
  | A completion locks in item order, not the global order | `DebtLockingIT.a_completion_locks…`: the completion was the deadlock victim (`409 check-in-stale`) |
  | `correct` without the lock | `DebtLockingIT.corrections_moving…`: six carriers, not one |
  | `delete` without the lock | `DebtLockingIT.a_delete_and_a_payment…`: a debt settled at $30.00 owed |
  | `confirmBalance` without the lock | `DebtLockingIT.a_statement_and_a_payment…`: payment 1000→980 and correction 1000→950 |
  | The web form does not re-ask when the date changes | `debt-detail.page.spec.ts`: "asks again for the date chosen…" |
  | The phone sheet does not re-ask when a date is picked | `debt_screens_test.dart`: "a back-dated payment is previewed for its own month" |
  | The latest date bounded at UTC, not UTC+14 | `MonthInterestIT.a_payment_dated_the_first_by_a_household_east_of_utc…`: `400` for Sept 1 at 23:00 UTC on Aug 31 |
  | No earliest date | `MonthInterestIT.a_date_before_1900_is_refused…`: `500` for `-5000-01-01` |
  | A debt paid off this month forgotten by month 1 | `MonthInterestIT.paying_a_debt_off_to_the_cent…` |
  | The ladder ignores what was paid outside its rungs | `PayoffLadderTest.whatWasPaidToADebtThatLeftTheLadder…` |
  | The lock taken by a `FOR UPDATE` query, without a fresh read | `DebtLockingIT.a_completion_decides_against_the_debt_as_it_is_once_locked…`: the completion answered `200` against a paid-off flag from before the wait |
  | Your own order written in name order | `DebtLockingIT.setting_your_own_order_writes_the_debts_in_the_global_lock_order`: one side lost a deadlock (`409 record-busy`) |
  | No lock timeout (`lock_timeout = 0`) | `DebtLockingIT.a_debt_held_too_long…`: still waiting after 15s |
  | The lock-failure handler removed | `DebtLockingIT.a_debt_held_too_long…`: `500`, not `409` |
  | _"+$50 more"_ on every rung, web · phone | `debt-detail.page.spec.ts`: "offers fifty more only on the plan's first rung…" · `debt_screens_test.dart`: "fifty more is offered on the plan's first rung only" |
  | The web date picker not capped at today | `debt-detail.page.spec.ts`: "…and no date after today" |
  | A bill marked paid in a check-in dated by the server (occurrence, or its debt payment) | `MonthInterestIT.a_bill_marked_paid_on_the_first_east_of_utc…`: `2026-08-31 0.0000 25.0000` |
  | A check-in's charge payment capped at the server's day | the same test (the cap applies to every bill prompt) |
  | The completion's `today` not range-checked | `HouseholdDayTest`, `MonthInterestIT.a_completion_dated_where_nobody_is_living…` |
  | Without `today`, the cap at the server's day | `HouseholdDayTest.without_a_date…` |
  | The completion sends no `today`, web · phone | `check-in.service.spec.ts` · `requests_test.dart`, and `check_in_test.dart` (the fixture no longer matches) |
  | Round 3's lock (find, then refresh `FOR UPDATE`) | `DebtLockingIT.a_debt_deleted_while_a_payment_waits…` and `…_a_completion_waits…`: both `500` |
  | The lock without the refresh after it | `DebtLockingIT.a_completion_decides_against_the_debt…` |
  | Your own order, or a debt's delete, with no lock timeout | `DebtLockingIT.your_own_order_and_a_delete_on_a_held_debt…`: still waiting after 15s |

- **`DebtTermsSolverTest`** — the arithmetic as a unit, with no database:
  - **Round-trip identity, stated exactly:** a *solved* field is a **fixed point** of the round
    trip. Solve the payment for a horizon, read the horizon its projection reaches, solve the
    payment again, and get the same payment to the currency's scale; likewise for `apr` to
    `0.001`pp. A solver that is not self-consistent will produce a plan that changes every time it
    is read.
    The stronger-sounding version — solve `monthsLeft` from an *entered* payment, solve the payment
    back, get the entered payment — **is not achievable and is not required**: many payments clear
    a balance in the same number of months, and the solver returns the smallest of them (the
    required payment). What is asserted for an entered payment is that the round trip lands on the
    same horizon and never asks for more than the household already pays.
  - **The iteration and the ledger agree:** the months the recurrence takes equals the number of
    rows a simulated payment history produces, for a spread of balances, rates and payments.
  - `payment ≤ interest(1)` yields `never-clears`, never a loop and never a huge number.
  - Solving for `apr` recovers a known rate from a balance, a payment and a term: exactly, to
    `0.001`pp, for a well-posed installment loan (20,000 over 60 months at 404.58 recovers 7.900).
    `0.001`pp is the **tolerance of the search**, not a guaranteed accuracy — a solved `apr` is the
    highest rate that still reaches the horizon, so it is an upper bound on the household's real
    rate and can sit a step or two above it when the payment was rounded up. What is exact, and
    asserted, is the boundary: one `0.001`pp step higher misses the horizon.
  - 0% APR terminates, with `monthsLeft = ceil(balance ÷ payment)` and zero total interest.
  - Bisection **terminates** at the bounds: 600 months and 64 steps, asserted, not assumed, with a
    timing assertion so that removing a cap fails rather than merely getting slower. The month cap
    is the one that fires on plausible input; the step cap is a backstop, and reaching it needs a
    balance far larger than `NUMERIC(19,4)` can hold — which is still worth a test, because the
    solve endpoint is arithmetic over a request body and reads nothing.
  - Rounding: `interest + principal = amount` for every row, and the chain of `balanceAfter` equals
    the anchor minus the sum of principals, exactly, over a hundred-month simulation.
- **`PayoffLadderTest`** — the ladder: the surplus goes to rung 1 only; a cleared debt's minimum
  *and* the surplus roll down; `monthlyAmount` is spent exactly in every month but the last and no
  rung is ever paid more than it owes; `AVALANCHE` and `SNOWBALL` differ **only** in order (proved
  by replaying the snowball order as a `CUSTOM` one); ties break deterministically (two debts with
  the same APR, then the same balance, then the same name, then the id); avalanche's total interest
  is never greater than snowball's for the same monthly amount, to within rounding (below); a `monthlyAmount` below the
  minimums produces no projection rather than a nonsense one.
  **Avalanche-never-worse holds because a plan never projects an overpayment** (see Strategies).
  Under a literal reading of the recurrence — where a clearing rung is paid its whole allocation
  and the excess is lost — it is false, and counterexamples are easy to find: a snowball that
  clears a small debt in one month stops that debt's interest a month earlier and comes out ahead.
  **It holds to within rounding, not exactly.** Interest is rounded to the cent once per debt per
  month, and the two orders round different balances, so where two rates are almost equal the
  rounding can outweigh the difference between them: £110.00 at 20.3% and £100.00 at 20.1%, at
  £97 a month, cost 5.87 under avalanche and 5.86 under snowball. The property therefore allows
  half a minor unit per rung-month of the two plans together, which an independent search over
  about 37,000 ladders never came within 85% of, and ADR-0036's own counterexample is pinned
  exactly, so the failure that ADR exists for still fails a test. It was never exact at four
  places either — the same search found inversions there at a hundredth of a cent. The page names
  whichever order costs less, so a near-tie reads truthfully either way.
- **`DebtIT`** — over-determined terms are `422` and store nothing; a released field becomes `NULL`
  and comes back `SOLVED`; the `CHECK` refuses a third entered solver field at the database level
  too; amounts and `apr` keep every digit across the wire and a JSON number is a `400`; a payment
  moves the derived balance and a `STATEMENT_CORRECTION` reconciles it; **deleting the linked
  transaction leaves the payment and does not move the balance**; a transaction cannot settle two
  debt payments; deleting a debt takes its payments and no transactions.
- **`DebtPeriodSummaryIT`** — the counted-once arithmetic, with the designs' own shape: three
  minimums of 185, 342 and 118 where the 342 is a linked bill, giving `debtMinimums = 645` and
  `debtMinimumsNotInBills = 303`. Then **four** ways the 342 stops being a bill row — delete the
  bill, retire it, make it yearly and ask for a month it is not due in, or skip the occurrence —
  and the second figure must become 645 in all four, with `left` unmoved at 1,755 rather than
  2,097. The yearly case is asked in both directions, because a rule that only works when the
  figure leaves the bills term is half a rule. It also asserts the subtraction itself — `left` is
  1,755 and **not** 1,413 — because the figure it guards had already shipped, and a quiet switch
  to `debtMinimums` would understate what is left by a figure nobody could tell apart from the
  right one.
- **`MoneyCrossHouseholdIT`** — another household's debt, payment, account, bill and transaction are
  all `404` on every route, as for transfers.
- **`DebtLimitsIT`** — the three controls the threat model added, each proved by removing it:
  - Past the per-household cap, `POST /api/debts` is `409 debt-limit-reached` and writes nothing.
    Without the cap, the same test creates the debts and the subsequent `GET /api/debt-plan` is
    the amplification the control exists to stop.
  - `GET /api/debts/{id}/payments` returns the page envelope and honours its bounds with more
    payments than one page holds — it does not return everything and call it a page.
  - `GET /api/debt-plan` on a never-clearing debt returns a **bounded** series. Asserted against
    the bound, not against "it came back", because an unbounded series also comes back.
  - `POST /api/debt-terms/solve` with values chosen to drive the search to a cap answers
    `terms-unsolvable` rather than running on. Timed, so a regression that removes a cap fails
    rather than merely getting slower. **The cap that fires is the 600-month one**: the bisection
    step cap needs a balance larger than `NUMERIC(19,4)` can hold, so it is unreachable through
    the edge's own `@Pattern` and is asserted in `DebtTermsSolverTest` instead.
  - A loop of solves past `TermsSolveRateLimiter.PERMITTED_PER_WINDOW` is `429` with a finite
    `Retry-After`, and `TermsSolveRateLimiterTest` proves the window rolls, that one caller's loop
    does not throttle another's editor, and that the refusal is never permanent.
  - **A rung that cannot clear on its own minimum still clears once the roll-down reaches it**,
    which is why the never-clears plan case is asserted on a ladder of **one** debt. With a second
    debt below it the plan clears both, and a test written the obvious way would prove the
    opposite of what it claims.
- **`DebtTest`, `DebtPaymentTest`, `PayoffPlanTest`** — the entities validating themselves with no
  database: releasing a field leaves `NULL` rather than a zero, entering one face of the horizon
  clears the other, the split reconciles by construction and a correction keeps the interest that
  was true on the day, an overpayment is not clamped, a monthly amount below the minimums is
  accepted, and no `toString` prints a name, a servicer, a balance or a rate.
- **`debt-data.spec.ts`** — the request builders: the entered set is sent explicitly, a release is
  sent as a release and not as an omission, amounts and `apr` leave as strings, the what-if is a
  `GET` that writes nothing, and a payment's split is displayed from the response rather than
  recomputed in the client.
- **`debt-plan.page.spec.ts`, `debt-detail.page.spec.ts`** — what a person sees: both strategies
  rendered whichever is selected; the never-clears debt showing a reason and not a blank; `Solved`
  on every derived cell and on no entered one; the plan-assigned payment labelled as coming from
  the plan; the released field named in the sheet; progress
  absent when there is no original balance; the empty state offering **Add a debt** and no $0
  debt-free date; a paid-off debt out of the ladder and in the history; and a `VIEWER` with every
  figure and not one control.
- **`tools/ui-check.sh`** — both screens, both viewports, light and dark, with axe, read as
  screenshots and not merely as a green run (non-negotiable #8). The chart needs an accessible
  text equivalent: a line a screen reader can use carrying the same two dates and two totals.
