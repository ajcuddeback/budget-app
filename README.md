# Budget Owl

**A self-hosted, open-source budgeting app for households.** Run it on your own hardware; your
financial data never leaves it.

Web app, mobile app, and an API — all yours, on your machine.

> **Status: early. Nothing is built yet.** The repository currently holds the architecture,
> decisions and development harness. See [`docs/roadmap.md`](docs/roadmap.md) for what is next.

## Why

Self-hosted budgeting already exists — [Actual Budget](https://actualbudget.org) and
[Firefly III](https://firefly-iii.org) are both good. Neither has a mobile app worth using, and
that is where most budgeting actually happens: standing in a shop, deciding whether to buy the
thing.

Budget Owl is a self-hosted budgeting app that takes mobile seriously.

The organising principle is **privacy by architecture, not by policy**. A promise not to look at
your data is worth whatever the next funding round says it is; an architecture where the data is
on your machine and we have no path to it is worth the same regardless of our intentions.

## Planned

**Core, always free and open source** — accounts · transactions · categories · budgets ·
transfers · recurring items · reporting · household sharing with roles · file import (CSV/OFX/QIF)
· web app · mobile app

**Optional, and off by default** — bank connections using *your own* aggregator credentials · AI
insights via a local model, your own API key, or a hosted endpoint

Full picture: [`docs/product/vision.md`](docs/product/vision.md).

## Stack

| | |
|---|---|
| API | Java 21 · Spring Boot · PostgreSQL |
| Web | Angular |
| Mobile | Flutter (iOS + Android) |
| Deployment | Docker Compose on your hardware |

## Layout

```
backend/   Spring Boot API      (not yet created)
frontend/  Angular SPA          (not yet created)
mobile/    Flutter app          (not yet created)
docs/      Architecture, decisions, specs, conventions
userguide/ Customer-facing help, written from the running UI
tools/     verify.sh (the gate), ui-check.sh, userguide-capture.sh
.claude/   AI harness: agents, skills, hooks
.agents/   Vendored Angular skills, pinned
```

## Development

```bash
tools/dev-up.sh     # local PostgreSQL in Docker
tools/verify.sh     # the gate: build, lint, test, scan
```

Requires Java 21, Maven 3.9+, Node 22+, Docker. Flutter once `mobile/` exists.

## Documentation

Start at [`docs/README.md`](docs/README.md).

- [**Product vision**](docs/product/vision.md) — what this is and who it is for
- [Architecture overview](docs/architecture/overview.md)
- [Security model](docs/architecture/security-model.md) — read before touching auth
- [Decision records](docs/adr/README.md) — 21 ADRs explaining why things are the way they are
- [Roadmap](docs/roadmap.md)

## Working on this with AI

The repository carries a purpose-built harness so an agent starts oriented instead of exploring:
[`CLAUDE.md`](CLAUDE.md) routes to the right doc, `.claude/agents/` holds nine specialists,
`.claude/skills/` holds the workflows, and three self-checking gates (`verify.sh`, `ui-check.sh`,
`userguide-capture.sh`) run with no application present.

## Licence

**Not yet chosen** — [ADR-0021](docs/adr/0021-licence-agpl-3.md) proposes AGPL-3.0 and is awaiting
a decision. Until a `LICENSE` file exists, no rights are granted.
