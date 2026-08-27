# Decision Log

Smaller decisions that don't warrant a full ADR but shouldn't be silently re-litigated.
Newest first. Add with `/remember`.

If a decision has real reversal cost or a genuine rejected alternative, write an ADR instead
and link it from here.

---

### 2026-08-27 — Harness built before any rewrite code

Set up `CLAUDE.md`, `docs/`, agents, skills, and hooks **before** writing a line of Angular or
Spring. The reasoning: conventions established after the code exists have to fight the code.
Established first, they're free.

Cost: no running app yet. Accepted deliberately.

See ADR-0010.

---

### 2026-08-27 — Legacy app kept in-tree, marked read-only

`client/` and `server/` stay until feature parity, as a behavioral reference. To stop agents
reading or copying them, `CLAUDE.md` marks them read-only, a `PreToolUse` hook blocks writes to
them, and `docs/domain/legacy-app.md` documents their behavior so nobody needs to open them.

Alternative rejected: delete immediately. Loses the reference too early.

---

### 2026-08-27 — Four public endpoints, everything else denies by default

`POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`
are the only routes reachable without a session. Spring Security is configured deny-by-default
so a new controller is protected the moment it exists, without anyone remembering to add a rule.

Adding a fifth public endpoint is a reviewed decision, not a config tweak.

---

### 2026-08-27 — `NUMERIC(19,4)` rather than `(19,2)`

Four decimal places so proportional allocation, interest, and splits keep precision through
intermediate steps. Rounding to two happens once, explicitly, at the end.

See ADR-0006.

---
