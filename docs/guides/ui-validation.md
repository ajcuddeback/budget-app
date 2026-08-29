# UI Validation Guide

How to check that the UI actually works and actually looks right — as an agent, without a human
watching the screen.

Tests prove logic. They do not prove the page isn't a stack of overlapping divs. This closes
that gap.

## The tool

```bash
tools/ui-check.sh --selfcheck        # prove the harness works — no app needed
tools/ui-check.sh                    # against http://localhost:4200
tools/ui-check.sh --serve            # start the dev server, check, stop it
tools/ui-check.sh --url http://...   # against any running instance
tools/ui-check.sh --grep login       # one feature
```

Every run produces `tools/ui/artifacts/REVIEW.md` and a set of PNGs.

## The two halves, and why you need both

**What the machine checks** — reliably, every time, better than a person:

| Check | Catches |
|---|---|
| axe-core (WCAG 2.1 AA) | missing labels, bad contrast, no alt text, broken heading order |
| console capture | JS errors and uncaught exceptions, including during initial load |
| network capture | failed requests, 5xx responses, broken image and asset URLs |
| layout | horizontal overflow — the classic mobile bug |

**What only eyes catch** — and this is why the harness hands you screenshots:

Visual hierarchy. Spacing that's technically valid but ugly. A mobile view that's really a
squashed desktop view. An empty state that looks like a crash. Money columns that don't align
so a user can't scan them. Nothing automated finds these.

## The workflow — this is the part that matters

1. Run `tools/ui-check.sh`.
2. **Read `REVIEW.md`.** Fix everything in the findings table. Critical and serious are not
   negotiable in an app handling financial data.
3. **Open each screenshot with the Read tool.** They render as images — you can genuinely see
   them. The run prints absolute paths for exactly this reason.
4. Judge each one against the prompts in the report. Say what's wrong specifically:
   "the Save button has no visual weight against Cancel", not "looks fine".
5. Fix, re-run, compare.

**Step 3 is the whole point.** A run that captures perfect screenshots nobody opens has
validated nothing. A green result means *nothing mechanically detectable is wrong* — it is not
a statement that the UI is good.

## Writing specs

Start from `tools/ui/specs/_TEMPLATE.spec.ts.example`. One file per feature.

```ts
test('a user can add an account', async ({ page, ui }) => {
  await page.goto('/accounts');
  await ui.shot('accounts-empty');          // capture for review

  await page.getByRole('button', { name: 'Add account' }).click();
  await page.getByLabel('Name').fill('Checking');
  await page.getByRole('button', { name: 'Save' }).click();

  await expect(page.getByText('Checking')).toBeVisible();
  await ui.shot('accounts-after-add');

  expect(await ui.a11y()).toBe(0);          // axe
  await ui.noOverflow();                    // layout
  ui.noErrors();                            // console + network
});
```

**Query by role, label, and text.** Never by CSS class. A spec coupled to styling breaks on
every redesign, and a suite that cries wolf gets ignored — which is worse than no suite.

**Screenshot the states nobody looks at**: empty, loading, error, and very long content. That's
where UIs are actually broken.

## Viewports

Every spec runs at desktop (1440×900) and mobile (Pixel 7). Findings are deduplicated across
viewports and the report says which ones each was seen at, so a mobile-only bug is obvious.

## What this does not do yet

- **Visual regression.** Playwright's `toHaveScreenshot()` can pixel-diff against committed
  baselines. Worth adding once the UI stops changing shape every week — baselines committed too
  early are pure churn.
- **Cross-browser.** Chromium only. Add Firefox and WebKit projects when it's worth the runtime.
- **Real authentication flows.** Once login exists, use a saved storage state so specs don't log
  in through the UI every time.

## Security note

Screenshots of a budgeting app contain financial data. Use seeded test data, never a real user's
records. `tools/ui/artifacts/` is gitignored — keep it that way. Never attach a screenshot taken
against real data to an issue or PR.
