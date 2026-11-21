import { mockApi } from '../../helpers/mock-api.js';
import { expect, test } from '../../helpers/ui-test.js';

/**
 * Moving money between your own accounts: `docs/features/transfers.md`, UI section.
 *
 * Four of these are the feature's rules rather than UI taste, and are here so a later redesign
 * cannot quietly drop them:
 *
 * - **A transfer is not spending.** There is no category picker on the sheet at all, and the
 *   ledger must not offer to file one.
 * - **The second amount appears only across currencies**, because within one the two figures are
 *   the same number and a field for it is a chance for them to disagree.
 * - **A transfer to itself is refused as it is chosen**, not on submit.
 * - **Edit and Delete act on the pair.** Acting on one leg answers 409, so a row that cannot
 *   address its pair must offer neither rather than a button that can only fail.
 */

test.describe('moving money, as an owner', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'owner' });
  });

  test('offers to move money from the ledger', async ({ page, ui }) => {
    await page.goto('/transactions');

    await expect(page.getByRole('button', { name: /Move money/ })).toBeVisible();

    await ui.shot('transfers-ledger');
    expect(await ui.a11y('the ledger with transfers in it')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('reads a transfer as a transfer, not as a purchase with no payee', async ({ page, ui }) => {
    await page.goto('/transactions');

    // Both legs are in the ledger, because each account has to show its own movement.
    const rows = page.locator('.ledger-row').filter({ hasText: 'Between your accounts' });
    await expect(rows).toHaveCount(2);
    // "No payee" on a transfer reads as missing data. It is not missing; there is no payee.
    await expect(rows.first()).not.toContainText('No payee');

    await ui.shot('transfers-ledger-row');
    ui.noErrors();
  });

  test('asks for one amount when both accounts share a currency', async ({ page, ui }) => {
    await page.goto('/transactions');
    await page.getByRole('button', { name: /Move money/ }).click();

    const dialog = page.getByRole('dialog');
    await dialog.getByLabel(/Out of/).selectOption({ label: 'Everyday Checking (USD)' });
    await dialog.getByLabel(/Into/).selectOption({ label: 'Emergency Fund (USD)' });

    // Both in USD, so there is one figure and asking twice would invite two that disagree.
    await expect(dialog.getByLabel(/Amount that arrived/)).toHaveCount(0);
    await expect(dialog.getByLabel(/Amount that left/)).toHaveAttribute('inputmode', 'decimal');
    // Deliberately not type="number": that hands back a double (ADR-0006).
    await expect(dialog.getByLabel(/Amount that left/)).toHaveAttribute('type', 'text');
    // A transfer is not spending. There is nothing here to file it under.
    await expect(dialog.getByLabel(/Category/)).toHaveCount(0);

    await ui.shot('transfers-same-currency');
    expect(await ui.a11y('the move money sheet')).toBe(0);
    ui.noErrors();
  });

  test('asks for the second figure only when the currencies differ', async ({ page, ui }) => {
    await page.goto('/transactions');
    await page.getByRole('button', { name: /Move money/ }).click();

    const dialog = page.getByRole('dialog');
    await dialog.getByLabel(/Out of/).selectOption({ label: 'Everyday Checking (USD)' });
    await dialog.getByLabel(/Into/).selectOption({ label: 'Berlin Savings (EUR)' });

    // $200 leaving does not make €200 arrive, and only the statement knows the second figure
    // (ADR-0033). So the screen asks rather than inventing a rate.
    await expect(dialog.getByLabel(/Amount that arrived/)).toBeVisible();
    // The pickers name each account's currency, which is how somebody sees why it appeared.
    await expect(dialog.getByLabel(/Into/)).toContainText('EUR');

    await ui.shot('transfers-cross-currency');
    expect(await ui.a11y('the sheet, across currencies')).toBe(0);
    ui.noErrors();

    // And it goes away again when the pair stops being cross-currency, so a stale figure cannot
    // be sent with a same-currency transfer and turn it into a 400.
    await dialog.getByLabel(/Into/).selectOption({ label: 'Emergency Fund (USD)' });
    await expect(dialog.getByLabel(/Amount that arrived/)).toHaveCount(0);
  });

  test('refuses a transfer to the same account as it is chosen', async ({ page, ui }) => {
    await page.goto('/transactions');
    await page.getByRole('button', { name: /Move money/ }).click();

    const dialog = page.getByRole('dialog');
    await dialog.getByLabel(/Out of/).selectOption({ label: 'Everyday Checking (USD)' });
    await dialog.getByLabel(/Into/).selectOption({ label: 'Everyday Checking (USD)' });
    // Moving on, as somebody filling the form in does. The message is attached to the field and
    // shows once the field has been left, which is the same rule every other field here follows.
    await dialog.getByLabel(/Amount that left/).click();

    // Inline, not on submit: a round trip to be told what the form already knew is a worse way
    // to learn it.
    await expect(dialog).toContainText(/different account/i);

    await ui.shot('transfers-same-account');
    expect(await ui.a11y('the sheet refusing the same account')).toBe(0);
    ui.noErrors();
  });

  test('moves money and says so', async ({ page, ui }) => {
    const calls = await mockApi(page, { session: 'owner' });
    await page.goto('/transactions');
    await page.getByRole('button', { name: /Move money/ }).click();

    const dialog = page.getByRole('dialog');
    await dialog.getByLabel(/Out of/).selectOption({ label: 'Everyday Checking (USD)' });
    await dialog.getByLabel(/Into/).selectOption({ label: 'Emergency Fund (USD)' });
    await dialog.getByLabel(/Amount that left/).fill('250.00');
    await dialog.getByRole('button', { name: /Move it|Save/ }).first().click();

    await expect
      .poll(() => calls.some((call) => call.method === 'POST' && call.path === '/api/transfers'))
      .toBe(true);
    const sent = calls.find((call) => call.method === 'POST' && call.path === '/api/transfers');
    const body = sent?.body as Record<string, unknown>;
    // A string, unsigned: the direction is in the field names (ADR-0006, ADR-0033).
    expect(body['amount']).toBe('250.00');
    expect(body).not.toHaveProperty('amountReceived');
    expect(body).not.toHaveProperty('categoryId');

    await ui.shot('transfers-moved');
    ui.noErrors();
  });

  test('edits the pair, and offers no way to move it between accounts', async ({ page, ui }) => {
    const calls = await mockApi(page, { session: 'owner' });
    await page.goto('/transactions');

    const row = page.locator('.ledger-row').filter({ hasText: 'Between your accounts' }).first();
    await row.getByRole('button', { name: /Edit/ }).click();

    const dialog = page.getByRole('dialog');
    // The accounts are fixed at creation, as a transaction's are — so they are named, not
    // offered, and the sheet says what to do instead rather than leaving somebody hunting.
    await expect(dialog.getByLabel(/Out of/)).toHaveCount(0);
    await expect(dialog.getByLabel(/Into/)).toHaveCount(0);
    // Opening it reads the pair, because a leg carries none of what the sheet edits.
    expect(calls.some((call) => call.method === 'GET' && call.path.startsWith('/api/transfers/')))
      .toBe(true);

    await ui.shot('transfers-edit');
    expect(await ui.a11y('the edit sheet')).toBe(0);
    ui.noErrors();
  });

  test('says both halves go when deleting one', async ({ page, ui }) => {
    await page.goto('/transactions');

    const row = page.locator('.ledger-row').filter({ hasText: 'Between your accounts' }).first();
    await row.getByRole('button', { name: /Delete/ }).click();

    // The thing somebody is actually afraid of, answered before they commit.
    await expect(page.getByRole('dialog')).toContainText(/Both halves/i);

    await ui.shot('transfers-delete-confirm');
    expect(await ui.a11y('the delete confirmation')).toBe(0);
    ui.noErrors();
  });
});

test.describe('moving money, as a viewer', () => {
  test('shows transfers and offers nothing to change', async ({ page, ui }) => {
    await mockApi(page, { session: 'viewer' });
    await page.goto('/transactions');

    await expect(page.locator('.ledger-row').filter({ hasText: 'Between your accounts' }).first())
      .toBeVisible();
    await expect(page.getByRole('button', { name: /Move money/ })).toHaveCount(0);

    await ui.shot('transfers-viewer');
    expect(await ui.a11y('the ledger as a viewer')).toBe(0);
    ui.noErrors();
  });
});

test.describe('moving money, in the dark theme', () => {
  test('reads correctly in the dark', async ({ page, ui }) => {
    await page.addInitScript(() => localStorage.setItem('budget-owl.theme', 'dark'));
    await mockApi(page, { session: 'owner' });
    await page.goto('/transactions');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');

    await page.getByRole('button', { name: /Move money/ }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel(/Out of/).selectOption({ label: 'Everyday Checking (USD)' });
    await dialog.getByLabel(/Into/).selectOption({ label: 'Berlin Savings (EUR)' });

    await ui.shot('transfers-cross-currency-dark');
    expect(await ui.a11y('the sheet across currencies, dark')).toBe(0);
    ui.noErrors();
  });
});
