import { mockApi } from '../../helpers/mock-api.js';
import { expect, test } from '../../helpers/ui-test.js';

/**
 * The categories screen: `docs/features/categories.md`, UI section.
 *
 * Three of these assertions are the feature's rules rather than UI taste:
 *
 * - **Two levels, shown as two levels.** The tree arrives flat and the screen nests it; a redesign
 *   that flattened it would lose the only thing that distinguishes `Bills → Utilities` from a
 *   category called "Utilities".
 * - **Deleting a category does not delete money**, and the confirmation says so. That is the thing
 *   somebody is actually afraid of, so it is answered before they commit rather than after.
 * - **A kind cannot be changed on an existing category**, and the form says why rather than hiding
 *   the field.
 */

test.describe('categories, as an owner', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'owner' });
  });

  test('shows the tree as two levels, income apart from spending', async ({ page, ui }) => {
    await page.goto('/categories');

    await expect(page.getByRole('heading', { name: 'Categories', level: 1 })).toBeVisible();
    const categories = page.getByRole('region', { name: 'Your categories' });

    await expect(categories.getByRole('heading', { name: 'Income', level: 3 })).toBeVisible();
    await expect(categories.getByRole('heading', { name: 'Spending', level: 3 })).toBeVisible();
    // Utilities is a child of Bills, and is rendered inside it rather than beside it.
    await expect(categories.locator('.children').getByText('Utilities')).toBeVisible();
    await expect(categories.getByText('Groceries')).toBeVisible();

    await ui.shot('categories-tree');
    expect(await ui.a11y('categories, populated tree')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('keeps archived categories out of the way until they are asked for', async ({ page, ui }) => {
    await page.goto('/categories');

    await expect(page.getByText('Wedding')).toHaveCount(0);

    await page.getByRole('button', { name: 'Show 1 archived' }).click();
    await expect(page.getByText('Wedding')).toBeVisible();

    await ui.shot('categories-archived');
    ui.noErrors();
  });

  test('promises that transactions survive a deleted category', async ({ page, ui }) => {
    await page.goto('/categories');

    const categories = page.getByRole('region', { name: 'Your categories' });
    await categories
      .getByRole('listitem')
      .filter({ hasText: 'Groceries' })
      .getByRole('button', { name: 'Delete' })
      .first()
      .click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText(/Your transactions are kept/)).toBeVisible();

    await ui.shot('categories-delete-confirm');
    expect(await ui.a11y('category delete confirmation')).toBe(0);
    ui.noErrors();
  });

  test('warns that sub-categories go too when the parent is deleted', async ({ page }) => {
    await page.goto('/categories');

    const categories = page.getByRole('region', { name: 'Your categories' });
    await categories
      .locator('.branch')
      .filter({ hasText: 'Bills' })
      .getByRole('button', { name: 'Delete' })
      .first()
      .click();

    await expect(page.getByRole('dialog').getByText(/sub-categories are deleted too/)).toBeVisible();
  });

  test('adds a sub-category with its parent already chosen', async ({ page, ui }) => {
    await page.goto('/categories');

    const categories = page.getByRole('region', { name: 'Your categories' });
    await categories
      .locator('.branch')
      .filter({ hasText: 'Bills' })
      .getByRole('button', { name: 'Add a sub-category' })
      .click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByLabel('Sits under')).toHaveValue(/.+/);
    // A child takes its parent's kind, so the kind question is answered rather than asked.
    await expect(dialog.getByLabel('Kind')).toBeDisabled();
    await expect(dialog.getByText(/same kind as the category it sits under/)).toBeVisible();

    await dialog.getByLabel('Name').fill('Water');

    await ui.shot('categories-add-child');
    expect(await ui.a11y('add a sub-category')).toBe(0);

    await dialog.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);

    ui.noErrors();
  });

  test('explains that an existing category cannot change its kind', async ({ page, ui }) => {
    await page.goto('/categories');

    const categories = page.getByRole('region', { name: 'Your categories' });
    await categories
      .getByRole('listitem')
      .filter({ hasText: 'Groceries' })
      .getByRole('button', { name: 'Edit' })
      .first()
      .click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByLabel('Kind')).toBeDisabled();
    await expect(dialog.getByText(/kind can't change/)).toBeVisible();

    await ui.shot('categories-edit');
    expect(await ui.a11y('edit a category')).toBe(0);
    ui.noErrors();
  });

  test('renders on a phone without anything running off the side', async ({ page, ui }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/categories');

    await expect(page.getByText('Groceries')).toBeVisible();

    await ui.shot('categories-tree-phone');
    await ui.noOverflow();
    expect(await ui.a11y('categories on a phone')).toBe(0);
    ui.noErrors();
  });
});

test.describe('categories, as a viewer', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'viewer' });
  });

  test('offers a viewer nothing to click', async ({ page, ui }) => {
    await page.goto('/categories');

    await expect(page.getByText('Groceries')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add a category' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Delete' })).toHaveCount(0);

    await ui.shot('categories-viewer');
    expect(await ui.a11y('categories as a viewer')).toBe(0);
    ui.noErrors();
  });
});
