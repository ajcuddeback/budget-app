---
name: user-guide
description: Write or update the customer-facing user guide in userguide/ — step-by-step how-to guides per feature and the end-to-end walkthrough — capturing screenshots from the running app. Use after a user-visible feature ships or when the guide goes stale. Invoked as /user-guide <feature>.
---

# Write the user guide

For **people using the app**, not developers. Read `userguide/STYLE.md` before writing a word —
this is a different craft from the docs in `docs/`.

## 1. Capture the screens

```bash
tools/userguide-capture.sh --serve       # start the app, capture, stop it
tools/userguide-capture.sh --url http:// # against a running instance
tools/userguide-capture.sh --selfcheck   # app not built — proves the pipeline works
```

Capture specs live in `tools/ui/specs/docs/`, tagged `@doc`. Write one per guide that walks the
journey a real user takes:

```ts
test('@doc add an account', async ({ page, doc }) => {
  doc.guide('add-an-account');
  await page.goto('/accounts');

  await doc.capture('accounts-empty', 'The Accounts screen before you add anything.');

  const add = page.getByRole('button', { name: 'Add account' });
  await doc.capture('click-add', 'Select Add account.', { highlight: add, step: 1 });
  await add.click();
  // ...
});
```

`highlight` draws a red ring, `step` puts a numbered badge on it, `focus` crops to one element.

## 2. Look at every screenshot

**Open each one with the Read tool.** They render as images.

This is the whole basis of the guide. Writing steps from a screenshot you didn't open is writing
fiction, and the reader finds out before you do.

## 3. Write from what you saw — not from the feature doc

Feature docs in `docs/features/` describe what we *meant* to build. Guides describe what
shipped. When they disagree, **the screen is right**: write what you see and report the
mismatch. You've found a bug, which is worth more than the paragraph you were writing.

Copy `userguide/_TEMPLATE.md` into `userguide/features/`, named for the task —
`add-an-account.md`, not `accounts.md`.

The rules people break most:
- One action per numbered step. "And then" means two steps.
- Say what happens after each action, so they know it worked.
- Button names exactly as the screen shows them, in **bold**.
- Never "simply", "just", "easy", "obviously".
- Alt text on every image.

## 4. Wire it in

- Add it to the table in `userguide/README.md`.
- Update `userguide/getting-started.md` if it's part of the first-run journey.

## 5. Check it

```bash
tools/userguide-check.sh
```

Missing images, orphans, broken links, and screenshots older than the UI they depict.

## Where the screenshots get their data

The `doc` fixture intercepts every `/api/**` call and answers it from
`tools/ui/fixtures/demo-data.ts`, so a capture run has no backend and no database behind it.
Captures also refuse any non-local target, enforced in the script with no override (ADR-0013).

If a guide needs a scenario the fixtures don't cover — an empty account list, an overspent
budget line — add it to `demo-data.ts` rather than reaching for a different data source.

## Report back

What you wrote, what you captured, **what you saw**, and anything where the UI disagreed with
the feature doc.

If a screen was hard to describe clearly, say so. Documentation that is hard to write is nearly
always documenting a confusing design, and you're the first to notice.
