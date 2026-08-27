# Budget App

A personal budgeting application: accounts, transactions, categories, budgets, recurring items,
and reporting.

**Status: being rewritten.** The original MERN app (`client/` + `server/`) is being replaced by
an **Angular** SPA on a **Java 21 / Spring Boot** API backed by **PostgreSQL**.
Security is the top priority of the rewrite — see [`docs/architecture/security-model.md`](docs/architecture/security-model.md).

## Layout

```
backend/   Spring Boot API      (not yet created)
frontend/  Angular SPA          (not yet created)
docs/      Architecture, decisions, feature specs, conventions
tools/     verify.sh (the gate), dev-up.sh (local Postgres)
.claude/   AI harness: agents, skills, hooks, permissions
client/    LEGACY React app  — read-only reference, being replaced
server/    LEGACY Express API — read-only reference, being replaced
```

## Getting started

```bash
tools/dev-up.sh     # local PostgreSQL in Docker
tools/verify.sh     # the gate: build, lint, test, scan
```

Requires Java 21, Maven 3.9+, Node 22+, and Docker.

## Documentation

Start at [`docs/README.md`](docs/README.md). The map:

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
  `security-auditor`, `test-author`, `docs-curator`.
- **`.claude/skills/`** — workflows: `/feature-doc`, `/adr`, `/vertical-slice`, `/threat-model`,
  `/remember`, `/verify`.
- **`.claude/hooks/`** — enforcement: session orientation, a write guard that blocks secrets and
  edits to the legacy app, and auto-formatting.

The rules of the road: start from a feature doc, record decisions where they will be found, and
run `tools/verify.sh` before calling anything done.

## Legacy app

The original app is documented in [`docs/domain/legacy-app.md`](docs/domain/legacy-app.md) —
what it did, and the ten specific defects that motivated the rewrite. Read that rather than the
legacy source. `client/` and `server/` are deleted once feature parity is reached.
