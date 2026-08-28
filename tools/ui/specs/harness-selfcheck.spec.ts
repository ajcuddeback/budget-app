import { test, expect } from '../helpers/ui-test.js';

/**
 * Proves the harness itself works — browser launches, screenshots land, axe runs,
 * console and layout checks fire — without needing the app to exist.
 *
 * Run with:  tools/ui-check.sh --selfcheck
 * Delete this only if the harness is ever removed.
 */

const FIXTURE = `data:text/html,${encodeURIComponent(`
<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Harness self-check</title>
<style>
  body { font: 16px system-ui; margin: 0; padding: 2rem; background: #fff; color: #111; }
  h1 { font-size: 1.5rem; }
  .card { border: 1px solid #ccc; border-radius: 8px; padding: 1rem; max-width: 40rem; }
</style></head>
<body>
  <h1>UI harness self-check</h1>
  <div class="card">
    <p>If you are reading this in a screenshot, the harness works.</p>
    <button type="button">A labelled button</button>
  </div>
</body></html>`)}`;

test('harness captures a screenshot and runs its checks', async ({ page, ui }) => {
  await page.goto(FIXTURE);

  await expect(page.getByRole('heading', { name: 'UI harness self-check' })).toBeVisible();

  const shot = await ui.shot('selfcheck');
  expect(shot).toContain('.png');

  const violations = await ui.a11y('selfcheck fixture');
  expect(violations, 'the self-check fixture is deliberately accessible').toBe(0);

  await ui.noOverflow();
  ui.noErrors();
});
