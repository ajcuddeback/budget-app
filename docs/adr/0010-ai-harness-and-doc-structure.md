# ADR-0010: AI harness and documentation structure

- **Status:** Accepted
- **Date:** 2026-08-27
- **Deciders:** Repository owner

## Context

This rewrite is being done largely with AI assistance. That creates failure modes a
conventional project doesn't have:

- **Context amnesia.** Each session starts blank. Without durable notes, agents re-explore the
  codebase every time — expensive, slow, and inconsistent between runs.
- **Convention drift.** Without written style rules, each session invents plausible but
  different conventions, and the codebase slowly loses coherence.
- **Silent security regressions.** An agent optimizing for "make the test pass" will happily
  disable CSRF or widen a query. Security intent must be written down and mechanically checked.
- **Lost reasoning.** Decisions made in chat vanish with the transcript.

## Decision

We maintain a repository-specific harness:

- **`CLAUDE.md`** — a short router at the repo root. Non-negotiables plus a table pointing at
  the right doc for each task. Deliberately short; depth lives behind links so it is read.
- **`docs/`** — four kinds of durable knowledge: `architecture/`, `domain/`, `features/`,
  `adr/`, plus `memory/` for accumulated conventions, glossary, and gotchas, and `guides/` for
  style. `docs/README.md` explains which is which.
- **`.claude/agents/`** — specialist subagents (`spring-api`, `angular-ui`, `persistence`,
  `security-auditor`, `test-author`, `docs-curator`) with narrowed tools and focused prompts.
- **`.claude/skills/`** — repeatable workflows as slash commands (`/feature-doc`, `/adr`,
  `/vertical-slice`, `/threat-model`, `/remember`, `/verify`).
- **`.claude/hooks/`** — mechanical enforcement: session orientation, a secret-and-legacy-path
  write guard, and auto-formatting.
- **`tools/verify.sh`** — one command that is the definition of "done".

## Alternatives considered

| Option | Why not |
|---|---|
| One large `CLAUDE.md` with everything | Long instruction files get skimmed and blow context budget on every turn. A router plus on-demand depth reads better and costs less |
| Rely on prompting each session | Depends on the human remembering every rule every time. It doesn't survive a bad day |
| Generated docs only (Javadoc, OpenAPI) | Describe *what* the code does, never *why*, and never what we chose not to do |

## Consequences

**Good:** sessions start oriented instead of exploring. Conventions stay stable across sessions
and contributors. Security rules are written and hook-enforced rather than remembered.
The docs help humans too.

**Bad / costs:** the harness needs maintenance — docs that drift from the code are worse than
none, because they get trusted. Feature docs must be updated in the same change as the behavior
they describe, and that discipline is on us.

**Follow-ups:** treat harness rot as a bug. If an agent gets misled by a stale doc, fix the doc
in that session, not later.
