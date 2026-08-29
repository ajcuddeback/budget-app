# Roadmap

Order matters: each slice depends on the ones above it. Every slice is **vertical** — database
through API through UI, tested and shippable — never a horizontal "build all the entities" phase.

Use `/vertical-slice` to work one.

| # | Slice | Depends on | Status |
|---|---|---|---|
| 0 | AI harness, docs, style guides | — | **Done** |
| 0b | UI validation harness (Playwright + axe) | 0 | **Done** |
| 0c | User-guide capture + `user-docs` agent | 0b | **Done** |
| 1 | Backend + frontend skeletons, CI, `verify.sh` proven | 0 | Next |
| 2 | [Users & authentication](features/accounts-and-auth.md) | 1 | Planned |
| 3 | Accounts (money containers) | 2 | Planned |
| 4 | Categories + defaults on signup | 2 | Planned |
| 5 | Transactions (create, list, edit, delete) | 3, 4 | Planned |
| 6 | Transfers between accounts | 5 | Planned |
| 7 | Budgets & periods | 4, 5 | Planned |
| 8 | Recurring transactions | 5 | Planned |
| 9 | Reporting & insights | 5, 7 | Planned |
| 10 | Legacy data migration | 2–7 | **Needs a decision** — see below |

## Rules

- Nothing starts without a feature doc (`/feature-doc`).
- Nothing is "done" until `tools/verify.sh` passes and the mandatory auth tests from
  `guides/testing-style.md` exist.
- Any slice with a UI is not done until `tools/ui-check.sh` has been run **and its screenshots
  read**. See `guides/ui-validation.md`.
- Any user-visible slice is not done until its guide exists in `userguide/`, written from the
  running app. See `guides/user-docs.md`.
## Is there any data to migrate?

Slice 10 is the one open question left by the clean-slate decision (ADR-0015). Deleting the old
code settled the *code*; it did not settle whether anyone's **data** should carry over.

The original deployment was a 2021 Heroku app, so plausibly there is nothing left to migrate —
but that is worth confirming rather than assuming. Everything needed to write a migration is
preserved in `domain/legacy-app.md`, so the decision can wait; nothing is lost by deferring it.

If the answer is no, drop slice 10 and say so here.
