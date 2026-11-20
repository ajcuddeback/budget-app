# ADR-0031: An envelope's period is a label, and rollover is not built yet

- **Status:** Accepted
- **Date:** 2026-11-20
- **Deciders:** Repository owner

## Context

`docs/features/envelopes.md` ends with two questions marked _"genuinely undecided. Answer them
before building, not during."_ Slice 6 is the build, so they are answered here.

**1. Rollover.** `docs/domain/model.md` lists a `rollover` boolean on `Envelope` because the
designs show a rollover affordance — _"Roll into next week / Target becomes $543"_. The designs do
not say whether an unspent envelope carries forward automatically, on request, or not at all. They
say nothing at all about the harder half: an **overspent** envelope. Does the overspend come out of
next period, out of the left-over figure, or sit as a negative?

**2. Period boundary.** Envelopes are per-`YearMonth`. The admin console (slice 9) is specified to
carry a configurable period start — _"it decides what a month means everywhere in the app"_ — so
"August" is not necessarily the 1st to the 31st. Slice 6 lands three slices before that exists.

## Decision

### An envelope's period is a LABEL, not a pair of boundaries

`envelopes.period` stores the first day of the labelled month as a `date` — `2026-08-01` means
"the envelope for August 2026". It does **not** mean "entries dated on or after 1 August".

The distinction is the whole point. An entry belongs to an envelope because somebody logged it
there, not because its date falls in a range; `envelope_entries.envelope_id` is the relationship,
and the entry's own `date` is descriptive. So when slice 9 makes a month start on the 25th, it
changes **which transactions a person chooses to log where** — it does not re-label a single
existing row, invalidate a single sum, or need a migration.

Had the period been stored as a start/end pair, every envelope ever created would have to be
rewritten when the boundary moved, and every historical total would change meaning silently.

### Rollover is not built, and the column is not created

Slice 6 ships no `rollover` column and no carry-forward arithmetic. An envelope is an amount for
one labelled month. Unspent money does not carry; an overspent envelope shows a **negative
remaining**, which is information rather than an error state.

**A column that is always `false` is worse than no column.** It reads as a feature, so the next
person building on it assumes the arithmetic beneath exists. Adding it later is an additive
migration plus a read-time computation, which is cheap; removing a half-honoured one is not.

When rollover is built it needs its own ADR answering, at minimum:

- whether carry-in is automatic or offered, per envelope or per household;
- what an overspend does — reduce next period, reduce the left-over figure, or neither;
- how far back the chain walks, and what breaks it (a missing envelope? an archived one?).

### A transaction may be attached to at most one envelope entry, ever

Slice 6 ships both entry sources the feature doc requires. A `MANUAL` entry is an amount somebody
typed. A `SYNCED` entry is one where **the person attached an existing transaction** — never the
app matching on its own, which `docs/features/envelopes.md` is explicit about.

`envelope_entries.transaction_id` therefore carries a **unique** index. The same purchase counted
in two envelopes would overstate spending in both and understate what is left in each, and it is
the kind of error nobody notices because both numbers look plausible. One attachment, enforced by
the database rather than by the service that happens to be in front of it today.

## Alternatives considered

| Option                                                       | Why not                                                                                                                                                                                                                   |
| ------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Store the period as a start and end date**                 | Moves the slice-9 boundary problem into every existing row. A configurable month start would rewrite history and silently change what past totals mean. The label does not have that failure mode                         |
| **Ship `rollover` as a column defaulting to false**          | A column nothing honours is a promise the schema makes and the code breaks. The next person reads the field and assumes the arithmetic is there                                                                           |
| **Ship rollover with "unspent carries, overspend does not"** | Picks the easy half and leaves the half that actually matters undecided. An overspent envelope is the common case for exactly the households this feature is for, and guessing at it is worse than saying it is not built |
| **Derive entries from transactions by category and date**    | The feature doc forbids it: _"A transaction never files itself, however confident the category match looks."_ An envelope is a record of what somebody decided, and an app that fills it in has taken that away           |
| **Let a transaction be attached to several envelopes**       | Double-counts spending while both figures still look right. If a purchase genuinely spans two envelopes it wants a split transaction, which is its own feature                                                            |

## Consequences

**Good.** The slice-9 period boundary becomes a display and guidance question rather than a
migration. Envelopes work on day one with nothing connected, as the feature doc requires. The
double-count that would quietly corrupt every envelope total is structurally impossible.

**Bad / costs.** A household that underspends in August gets no credit for it in September, and
one that overspends sees a negative until the month turns. That is a real gap, and it is the gap
the designs gestured at. It is written down in `docs/features/envelopes.md` under Known gaps rather
than half-built.

`docs/domain/model.md` lists `rollover` on `Envelope`; that line is now ahead of the schema and is
annotated to say so.
