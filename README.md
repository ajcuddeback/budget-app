# Budget App

A personal budgeting application: accounts, transactions, categories, budgets, recurring items,
and reporting.

**Status: rebuilding from scratch.** An **Angular** SPA on a **Java 21 / Spring Boot** API backed
by **PostgreSQL**. Security is the top priority — see
[`docs/architecture/security-model.md`](docs/architecture/security-model.md).

The original MERN version has been removed; this is a clean-slate rewrite, not a migration
([ADR-0015](docs/adr/0015-delete-the-legacy-app.md)). What that app did, and the defects that
prompted the rewrite, are recorded in
[`docs/domain/legacy-app.md`](docs/domain/legacy-app.md).

## Layout

```
backend/   Spring Boot API      (not yet created)
frontend/  Angular SPA          (not yet created)
docs/      Architecture, decisions, feature specs, conventions
userguide/ Customer-facing help, written from the running UI
tools/     verify.sh (the gate), ui-check.sh, userguide-capture.sh
.claude/   AI harness: agents, skills, hooks, permissions
.agents/   Vendored Angular skills, pinned in skills-lock.json
```

## Getting started

```bash
tools/dev-up.sh     # local PostgreSQL in Docker
tools/verify.sh     # the gate: build, lint, test, scan
```

Requires Java 21, Maven 3.9+, Node 22+, and Docker.

## Documentation

Start at [`docs/README.md`](docs/README.md).

- [Architecture overview](docs/architecture/overview.md)
- [Security model](docs/architecture/security-model.md) — read before touching auth
- [Domain model](docs/domain/model.md)
- [Decision records](docs/adr/README.md) — why things are the way they are
- [Feature specs](docs/features/README.md)
- [Style guides](docs/guides/)
- [Roadmap](docs/roadmap.md)

## Working on this with AI

The repository carries a purpose-built harness so an agent starts oriented instead of exploring:

- **[`CLAUDE.md`](CLAUDE.md)** — a short router: non-negotiables, plus which doc to read for
  which task.
- **`docs/`** — durable knowledge, so nothing has to be rediscovered by reading code.
- **`.claude/agents/`** — specialists: `spring-api`, `angular-ui`, `persistence`,
  `security-auditor`, `test-author`, `docs-curator`, `ui-validator`, `user-docs`.
- **`.claude/skills/`** — workflows: `/feature-doc`, `/adr`, `/vertical-slice`, `/threat-model`,
  `/remember`, `/verify`, `/ui-check`, `/user-guide`.
- **`.agents/skills/`** — Angular's own official skills, vendored and pinned
  ([ADR-0014](docs/adr/0014-adopt-official-angular-skills.md)).
- **`.claude/hooks/`** — enforcement: session orientation, a write guard for secrets, and a
  scaffolding guard.

The rules of the road: start from a feature doc, record decisions where they will be found, and
run `tools/verify.sh` before calling anything done.
