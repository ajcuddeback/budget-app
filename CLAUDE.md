# Budget App — Agent Guide

Personal budgeting app. **Angular** SPA + **Java 21 / Spring Boot** API + **PostgreSQL**.
This app handles people's financial data. **Security is the top priority, ahead of speed and
ahead of features.** When a tradeoff appears, take the secure option and note it in the PR.

## Read this before you start

Do **not** re-explore the codebase to answer questions the docs already answer.
Read the doc, then read only the code you are about to change.

| You are about to… | Read first |
|---|---|
| Anything at all | this file |
| Build or change a feature | `docs/features/<feature>.md`, then `docs/features/README.md` |
| Understand the system shape | `docs/architecture/overview.md` |
| Touch auth, sessions, cookies, CSRF | `docs/architecture/security-model.md` (**mandatory**) |
| Write Java | `docs/guides/java-style.md` |
| Write Angular / TypeScript | `docs/guides/angular-style.md` |
| Add or change a REST endpoint | `docs/guides/api-style.md` |
| Write a migration or entity | `docs/guides/database-style.md` |
| Write tests | `docs/guides/testing-style.md` |
| Check how the UI actually looks | `docs/guides/ui-validation.md` |
| Write docs for the people using the app | `docs/guides/user-docs.md`, `userguide/STYLE.md` |
| Commit, branch, or open a PR | `docs/guides/git-style.md` |
| Understand a domain term | `docs/domain/model.md`, `docs/memory/glossary.md` |
| Wonder "why is it like this?" | `docs/adr/` (index in `docs/adr/README.md`) |
| Hit something surprising | `docs/memory/gotchas.md` |

## Repository layout

```
backend/     Spring Boot API (Java 21, Maven)   — not yet created
frontend/    Angular SPA (TypeScript)           — not yet created
docs/        Durable knowledge for DEVELOPERS and agents. See docs/README.md
userguide/   Customer-facing help for PEOPLE USING THE APP. Different reader — see its STYLE.md
tools/       Dev + CI scripts. tools/verify.sh is the gate.
.claude/     Agents, skills, hooks for this repo
client/      LEGACY React app  — read-only reference, being replaced
server/      LEGACY Express API — read-only reference, being replaced
```

`client/` and `server/` are the **old** MERN app. Never add features there and never copy their
patterns. They exist only as a behavioral reference until parity is reached; see
`docs/domain/legacy-app.md` for what they did, so you don't have to read them.

## Non-negotiables

1. **No secrets in the repo.** No passwords, keys, tokens, or connection strings in source,
   config, tests, or docs. Configuration comes from environment variables. A hook blocks
   commits of likely secrets; do not work around it.
2. **Every endpoint is authenticated and authorized by default.** Public routes are an explicit,
   reviewed exception. Every query that touches user data filters by the authenticated user —
   never trust an ID from the request body or path to imply ownership.
3. **Never `float`/`double` for money.** `BigDecimal` in Java, `NUMERIC(19,4)` in Postgres,
   minor-unit integers or strings over the wire. See ADR-0006.
4. **Schema changes are Flyway migrations.** Never `ddl-auto: update`. Migrations are
   append-only — never edit one that has been merged.
5. **Validate input at the edge.** Bean Validation on every request DTO. Entities are never
   request or response bodies.
6. **Tests ship with the change.** A feature without tests is not done. See
   `docs/guides/testing-style.md` for what "enough" means.
7. **`tools/verify.sh` must pass before you say you're done.** Not "should pass" — run it.
8. **Frontend changes are checked in a real browser.** Run `tools/ui-check.sh` and *read the
   screenshots*. A green test suite does not tell you the page renders correctly.
9. **A user-visible feature ships with its user guide.** `userguide/` is written from the
   running app, never from the feature doc — see ADR-0012. Screenshots use demo data only;
   they are committed, and this is a financial app.

## Working agreement

- **Start from a feature doc.** Before writing code for a feature, open
  `docs/features/<feature>.md`. If it does not exist, create it with `/feature-doc` first —
  planning in the doc is cheaper than planning in code.
- **Record decisions where they'll be found.** An architectural choice → an ADR (`/adr`).
  A small convention, a gotcha, a "we tried X and it didn't work" → `/remember`.
  Do not leave decisions only in a chat transcript; the next session cannot read it.
- **Update the feature doc in the same change** that alters the behavior it describes.
  Stale docs are worse than no docs — they get trusted.
- **Prefer the specialists.** `spring-api`, `angular-ui`, `persistence`, `security-auditor`,
  `test-author`, `docs-curator`, `ui-validator`, `user-docs`. Use `security-auditor` on anything touching auth, money
  movement, or user data boundaries — including your own work.
- **Ask when the security answer is unclear.** Guessing at a security control is worse than
  a question.

## Common commands

```bash
tools/verify.sh              # full local gate: build, lint, test, both stacks
tools/verify.sh backend      # backend only
tools/verify.sh frontend     # frontend only
tools/dev-up.sh              # start Postgres (docker) for local dev
tools/ui-check.sh            # drive the running UI, screenshot it, check a11y
tools/ui-check.sh --selfcheck  # prove the UI harness works with no app present
tools/userguide-capture.sh   # capture annotated screenshots for the user guide
tools/userguide-check.sh     # find stale/missing/orphaned user-guide screenshots
```

## Slash commands

`/feature-doc` · `/adr` · `/vertical-slice` · `/threat-model` · `/remember` · `/verify` · `/ui-check` · `/user-guide`
