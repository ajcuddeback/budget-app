# Domain Model

The target model for the full budget app. This is the shared vocabulary — use these names in
code, in the API, and in conversation. Terms are defined in `../memory/glossary.md`.

Status: **proposed**. Entities become real one feature at a time; each arrives with its own
feature doc and migration. Changing this model is an ADR-worthy decision.

## Core entities

```
User ──< Account ──< Transaction >── Category
 │                        │
 │                        └── Payee
 ├──< Budget ──< BudgetLine >── Category
 ├──< RecurringTransaction
 └──< Goal
```

### User
The account holder and the **ownership root**. Every other entity below traces to exactly one
User, and every query is scoped by it (ADR-0008).

Fields: `id` (UUID), `email` (unique, citext), `displayName`, `passwordHash`, `status`,
`createdAt`, `updatedAt`. No `firstName`/`lastName` split — a single display name avoids
wrong assumptions about how people's names work.

### Account
A place money sits: checking, savings, credit card, cash, investment. Has a `type`, a
`currency` (ISO-4217), an `openingBalance`, and an `archived` flag.

**Balance is derived** from transactions, never stored as a mutable column that can drift out
of sync. If performance demands a cached balance later, it is a materialized projection with a
rebuild path — and that needs an ADR.

### Transaction
A single movement of money. `accountId`, `date` (a real `LocalDate`), `amount` (`Money`,
signed — negative is an outflow), `payeeId`, `categoryId`, `note`, `status`
(`PENDING` | `CLEARED` | `RECONCILED`), `externalId` for imports.

A **transfer** between two accounts is a linked pair of transactions sharing a `transferGroupId`,
equal and opposite. It is never a single row with two account columns, and a transfer is never
counted as income or expense in reporting.

Transactions are **append-mostly**: correcting one keeps an audit trail rather than silently
overwriting history.

### Category
A hierarchy (parent → children, max two levels) used to classify spending. `kind` is `INCOME`
or `EXPENSE`. Users get a sensible default set on signup and can edit it. Deleting a category
in use re-assigns its transactions to "Uncategorized" — it never orphans or deletes them.

### Budget and BudgetLine
A `Budget` covers one `YearMonth` **period** for one user. Each `BudgetLine` allocates a
planned `Money` amount to a `Category`.

This is where the legacy app's month/year strings are replaced by a real, orderable period.
Rollover behavior — whether an unspent line carries into next month — is per-line configuration
and needs its own feature doc.

### RecurringTransaction
A template plus a schedule (RRULE-ish: frequency, interval, day-of-month, end condition) that
generates future `Transaction` rows. Generated instances are real transactions marked
`PENDING`, so a user can edit a single occurrence without breaking the series.

### Payee
Who money went to or came from. Normalized so "STARBUCKS #1234" and "Starbucks" reconcile to
one payee, which makes reporting and auto-categorization possible.

### Goal
A savings target: `name`, `targetAmount`, `targetDate`, linked `accountId`, computed progress.

## Value objects

### Money — read this before writing any amount code

`Money` is a value object of `BigDecimal amount` + `Currency currency`.

- **Never** `float` or `double`. Not for amounts, not for totals, not "just for display".
- Stored as `NUMERIC(19,4)`. Four decimal places, so intermediate results (interest, splits,
  proportional allocation) don't lose precision before rounding.
- Arithmetic between different currencies **throws**. It does not silently coerce.
- Rounding is always explicit: `RoundingMode.HALF_EVEN` for allocation, and a split must
  reconcile exactly to the original — the remainder cent goes somewhere deterministic, never
  vanishes.
- Over the wire: a string (`"1234.56"`) or minor units, never a JSON number. JSON numbers are
  IEEE-754 doubles in most parsers, which is exactly the bug we're avoiding.

See ADR-0006.

### Period
A `YearMonth`. Orderable, comparable, arithmetic-capable. Serialized as `"2026-08"`.

## Invariants

These hold everywhere. A change that breaks one needs a very good reason and an ADR.

1. Every user-owned row has a non-null owner traceable to a `User`, and every query filters on it.
2. Account balance always equals opening balance plus the sum of its transactions.
3. A transfer's two legs always sum to zero and always share a `transferGroupId`.
4. `Money` arithmetic never mixes currencies.
5. A budget period is a real date range, never a string.
6. Deleting a user deletes or anonymizes everything they own — no orphaned financial rows.
