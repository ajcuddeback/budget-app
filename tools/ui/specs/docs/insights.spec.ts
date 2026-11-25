import type { Page } from '@playwright/test';
import { expect, test } from '../../helpers/doc-capture.js';
import { mockApi } from '../../helpers/mock-api.js';

/**
 * Screens for the slice 13 guide: see-where-your-money-went.
 *
 * Every figure comes from the GENERATED report fixtures (`DemoFixtureIT`): the real application's
 * sums over the demo household's twelve months, with the browser's clock at the demo instant, 20
 * August 2026, because the page builds its ranges from the device's own date.
 */

const DEMO_NOW = new Date('2026-08-20T12:00:00Z');

const incomeCard = (page: Page) => page.locator('section[aria-labelledby="insights-income-heading"]');
const whereCard = (page: Page) => page.locator('section[aria-labelledby="insights-where-heading"]');

async function open(page: Page): Promise<void> {
  await page.clock.setFixedTime(DEMO_NOW);
  await page.goto('/insights');
  await expect(page.getByRole('heading', { name: 'Insights', level: 1 })).toBeVisible();
}

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await mockApi(page, { session: 'owner' });
});

test('@doc insights, end to end', async ({ page, doc }) => {
  doc.guide('see-where-your-money-went');
  await open(page);

  await doc.capture(
    'page',
    'The Insights page: the Income vs spending card on the left and the Where July 2026 went card on the right.',
    { fullPage: true },
  );

  await doc.capture(
    'income',
    'The Income vs spending card: 12 mo, 6 mo and 3 mo buttons, the average kept and best month, a solid income line and a dashed spending line from Sep 2025 to Aug 2026, and the line saying one transaction needs a category.',
    { focus: incomeCard(page) },
  );

  await doc.capture(
    'range',
    'The 12 mo, 6 mo and 3 mo buttons, with 12 mo selected.',
    { highlight: page.getByRole('group', { name: 'Range' }), step: 1 },
  );

  await page.getByText('Show the figures').click();
  await doc.capture(
    'figures',
    'Show the figures opened: a table with one row per month, from September 2025 to August 2026 (so far), giving income, spending, needs a category in and out, and kept.',
    { focus: incomeCard(page) },
  );
});

test('@doc where a month went', async ({ page, doc }) => {
  doc.guide('see-where-your-money-went');
  await open(page);

  await doc.capture(
    'where',
    'The Where July 2026 went card: a bar split by category, $1,943.10 spent against $2,271.20 in June 2026, and a row for each category with its amount and how it moved.',
    { focus: whereCard(page) },
  );

  await page.getByRole('button', { name: "Show what's in Bills" }).click();
  await doc.capture(
    'children',
    'Bills opened to show Rent at $1,450.00, flat, and Utilities at $101.30, up 14%.',
    { highlight: page.getByRole('button', { name: "Hide what's in Bills" }), step: 2 },
  );

  await page.getByRole('button', { name: 'Next month' }).click();
  await expect(page.getByRole('heading', { name: 'Where August 2026 has gone so far' })).toBeVisible();
  await doc.capture(
    'so-far',
    'Where August 2026 has gone so far, with the Next month button greyed out and a line saying $92.15 across 1 transaction needs a category.',
    { focus: whereCard(page) },
  );
});

test('@doc insights with nothing recorded', async ({ page, doc }) => {
  doc.guide('see-where-your-money-went');
  await page.unrouteAll({ behavior: 'ignoreErrors' });
  await mockApi(page, { session: 'owner', reports: 'empty' });
  await open(page);

  await doc.capture(
    'empty',
    'The Income vs spending card saying nothing was recorded between September 2025 and August 2026, that amounts logged straight into an envelope do not appear here, and a Record a transaction link.',
    { focus: incomeCard(page) },
  );
});

test('@doc insights with two currencies', async ({ page, doc }) => {
  doc.guide('see-where-your-money-went');
  await page.unrouteAll({ behavior: 'ignoreErrors' });
  await mockApi(page, { session: 'owner', reports: 'two-currencies' });
  await open(page);

  await doc.capture(
    'currency',
    'The Currency buttons, USD and EUR, above the two cards, with the note that each currency is reported on its own.',
    { highlight: page.getByRole('group', { name: 'Currency' }), step: 1 },
  );
});
