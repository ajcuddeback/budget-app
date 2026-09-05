# Budget Owl — Agent Guide

**Budget Owl is a self-hosted, open-source budgeting app for households.** Users run it on their
own hardware; their financial data never leaves it. **Angular** SPA + **Java 21 / Spring Boot**
API + **PostgreSQL**, with a **Flutter** mobile app.

Read `docs/product/vision.md` before making a product judgement call. The organising principle is
**privacy by architecture, not by policy** — if a decision would put us in possession of a user's
financial data, it is the wrong decision (ADR-0016).
This app handles people's financial data. **Security is the top priority, ahead of speed and
ahead of features.** When a tradeoff appears, take the secure option and note it in the PR.

## Read this before you start

Do **not** re-explore the codebase to answer questions the docs already answer.
Read the doc, then read only the code you are about to change.

| You are about to… | Read first |
|---|---|
| Anything at all | this file |
| Decide anything product-shaped | `docs/product/vision.md` |
| Build or change a feature | `docs/features/<feature>.md`, then `docs/features/README.md` |
| Understand the system shape | `docs/architecture/overview.md` |
| Touch auth, sessions, cookies, CSRF | `docs/architecture/security-model.md` (**mandatory**) |
| Write Java | `docs/guides/java-style.md` |
| Write Angular / TypeScript | the `angular-developer` skill, then `docs/guides/angular-style.md` |
| Write Flutter / Dart | `docs/guides/flutter-style.md` |
| Add or change a REST endpoint | `docs/guides/api-style.md` |
| Write a migration or entity | `docs/guides/database-style.md` |
| Write tests | `docs/guides/testing-style.md` |
| Check how the UI actually looks | `docs/guides/ui-validation.md` |
| Write docs for the people using the app | `docs/guides/user-docs.md`, `userguide/STYLE.md` |
| Commit, branch, or open a PR | `docs/guides/git-style.md` |
| Understand a domain term | `docs/domain/model.md`, `docs/memory/glossary.md` |
| Wonder what the old app did | `docs/domain/legacy-app.md` (the code is gone) |
| Wonder "why is it like this?" | `docs/adr/` (index in `docs/adr/README.md`) |
| Hit something surprising | `docs/memory/gotchas.md` |

## Repository layout

```
backend/     Spring Boot API (Java 21, Maven)   — not yet created
frontend/    Angular SPA (TypeScript)           — not yet created
mobile/      Flutter app (Dart), iOS + Android  — not yet created (ADR-0019)
docs/        Durable knowledge for DEVELOPERS and agents. See docs/README.md
userguide/   Customer-facing help for PEOPLE USING THE APP. Different reader — see its STYLE.md
tools/       Dev + CI scripts. tools/verify.sh is the gate.
.agents/     Vendored third-party agent skills (Angular's). Pinned; see skills-lock.json
.claude/     Agents, skills, hooks for this repo
```

The original MERN app (`client/`, `server/`) was **deleted** — this is a clean-slate rewrite,
not a migration (ADR-0015). What it did, and the ten defects that motivated the rewrite, are
recorded in `docs/domain/legacy-app.md`. That document is now the only record: read it rather
than digging through git history.

## Non-negotiables

1. **No secrets in the repo.** No passwords, keys, tokens, or connection strings in source,
   config, tests, or docs. Configuration comes from environment variables. A hook blocks
   commits of likely secrets; do not work around it.
2. **Every endpoint is authenticated and authorized by default.** Public routes are an explicit,
   reviewed exception. Every query that touches financial data filters by a **household the
   authenticated user is a verified member of** — never trust an ID from the request body or
   path to imply ownership (ADR-0008, amended by ADR-0017). Roles matter too: a `VIEWER` may
   read but never write.
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
9. **Nothing in the core may require a service we operate.** No mandatory internet access, no
   account with us, no third-party SaaS. A self-hoster's instance works standing alone
   (ADR-0016). Optional integrations are opt-in and off by default.
10. **A user-visible feature ships with its user guide.** `userguide/` is written from the
   running UI, never from the feature doc — see ADR-0012. Captures render against the demo
   fixtures in `tools/ui/fixtures/demo-data.ts` and refuse any non-local target (ADR-0013).

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
  `test-author`, `docs-curator`, `ui-validator`, `user-docs`, `flutter-ui`. Use `security-auditor` on anything touching auth, money
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
tools/update-skills.sh       # refresh vendored third-party agent skills (review the diff)
```

## Slash commands

`/feature-doc` · `/adr` · `/vertical-slice` · `/threat-model` · `/remember` · `/verify` · `/ui-check` · `/user-guide`

Plus Angular's own vendored skills: `angular-developer` (framework guidance) and
`angular-new-app` (scaffolding). They are the source of truth for **Angular**;
`docs/guides/angular-style.md` is the source of truth for **this project**, and its override
table lists every point where we differ. See ADR-0014.
