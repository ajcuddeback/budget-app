import { expect, test } from '../../helpers/doc-capture.js';
import { mockApi } from '../../helpers/mock-api.js';

/** Screens for the slice 4 guide: organise-your-categories. */

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await mockApi(page, { session: 'owner' });
});

test('@doc the categories screen, and adding one', async ({ page, doc }) => {
  doc.guide('organise-your-categories');

  await page.goto('/categories');
  await expect(page.getByRole('heading', { name: 'Categories', level: 1 })).toBeVisible();
  await doc.capture(
    'tree',
    'The Categories screen: Income and Spending groups, with sub-categories indented under their parent.',
    {
      fullPage: true,
      highlight: page.getByRole('button', { name: 'Add a category' }),
      step: 2,
    },
  );

  await page.getByRole('button', { name: 'Add a category' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('heading', { name: 'Add a category' })).toBeVisible();
  await dialog.getByLabel('Name').fill('Childcare');
  await doc.capture('add', 'The Add a category window with Childcare typed in the Name box.', { step: 3 });
});

test('@doc adding a sub-category and deleting a category', async ({ page, doc }) => {
  doc.guide('organise-your-categories');

  await page.goto('/categories');
  const categories = page.getByRole('region', { name: 'Your categories' });
  const bills = categories.locator('.branch').filter({ hasText: 'Bills' });

  await bills.getByRole('button', { name: 'Add a sub-category' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Name').fill('Water');
  await doc.capture(
    'sub-category',
    'The Add a category window opened from Bills, with Sits under already set to Bills and Kind greyed out.',
    { step: 7 },
  );
  await page.keyboard.press('Escape');

  await categories
    .getByRole('listitem')
    .filter({ hasText: 'Groceries' })
    .getByRole('button', { name: 'Delete' })
    .first()
    .click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await doc.capture(
    'delete',
    'The confirmation for deleting a category, saying your transactions are kept and will need a category again.',
    { step: 11 },
  );
});
