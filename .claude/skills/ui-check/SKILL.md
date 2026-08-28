---
name: ui-check
description: Validate the running UI — drive it with Playwright, capture screenshots, read them back, and check accessibility, console errors, and layout. Use after a frontend change, when asked if something looks right, or to check responsive/empty/error states. Invoked as /ui-check [feature].
---

# Check the UI live

Tests prove the logic works. They do not prove the page isn't visually broken. This closes that
gap. Full detail in `docs/guides/ui-validation.md`.

## 1. Run it

```bash
tools/ui-check.sh                    # app already serving on :4200
tools/ui-check.sh --serve            # start the dev server, check, stop it
tools/ui-check.sh --url http://...   # any running instance
tools/ui-check.sh --grep <feature>   # one feature
tools/ui-check.sh --selfcheck        # no app yet — proves the harness works
```

Nothing serving? Start the app. Don't report "cannot check" — that's a step, not a blocker.

## 2. Read the findings

`tools/ui/artifacts/REVIEW.md` — accessibility violations, console errors, failed requests,
layout overflow, deduplicated across viewports.

Critical and serious findings get fixed. This app handles financial data; an unlabelled input is
a real user locked out, not a lint nit.

## 3. Read the screenshots — the step that actually matters

The run prints absolute paths. **Open each with the Read tool** — they render as images.

Skipping this makes the whole run pointless. Automated checks confirm nothing *detectable* is
wrong; they say nothing about whether the thing is usable or looks like a finished product.

Judge each shot:
- Does the primary action stand out, or is everything the same weight?
- Is anything clipped, overlapping, misaligned, or cramped?
- Is the mobile view designed, or just narrow?
- Do empty and error states look intentional rather than broken?
- Are money amounts formatted and aligned so a column can be scanned?
- Does it match the feature doc?

## 4. Fix and re-run

Small, clearly-correct fixes: make them, run again, compare. Design-level problems: describe and
propose rather than guess.

## 5. Report honestly

Say what you ran, what you found, **what you saw**, and what you'd still change.

"Looks good" is not a report. If you didn't open the screenshots, say that instead of implying
you validated something.

## Never

Weaken a check to get green. Deleting an axe assertion doesn't fix an accessibility bug — it
hides one, in an app where being locked out means being locked out of your own finances.
