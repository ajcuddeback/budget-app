import { test, expect } from '../helpers/ui-test.js';

/**
 * Negative control. A checker that never fails is worthless, so this proves each detector
 * actually fires: it loads a deliberately broken page and asserts the problems are FOUND.
 *
 * If this spec starts failing, the harness has gone blind — fix it before trusting a green run.
 */

const BROKEN = `data:text/html,${encodeURIComponent(`
<!doctype html><html><head><meta charset="utf-8"><title>Broken</title>
<style>
  body { margin: 0; font: 16px system-ui; }
  /* deliberately forces horizontal overflow */
  .wide { width: 3000px; height: 40px; background: #eee; }
  /* deliberately fails contrast */
  .faint { color: #d8d8d8; background: #ffffff; }
</style></head>
<body>
  <div class="wide"></div>
  <p class="faint">Low contrast text that axe should flag.</p>
  <img src="/does-not-exist-anywhere.png">
  <input type="text">
  <script>console.error('deliberate console error');</script>
</body></html>`)}`;

test('detectors fire on a deliberately broken page', async ({ page, ui }) => {
  await page.goto(BROKEN);
  await page.waitForTimeout(200);

  // a11y: missing alt text, unlabelled input, low contrast, missing lang
  const violations = await ui.a11y('broken fixture');
  expect(violations, 'axe should find violations on the broken page').toBeGreaterThan(0);

  // layout: 3000px element in a smaller viewport
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow, 'horizontal overflow should be detected').toBeGreaterThan(0);

  // console: the inline script logged an error
  expect(ui.consoleErrors.join(' '), 'console errors should be captured').toContain(
    'deliberate console error',
  );

  await ui.shot('broken-fixture');
});
