# ADR-0040: Next week's spending figure is built from the plan, not from balances

- **Status:** Accepted
- **Date:** 2026-11-26
- **Deciders:** Repository owner (decision 1 of `docs/features/weekly-check-in.md`)

## Context

The weekly check-in (slice 14) ends with one number: **what the household can spend next week**.
The canvas draws it as *"Next week you can spend $480 · ≈ $68/day"*, and a rolled-over surplus
raises it (*"Weekly target becomes $543"*). Something has to define that number, and there were
two candidates already in the repository:

- The glossary's **Safe to spend**: *"money on hand minus bills still due minus what envelopes
  still hold, over the days remaining"*. It is balance-based. Nothing in the product computes it,
  and no slice has defined "money on hand": which account types count, whether a credit card's
  balance is subtracted, whether a savings account that holds goals is "on hand".
- The shipped **What's left** figure on Bills & Income (`GET /api/periods/{M}/summary`), which is
  plan-based: expected income minus the month's bills, envelope allocations, debt minimums not
  already in a bill, and goal set-asides. It is tested, reconciled with the designs, and already
  the household's headline every month.

Two further facts constrain the choice. Many self-hosted households never enter an exact opening
balance, so any balance-derived figure is wrong for them in a way they cannot see. And slice 10
already shipped, and had to fix, a figure composed from two services that each defined a term
differently (`docs/memory/gotchas.md`, "a figure composed from two services can fall between their
definitions"). A second "what's left" figure would be the same defect at product scale.

## Decision

**Next week's figure is derived from the month's `left`, read through `PeriodSummaryService` and
nothing else.**

```
free(M)  = left(M)
         − unbudgeted(first day of M, min(week's Sunday, last day of M))
         − Σ debt payments with provenance CHECK_IN dated in M
daily(d) = (free(M) − held) ÷ days from next Monday (or the 1st) to the end of M,   M = month(d)
           left(M) ÷ days in M                                   for a month not yet started
base     = Σ daily(d) over the seven days of next week
```

`held` is the week's surplus when it is positive and `free(M)` covers it, so the three choices for
the surplus (a debt, a goal, roll it over) start from the same `base`. *Roll over* sets
`next_week_target = base + surplus`. `unbudgeted` is the week's spending that the month's plan had
not already set aside (decision 2): base-currency, non-transfer, expense or uncategorised outflow,
net of refunds, excluding rows attached to a plan record and rows in a category an envelope or a
bill of the month already budgets.

The step runs **no query of its own for `left`**. Every term of it stays defined in exactly one
place. A negative figure is shown negative and never clamped, as `left` is.

## Alternatives considered

| Option | Why not |
|---|---|
| The glossary's balance-based *Safe to spend* | Needs a "money on hand" definition nobody has made (account types, card balances, savings that hold goals). Wrong for every household whose opening balances are not exact, silently. And it would be a second "what's left" figure that disagrees with the one on Bills & Income |
| A fixed weekly budget the household types in | A number with no connection to the plan: income, bills and goals could all change and the weekly figure would not notice. It is also a new thing to maintain, which the check-in exists to avoid |
| `left(M) ÷ weeks in M`, ignoring what was spent | Ignores the week's overspend entirely, so an overspent week would be followed by the same figure. The point of the weekly number is that it moves with what happened |
| Count every outflow against the week (reporting's `spending`) | Rent and groceries are already in `left` as a bill and an envelope. Counting them again makes every week containing the 1st read hundreds over |

## Consequences

**Good:** one "what's left" in the product, and the weekly figure inherits every term's tests and
definition. It works for a household that never entered an opening balance. An overspent week
lowers next week by construction, with no separate penalty, which answers for weekly targets the
overspend question ADR-0031 raised about envelope rollover. A plan change (a new bill, a raised
goal) moves next week's figure without anyone touching the check-in.

**Bad / costs:** the figure is only as good as the plan. A household with no income sources set up
gets a negative weekly figure, honestly, and the screen has to say why rather than show $0.
"Unbudgeted" depends on categories and attachments, so an uncategorised refund is not netted. The
figure is a plan figure, not money in an account, and the copy must never imply otherwise.

**Follow-ups:** the glossary's *Safe to spend* entry and the home screen adopt this definition when
the home screen is built, so the product never shows two "you can spend" numbers. Decision 6 of the
check-in (a check-in goal contribution counts on top of the month's plan) is what lets an allocated
surplus actually leave `left`; it changes the fifth term and is recorded in `bills-and-income.md`.
