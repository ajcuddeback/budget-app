# ADR-0043: Interest is charged once per debt per calendar month

- **Status:** Accepted
- **Date:** 2026-11-27
- **Deciders:** Repository owner

## Context

Slice 10 split every logged debt payment the same way: `interest = round(balance × apr ÷ 100 ÷ 12)`
on the balance at the time, and the principal took the rest (`debt-plan.md`, The split). That is a
whole month of interest **per payment**, so a household that pays twice in a month is charged two
months of interest for it.

Slice 14's weekly check-in hit it first. Its surplus is an extra payment on top of the month's
regular one. On the canvas Visa ($6,180.00 at 22.9%) a $63 surplus was charged $117.94 of interest.
The balance went **up** by $54.94, and the preview said paying more moved the payoff month
**later**. Slice 14 patched only its own path: a `CHECK_IN` payment was recorded with interest 0, a
special case keyed on provenance (`weekly-check-in.md`, Departures). Hand-logged extra payments
still double-charged, and so did the check-in's own bill prompt.

The projections had the same flaw one step removed. The plan's month 1 is the current month and
charged that month's interest on today's balance, even when a payment recorded this month had
already added it.

## Decision

**Interest is charged once per debt per calendar month.**

- The first payment recorded for a debt in a month, by the payment's date (`YearMonth.from(paidDate)`,
  the same month the rest of the app uses), carries that month's interest. Every further payment for
  that debt dated in the same month is all principal.
- "Already charged" is read off the **stored rows**: a month is booked when a stored payment for the
  same debt, dated in it, carries non-zero interest. A back-dated payment into a booked month is
  therefore principal only, and stored splits are never rewritten. A month whose only rows carry no
  interest (a statement correction, a payment made while nothing was owed, a slice-14 check-in row)
  is not booked, so its next payment carries the interest.
- One rule for every writer. Hand-logged payments, the check-in's bill prompt and its surplus all
  build the row through `DebtPaymentService.draft`, which asks `MonthInterest.decide`. The `CHECK_IN`
  special case is removed.
- Writes to one debt are serialised by a `FOR UPDATE` lock on the debt row, so two payments recorded
  at once cannot both find the month unbooked.
- A projection whose first month is the current month charges that month no interest when it is
  already booked: the ladder, a debt's own projection, and the solver's searches. The check-in's
  what-if passes the same fact for a surplus that would book the month.
- A confirmed statement still overrides the arithmetic, as before.

## Alternatives considered

| Option | Why not |
|---|---|
| Keep the check-in's special case and nothing else | Two rules for one question. A hand-logged extra payment, or the check-in's own bill prompt, still charged a second month. It also left a check-in surplus that is the month's *first* payment uncharged, so that month lost its interest entirely |
| Interest by the days since the last payment (daily accrual) | Closer to what a lender does, but the projections are monthly (`debt-plan.md`, How a projection is computed) and a household can check them with a calculator. A daily split beside a monthly projection would disagree with it every month, and a few cents compounded becomes a whole month of payoff date. It also needs a per-day balance history the schema does not keep |
| Charge the month's interest on the *last* payment in the month, or re-split the month on every write | Rewrites stored splits after the fact. A stored split records what was true when it was recorded, and a statement may already have corrected it (`debt-plan.md`, What is stored) |
| Mark the carrier in a new column | Interest ≠ 0 already says it, with no second fact to keep in step. No migration needed |

## Consequences

**Good:** A month is charged once however many payments are logged. An extra payment always lowers
the month-end balance by its full amount. The check-in's "never later" guarantee holds under the
general rule. With the canvas Visa, a $63 surplus that is August's first payment carries the
$117.94, its own balance after is $6,234.94, and the regular $185 later in August is all principal,
so August ends at $6,049.94, exactly $63 below the $6,112.94 the regular payment alone leaves. Plan
interest goes from $2,847.88 to $2,701.45 projected plus $117.94 booked, $28.49 less in total.

**Bad / costs:**

- A month's first payment can be smaller than the month's interest, so its principal is negative
  and the balance after it is higher than before. That is honest, but it looks wrong, so the server
  says why (`monthInterest` on the preview and the check-in choice) and both clients word it.
- Deleting the carrier, or moving it to another month, un-books its month. The next payment in it
  carries the interest again. If no payment follows, the month stays uncharged until a statement
  corrects it. Nothing is re-split.
- **The plan's month 1 still assumes the month's planned payment is still to come**, as it always
  has. Before, the double-charged interest roughly cancelled that out. Now it does not: a debt
  whose regular payment is already logged this month is projected as if it got a second,
  interest-free payment this month. On the demo Visa (paid August 20, read August 20) the payoff
  moves from Dec 2027 to Nov 2027 and its interest still to come from $1,069.36 to $910.35. Recorded
  as a known gap in `debt-plan.md`.
- The per-debt lock adds a little contention between two members logging against the same debt at
  the same moment. That is the point of it.

**Follow-ups:** net this month's recorded plan payments out of the plan's month 1 (see the known
gap). Have the clients send the sheet's `paidDate` to the preview, so a back-dated payment across a
month boundary is previewed for its own month.
