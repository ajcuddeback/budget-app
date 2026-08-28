# User Documentation Workflow

How the customer-facing guide in `userguide/` gets written and kept true.

**Audience discipline first.** `docs/` is for developers and agents. `userguide/` is for people
using the app. Never mix them — a user guide with a word like "entity" in it has already lost
its reader, and a feature doc written in marketing prose is useless as a spec.

| | `docs/` | `userguide/` |
|---|---|---|
| Reader | developers, agents | people using the app |
| Answers | why is it built this way | how do I do this thing |
| Written by | `docs-curator` | `user-docs` |
| Source of truth | the decision, the spec | **the running app** |
| Screenshots | none | every guide |

## The workflow

1. **Write a capture spec** in `tools/ui/specs/docs/`, tagged `@doc`. It walks the journey a
   real user takes and captures at each point they need to see where they are.
2. **Capture**: `tools/userguide-capture.sh --serve`
3. **Read every screenshot** with the Read tool.
4. **Write the guide** from `userguide/_TEMPLATE.md`, following `userguide/STYLE.md`.
5. **Index it** in `userguide/README.md`.
6. **Check**: `tools/userguide-check.sh`

## Write from the app, never from the feature doc

The single rule that decides whether these guides are worth anything.

A developer feature doc says what we intended. The app is what shipped. They drift — a field
gets renamed, a step gets merged into another, a button moves. A guide written from intent then
tells the reader to click something that is not there. They hunt for it, fail, and conclude they
broke the app. That is worse than having no guide, because it destroys trust in the rest of it.

So the order is: capture, look, write. When the screen disagrees with the feature doc, **the
screen wins** — write what you see, and report the mismatch as a bug.

## Staleness is the real enemy

User docs do not fail loudly. They quietly start lying.

`tools/userguide-check.sh` catches this mechanically: every screenshot records the commit and
date it was taken, and the check warns when one predates the last change to `frontend/`. It also
finds missing images, orphans, and broken links.

Warnings are not build failures, but a stale screenshot is exactly how a guide starts lying.
Re-capture with `tools/userguide-capture.sh --serve`.

## Guides ship with the feature

A user-visible slice is not done until its guide exists. Same rule as tests and the feature doc:
written in the same change, not in a documentation sprint later. Documentation debt is repaid at
a terrible exchange rate — nobody remembers the edge cases three weeks on.

## Privacy

Screenshots of a budgeting app show financial data, and `userguide/images/` is **committed**.

- Demo/seeded data only. Never a real user's records, never your own.
- No real names, real balances, or real account numbers.
- Check the image before committing it — including the parts of the screen you weren't
  thinking about, like a sidebar total or a notification.

This is the one rule here that causes real harm if broken.

## Capture helper

```ts
doc.guide('add-an-account');            // filename prefix for this guide

doc.capture('name', 'caption');                          // viewport screenshot
doc.capture('name', 'caption', { highlight: locator,     // red ring
                                 step: 1 });             // numbered badge
doc.capture('name', 'caption', { focus: locator });      // crop to one element
doc.capture('name', 'caption', { fullPage: true });      // whole scrollable page
```

Desktop-only (1280×720) and single-project, so a guide shows one consistent view. Every capture
is stamped into `userguide/images/manifest.json`.
