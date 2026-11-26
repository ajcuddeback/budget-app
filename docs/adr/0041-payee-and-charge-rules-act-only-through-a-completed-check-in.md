# ADR-0041: Payee and charge rules act only through a completed check-in

- **Status:** Accepted
- **Date:** 2026-11-26
- **Deciders:** Repository owner (decisions 3 and 4 of `docs/features/weekly-check-in.md`)

## Context

The weekly check-in (slice 14) introduces two kinds of standing rule:

- a **payee rule**, `payee → category`, written when a member ticks *"Always file {payee} here"*
  in step 2;
- a **charge rule**, `(payee, account) → bill`, written when a member maps a repeating charge to a
  bill in step 3 (*"Map it to your Internet bill?"*). It was planned for slice 16 (bank
  connections) because matching was thought to need a connected account. File import (slice 12)
  made real charges available without one.

A rule is only useful if it acts somewhere. The obvious places are where rows arrive: at import
and at manual entry. But two shipped rules stand in the way. `file-import.md` rule 8 says import
never sets a category, and transactions carry no column that says how a category was set. A
category written by a rule at import would be indistinguishable, in the ledger and forever after,
from one a person chose, and nobody would have seen it being written. A mixed payee (one shop that
sells groceries and electronics) filed one way at import would be a silent, durable error.

Bills already have `statusSource` with an `AUTO_MATCHED` value reserved for "the app inferred this
from a rule", so the bill side has somewhere to record provenance. Transactions do not.

## Decision

**A payee rule and a charge rule act only inside a check-in, and only take effect when a member
completes it.**

- In a later check-in, a row whose payee has a payee rule, or a charge whose `(payee, account)`
  has a charge rule within tolerance, arrives **pre-answered**. The pre-answer is derived on every
  read and never written by a `GET`. The member can see it, labelled *your rule*, and change it.
- Completing the check-in applies the pre-answers alongside the member's own answers, in one
  transaction, through the owning services. A charge-rule attach is recorded with `statusSource =
  AUTO_MATCHED`; one the member answered is `MANUAL`.
- **Rules do nothing anywhere else.** Import still never sets a category or attaches a bill, and
  neither does recording a transaction by hand. There is no background job.
- Rules are **created only by completing a check-in** (there is no `POST` or `PATCH`), and are
  listed and removable on the categories page and on Bills & Income.
- *"Always file here"* is **off by default** on every row.

## Alternatives considered

| Option | Why not |
|---|---|
| Apply payee rules at import and on manual entry | Changes `file-import.md` rule 8, which shipped. Writes categories nobody saw, with no column to tell them apart later. A mixed payee becomes a permanent silent mis-filing |
| Apply charge rules at import (auto-mark bills paid) | Writes a bill status nobody confirmed. `AUTO_MATCHED` records the provenance but not the fact that nobody looked. Belongs with bank connections (slice 16), where a feed makes it the only practical path and gets its own decision |
| Keep charge rules in slice 16 and ship step 3 as one-off attaches | Asks the same COMCAST question every month, leaves step 1's *prepaid* with nothing to stand on, and makes "Skips if all mapped" unbuildable |
| *"Always file here"* on by default | A mixed payee filed one way forever, from one tap the member did not make |
| A rule that applies immediately when created | Rewrites past rows the member is not looking at. The check-in's promise is that nothing changes until Finish, and only what was shown |

## Consequences

**Good:** every category and bill status the app writes on a rule's behalf is one a person was
shown, in a list they confirmed, at a moment they chose. Import and manual entry are unchanged, so
`file-import.md` and `transactions.md` stand as shipped. A rule is visible and removable, and a
wrong one costs one week's review rather than a ledger of silent errors.

**Bad / costs:** a household that never completes a check-in gets no benefit from its rules. Rows
imported on Tuesday stay uncategorised until Sunday. A category set by a rule is still
indistinguishable in the ledger from one typed, because transactions have no category-source
column; that is acceptable only because a person confirmed it. *"Not a bill"* is not remembered, so
a declined suggestion returns next month (no negative rule).

**Follow-ups:** rule-at-import, if ever wanted, needs its own decision, an ADR superseding this one,
and a category-source column on transactions so a rule-written category can be told apart.
Automatic matching for connected accounts is slice 16's, and it starts from this ADR.
