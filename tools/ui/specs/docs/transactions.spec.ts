import { expect, test } from '../../helpers/doc-capture.js';
import { mockApi } from '../../helpers/mock-api.js';

/**
 * Screens for the slice 5 guide: record-a-transaction.
 *
 * Everything renders from tools/ui/fixtures/demo-data.ts (ADR-0013) — no backend, no database,
 * no real money anywhere near a screenshot.
 */

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await mockApi(page, { session: 'owner' });
});

test('@doc the ledger, and recording a transaction', async ({ page, doc }) => {
  doc.guide('record-a-transaction');

  await page.goto('/transactions');
  await expect(page.getByRole('heading', { name: 'Transactions', level: 1 })).toBeVisible();
  await doc.capture(
    'ledger',
    'The Transactions screen: what has happened to your money, newest first, with the Record a transaction button circled.',
    {
      fullPage: true,
      highlight: page.getByRole('button', { name: 'Record a transaction' }),
      step: 2,
    },
  );

  await page.getByRole('button', { name: 'Record a transaction' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('heading', { name: 'Record a transaction' })).toBeVisible();
  await doc.capture('form', 'The Record a transaction window as it opens, with today\'s date already filled in and Money out chosen.', {
    step: 3,
  });

  await dialog.getByLabel('Amount').fill('18.40');
  await dialog.getByLabel('Who it was with').fill('Green');
  await doc.capture(
    'payee',
    'The Who it was with box with "Green" typed, showing Greenfield Market suggested underneath.',
    { step: 6 },
  );

  await dialog.getByLabel('Who it was with').fill('Greenfield Market');
  await dialog.getByLabel('Category').selectOption({ label: 'Groceries' });
  await doc.capture('filled', 'The filled-in window: 18.40 out of Everyday Checking, Greenfield Market, Groceries.', {
    step: 7,
  });
});

test('@doc the work queue for transactions that need a category', async ({ page, doc }) => {
  doc.guide('give-a-transaction-a-category');

  await page.goto('/transactions');
  await expect(page.getByRole('heading', { name: 'Transactions', level: 1 })).toBeVisible();
  await doc.capture(
    'needs-a-category',
    'A transaction in the list marked "Needs a category", with the Needs a category filter button circled.',
    {
      fullPage: true,
      highlight: page.getByRole('button', { name: 'Needs a category' }),
      step: 2,
    },
  );

  await page.getByRole('button', { name: 'Needs a category' }).click();
  await expect(page.locator('.ledger').getByRole('listitem')).toHaveCount(1);
  await doc.capture('filtered', 'The list narrowed to just the transactions that still need a category.', {
    fullPage: true,
    step: 3,
  });

  await page.locator('.ledger').getByRole('button', { name: 'Add a category' }).first().click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await doc.capture('choose', 'The Edit transaction window with the Category list open.', { step: 4 });
});
