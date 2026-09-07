# Contributing to Budget Owl

Thanks for considering it. This is software about people's money that they run on their own
hardware, so the standards here are higher than a typical side project — but they are written
down, and none of them are secret.

> **⚠️ No licence has been chosen yet** ([ADR-0021](docs/adr/0021-licence-agpl-3.md) proposes
> AGPL-3.0). Until a `LICENSE` file exists, **no rights are granted and contributions cannot be
> accepted**. If you are reading this before that lands, feel free to open an issue — but hold
> off on code.

## You do not have to use AI

This repository contains an AI harness (`CLAUDE.md`, `.claude/`) and a lot of the code was
written with AI assistance. **None of that is required of you.**

Write code by hand. Use a different AI tool. Use no tool at all. It genuinely does not matter,
and a hand-written patch is not second-class here.

What *is* required is that the result meets the same standards — because they are not "AI rules",
they are **the project's engineering standards**, and the harness is only one way of having them
applied automatically:

| | |
|---|---|
| `docs/` | The standards. Read these whoever or whatever writes the code |
| `.claude/` | One way to have them applied automatically. Optional, ignorable |

If you use your own AI tooling, pointing it at `docs/guides/` and `CLAUDE.md` will save you a
review round. That is a suggestion, not a requirement.

## Before you write code

**Open an issue first** for anything beyond a small fix. A feature that does not fit the product
direction is a painful thing to discover in review after you have written it.

**Read the decision records.** [`docs/adr/README.md`](docs/adr/README.md) explains why things are
the way they are, including the alternatives that were rejected and why. If your change
contradicts one, that is not automatically wrong — but say so in the PR, and expect to discuss
it. Sometimes the ADR is what needs superseding.

**Start from the feature doc.** Anything user-facing has one in
[`docs/features/`](docs/features/README.md) describing intent, rules, edge cases and what is
deliberately out of scope. If there is not one, that is the first thing to write.

## Getting set up

```bash
tools/dev-up.sh     # PostgreSQL in Docker
tools/verify.sh     # the gate — everything CI runs, minus the slow parts
```

You need Java 21, Maven 3.9+, Node 22+, Docker, and Flutter if you are touching `mobile/`.

`tools/verify.sh` is deliberately forgiving locally: missing tooling prints `!` and warns rather
than failing, so you can still get partial signal. **The same conditions fail in CI.** If you see
a `!`, that will be red on your PR.

## The standards, in short

The full versions are in [`docs/guides/`](docs/guides/). The ones that get PRs sent back:

1. **Never `float`/`double` for money.** `BigDecimal` in Java, `NUMERIC(19,4)` in Postgres, strings
   over the wire, `decimal` in Dart ([ADR-0006](docs/adr/0006-money-representation.md)).
2. **Every query touching financial data is scoped to a household the authenticated user is a
   verified member of** — resolved from membership, never from the request. Roles are enforced
   server-side ([ADR-0008](docs/adr/0008-user-scoped-data-access.md),
   [ADR-0017](docs/adr/0017-households-own-financial-data.md)).
3. **Nothing in the core may require a service the maintainers operate.** No mandatory internet,
   no external account. Someone's instance must work standing alone
   ([ADR-0016](docs/adr/0016-self-hosted-open-source-product.md)).
4. **Schema changes are Flyway migrations, append-only.** Never edit a merged one.
5. **No secrets, ever** — not in code, config, tests, fixtures or docs.
6. **Strings come from translation files; amounts and dates are never hand-formatted**
   ([ADR-0023](docs/adr/0023-internationalisation.md)).
7. **Tests ship with the change.** See below.

## Tests

`docs/guides/testing-style.md` is the full picture, including a primer on the tooling if PIT,
ArchUnit or Stryker are new to you. The parts that block a merge:

- **The mandatory tests for every endpoint**: unauthenticated → `401`; another household's
  resource → `404`; invalid input → `400`; a `VIEWER` gets `403` on writes; identical behaviour
  under both session and bearer transports.
- **Coverage thresholds.** A floor that catches "no tests were written" — not evidence of good
  testing, and never a substitute for the list above.
- **Mutation testing** on changed code. If a mutant survives, some line is covered but unguarded.

If you are choosing between raising coverage and writing one of the mandatory tests, write the
mandatory test.

## Commits and pull requests

- Branch from `main`. Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`…).
- The body explains **why**; the diff already shows what.
- **Sign off your commits** — `git commit -s` adds the `Signed-off-by` line, certifying you have
  the right to contribute the code under the project's licence
  ([DCO](https://developercertificate.org/)). There is no CLA and no copyright assignment.
- Keep PRs to one logical change. Formatting-only changes go in their own commit.

Your PR should say what changed and why, link the feature doc or ADR, and note anything it
touches in the security model. "None" is a fine answer when it is genuinely none.

**Disclose AI assistance if you used it.** Not because it is discouraged — most of this codebase
was written that way — but because reviewers calibrate differently, and honesty about it is worth
more than the appearance of hand-craftsmanship.

## Translations

Translations are community-contributed and very welcome; English is the only language the
maintainers own ([ADR-0023](docs/adr/0023-internationalisation.md)). A translation platform will
be set up before the first non-English language lands — open an issue if you want to translate
and it is not there yet.

## Security

**Do not open a public issue for a security vulnerability.** See [SECURITY.md](SECURITY.md).

## Decisions

If your change involves a real decision — one that is hard to reverse, rejects a reasonable
alternative, or changes a security posture, data model or public contract — write an ADR for it
using [`docs/adr/template.md`](docs/adr/template.md). The most valuable section is
*Alternatives considered*: an ADR with no genuine alternatives usually documents a decision that
was not actually made.

Smaller things — a convention, a trap that cost you an hour — go in
[`docs/memory/`](docs/memory/README.md). Future contributors will thank you, and so will the
version of you that comes back in six months.
