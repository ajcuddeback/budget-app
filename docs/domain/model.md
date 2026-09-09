# Domain Model

The target model for the full budget app. This is the shared vocabulary — use these names in
code, in the API, and in conversation. Terms are defined in `../memory/glossary.md`.

Status: **proposed**. Entities become real one feature at a time; each arrives with its own
feature doc and migration. Changing this model is an ADR-worthy decision.

**Derived from the designs, not from first principles.** An earlier draft of this document was
written before any design existed and guessed at the shape of the product. The canvas in
`design/` is the authoritative statement of what Budget Owl is; where the two disagreed, the
designs won. Anything the designs do not settle is marked **open** below rather than invented —
if you are about to answer one of those in code, it needs a decision first.

## Core entities

```
User >──< HouseholdMember >──< Household
                                  │
                                  ├──< Account ──< Transaction >── Category
                                  │                     │
                                  │                     └── Payee
                                  │
                                  │   ── what is planned ──
                                  ├──< IncomeSource        money arriving on a schedule
                                  ├──< Bill ── ChargeRule   money leaving on a date
                                  ├──< Envelope ──< EnvelopeEntry
                                  │                        money you decided on and spend down
                                  ├──< Debt ──< DebtPayment
                                  ├──< Goal
                                  └──< CheckIn             the weekly pass over all of it
```

Three planning entities, deliberately distinct, because they answer different questions:

| | What it is | Example |
|---|---|---|
| `Bill` | An amount you **owe on a date** | Rent, 1st, $1,650 |
| `Envelope` | An amount you **decided on and spend down** | Groceries, $750 this month |
| `Debt` | A **balance you are reducing**, with terms | Visa •8823, $6,180 at 22.9% |

They all feed the same left-over figure, which is why the designs put bills and envelopes on one
page. Do not collapse them into one table — the design tried that and separated them for a
reason (see `Envelope`).

**The `Household` is the ownership root** (ADR-0017). A single-user instance is a household of
one — there is no special case and no second code path.

### Household
The **ownership root** for all financial data. Every account, transaction, category, budget,
payee and goal belongs to exactly one household, and every query is scoped by it
(ADR-0008 as amended by ADR-0017).

Fields: `id` (UUID), `name`, `baseCurrency` (ISO-4217, used for roll-ups), `createdAt`,
`updatedAt`.

### HouseholdMember
Joins a `User` to a `Household` with a **role**: `OWNER` (full control, may invite and remove),
`MEMBER` (read and write financial data), `VIEWER` (read only).

Also carries per-member preferences: **`displayCurrency`** (nullable, falls back to the household
base — ADR-0022) and **`locale`** (nullable, falls back to the platform locale — ADR-0023). Both
change only what that member *sees*; neither changes what is stored.

### ExchangeRate
`baseCurrency`, `quoteCurrency`, `rate`, `asOf` (date), `source` (`MANUAL` | provider name).

Used **only** to derive display figures and roll-ups (ADR-0022). A converted amount is never
persisted and is always presented with its rate and date. Where no rate exists for a pair on a
date, the roll-up is shown as unavailable rather than guessed.

Authorization has two axes now: *which household*, then *what may this role do in it*. Both are
enforced in the service layer, and both need tests.

### User
A person with credentials. **Not** the ownership root — a user reaches financial data only
through a household membership.

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

### Envelope and EnvelopeEntry
**The planning primitive for variable spending**, and the thing a spreadsheet cannot do well.

An `Envelope` holds a set `amount` for a `Period` and a `Category`; an `EnvelopeEntry` is one
purchase logged against it. `remaining` is **computed as entries arrive**, not backfilled at
month end — that is the whole point, because the remaining figure is the number a person acts on
while standing in a shop.

Fields: `id`, `householdId`, `name`, `categoryId`, `period` (`YearMonth`), `amount` (`Money`),
`rollover` (boolean), `archived`.

`EnvelopeEntry`: `envelopeId`, `date`, `amount` (`Money`), `payeeId` (**nullable — an amount
alone is a valid entry**), `note`, `source` (`MANUAL` | `SYNCED`), `transactionId` (nullable,
set when an entry came from or was matched to a real transaction).

Three rules that come straight from the designs and are easy to get wrong:

- **Envelopes work fully unsynced.** Manual entry is the primary path, not a fallback. Nothing
  about an envelope may require a connected account.
- **Every entry says where it came from.** `MANUAL` and `SYNCED` are visually distinct in the
  log, so a person can tell what they typed from what arrived.
- **Envelopes are separate from bills, and separate from each other.** "Groceries" and "Adhoc"
  are two envelopes, not one mixed column — a $12 Publix run and a $299 Home Depot run are not
  the same decision, and averaging them together destroys the signal.

An envelope is **not** a `Bill`: a bill is owed on a date and is largely not your choice this
month; an envelope is an amount you chose and can spend down early.

**A transaction never becomes an envelope entry on its own.** Assigning spending to an envelope
is a decision the person makes; the app may suggest, but it does not file. `SYNCED` means the
user attached an entry to a real transaction, not that the app did it unprompted. This keeps the
envelope a record of intent rather than a guess, and it is the same principle as marking a bill
paid by hand (ADR-0025).

> **Open:** rollover. The field is here because the designs show a `rollover` affordance
> ("Roll into next week / Target becomes $543"), but the designs do not say whether an unspent
> envelope carries forward automatically, on request, or not at all — nor what happens to an
> *overspent* one. Needs a feature doc before implementation.

> **Open:** `Budget`/`BudgetLine` are gone from this model. The designs have no budget page —
> planning happens on Bills & Income (bills, income, envelopes) and on the debt plan. If a
> distinct monthly budget document is still wanted, it needs its own decision; right now nothing
> in the designs asks for one.

### Bill, BillOccurrence and ChargeRule
A `Bill` is a named obligation that recurs: `name`, `expectedAmount` (`Money`), `dueDay`,
`categoryId`, `schedule`, `amountVaries` (with a tolerance, e.g. "varies ±$22"), `active`.

A **`BillOccurrence`** is one instance of it in one period — and this, not a generated
transaction, is what the app records (ADR-0025). Fields: `billId`, `period`, `dueDate`,
`expectedAmount`, `status` (`DUE` | `PAID` | `SKIPPED`), `actualAmount` (nullable), `paidDate`
(nullable), `transactionId` (nullable), `statusSource` (`MANUAL` | `AUTO_MATCHED`).

Three consequences worth stating plainly:

- **Planned and actual are both kept.** You budget an amount, and fill in what you actually paid
  as the month goes on. The difference is the useful number, and a variable bill is
  unrepresentable without it.
- **Marking a bill paid by hand is the primary path**, not a fallback. It needs no connection, no
  import, and no match. Where an account *is* connected, a confident `ChargeRule` match sets the
  status, the actual amount and the paid date automatically — `statusSource` records which
  happened, so a figure the user asserted never looks like one the app inferred.
- **An occurrence is not a ledger entry.** It contributes to the plan; only the real
  `Transaction` it matched contributes to income and expense. Counting both is the obvious bug
  this model invites.

A `ChargeRule` maps a bill to the **real charge** that pays it — the bit that turns a list of
intentions into a reconciled ledger. It carries the matched `payeeId`/descriptor, the
`accountId` it lands on, a `matchKind` (`EXACT` | `VARIES_WITHIN_TOLERANCE`), and a
`standing` flag meaning "label this every month without asking again".

An unmapped bill is a **first-class state**, not an error: the designs show "Not mapped" beside
a suggestion ("COMCAST XFINITY $71.99 looks likely") with `Link` / `Different bill` actions. A
bill you typed in by hand with nothing connected is entirely normal and stays that way forever
if you like.

**Overdue is derived** from `dueDate`, never stored. A status that needs a nightly job to stay
true will eventually be wrong.

`RecurringTransaction` is gone: nothing generates speculative ledger rows. See ADR-0025 for why,
and for the alternatives rejected.

### IncomeSource
Money arriving on a schedule: `name`, `amount` (`Money`), `schedule` (`Every other Wednesday`,
`Irregular`), `accountId` it lands in, and a **`sourceOfTruth`** — `AUTO` (derived from history,
e.g. "Auto · 12 mo history") or `MANUAL_ESTIMATE` (e.g. "Variable · averaged over 6 months").

Irregular income is averaged rather than assumed fixed. The average window is part of the record,
because a number derived from six months means something different from a number someone typed.

### Debt and DebtPayment
First-class, and the most arithmetically demanding part of the product.

`Debt`: `name`, `kind` (`CREDIT_CARD` | `INSTALLMENT`), `originalBalance`, `currentBalance`,
`apr`, `minimumPayment`, `accountId` (nullable — a debt need not be linked), `rung` (its place
in the plan), plus a **`balanceSource`**: `LINKED_STATEMENT` | `CONFIRMED` | `ESTIMATED`.

`balanceSource` is not decoration. The designs show a student loan whose balance is Owl's
*estimate* ("I've assumed three $118 payments since June — if that's right, tap confirm"), and
that estimate must never be presented as fact. A figure the app inferred and a figure a statement
confirmed are different kinds of number and the UI says which.

`DebtPayment`: `date`, `amount`, `interest`, `principal`, `balanceAfter`, and `provenance`
(`AUTO_MATCHED` | `USER_LOGGED` | `STATEMENT_CORRECTION`). This is the audit trail the balance
is derived from — the same append-mostly discipline as `Transaction`.

Interest is `balance × APR ÷ 12` **until a statement says otherwise, and then the statement
wins**; the difference is recorded as a `STATEMENT_CORRECTION` row rather than silently adjusting
history. Money arithmetic here is exactly why `NUMERIC(19,4)` and `BigDecimal` are
non-negotiable (ADR-0006).

**The terms solver.** The designs state it plainly: *"Give me any four and I'll solve the
rest."* Original balance, APR, current balance, minimum payment, months left, payoff date, and
payment amount are mutually constrained; entering four derives the others, and each derived field
is labelled `Solved`. Overriding a solved field makes it entered and re-solves around it. Every
field therefore needs an `entered` / `solved` flag — this is a real modelling requirement, not UI
sugar.

A debt's **minimum payment appears in Bills & Income as a bill row** and is counted once. The
designs are explicit: *"Minimums · each counted once in Bills & Income (the car payment is its
bill row)."* Double-counting it is the obvious bug here.

### PayoffPlan
The ordering strategy over debts: `strategy` (`AVALANCHE` | `SNOWBALL` | `CUSTOM`), the monthly
amount committed, and the derived projection (debt-free date, total interest). Both strategies
are computed and **compared**, never one silently chosen — the designs present them side by side
and say "Either is a good answer."

### CheckIn
The weekly pass: `period` (week), `status`, and per-step outcomes. Four steps — what's new,
categorize, bills, next week — each of which **writes something** (income confirmed, a category
and payee rule, a bill↔charge mapping, an allocation and weekly target).

A step with nothing to answer is **skipped, not shown empty**, which is why a quiet week is
much shorter than four steps implies. Only "next week" is never skipped.

A check-in **is persisted**, as a draft. The designs show progress across a session ("2 of 4
answered") alongside "Nothing is saved until you finish at the bottom", so the draft holds the
answers and nothing it decides takes effect until the check-in is completed. A half-finished
check-in on a phone must be resumable on a laptop.

### Payee
Who money went to or came from. Normalized so "STARBUCKS #1234" and "Starbucks" reconcile to
one payee, which makes reporting and auto-categorization possible.

### Goal
A savings target: `name`, `targetAmount`, `targetDate`, linked `accountId`, `monthlyContribution`,
computed progress. The designs show goals absorbing surplus from the weekly check-in and being
adjusted by the assistant ("Japan trip: $110 → $165 per month, starting Sep 1").

## Derived figures

None of these are stored. They are computed, and the designs make each one a headline, which
means an off-by-one here is a product bug rather than a rounding nit.

**Safe to spend** — the number the mobile home screen leads with. Money on hand, minus bills
still due this period, minus what envelopes still hold, divided across the days remaining. The
designs render it as both a total and a per-day rate (`$412 · ≈ $41/day`, `10 days left`), and
show its parts underneath (`Spent $2,180 · Bills left $706 · Free $412`) — it is never a bare
number with no way to see how it was reached.

**Debt-free date** and **total interest** — from the `PayoffPlan` projection, per strategy.
Shown with the delta against the previous plan ("4 months earlier"), so the projection must be
snapshotted when a plan changes or that comparison has nothing to compare against.

**Envelope remaining** — the envelope amount minus its entries, live.

**Monthly committed** — bills plus debt minimums, counted once each. See the note under `Debt`.

## Instance administration

The designs include a nine-page admin console for the person running the container. It is not
household financial data and does not belong in the model above; it is instance-level state —
users and invites, connections and API keys, the job queue, storage, backups, logs, instance
settings, and the update channel. Its entities land with its feature doc.

One piece of it is a **data-protection control rather than a screen**: a first-run
acknowledgement that gates the Connections page. Three statements, ticked individually, and
`Continue` stays inert until all three are — which is the mechanism behind ADR-0020's shifting of
aggregator liability onto the operator. That acknowledgement needs to be recorded with a
timestamp and the acknowledging user, or it does not do its job.

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

1. Every financial row has a non-null `household_id`, and every query filters on a household the
   authenticated user is a verified member of.
2. Account balance always equals opening balance plus the sum of its transactions.
3. A transfer's two legs always sum to zero and always share a `transferGroupId`.
4. `Money` arithmetic never mixes currencies. Totals group by currency first; conversion, if
   any, happens last and only for display.
5. A transaction's amount is in its account's currency and is **never rewritten** by a conversion
   or a rate correction.
5. A budget period is a real date range, never a string.
6. Deleting a household deletes or anonymizes all of its financial data — no orphaned rows.
   Removing a *member* revokes their access; it never deletes household data.
7. A user with no household membership can see no financial data at all.
