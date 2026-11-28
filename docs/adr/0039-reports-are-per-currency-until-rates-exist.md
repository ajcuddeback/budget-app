# ADR-0039: Reports are per currency; exchange rates and the roll-up get their own slice

- **Status:** Accepted
- **Date:** 2026-11-25
- **Deciders:** Repository owner
- **Amends:** [ADR-0022](0022-multi-currency-display.md) — its follow-up note only. Its decision
  (store in the account's currency, convert only for display, label every converted figure, never
  mix currencies in arithmetic) is unchanged and applies to reporting in full.

## Context

ADR-0022's follow-ups say that "the roll-up logic and the `ExchangeRate` entity land with the
reporting slice". Slice 13 (`docs/features/reporting.md`) is that slice, and it was planned on the
assumption that they would.

When the slice was specified, the roll-up turned out to be a feature of its own rather than a part
of reporting:

- **An entity and a migration.** `ExchangeRate` is the first table reporting would add. Every other
  figure in the first cut is derived on request from `transactions`, `accounts`, `categories` and
  `households`, with no migration.
- **A screen.** ADR-0022 makes manual rates the default path, and "it must never be the broken
  one". A manual rate needs somewhere to be entered, corrected and dated.
- **A state.** ADR-0022 requires a missing rate to be a visible state that shows the per-currency
  subtotals and says the roll-up is unavailable. That is a third rendering of every report.
- **No design.** The Insights artboard draws one currency. Nothing draws a converted figure, a rate
  or a missing rate.

Meanwhile a per-currency report is complete without any of it. A household with a GBP account and a
EUR account gets two correct reports, each in its own terms, and never a figure computed at a rate
nobody agreed to. The product owner settled this as decision 9 in `reporting.md` on 2026-11-25.

## Decision

**Reports are computed and returned per currency, and only per currency.** Every figure in
`/api/reports/**` sits inside an object that names its currency. There is no combined figure,
no converted figure and no rate anywhere in the slice-13 responses, the page or the CSV.

**`ExchangeRate`, the converted household roll-up and the manual-rate screen move to a follow-on
slice of their own.** It has no roadmap row yet and gets one when it has a design. When it lands,
the converted figure is a separate, clearly named field beside the per-currency ones, with its rate
and date (`api-style.md`), so nothing in the first cut has to change shape.

The optional rate provider stays where ADR-0022 put it: its own opt-in feature, off by default,
with its own threat model.

## Alternatives considered

| Option | Why not |
|---|---|
| Build the roll-up in slice 13, as ADR-0022 planned | Adds an entity, a migration, a screen and a third state to a slice whose two reports need none of them, with no design for any of the three. The per-currency reports would wait for it without being any less correct in the meantime |
| Ship a roll-up at a hard-coded or "latest known" rate | ADR-0022 forbids exactly this: an estimate presented as a total, at a rate nobody chose. "Silently substituting a stale or wrong rate is the worst possible behaviour here" |
| Report the base currency only, and leave other accounts out | A EUR account's spending would vanish from the household's Insights with nothing on screen saying so. Two complete reports are honest; one partial report looks complete |
| Sum across currencies without converting | The bug `Money` exists to make impossible: `£100 + €100 = 200` of nothing. `Money` throws, and the report service relies on that |

## Consequences

**Good:** slice 13 needs no migration and no rate. Every figure it shows matches the ledger rows
it was summed from, in the currency they were recorded in. A two-currency household sees both
reports, with a switcher, and never a number it has to take on trust.

**Bad / costs:** a household that genuinely thinks in one currency and holds money in two cannot
yet see one total for "what did we spend". That is the requirement ADR-0022 calls "a household
roll-up", and it is now deferred rather than delivered. The display currency a member can set
(ADR-0022) still changes nothing on the Insights page, because there is no converted figure to
display. ADR-0022's follow-up note is now wrong on its own, which is why its header points here.

**Follow-ups:**

- A roadmap row for the rates and roll-up slice once it has a design: the `ExchangeRate` entity,
  the manual-rate screen, the missing-rate state, and the converted field beside each report.
- The optional rate provider, as ADR-0022 already says, with its own threat model.
