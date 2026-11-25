# Feature Docs

**One document per user-facing feature.** This is the first thing to read before working on a
feature and the last thing to update after changing it.

## Why this exists

Without it, every session re-derives the same understanding by reading the same code: what the
rules are, which endpoints exist, what the edge cases were, what was deliberately left out.
That is slow, expensive, and inconsistent — two sessions reading the same code reach different
conclusions about intent, because intent isn't in the code.

A feature doc is the answer to "what is this supposed to do, and what did we already decide?"

## Rules

1. **Create it before writing code.** Use `/feature-doc`. Designing in prose is cheaper than
   designing in Java.
2. **Update it in the same change** that alters the behavior. Not in a follow-up, not later.
   A stale feature doc is worse than none, because it gets trusted.
3. **Record what you decided *not* to do**, and why. "Out of scope" saves the next session from
   re-proposing it.
4. **Keep the status line honest.** `Planned` / `In progress` / `Shipped` / `Deprecated`.
5. One file, `kebab-case.md`, named for the feature as a user would name it.

## Registry

| Feature | Status | Doc |
|---|---|---|
| Authentication & households | Shipped — API, web UI and user guide | [authentication-and-households.md](authentication-and-households.md) |
| Accounts (money containers) | Shipped — API, web UI and user guide | [accounts.md](accounts.md) |
| Categories | Shipped — API and web UI; user guide outstanding | [categories.md](categories.md) |
| Transactions | Shipped — API and web UI; user guide outstanding | [transactions.md](transactions.md) |
| Envelopes | Shipped — API, web UI and user guide | [envelopes.md](envelopes.md) |
| Bills & income | Shipped — API, web UI and user guide | [bills-and-income.md](bills-and-income.md) |
| Transfers | In progress — slice 8 | [transfers.md](transfers.md) |
| Household management (invites, roles) | Shipped with authentication & households | [authentication-and-households.md](authentication-and-households.md) |
| File import (CSV/OFX/QIF) | Planned | [file-import.md](file-import.md) |
| Bank connections | Planned | _not written_ |
| Admin console (instance operation) | In progress — slice 9 | [admin-console.md](admin-console.md) |
| Debt plan & debt detail | Done — slice 10 | [debt-plan.md](debt-plan.md) |
| Goals | Planned | [goals.md](goals.md) |
| Reporting & insights | Done — slice 13 | [reporting.md](reporting.md) |
| Weekly check-in | Planned | _not written_ |
| Owl chat (AI insights) | Planned | _not written_ |

Keep this table current — it is the index an agent scans first.
