# ADR-0032: Bill occurrences are projected on read, and written only when something is recorded

- **Status:** Accepted
- **Date:** 2026-11-20
- **Deciders:** Repository owner

## Context

ADR-0025 settled _what_ a bill produces: `BillOccurrence` rows, not speculative `Transaction`
rows. It did not settle _when those rows come into existence_, and that question has to be
answered before slice 7 can be built.

The conventional answer is a scheduled job: every night, or on the first of the month, walk every
active bill and insert the occurrences for the coming period. It is simple to describe and it is
what most of this kind of software does.

ADR-0025 also contains a warning that cuts against it, about a different field:

> `status` is `DUE` | `PAID` | `SKIPPED`, with overdue derived from `dueDate` rather than stored
> (**a status that needs a nightly job to stay true will eventually be wrong**).

The same reasoning applies to the rows themselves. A self-hosted instance is a machine in
somebody's house. It gets powered off, it gets restored from a backup taken three weeks ago, its
clock is wrong, the container is recreated on a different day. A design whose correctness depends
on a job having run is a design that is wrong on exactly the instances this product is for — and
wrong silently, because a missing occurrence looks identical to a bill nobody has set up.

## Decision

**A bill occurrence is projected from the bill when a period is read, and a row is written only
when somebody records something against it.**

- Listing a period computes, from each active bill's schedule, the occurrences that period
  implies. Nothing is written.
- Marking one paid, skipped, or back to due writes the row — an upsert keyed on
  `(bill_id, period)`.
- A period that nobody has touched has no rows at all, and reads exactly the same as one where
  every occurrence is still `DUE`. That equivalence is the property worth having.

**An occurrence is identified by `(billId, dueDate)`, not by a generated id**, because for most of
its life it has no row. The API addresses one as `/api/bills/{billId}/occurrences/{dueDate}`, and
that URL is valid before anything exists at it — a `PUT`-shaped idea, which is why recording a
status is idempotent.

The due date, not the period: a `WEEKLY` bill falls four or five times in one month. Keying on the
month would have let a household tick off one of a month's four payments and no more, which is not
a limitation anybody would have described out loud — it would simply have refused the second one.
A month is how occurrences are **listed**; a date is what one **is**. The month is still stored,
as a label on the row, so listing a period is an equality rather than a range, and a `CHECK` keeps
the label and the date it labels from disagreeing.

### Reading a period that was settled before the schedule changed

A period's list is the projection merged with whatever rows exist for that month:

1. a recorded row whose due date **is** one of the projected dates is that occurrence;
2. leftover recorded rows are matched to leftover projected dates **in date order**, so moving a
   monthly bill from the 15th to the 20th leaves the settled month settled rather than showing a
   paid 15th beside an unpaid 20th;
3. a recorded row still left over is shown as it stands. It is a thing the household asserted, and
   a schedule edit does not get to delete it.

Step 2 is what makes editing a bill safe for a household that has been keeping records, and step 3
is what makes it honest.

**There is no job, no scheduler, and no clock dependency anywhere in the write path.** The only
thing that reads the clock is the derived "overdue" flag, which is computed per request and is
therefore correct on an instance that has been off for a month.

### Consequences for the schedule

Projection has to be cheap and total: for any period, a bill must be able to say which dates it
falls on without consulting anything. So the schedule is a **small closed set** rather than a
general recurrence rule:

| Schedule      | Parameters               | Projects                                              |
| ------------- | ------------------------ | ----------------------------------------------------- |
| `MONTHLY`     | day of month 1–31        | one occurrence, clamped to the month's last day       |
| `YEARLY`      | month + day              | one occurrence, in that month only                    |
| `WEEKLY`      | weekday                  | every matching weekday in the period                  |
| `FORTNIGHTLY` | weekday + an anchor date | every other matching weekday, counted from the anchor |
| `IRREGULAR`   | none                     | nothing — it is listed, never projected               |

`IRREGULAR` exists because the designs show it and because a bill somebody cannot predict is still
a bill they want listed. It projects no occurrence; recording one against a period creates it.

RRULE was rejected: it brings a parser, a timezone model and an infinite tail of cases nobody
asked for, and `docs/domain/legacy-app.md` is a list of what happens when a small product carries
a large dependency it does not need.

## Alternatives considered

| Option                                                          | Why not                                                                                                                                                                                                                                                                                            |
| --------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **A nightly or monthly job that inserts occurrences**           | Correctness depends on the job having run. On a self-hosted box that is off, restored from a backup, or recreated, it silently has not — and a missing occurrence is indistinguishable from a bill nobody set up. ADR-0025 already rejects this reasoning for `status`; it applies to the rows too |
| **Insert the next occurrence when the previous one is settled** | Breaks for a bill nobody ever marks paid, which is the common case for a bill paid by direct debit. The list would simply stop                                                                                                                                                                     |
| **Key an occurrence by `(billId, period)`**                      | One row per month, which silently caps a `WEEKLY` bill at one settled payment out of four or five. Discovered while writing the service against the migration, before either was committed                                                                                                                              |
| **Materialise on first read of a period**                       | A `GET` that writes. It makes a read non-idempotent, means two clients racing on a list can conflict, and makes "has anybody touched this month" unanswerable. The projection gives the same screen with none of that                                                                              |
| **A general recurrence rule (RRULE)**                           | A parser, a timezone model and an infinite tail of cases for a product whose designs show five shapes. Rejected on the same grounds as every other dependency this rewrite exists to shed                                                                                                          |
| **Generate rows so that reporting can query them**              | Reporting can query the projection just as well, and a projected occurrence is always consistent with its bill. Generated rows go stale the moment a bill's amount or day changes, and then the plan disagrees with itself                                                                         |

## Consequences

**Good.** No scheduler, no clock in the write path, and no state that decays while an instance is
switched off. Editing a bill's amount or due day instantly corrects every future period, because
nothing was written down to contradict it. A fresh instance and a six-month-old one answer a
question about next month identically.

**Bad / costs.** Changing a bill's schedule also changes the _past_, for every period where
nothing was recorded — the projection is computed from the bill as it is now, so last March will
be re-projected against today's due day. Periods somebody actually settled are unaffected, because
those have rows. This is the real cost of the decision and it is the right trade: a household that
has been marking bills paid has a true history, and one that has not has nothing to be wrong
about.

A bill deleted while occurrences exist takes them with it. Its _transactions_ are untouched: a
bill is a plan, and ADR-0025 is explicit that only the real transaction is ever income or expense.

**Not built here.** `ChargeRule` and auto-matching need a connected account (slice 16). The
`statusSource` field exists and is always `MANUAL` until then, so that a figure somebody asserted
never becomes indistinguishable from one the app inferred — which is the whole reason the field
exists.
