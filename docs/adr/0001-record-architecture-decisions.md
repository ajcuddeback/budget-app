# ADR-0001: Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-08-27
- **Deciders:** Repository owner

## Context

This app is being rewritten largely with AI assistance. Each session starts without memory of
the last one. Decisions made in a chat transcript are invisible to the next session, which means
they get silently re-litigated, contradicted, or reversed — usually without anyone noticing.

Human contributors have the same problem on a slower clock.

## Decision

We record significant architectural decisions as numbered ADRs in `docs/adr/`, using the
template in `template.md`. ADRs are immutable once accepted; a change means a new ADR that
supersedes the old one.

## Alternatives considered

| Option | Why not |
|---|---|
| Decisions in commit messages | Not discoverable; nobody greps history to find "why" |
| A wiki outside the repo | Drifts from the code, and agents can't read it |
| Nothing — rely on the code | The code shows *what*, never *why not the other thing* |

## Consequences

**Good:** the "why" survives session boundaries and staff changes. Agents can read
`docs/adr/README.md` and stop asking settled questions.

**Bad / costs:** a small tax on every significant decision, and discipline is required — an ADR
written three months late is fiction.

**Follow-ups:** the `/adr` skill automates numbering and templating so the tax stays small.
