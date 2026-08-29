# Decision Log

Smaller decisions that don't warrant a full ADR but shouldn't be silently re-litigated.
Newest first. Add with `/remember`.

If a decision has real reversal cost or a genuine rejected alternative, write an ADR instead
and link it from here.

---

### 2026-08-29 — Deleted the legacy app; the doc is what made it safe

Removed `client/`, `server/`, `images/` and the root MERN `package.json`. Clean slate, no parity
obligation (ADR-0015).

Worth noting *why* this was low-risk: `docs/domain/legacy-app.md` was written a few days earlier
specifically so nobody would have to read the old code. That document turned out to be the thing
that made deleting the code safe — the reference had already been extracted, so the source was
redundant.

Generalises: writing down what a system does is also what lets you delete it.

---

### 2026-08-29 — CI's first real run failed, and the harness could not have caught it

`tools/verify.sh` passes locally and skips gracefully when `backend/` is absent. The CI job still
died in seven seconds, because `actions/setup-java` with `cache: maven` errors outright when no
`pom.xml` exists — it failed *before* the gate ran.

The lesson is about scope: a local gate proves the repository is healthy, not that the workflow
wrapping it is. Workflow YAML only gets tested by running on the real runner, so treat the first
push of any new workflow as unverified regardless of how green things look locally.

CodeQL had the same shape — `java-kotlin` autobuild fails on a repository with no Java source, so
that language is added when `backend/` lands rather than sitting permanently red. A check that is
always red teaches people to ignore CI.

---

### 2026-08-28 — Two sources of guidance need a stated precedence and an enumerated exception list

Adopting Angular's official skills alongside our own style guide created the exact risk the
harness exists to remove: two documents telling an agent different things.

Resolved not by picking one, but by scoping them — the skill owns *framework* questions, our
guide owns *project* questions — plus an **override table** listing every point where we
deliberately differ.

The table is the important half. "Our guide wins" alone is unusable: an agent cannot tell which
of a hundred statements is a deliberate override and which is our guide simply being out of
date. Enumerating the exceptions makes them countable, reviewable, and removable when upstream
catches up.

Generalises to any vendored guidance: state the precedence, then list the exceptions.

See ADR-0014.

---

### 2026-08-28 — Switched Angular tests from Jest to Vitest

Angular's skill assumes Vitest, which is now Angular's default runner; Jest needs
`jest-preset-angular` and is drifting toward legacy. Changed while it costs nothing — no
frontend code exists yet.

An example of adopting upstream guidance actually changing our mind rather than being overridden.

---

### 2026-08-28 — Prefer a mechanism over a warning, and notice when you've written a warning

The first version of the capture pipeline handled the "don't screenshot real data" risk with
four copies of a warning and an unrestricted `--url` flag. It was corrected to fixtures plus a
local-only guard with no override (ADR-0013).

The general lesson, worth applying beyond this case: **when you find yourself writing the same
caution in several files, that is a signal you are missing a control.** Repetition is what
people reach for when the mechanism isn't there. The fix is usually not a better-worded warning.

Related: the underlying gap was a missing capability. There were no fixtures, so a populated
screenshot required a real instance, so a warning was needed to compensate. Warnings often mark
the spot where something wasn't built.

---

### 2026-08-28 — User guide is a separate tree with a separate agent

`userguide/` sits beside `docs/`, not inside it, and is written by `user-docs` rather than
`docs-curator`. Different reader, different vocabulary, different source of truth: developer docs
come from decisions and specs, the user guide comes from the running app.

Keeping them apart also protects the routing in `CLAUDE.md` — an agent looking for a spec would
otherwise land in a how-to guide.

See ADR-0012.

---

### 2026-08-28 — Self-check output never lands in committed directories

The user-guide capture self-check originally wrote its proof-of-pipeline screenshots straight
into `userguide/images/`, where they showed up as permanent orphans and would have been
committed as repo bloat.

Self-checks now write to `tools/ui/artifacts/`. General rule: a self-check proves machinery
works; its output is never content, and it should never touch a directory that gets committed.

---

### 2026-08-28 — UI harness lives in tools/, not frontend/e2e/

Put the Playwright harness in `tools/ui/` rather than the conventional Angular `frontend/e2e/`.

Reasons: it must work before `frontend/` exists (it does — `--selfcheck` passes today); it is
developer tooling rather than app code; and it can be pointed at any URL, including the legacy
app, which makes parity comparison possible during the rewrite.

Cost: diverges from Angular convention, so people may look for it in the wrong place.
Mitigated by pointing at it from `CLAUDE.md`, `tools/README.md`, and the roadmap.

See ADR-0011.

---

### 2026-08-28 — Screenshot review is a required step, not an optional one

The harness prints absolute screenshot paths and the report tells the agent to open each with
the Read tool. The `ui-validator` agent, the `/ui-check` skill, and the guide all state that
reporting on the UI without having read the screenshots is worse than reporting no check.

This is a soft control — an agent *can* skip it. It's stated in three places because that is the
only enforcement available: the value of the harness is entirely in step 3, and a run that stops
at "tests passed" recreates the exact blind spot it exists to remove.

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
