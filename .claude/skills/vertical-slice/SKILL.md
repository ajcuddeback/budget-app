---
name: vertical-slice
description: Build one feature end to end — migration, backend, API, frontend, tests, docs — in the right order with the right specialists. Use when implementing a feature from the roadmap. Invoked as /vertical-slice <feature-name>.
---

# Build a vertical slice

A slice is database → API → UI → tests → docs for **one** feature: shippable on its own. Never
build horizontally ("all the entities first") — that produces a lot of untested code that
proves nothing works.

## 0. Orient

Read `CLAUDE.md`, the feature doc, and `docs/architecture/overview.md`.

**No feature doc? Run `/feature-doc` first and stop here.** Building without a spec produces
the wrong thing, confidently.

## 1. Threat-model it

Run `/threat-model` for the feature before writing code — cheaper than discovering the
authorization gap in review. Fold the result into the feature doc's security section.

## 2. Schema — `persistence` agent

Flyway migration, entities, repositories with owner-scoped finders, constraints, indexes.
The migration lands before the code that uses it.

## 3. Backend — `spring-api` agent

Domain, service (rules + transactions + authorization), controller, DTOs with validation, error
mapping. Feature-first package layout.

## 4. Tests — `test-author` agent

The mandatory set from `docs/guides/testing-style.md` — including the ADR-0008 test that user B
gets `404` on user A's resource — plus whatever this feature specifically needs. Run them.

## 5. Frontend — `angular-ui` agent

Lazy route, smart page, presentational components, typed service and forms. Amounts stay strings.

## 6. Look at it — `ui-validator` agent

```bash
tools/ui-check.sh --serve
```

Then **read the screenshots it prints**. Accessibility violations, console errors, and layout
overflow come back automatically; whether the screen actually looks finished is a judgement you
can only make by opening the images. A slice with green tests and an unusable mobile layout is
not done.

## 7. Document it for users — `user-docs` agent

If the slice is user-visible, it ships with its guide:

```bash
tools/userguide-capture.sh --serve
```

Read the screenshots, then write the guide in `userguide/` **from what you saw** — never from
the feature doc. Writing it is also a second review pass: a step that's hard to describe
clearly is usually a design problem worth fixing now.

## 8. Security review — `security-auditor` agent

Run it on the whole slice, including work you did yourself. Fix everything Critical and High
before proceeding; record any accepted Medium in the feature doc with the reasoning.

## 9. Verify

```bash
tools/verify.sh
```

Actually run it. If it fails, fix it — or say plainly what's broken and why. Never report a
slice as done on an assumed-green build.

## 10. Close the loop

- Update the feature doc: status, and any behavior that changed during implementation.
- `/adr` for structural decisions made along the way; `/remember` for smaller ones and for
  anything that surprised you.
- Update the status in `docs/roadmap.md`.
- Commit with a message referencing the feature doc.

## Order matters

Don't skip ahead to the UI because it's more visible. A slice with a beautiful frontend and an
unscoped query is a data breach with good typography.
