# Legacy App Behavior (historical record)

The original MERN-ish app that this project replaced. Its code lived in `client/` and `server/`
and was **deleted** in the clean-slate rewrite (ADR-0015).

**This document is now the only record of it.** It was written while the code was still present,
specifically so that nobody would need to read it — and that turned out to be the thing that
made deleting the code safe. Keep it accurate; the source is no longer there to check against.

If you ever do need the original source, it is in git history (`git show bd6b875:server/server.js`),
but prefer this document.

## What it did

A single-user-per-account monthly budget tracker. You logged in, picked a month, and added
named "bills" (expenses) and "income" rows. It showed the leftover: income minus bills. You
could mark a bill paid. Months were navigable forward and back, so you could pre-load future
months.

## Its data model

Three tables, `underscored`, no timestamps:

- **`user`** — `id`, `username` (unique, min length 5), `first_name`, `last_name`,
  `password` (bcrypt, cost 10, min length 5).
- **`bills`** — `id`, `name`, `amount` (DECIMAL), `is_payed` (bool, default false),
  `month` (**string**), `year` (**string**), `user_id` → `user.id`.
- **`income`** — `id`, `name`, `amount` (DECIMAL), `month` (string), `year` (string),
  `user_id` → `user.id`.

## Its API

`GET /api/user/bill/:month/:year`, `GET /api/user/income/:month/:year`, `POST /api/user`
(register), `POST /api/user/login`, `GET /api/user/auth`, `POST /api/user/logout`, plus
CRUD under `/api/bills` and `/api/income`.

## What it got wrong — do not reproduce any of this

These are the concrete reasons for the rewrite. Each maps to a rule in the new system.

1. **Month and year stored as strings.** No ordering, no range queries, no date arithmetic,
   locale-dependent parsing. → New model uses real dates and a typed `YearMonth` period.
2. **`GET /api/user` returned every user in the database**, with their bills and income, to
   any caller. Unauthenticated. This is a full data breach as a feature.
   → ADR-0008: every query scoped to the authenticated user, no exceptions.
3. **User enumeration on login**: distinct messages for "no user found at this username" vs
   "your password is not correct". → Identical response for both.
4. **Weak credential policy**: 5-character minimum password, bcrypt cost 10.
   → 12 characters minimum, BCrypt strength ≥ 12, breached-password check.
5. **`cookie: { httpOnly: false }`** on the session — the session cookie was readable by any
   script on the page. → `HttpOnly`, `Secure`, `SameSite=Lax`.
6. **No CSRF protection at all**, with cookie-based auth. → CSRF tokens mandatory.
7. **`beforeUpdate` re-hashed the password on every user update**, so any profile edit
   corrupted the credential. → Password changes are their own explicit operation.
8. **`sequelize.sync()` managed the schema.** → Flyway migrations, append-only (ADR-0007).
9. **Raw errors returned to the client** (`res.status(500).json(err)`), leaking driver and
   query internals. → RFC 7807 problem responses with a correlation id.
10. **No tests.** → Tests ship with the change.

## Data migration — open question

Deleting the code did not decide what happens to any **data** that still exists in a deployed
instance. Those are separate questions and only the first is settled.

If a migration is wanted, everything needed to write one is above: the three tables, their
columns, and the traps (month/year stored as strings, so they need parsing into real dates;
ownership re-derived from `user_id`). Password hashes are BCrypt and can be carried over —
`DelegatingPasswordEncoder` upgrades the cost on next successful login — **except** for accounts
hit by the `beforeUpdate` re-hashing bug (see `docs/memory/gotchas.md`), which are unrecoverable
and need a password reset.

The original deployment was a 2021 Heroku app, so there may well be nothing left to migrate.
**Confirm before planning this work** — see `docs/roadmap.md`.
