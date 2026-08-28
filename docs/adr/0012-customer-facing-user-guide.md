# ADR-0012: Customer-facing user guide, captured from the running app

- **Status:** Accepted
- **Date:** 2026-08-28
- **Deciders:** Repository owner

## Context

`docs/` serves developers and AI agents: architecture, decisions, specs, conventions. It is
deliberately written in our language, about how the system is built.

None of it helps someone trying to use the app. That reader has never seen the product, does not
know the domain vocabulary, and wants to know which button to press — often while stressed about
money, which is when documentation matters most and patience is shortest.

Two things make this hard to do well:

- **Audience drift.** Documentation written by the people who built a thing defaults to their
  vocabulary. A guide that says "create a transaction entity" has lost its reader.
- **Silent rot.** User docs do not fail a build when they go wrong. A screenshot of a button
  that has since moved sends a reader hunting for something that is not there, and they conclude
  they broke the app. That is worse than no guide, because it undermines the parts that are
  still correct.

We already have a Playwright harness (ADR-0011) that drives the running app and captures
screenshots an agent can read back. That is most of the machinery a user guide needs.

## Decision

We maintain a customer-facing guide in **`userguide/`**, separate from `docs/`, written by a
dedicated `user-docs` agent from screenshots of the **running app**.

- `userguide/getting-started.md` — the end-to-end first-run walkthrough.
- `userguide/features/<task>.md` — one guide per task, named as a user would name it.
- `userguide/STYLE.md` — how to write for this reader.
- `userguide/images/` — screenshots, **committed**, each stamped in `manifest.json` with the
  commit and date it was captured.

Captured by `tools/userguide-capture.sh`, which extends the ADR-0011 harness with a `doc`
fixture supporting element highlighting, numbered step badges, and element-scoped crops.
Checked by `tools/userguide-check.sh`: missing images, orphans, broken links, and screenshots
older than the last change to `frontend/`.

**The governing rule: guides are written from the app, never from the feature doc.** Feature docs
state intent; the app is what shipped. Where they disagree, the app wins and the mismatch is
reported as a bug.

## Alternatives considered

| Option | Why not |
|---|---|
| Extend `docs-curator` to write user docs too | Different reader, different vocabulary, different source of truth. One agent optimizing for both produces developer prose with screenshots in it |
| Put user docs in `docs/user/` | Muddies the routing `CLAUDE.md` establishes — an agent looking for the spec would find the user guide. Separation is the point |
| A hosted docs site (Docusaurus, GitBook) | Better reading experience eventually, but drifts from the code and cannot be updated in the same PR as the change. Markdown in-repo keeps the ship-together discipline. Publishing from `userguide/` stays possible later |
| Hand-written screenshots | Go stale invisibly, cannot be regenerated, and tempt people to mock up a screen that does not exist |
| No user docs until the app is finished | Guarantees they are written from memory about work done months earlier, which is how edge cases get lost |

## Consequences

**Good:** the guide is grounded in what actually renders, and regenerating screenshots is one
command. Writing a guide is a genuine second review pass — a step that is hard to describe is
usually a design problem, and the `user-docs` agent is positioned to notice. Staleness is
detected mechanically rather than by someone eventually noticing.

**Bad / costs:** committed PNGs grow the repository. Every user-visible change now has a
documentation obligation, which is real work. Capture specs are a second set of browser
automation to maintain alongside the validation specs, and they break for the same reasons.

**Privacy risk, called out explicitly:** these screenshots are committed and show a budgeting
app. A capture run against real data would commit someone's financial records to a repository.
The capture must run against seeded demo data — stated in `STYLE.md`, the agent prompt, the
skill, and the workflow guide, because it is the one rule here that causes real harm.

**Follow-ups:** write each guide as its slice ships, per `docs/roadmap.md`. Consider publishing
`userguide/` as a static site once the content justifies it. Consider committing a seeded demo
dataset so captures are reproducible run to run.
