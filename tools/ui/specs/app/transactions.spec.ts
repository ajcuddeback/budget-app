import { mockApi } from '../../helpers/mock-api.js';
import { expect, test } from '../../helpers/ui-test.js';

/**
 * The ledger: `docs/features/transactions.md`, UI section.
 *
 * Four of these assertions are the feature's rules rather than UI taste, and are here so a later
 * redesign cannot quietly drop them:
 *
 * - **"Needs a category" is a visible state with a filter of its own.** It is the work queue that
 *   turns a pile of transactions into a budget, and the way it gets lost is somebody rendering an
 *   uncategorised row as a blank cell.
 * - **An amount keeps every digit it was given** (ADR-0006). The figure on screen is formatted from
 *   a string, and no screen in this app parses one into a number.
 * - **Nothing is totalled across currencies** (ADR-0022). The page total comes from the server, and
 *   a mixed page says so instead of converting.
 * - **A VIEWER is offered nothing to click.** The server refuses them anyway; the UI's job is not
 *   to offer a button that is going to be refused.
 */

test.describe('the ledger, as an owner', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'owner' });
  });

  test('lists what happened, newest first, with the payee, amount and category', async ({
    page,
    ui,
  }) => {
    await page.goto('/transactions');

    await expect(page.getByRole('heading', { name: 'Transactions', level: 1 })).toBeVisible();
    // Scoped to the list, not the region: the filter form's <option>s carry the same names, and
    // asserting against the region would pass on a dropdown while the ledger rendered nothing.
    const rows = page.locator('.ledger');

    await expect(rows.getByText('Greenfield Market').first()).toBeVisible();
    await expect(rows.getByText('Northwind Systems')).toBeVisible();
    // An expense keeps its sign, and income does not wear one.
    await expect(rows.getByText('-$1,450.00')).toBeVisible();
    await expect(rows.getByText('$3,200.00')).toBeVisible();
    // A child category is composed from parent and name, never pre-joined by the server.
    await expect(rows.getByText('Bills → Utilities')).toBeVisible();
    await expect(rows.getByText('big shop')).toBeVisible();

    await ui.shot('transactions-ledger');
    expect(await ui.a11y('ledger, populated')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('says a transaction needs a category rather than leaving the space blank', async ({
    page,
    ui,
  }) => {
    await page.goto('/transactions');

    await expect(page.locator('.ledger').getByText('Needs a category').first()).toBeVisible();

    await ui.shot('transactions-needs-category');
    ui.noErrors();
  });

  test('filters down to only the transactions that need a category', async ({ page, ui }) => {
    await page.goto('/transactions');

    await page.getByRole('button', { name: 'Needs a category' }).click();

    // One fixture row has no category. The two transfer legs also have none, and are deliberately
    // NOT here: a transfer is neither income nor spending, so no category could be right for it.
    const rows = page.locator('.ledger');
    await expect(rows.getByRole('listitem')).toHaveCount(1);
    await expect(rows.getByText('Northwind Systems')).toHaveCount(0);
    await expect(rows.getByText(/Transfer to/)).toHaveCount(0);

    await ui.shot('transactions-work-queue');
    expect(await ui.a11y('ledger, uncategorised only')).toBe(0);
    ui.noErrors();
  });

  test('shows the page total the server sent, and never adds two currencies', async ({ page }) => {
    await page.goto('/transactions');

    await expect(page.getByText('This page adds up to')).toBeVisible();
    await expect(page.getByText('$1,235.20')).toBeVisible();
  });

  test('narrows the ledger to one account and drops the account column', async ({ page, ui }) => {
    await page.goto('/transactions');

    await page.getByLabel('Account').first().selectOption({ label: 'Travel Card' });

    const rows = page.locator('.ledger');
    await expect(rows.getByRole('listitem')).toHaveCount(2);
    await expect(rows.getByText('City Transit')).toBeVisible();
    // With one account in view its name on every row is noise.
    await expect(rows.getByText('Everyday Checking')).toHaveCount(0);

    await ui.shot('transactions-one-account');
    ui.noErrors();
  });

  test('finds a transaction by its note or its payee', async ({ page }) => {
    await page.goto('/transactions');

    await page.getByLabel('Search').fill('big shop');
    await page.getByLabel('Search').blur();

    const rows = page.locator('.ledger');
    await expect(rows.getByRole('listitem')).toHaveCount(1);
    await expect(rows.getByText('big shop')).toBeVisible();
  });

  test('says nothing matches, rather than looking empty, when a filter excludes everything', async ({
    page,
    ui,
  }) => {
    await page.goto('/transactions');

    await page.getByLabel('Search').fill('nothing is called this');
    await page.getByLabel('Search').blur();

    await expect(page.getByText(/Nothing matches/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Clear filters' })).toBeVisible();

    await ui.shot('transactions-no-matches');
    expect(await ui.a11y('ledger, no matches')).toBe(0);
    ui.noErrors();
  });

  test('records a transaction without asking anyone to type a minus sign', async ({ page, ui }) => {
    await page.goto('/transactions');

    await page.getByRole('button', { name: 'Record a transaction' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: 'Record a transaction' })).toBeVisible();
    // The direction is a choice, and "Money out" is the common case so it is already selected.
    await expect(dialog.getByLabel('Money out')).toBeChecked();
    // A category is optional, and the option that says so comes first.
    await expect(dialog.getByLabel('Category')).toHaveValue('');

    await dialog.getByLabel('Amount').fill('18.40');
    await dialog.getByLabel('Who it was with').fill('Greenfield Market');
    await dialog.getByLabel('Category').selectOption({ label: 'Groceries' });

    await ui.shot('transactions-entry-form');
    expect(await ui.a11y('transaction entry form')).toBe(0);

    await dialog.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(
      page.getByRole('status').filter({ hasText: 'Transaction recorded' }),
    ).toBeVisible();

    ui.noErrors();
  });

  test('suggests payees the household has used before', async ({ page, ui }) => {
    await page.goto('/transactions');
    await page.getByRole('button', { name: 'Record a transaction' }).click();

    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Who it was with').fill('Green');

    // A native datalist, so the options exist in the DOM rather than in a hand-built popup.
    await expect(page.locator('#transaction-payee-suggestions option')).toHaveCount(1);

    await ui.shot('transactions-payee-suggestions');
    ui.noErrors();
  });

  test('says where a transaction goes before deleting it', async ({ page, ui }) => {
    await page.goto('/transactions');

    await page.locator('.ledger').getByRole('button', { name: 'Delete' }).first().click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText(/account balance goes back/)).toBeVisible();

    await ui.shot('transactions-delete-confirm');
    expect(await ui.a11y('delete confirmation')).toBe(0);
    ui.noErrors();
  });

  test('renders on a phone without anything running off the side', async ({ page, ui }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/transactions');

    await expect(page.getByText('Greenfield Market').first()).toBeVisible();

    await ui.shot('transactions-ledger-phone');
    await ui.noOverflow();
    expect(await ui.a11y('ledger on a phone')).toBe(0);
    ui.noErrors();
  });
});

/**
 * The ledger's coloured text, on the dark ground. The two classes it colours, `.incoming` and
 * `.needs-category`, once used the light theme's fixed -700 ramp steps, about 2.2:1 on the dark
 * card. Nothing caught it because no ledger test ran axe in the dark: the transfers spec checks a
 * dialog in the dark, and the ledger behind a modal dialog is inert, so axe skips it.
 *
 * The desktop and mobile projects (playwright.config.ts) run it in both viewports; the phone
 * layout restacks the row, and CI's headless shell has failed a phone-only dark contrast check
 * before (docs/memory/gotchas.md).
 */
test.describe('the ledger, in the dark theme', () => {
  test('keeps income and "Needs a category" readable in the dark', async ({ page, ui }) => {
    await page.addInitScript(() => localStorage.setItem('budget-owl.theme', 'dark'));
    await mockApi(page, { session: 'owner' });
    await page.goto('/transactions');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');

    // The fixture's two coloured cases are rendered, so axe has something to measure: the salary
    // row is income, and one row has no category. Without them a pass would prove nothing.
    const rows = page.locator('.ledger');
    await expect(rows.locator('.incoming', { hasText: '$3,200.00' })).toBeVisible();
    await expect(rows.locator('.needs-category').first()).toHaveText('Needs a category');

    await ui.shot('transactions-ledger-dark');
    expect(await ui.a11y('ledger, dark')).toBe(0);
    ui.noErrors();
  });
});

test.describe('the ledger, as a viewer', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'viewer' });
  });

  test('offers a viewer nothing to click', async ({ page, ui }) => {
    await page.goto('/transactions');

    await expect(page.getByText('Greenfield Market').first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Record a transaction' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Delete' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Edit' })).toHaveCount(0);

    await ui.shot('transactions-viewer');
    expect(await ui.a11y('ledger as a viewer')).toBe(0);
    ui.noErrors();
  });
});
