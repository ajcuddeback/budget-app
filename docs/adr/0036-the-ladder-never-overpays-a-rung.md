# ADR-0036: The ladder never overpays a rung, and the leftover flows down the same month

- **Status:** Accepted
- **Date:** 2026-11-24
- **Deciders:** Repository owner

## Context

The payoff plan allocates a monthly amount across debts: every debt gets its minimum, the surplus
goes to rung 1, and when a debt clears its minimum _and_ the surplus roll down to the next rung.
That is the standard description of both avalanche and snowball, and it is how `debt-plan.md`
described it.

It leaves one thing unsaid: **what happens in the month a rung clears, when its allocation is more
than it still owes.** Two readings:

1. **Literal.** The rung is paid its whole allocation. The overshoot is simply gone — the household
   pays £1,196 and £338 of it lands nowhere.
2. **Flow-down.** A rung is never paid more than it owes; the leftover moves down the ladder within
   the same month, to the next debt that still has a balance.

The literal reading is the simpler recurrence and the obvious one to implement. It is also wrong,
and it breaks something the product says out loud.

**Avalanche is not guaranteed to beat snowball under the literal rule.** The designs present the
two strategies side by side and the whole reason to offer avalanche is that it costs least in
interest. Under the literal rule that is false, and not as a rounding artefact. A worked
counterexample, verified independently of the implementation:

|     | Balance   | APR     | Minimum |
| --- | --------- | ------- | ------- |
| A   | £858.46   | 9.645%  | £169.95 |
| B   | £1,298.52 | 10.474% | £169.95 |

At £1,196.45 a month — avalanche pays B first, snowball pays A first:

```
LITERAL (overshoot lost)     avalanche = £30.56   snowball = £28.18   avalanche WORSE
WITH same-month flow-down    avalanche = £26.30   snowball = £26.78   avalanche better
```

A brute-force search found 2 such counterexamples in 390 random ladders with varying minimums, and
9 in 1,154 with equal minimums. With flow-down: **0 in 5,577**.

The mechanism is not subtle once seen. Paying the highest-rate debt first means it clears first,
and the month it clears, the literal rule throws away whatever overshoot that allocation carried.
Snowball clears the _smaller_ debt first, so its overshoot is smaller, so it wastes less. The
literal rule penalises exactly the strategy that targets the big expensive debt — which is the one
avalanche exists to recommend.

## Decision

**A rung is never allocated more than it owes. The leftover flows down the ladder in the same
month**, to the next debt that still carries a balance, repeating until the money is spent or no
debt remains.

This applies to the **plan**. It deliberately does **not** apply to a single debt's own projection,
where `debt-plan.md` wants the final month's overshoot shown — a household paying a flat £445 into
a debt that owes £300 pays £300, and the screen says so, but there is no other rung for the
remainder to flow to.

## Consequences

- **"Avalanche never costs more interest than snowball, for the same monthly amount" becomes
  true**, and is asserted as a property test rather than assumed. Under the literal rule that test
  would have to be deleted — and deleting it would mean shipping a screen that recommends avalanche
  while the arithmetic sometimes disagrees.
- **A second, stronger invariant becomes testable:** the monthly amount is spent _exactly_ every
  month but the last. Under the literal rule it is not, and the gap is invisible.
- **The allocation is no longer a pure function of the rung order alone** — it depends on each
  debt's remaining balance that month, so the implementation walks the ladder rather than computing
  per-rung amounts up front.
- **`Rung.extra` can be negative** in one case: a debt that owes less than its own minimum and
  clears in month 1. `minimum + extra` is always what was actually allocated, so the screen has a
  true figure to show, but the UI needs wording for it rather than rendering a minus sign beside
  "extra".
- **The displayed per-debt monthly amount is the first month's allocation**, which is what a
  household will act on. Later months differ as rungs clear; the projection carries the real series.

## Alternatives considered

**The literal rule, and delete the avalanche-optimality test.** Rejected. The test is not decoration
— it encodes the claim the strategy picker makes to the user. A product that offers "pay the
highest rate first, it costs you least" and cannot assert that property is making a recommendation
it has not checked.

**Flow the overshoot into the _next month_ instead of the same month.** Rejected: it produces the
same totals eventually but makes the monthly amount wrong on screen — the household is told they
are paying £1,196 this month when £338 of it has been deferred.

**Reduce the surplus so no rung ever overshoots.** Rejected: it underspends the household's stated
monthly amount, which is worse than either — they committed the money and the plan quietly does
not use it.

## Amendment (2026-11-25): "never costs more" holds to within rounding

Interest is now rounded to the currency's minor unit when it is computed (`debt-plan.md`,
decision 9), not kept to four places. Under that rule the first consequence above is true only
**to within rounding**.

When two rates are nearly equal, avalanche can cost a few cents more than snowball. The smallest
case found:

- £110.00 at 20.3% and £100.00 at 20.1%, paid at £97 a month;
- avalanche costs £5.87 in interest and snowball £5.86.

At four places the same search found inversions too, at a hundredth of a cent, so the claim was
never exact; rounding to the cent only makes the gap visible.

What changed in the tests:

- **The property:** it now allows half a minor unit per rung-month across the two plans. Across
  about 37,000 independently modelled ladders, the worst inversion was 85% of that allowance.
- **A pinned example:** this ADR's own worked example is now asserted exactly (avalanche 26.29,
  snowball 26.77). The loosened property no longer reliably catches a return to the literal rule,
  and this example does.

The screen is unaffected. It names whichever order the server says is cheaper, so a near-tie is
reported truthfully. The decision itself (never overpay a rung, and let the leftover flow down the
same month) stands unchanged.
