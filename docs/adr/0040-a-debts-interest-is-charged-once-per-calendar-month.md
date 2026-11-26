# ADR-0040: Charge a debt's interest once per calendar month

- **Status:** Accepted
- **Date:** 2026-11-26
- **Deciders:** Repository owner (task brief); implemented in the debt-plan follow-up to slice 10

## Context

A logged debt payment stores its own split, `interest`, `principal` and `balanceAfter`, as at the
time (`docs/features/debt-plan.md`, "The split"; ADR-0035). Slice 10 computed the interest the
same way for every payment: `interest = round(balance × apr ÷ 1200, minor unit, HALF_EVEN)`. In
effect, every logged payment was treated as **a whole month's payment**.

That holds for a household that pays once a month. It is wrong for one that pays more often, and
a household working hard on a debt does exactly that. A second, extra payment in the same month
was charged another month of interest:

- The designs' Visa at $6,180.00 and 22.9% accrues **$117.94** a month.
- After the month's $445 payment, a **$63 extra payment** was charged $111.69 of interest. Its
  principal was **−$48.69**, so it **raised** the balance from $5,852.94 to $5,901.63.
- Any extra payment smaller than a month's interest made the household look worse off for paying.

A second, related defect was in the projection. Its first month was "the debt's next payment
date", taken from the day of the month of the latest payment. A payment logged **today** matched
today, and `!isBefore(today)` treated that as still to come. So month 1 of the projection was the
month just paid, and the projection charged that month's interest and counted its payment a
second time. The same happened when a linked bill fell due after an early payment in its month.

The brief for this change asked for a decision between two candidate rules, with
`interest + principal = amount` kept exact. The constraints on that decision are:

- **The projection is the definition** (feature doc, "How a projection is computed"). It is
  monthly, nominal and on the balance, `balance(m) = balance(m-1) + interest(m) − payment`,
  chosen because a household can check it with a calculator. The stored history and the
  projection have to describe the same arithmetic, or the payment table and the payoff date on the
  same page contradict each other.
- **A stored split is never rewritten.** It is what was true on the day, and a statement may since
  have corrected it.
- **Payments arrive out of order.** "Add a past payment" back-dates. Two members log from two
  phones. A typo is deleted and logged again.
- The weekly check-in (slice 14, specified but not built) will log surplus payments with
  provenance `CHECK_IN` through the same path as `POST /api/debts/{id}/payments`
  (`docs/features/weekly-check-in.md`). Whatever rule is chosen applies to those too.

## Decision

**A debt is charged interest once per calendar month.** A payment carries its month's interest
only if no other payment in the same calendar month already carries it. Otherwise its interest is
zero and the whole amount is principal.

Precisely, for a payment dated `d` against a debt with anchor date `a`:

- The month is `YearMonth.from(d)`.
- **It has already been charged** when a payment of that debt dated from `max(first day of the
  month, a)` to the last day of the month has `interest <> 0`.
  (`DebtPaymentRepository.countCarryingInterestBetween`)
- If it has already been charged: `interest = 0`, `principal = amount`.
- Otherwise: `interest = round(balance × apr ÷ 1200, minor unit, HALF_EVEN)` as before, with
  `principal = amount − interest`.

`interest + principal = amount` holds exactly on both branches, by construction. Three
consequences of how "already charged" is defined are deliberate:

- **It is about carrying interest, not provenance.** A `STATEMENT_CORRECTION` always has zero
  interest, so it never counts. A future `CHECK_IN` or `AUTO_MATCHED` payment follows the same
  rule as a hand-logged one, so slice 14 needs no special case.
- **It is bounded by the anchor.** A payment before the anchor does not count against the balance,
  so it cannot have charged the balance anything. A confirmed statement is a fresh start, as it
  was before.
- **Deleting the payment that carried a month's interest lets the next payment logged in that
  month carry it.** The common flow is "log $44.50, notice the typo, delete it, log $445". The
  surviving principal-only rows are not rewritten.

**Logging and deleting a payment lock the debt row** (`DebtRepository.findByIdAndHouseholdIdForUpdate`,
`SELECT … FOR UPDATE`). "Does this month already carry interest?" is a read followed by an insert.
Without the lock, two members logging at the same moment both read "no" and the month is charged
twice. `DebtIT.two_members_logging_the_months_first_payment_at_once_…` failed on its first round
with the lock removed. Found by the security review of this change. The same lock makes the
per-debt payment cap hold under concurrency.

**The projection never starts in a month already charged.** `DebtBalanceService.firstPaymentMonth`
is the scheduled month as before (the linked bill's next occurrence, otherwise the last payment's
day of the month, otherwise today). It is then pushed past the latest month a payment charged
(`DebtPaymentRepository.latestCharges`, under the same anchor bound). Logging the month's scheduled
payment therefore leaves the payoff month exactly where it was. Logging an extra payment can only
bring it earlier or leave it alone.

**The preview takes the date.** Under this rule the date decides whether a payment carries
interest. So `POST /api/debts/{id}/payments/preview` accepts an optional `paidDate`, defaulting to
today and refused in the future, as logging refuses it. The sheet sends the date it will log. That
keeps the preview's contract: it cannot disagree with logging.

## Alternatives considered

| Option | Why not |
| --- | --- |
| **Interest accrued since the last payment's date** (`balance × apr × days ÷ 365`, or `÷ 12` pro rata) | Closer to how lenders accrue, but it contradicts the projection, which is monthly and is the definition. The history would follow one model and the payoff date above it another, the inconsistency the feature doc rules out by name. It is also order-dependent with stored splits. A back-dated payment inserted between two existing ones changes the "days since last payment" of the later one, whose split can't be rewritten. So the month total would depend on the order in which rows were typed. And a household can no longer check it with a calculator. |
| **Spread the month's interest across its payments in proportion to their amounts** | No single row's split is final until the month ends. Every new payment would have to rewrite the splits already stored, which is exactly what an audit trail must not do. |
| **Cap the first payment's interest at its amount, carrying the rest to later payments in the month** | It hides a real fact. A payment smaller than the month's interest does leave the balance higher, and the negative principal says so honestly, as the overpayment rule already does for the other direction. If no second payment arrives, the carried interest is never charged and the balance is understated. |
| **Make only the check-in's own payments principal-only** (the `CHECK_IN` special case the brief describes) | It fixes one door of a two-door room. A household that logs its extra payment by hand still gets the defect, and the same amount would be split two ways depending on which screen it came through. The rule above covers `CHECK_IN` without naming it. |
| **"Already charged" means any payment exists in the month** (rather than one carrying interest) | A statement correction would then mark a month as charged and the next real payment would skip interest. Deleting a typo would leave the month permanently uncharged if a principal-only row survived it. |
| **Recompute every stored split when a payment is added, moved or deleted** | It rewrites history to agree with the present (feature doc, "What is stored and what is recomputed"). It is also unbounded write amplification on every payment write. |

## Consequences

**Good:**

- An extra payment never raises the balance and never moves the payoff month later. `DebtIT` asserts
  this across amounts from $0.01 to $5,000.
- The stored history and the monthly projection now describe the same arithmetic: one charge per
  month, then payments.
- The rule doesn't depend on the order rows were written. Two payments in a month carry one
  month's interest between them, whichever was logged first.
- Slice 14's `CHECK_IN` payments inherit it with no code of their own.

**Bad / costs:**

- **It changes a figure that has already shipped.** A second payment in a month used to show
  interest and now shows none. The user guide says so, and the feature doc's split rule changed in
  the same change. The debt code has **not yet merged to `main`**, so no self-hoster has stored
  rows split the old way, and there is nothing to migrate. Rows written on a development branch
  before this change keep their double interest. A statement confirmation corrects the balance, as
  for any other drift, and the rows themselves are never rewritten.
- **Calendar months, not statement cycles.** A lender whose cycle closes on the 12th charges
  interest across two calendar months. This is the same simplification the projection already
  makes, and the screen already says it is not what the lender will charge.
- **Moving a payment's date between months keeps its interest.** `PATCH` keeps the stored interest
  (feature doc, "A correction keeps the interest"). Moving the month's interest-bearing payment
  into a month that is already charged leaves that month charged twice and the original month
  uncharged. Recorded as a known gap. The fix is the statement confirmation the app already
  offers.
- **The plan ladder still starts this month for every debt.** `PayoffLadder` runs one shared month
  index across all rungs, starting at today's month. A debt already paid this month is charged
  and paid again in the plan's month 1, so its plan dates are a month optimistic. Fixing it needs
  a decision on how much of this month's `monthlyAmount` the household has already spent, which
  is a product call and not arithmetic. Recorded as a known gap.

**Follow-ups:**

- The plan ladder's month 1 (above).
- When slice 14 is built, its feature doc should cite this ADR rather than define a `CHECK_IN`
  exception.
