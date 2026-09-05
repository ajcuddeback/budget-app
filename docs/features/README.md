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
| Users, households & auth | **Doc out of date** — rewrite before slice 2 | [accounts-and-auth.md](accounts-and-auth.md) |
| Household management (invites, roles) | Planned | _not written_ |
| File import (CSV/OFX/QIF) | Planned | _not written_ |
| Bank connections | Planned | _not written_ |
| Accounts (money containers) | Planned | _not written_ |
| Transactions | Planned | _not written_ |
| Categories | Planned | _not written_ |
| Budgets & periods | Planned | _not written_ |
| Transfers | Planned | _not written_ |
| Recurring transactions | Planned | _not written_ |
| Reporting & insights | Planned | _not written_ |
| Legacy data migration | Planned | _not written_ |

Keep this table current — it is the index an agent scans first.
