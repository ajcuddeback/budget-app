# Roadmap

Order matters: each slice depends on the ones above it. Every slice is **vertical** — database
through API through UI, tested and shippable — never a horizontal "build all the entities" phase.

Use `/vertical-slice` to work one.

**The designs lead.** Slices below are derived from `design/` — the canvas is the statement of
what the product is, and this order is how it gets built. Where a slice has no feature doc yet,
write one from the designs first (`/feature-doc`), not from imagination.

| # | Slice | Depends on | Status |
|---|---|---|---|
| 0 | AI harness, docs, style guides | — | **Done** |
| 0b | UI validation harness (Playwright + axe) | 0 | **Done** |
| 0c | User-guide capture + `user-docs` agent | 0b | **Done** |
| 0d | Product direction: self-hosted, households, mobile (ADR-0016–0021) | 0 | **Done** |
| 0e | Designs imported; docs reconciled to them | 0d | **Done** |
| 1 | Backend + frontend skeletons, design tokens, Compose packaging, CI proven | 0e | Next |
| 2 | [Authentication & households](features/authentication-and-households.md) | 1 | Planned |
| 3 | Accounts (money containers) | 2 | Planned |
| 4 | Categories + defaults on household creation | 2 | Planned |
| 5 | Transactions (create, list, edit, delete) | 3, 4 | Planned |
| 6 | **Envelopes** — set an amount, log against it, fully unsynced | 4, 5 | Planned |
| 7 | **Bills & Income** — the scheduled page, incl. charge mapping | 5 | Planned |
| 8 | Transfers between accounts | 5 | Planned |
| 9 | **Admin console** — the instance the operator runs | 2 | Planned |
| 10 | **Debt plan & debt detail** — terms solver, payments, strategies | 7 | Planned |
| 11 | Goals | 5 | Planned |
| 12 | File import (CSV / OFX / QIF) | 5 | Planned |
| 13 | Reporting & insights | 5, 7 | Planned |
| 14 | **Weekly check-in** — the four-step pass | 6, 7, 10 | Planned |
| 15 | Mobile app (Flutter) | 2–10 | Planned |
| 16 | Bank connections (pluggable providers) | 12 | Planned |
| 17 | AI insights / Owl chat (opt-in, local-first) | 13 | Planned |

## Why this order

**Envelopes (6) come before bills (7), and both come before everything clever.** Envelopes are
the thing a spreadsheet genuinely cannot do — a set amount with a remaining figure that updates
as you log against it, instead of a column you total at month end. They need nothing connected,
which makes them the first slice that is useful to somebody on day one.

**The admin console (9) is a product surface, not a chore.** Budget Owl is software people run
themselves; the person running it needs to see whether it is healthy, invite their household,
take a backup and restore it. It sits after auth because it administers users, and early because
an instance nobody can operate is not self-hostable. It also carries the first-run
acknowledgement that gates account connections (ADR-0020) — so connections (16) cannot ship
before it.

**The weekly check-in (14) is late on purpose.** It is a pass over everything else — income,
categorization, bills, allocation — so every one of those has to exist and be trustworthy first.
Built early it would be a wizard over empty tables.

**Slice 1 now includes the Compose packaging.** Under ADR-0016 the deployable unit is a
`docker-compose.yml` plus published images; if that is bolted on at the end it will be bad, and
it is the first thing a self-hoster touches.

**Households land with auth in slice 2, not later.** The `household_id` is the ownership root
(ADR-0017) — retrofitting it would touch every financial table and every query in the app.

**File import (12) comes before bank connections (16).** It works for every bank in every country
with no credentials, no third party and no regulatory status. Connections are an enhancement on
top, and some users will never be able to obtain provider credentials at all (ADR-0020).

**Mobile (15) waits for a real API.** Building a client against endpoints that do not exist is
worse than not building it. Note that the designs treat the phone as *the primary surface*, not a
companion — every screen is drawn at 390 and at desktop width. Late in the order is a sequencing
call about dependencies, not a statement that mobile matters less.

**Internationalisation is not a slice.** It is a constraint on every slice from 1 onwards
(ADR-0023): strings come from translation files, amounts and dates are never hand-formatted, and
layouts are RTL-safe. Retrofitting it after fifty screens exist is a rewrite; honouring it from
the first component is nearly free. The same applies to currency (ADR-0022) — totals are
currency-aware from the first sum, not after someone adds a second account.

**AI is last (17)** because there is nothing to analyse until transactions and budgets exist, and
because it is the feature most able to damage trust if rushed.

## The old legacy-data question

Previously tracked as a roadmap slice. It is no longer product scope: Budget Owl is a product for
other people, not a migration of one person's 2021 Heroku data. If the owner wants their own old
records, that is a personal one-off import, and everything needed to write it is preserved in
`domain/legacy-app.md`.

Removed from the numbered roadmap rather than left dangling.
