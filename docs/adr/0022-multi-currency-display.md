# ADR-0022: Multi-currency — store in account currency, convert only for display

- **Status:** Accepted
- **Date:** 2026-09-07
- **Deciders:** Repository owner

## Context

Budget Owl should support as many currencies as possible (`../product/vision.md`). Three separate
requirements hide inside that, and conflating them is how budgeting apps get money wrong:

1. **Accounts in different currencies.** Someone holds a GBP current account and a EUR savings
   account. Each transaction happens in one real currency.
2. **A household roll-up.** "What is our total?" needs one currency to add up in.
3. **Per-member display currency** (ADR-0017 households): two people in one household may think
   in different currencies.

Requirements 2 and 3 need conversion, and conversion needs exchange rates — which come from the
internet. ADR-0016 forbids the core from requiring any service we operate, and a self-hosted
instance may have no internet access at all.

## Decision

**Amounts are stored in the currency they happened in, and are never converted for storage.**

- Every `Account` has a currency. Every `Transaction` is in its account's currency. That figure
  is the **truth** and is never rewritten.
- A `Household` has a **base currency** for roll-ups. A `HouseholdMember` may set a **display
  currency**; absent one, they see the household base.
- **Conversion happens at read time, for display only.** A converted figure is an estimate
  derived from a rate on a date — never persisted, never the input to anything that is.
- **Any converted figure is labelled as converted**, showing the rate and the date it came from.
  Presenting an estimate as a balance is lying about money.
- **Arithmetic never mixes currencies** (ADR-0006 already requires this — `Money` throws). Sums
  group by currency first; the roll-up converts the per-currency subtotals last.

**Rates, given ADR-0016:**

- **Manual by default.** A user can enter rates by hand and everything works offline. This is the
  guaranteed path and it must never be the broken one.
- **An optional rate provider** the user turns on — off by default, and a source needing no
  account or key (a central-bank reference feed) is strongly preferred over anything requiring
  registration.
- **Missing rate is a visible state, not a zero or a crash.** If no rate exists for a pair on a
  date, the UI shows the per-currency subtotals and says the roll-up is unavailable. Silently
  substituting a stale or wrong rate is the worst possible behaviour here.

## Alternatives considered

| Option | Why not |
|---|---|
| Convert everything to the base currency on write | Destroys the original amount and bakes a rate into history. Reconciling against a bank statement becomes impossible, and a rate correction can never be applied retroactively |
| Single currency per instance | Simplest, and wrong for anyone with a foreign account or a household spanning two countries — the audience most likely to want this |
| Require a rate provider | Breaks ADR-0016: an instance with no internet must remain fully usable |
| Store an amount plus a snapshotted rate on every row | Denormalises a moving value onto immutable history and multiplies the places a rate error must be corrected |

## Consequences

**Good:** the number on a transaction always matches the bank statement. Rate corrections apply
everywhere at once because nothing was baked in. An offline instance works completely, with the
one honest limitation that cross-currency roll-ups need rates the user supplies.

**Bad / costs:** every total is currency-aware, so "sum these transactions" is never a plain sum —
this must be in the domain layer from the first slice, not retrofitted. The UI has to express
"converted, at this rate, on this date" without becoming noisy. Manual rate entry is genuinely
tedious for anyone with regular cross-currency activity, and the optional provider will be the
common path in practice even though it cannot be the default.

**Follow-ups:** `Money` already carries currency and throws on mixed arithmetic (ADR-0006) — the
roll-up logic and the `ExchangeRate` entity land with the reporting slice. The rate provider is
its own opt-in feature with its own threat model.
