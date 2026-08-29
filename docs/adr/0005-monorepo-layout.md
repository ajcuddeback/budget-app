# ADR-0005: Monorepo — `backend/` + `frontend/`

- **Status:** Accepted
- **Date:** 2026-08-27
- **Deciders:** Repository owner
- **Amended by:** [ADR-0015](0015-delete-the-legacy-app.md) — the legacy app was deleted before
  feature parity, not after. The layout decision itself stands.

## Context

The rewrite introduces two new applications. The existing `client/` and `server/` directories
hold the legacy React and Express apps, which stay temporarily as a behavioral reference.

Directory naming matters more than usual here: an AI agent that opens `server/` expecting
Spring and finds Express will produce confused, blended code.

## Decision

New code lives in `backend/` (Spring Boot) and `frontend/` (Angular) at the repository root.
The legacy `client/` and `server/` directories are **read-only reference** and are deleted once
feature parity is reached.

Deliberately distinct names — not reusing `client/` and `server/` — so no path is ever ambiguous
about which stack it holds.

## Alternatives considered

| Option | Why not |
|---|---|
| Two separate repositories | Cross-cutting changes need two PRs; version skew between API and client becomes a standing problem for a one-person project |
| Reuse `client/` and `server/` paths in place | Confusing git history, and mixed conventions during the transition — exactly the ambiguity we want to avoid |
| Delete the legacy app immediately | Loses the behavioral reference before parity is reached |

## Consequences

**Good:** one PR per feature across both stacks; one CI pipeline; unambiguous paths.

**Bad / costs:** two toolchains in one repo, and CI must scope jobs by changed paths to stay
fast. Legacy code sits in the tree for a while — mitigated by `CLAUDE.md` marking it read-only
and `docs/domain/legacy-app.md` documenting it so nobody needs to open it.

**Follow-ups:** delete `client/`, `server/`, and the root MERN `package.json` when parity lands.
