# Budget Owl

**A self-hosted budgeting app for your household.** It runs on your hardware. Your financial data
never leaves it — not to us, not to anyone.

Web, mobile, and an API. All of it yours.

> **Status: early.** No application code exists yet. The repository holds the architecture, the
> decisions behind it, the designs, and the development harness. See
> [`docs/roadmap.md`](docs/roadmap.md) for what is being built and in what order.

## Why

Self-hosted budgeting already exists — [Actual Budget](https://actualbudget.org) and
[Firefly III](https://firefly-iii.org) are both good. Neither has a mobile app worth using, and
that is where budgeting actually happens: standing in a shop, deciding whether to buy the thing.

Budget Owl takes mobile seriously, and takes privacy seriously in the only way that survives
contact with reality: **privacy by architecture, not by policy.** A promise not to look at your
data is worth whatever the next funding round says it is. An architecture where the data is on
your machine and we have no path to it is worth the same regardless of what we intend.

## What it does

**Envelopes** — set an amount for groceries, log against it as you spend, see what is left. The
one thing a spreadsheet does badly, because the total that matters arrives at month end when it
can no longer change a decision.

**Bills & income** — everything scheduled, in and out. Enter what you owe and when; mark it paid
as the month goes on. If you have connected an account, a matching charge marks it for you.

**Debt plan** — every balance, its terms, and one order of attack. Highest rate first or smallest
balance first, with what each choice costs you and when you are actually clear.

**Weekly check-in** — four questions on a Sunday: anything unusual, what needs a category, which
bills cleared, where the surplus goes. Steps with nothing to answer are skipped.

**Also** — accounts, transactions, categories, transfers, goals, reporting, file import
(CSV/OFX/QIF), household sharing with roles, and a console for running the instance.

**As many currencies and languages as we can manage.** Two people in one household seeing the
same data in different currencies and different languages is a normal case here, not an edge one.

## What it will not do

**Require anything of ours.** No account with us, no mandatory internet, no third-party service in
the core. Your instance works standing alone ([ADR-0016](docs/adr/0016-self-hosted-open-source-product.md)).

**Send your finances anywhere.** The optional assistant runs in a container on your own hardware
or it does not exist on your instance — there is no bring-your-own-key and no endpoint of ours
([ADR-0027](docs/adr/0027-llm-is-an-optional-self-hosted-sidecar.md)).

**Hold your bank credentials.** Connecting accounts is optional and uses *your own* aggregator
credentials, with the risk that implies stated plainly
([ADR-0020](docs/adr/0020-bank-connections-use-user-credentials.md)). Everything works without it.

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
design/    Visual source of truth, exported from Claude Design
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
- [Decision records](docs/adr/README.md) — 27 ADRs explaining why things are the way they are
- [Roadmap](docs/roadmap.md)

## Contributing

Read [**CONTRIBUTING.md**](CONTRIBUTING.md) first — the standards, the mandatory tests, and how to
open a change.

**You do not have to use AI to contribute.** By hand, with a different tool, with no tool at all —
it does not matter, and a hand-written patch is not second-class here. What matters is that the
result meets the same standards, because they are not "AI rules", they are the project's
engineering standards. `docs/` holds them; `.claude/` is one way of having them applied
automatically, and you are free to ignore it.

Found a security problem? **Do not open a public issue** — see [SECURITY.md](SECURITY.md).

## Working on this with AI

If you do want the harness: [`CLAUDE.md`](CLAUDE.md) routes to the right doc so an agent starts
oriented instead of exploring, `.claude/agents/` holds nine specialists, `.claude/skills/` holds
the workflows, and three self-checking gates (`verify.sh`, `ui-check.sh`, `userguide-capture.sh`)
run with no application present.

## Licence

**Not yet chosen.** [ADR-0021](docs/adr/0021-licence-agpl-3.md) proposes AGPL-3.0 and is still
open. Until a `LICENSE` file exists, no rights are granted and contributions cannot be accepted.
