# ADR-0011: Playwright harness for agent-driven UI validation

- **Status:** Accepted
- **Date:** 2026-08-28
- **Deciders:** Repository owner

## Context

The rewrite is being built largely by AI agents, which cannot see the running application. Unit
and integration tests prove logic is correct; they say nothing about whether the page renders as
a usable interface. An agent can therefore produce a fully green build whose UI is visually
broken — overlapping elements, an unreadable mobile layout, an empty state that looks like a
crash — and report success in good faith.

The gap has two distinct halves, and conflating them is the usual mistake:

- **Mechanically detectable defects** — accessibility violations, console errors, failed
  requests, horizontal overflow. A machine finds these more reliably than a person.
- **Judgement** — visual hierarchy, spacing, whether a screen looks finished. No automated check
  finds these, but a model *can* assess them if it is given an image to look at.

Agents can read image files. That makes the second half tractable: capture screenshots, then read
them back.

## Decision

We maintain a self-contained UI validation harness at `tools/ui/`, driven by
`tools/ui-check.sh`, built on Playwright plus axe-core.

Each run:
1. drives the running app in Chromium at desktop (1440×900) and mobile (Pixel 7) viewports;
2. captures full-page screenshots at labelled points;
3. runs axe-core (WCAG 2.1 AA) and captures console errors, failed requests, and layout overflow;
4. writes `tools/ui/artifacts/REVIEW.md` and **prints the absolute path of every screenshot**.

Step 4 is the load-bearing part. Screenshots nobody opens validate nothing, so the run puts the
paths in the agent's transcript and the report instructs it to read each image.

Supporting pieces: a `ui-validator` agent, a `/ui-check` skill, and
`docs/guides/ui-validation.md`. A self-check (`--selfcheck`) proves the harness works with no app
present, and a negative-control spec proves each detector still fires against a deliberately
broken page.

The harness lives in `tools/`, not `frontend/e2e/`, because it is developer tooling that must
work before `frontend/` exists and can be pointed at any URL — including the legacy app, for
parity comparison.

## Alternatives considered

| Option | Why not |
|---|---|
| Unit and integration tests only | Cannot detect a visually broken page. This is precisely the gap |
| Playwright without screenshot review | Catches assertion failures but not "it renders, and it's ugly or unusable". Half the problem |
| Committed visual-regression baselines from the start | Valuable later; premature now. Baselines committed while the UI changes shape weekly are pure churn. Playwright's `toHaveScreenshot()` is available when the design settles |
| Cypress | Comparable; Playwright has better multi-viewport handling, a first-party axe integration, and is already present in the container |
| A human looks at it | Doesn't scale to agent-driven development, and is the bottleneck this exists to remove |

## Consequences

**Good:** an agent can genuinely see its own UI work and judge it. Accessibility is enforced
continuously rather than in a late audit, which matters for an app people use to manage money
under stress. Responsive bugs surface at every run, not at release.

**Bad / costs:** another Node toolchain and a browser to keep working, adding runtime to the
gate. Screenshot review costs tokens — a large run means many images. The pinned
`@playwright/test` version and the container's pre-baked Chromium revision can drift apart, so
the config resolves an executable explicitly rather than assuming Playwright's default path.
Most importantly, the harness **can** be defeated by an agent that runs it and skips reading the
images; the guide, skill, and agent prompt all state that reporting on unviewed screenshots is
worse than reporting that no check was done.

**Follow-ups:** add visual-regression baselines once the UI stabilizes. Add a saved
authentication storage state once login exists, so specs don't log in through the UI every time.
Consider Firefox and WebKit when cross-browser risk justifies the runtime.
