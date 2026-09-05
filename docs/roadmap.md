# Roadmap

Order matters: each slice depends on the ones above it. Every slice is **vertical** — database
through API through UI, tested and shippable — never a horizontal "build all the entities" phase.

Use `/vertical-slice` to work one.

| # | Slice | Depends on | Status |
|---|---|---|---|
| 0 | AI harness, docs, style guides | — | **Done** |
| 0b | UI validation harness (Playwright + axe) | 0 | **Done** |
| 0c | User-guide capture + `user-docs` agent | 0b | **Done** |
| 0d | Product direction: self-hosted, households, mobile (ADR-0016–0021) | 0 | **Done** |
| 1 | Backend + frontend skeletons, Compose packaging, CI proven | 0d | Next |
| 2 | [Users, households & auth](features/accounts-and-auth.md) — **doc needs rewriting first** | 1 | Planned |
| 3 | Accounts (money containers) | 2 | Planned |
| 4 | Categories + defaults on household creation | 2 | Planned |
| 5 | Transactions (create, list, edit, delete) | 3, 4 | Planned |
| 6 | Transfers between accounts | 5 | Planned |
| 7 | Budgets & periods | 4, 5 | Planned |
| 8 | Recurring transactions | 5 | Planned |
| 9 | File import (CSV / OFX / QIF) | 5 | Planned |
| 10 | Reporting & insights | 5, 7 | Planned |
| 11 | Mobile app (Flutter) | 2–7 | Planned |
| 12 | Bank connections (pluggable providers) | 9 | Planned |
| 13 | AI insights (opt-in, local-first) | 10 | Planned |

## Why this order

**Slice 1 now includes the Compose packaging.** Under ADR-0016 the deployable unit is a
`docker-compose.yml` plus published images; if that is bolted on at the end it will be bad, and
it is the first thing a self-hoster touches.

**Households land with auth in slice 2, not later.** The `household_id` is the ownership root
(ADR-0017) — retrofitting it would touch every financial table and every query in the app.

**File import (9) comes before bank connections (12).** It works for every bank in every country
with no credentials, no third party and no regulatory status. Connections are an enhancement on
top, and some users will never be able to obtain provider credentials at all (ADR-0020).

**Mobile (11) waits for a real API.** Building a client against endpoints that do not exist is
worse than not building it.

**AI is last (13)** because there is nothing to analyse until transactions and budgets exist, and
because it is the feature most able to damage trust if rushed.

## The old legacy-data question

Previously tracked as a roadmap slice. It is no longer product scope: Budget Owl is a product for
other people, not a migration of one person's 2021 Heroku data. If the owner wants their own old
records, that is a personal one-off import, and everything needed to write it is preserved in
`domain/legacy-app.md`.

Removed from the numbered roadmap rather than left dangling.
