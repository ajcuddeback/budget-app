# ADR-0015: Delete the legacy app; rebuild from scratch

- **Status:** Accepted
- **Date:** 2026-08-29
- **Deciders:** Repository owner
- **Amends:** the deletion timing in ADR-0005

## Context

ADR-0005 kept the original MERN app (`client/`, `server/`) in the tree as a read-only behavioral
reference, to be deleted "once feature parity is reached". The reasoning was that the old code
answers questions the new code cannot yet: what a screen did, what an endpoint returned, what an
edge case was.

Two things changed that calculation.

**The reference was already extracted.** `docs/domain/legacy-app.md` was written while the code
was present, and deliberately covers what the app did, its complete data model, its API surface,
and the ten specific defects that motivated the rewrite. Nothing in the source was still needed
to answer a design question.

**Parity was never the actual goal.** The old app was a monthly list of named "bills" and
"income" rows. The intended product — accounts, transactions, categories, budgets, transfers,
recurring items, reporting — is a different application. Waiting for "parity" with a much
smaller app would have been waiting for a milestone that does not describe anything we want.

Meanwhile the cost was ongoing: two stacks in one tree, a hook and permission rules existing
only to stop agents editing dead code, and a standing risk that an agent reads `server/` and
copies a pattern from an app whose security defects are the reason for the rewrite.

## Decision

Delete `client/`, `server/`, `images/`, and the root MERN `package.json` and `package-lock.json`.
This is a clean-slate rewrite, not a migration.

`docs/domain/legacy-app.md` is promoted to the sole record of the old app and is maintained as
such. The code remains in git history (`bd6b875`), but the document is the intended reference —
history is a fallback, not a workflow.

The write guard that blocked edits to `client/` and `server/` is **kept**, repurposed: it now
prevents an agent *recreating* those paths, which would silently reintroduce what this ADR
removed.

**Explicitly not decided here: what happens to any data** in a deployed instance. Deleting code
does not delete a database. That question is tracked as slice 10 in `docs/roadmap.md`; the
information needed to write a migration is preserved in `legacy-app.md`, so deferring it costs
nothing.

## Alternatives considered

| Option | Why not |
|---|---|
| Keep until parity (ADR-0005 as written) | Parity with a much smaller, differently-shaped app was never a milestone worth reaching. It deferred deletion indefinitely for a reference already extracted into docs |
| Move it to an `archive/` directory | Same risks — an agent can still read and copy from it — with the added implication that it is somehow still live |
| Delete the code *and* `legacy-app.md` | Throws away the reasoning behind the rewrite and the only description of the data shapes. A future migration, or a "why didn't we just fix the old one?" question, would have no answer |
| Move it to a separate repository | Preserves it at the cost of a repository nobody will open. Git history already does this, for free |

## Consequences

**Good:** one stack in the tree, so no agent can read or copy the old patterns. The harness gets
simpler — permission rules and grep exclusions that existed only to fence off dead code are gone.
Roughly 2MB and 42 files removed. The rewrite is now unambiguously a rewrite rather than
something shadowed by an implicit parity obligation.

**Bad / costs:** the behavioral reference is now a document rather than executable code, so if
`legacy-app.md` is wrong, nothing contradicts it — it must be treated as a primary source and
kept accurate. Recovering original source now means a git-history archaeology step
(`git show bd6b875:<path>`) rather than opening a file. And if data migration is eventually
wanted, it will be written from a description rather than from working code.

**Follow-ups:** answer the data question in `docs/roadmap.md` slice 10 — most likely by
confirming there is nothing to migrate and dropping it.
