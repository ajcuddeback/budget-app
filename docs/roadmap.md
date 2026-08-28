# Roadmap

Order matters: each slice depends on the ones above it. Every slice is **vertical** — database
through API through UI, tested and shippable — never a horizontal "build all the entities" phase.

Use `/vertical-slice` to work one.

| # | Slice | Depends on | Status |
|---|---|---|---|
| 0 | AI harness, docs, style guides | — | **Done** |
| 0b | UI validation harness (Playwright + axe) | 0 | **Done** |
| 1 | Backend + frontend skeletons, CI, `verify.sh` proven | 0 | Next |
| 2 | [Users & authentication](features/accounts-and-auth.md) | 1 | Planned |
| 3 | Accounts (money containers) | 2 | Planned |
| 4 | Categories + defaults on signup | 2 | Planned |
| 5 | Transactions (create, list, edit, delete) | 3, 4 | Planned |
| 6 | Transfers between accounts | 5 | Planned |
| 7 | Budgets & periods | 4, 5 | Planned |
| 8 | Recurring transactions | 5 | Planned |
| 9 | Reporting & insights | 5, 7 | Planned |
| 10 | Legacy data migration | 2–7 | Planned |
| 11 | Delete `client/`, `server/`, root MERN `package.json` | 10 | Planned |

## Rules

- Nothing starts without a feature doc (`/feature-doc`).
- Nothing is "done" until `tools/verify.sh` passes and the mandatory auth tests from
  `guides/testing-style.md` exist.
- Any slice with a UI is not done until `tools/ui-check.sh` has been run **and its screenshots
  read**. See `guides/ui-validation.md`.
- Slice 11 only happens after parity is genuinely reached — not "close enough".
