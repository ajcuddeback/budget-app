import { expect, test } from '../../helpers/doc-capture.js';
import { mockApi } from '../../helpers/mock-api.js';

/** Screens for the slice 6 guide: set-up-an-envelope and log-spend. */

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await mockApi(page, { session: 'owner' });
});

test('@doc the envelopes screen, and setting one up', async ({ page, doc }) => {
  doc.guide('set-up-an-envelope');

  await page.goto('/envelopes');
  await expect(page.getByRole('heading', { name: 'Envelopes', level: 1 })).toBeVisible();
  await doc.capture(
    'list',
    'The Envelopes screen for August 2026: each envelope leads with what is left, with the month and the totals above.',
    { fullPage: true, highlight: page.getByRole('button', { name: 'Add an envelope' }), step: 2 },
  );

  await page.getByRole('button', { name: 'Add an envelope' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Name').fill('Birthdays');
  await dialog.getByLabel('Amount').fill('60');
  await doc.capture('form', 'The Add an envelope window with a name, the month and an amount filled in.', {
    step: 3,
  });
});

test('@doc logging spend and reading the log', async ({ page, doc }) => {
  doc.guide('log-spend-in-an-envelope');

  await page.goto('/envelopes');
  const groceries = page.locator('.envelope').filter({ hasText: 'Groceries' });
  await doc.capture(
    'envelope',
    'The Groceries envelope showing $116.70 left, the bar part filled, and the Log spend button circled.',
    { highlight: groceries.getByRole('button', { name: 'Log spend' }), step: 2 },
  );

  await groceries.getByRole('button', { name: 'Log spend' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Amount').fill('18.40');
  await doc.capture('quick-add', 'The Log spend window with 18.40 typed into Amount and everything else empty.', {
    step: 3,
  });
  await page.keyboard.press('Escape');

  await groceries.getByRole('button', { name: /Show 3 entries/ }).click();
  await doc.capture(
    'log',
    'The Groceries log: three entries, each marked either Typed or From a transaction.',
    { fullPage: true, step: 7 },
  );
});

test('@doc an overspent envelope', async ({ page, doc }) => {
  doc.guide('log-spend-in-an-envelope');

  await page.goto('/envelopes');
  const overspent = page.locator('.envelope').filter({ hasText: 'Eating Out' });
  await doc.capture(
    'overspent',
    'The Eating Out envelope showing -$11.20 over, with its bar full and marked.',
    { step: 9 },
  );
  await expect(overspent).toBeVisible();
});
