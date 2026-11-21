import { expect, test } from '../../helpers/doc-capture.js';
import { mockApi } from '../../helpers/mock-api.js';

/**
 * Screens for the slice 8 guide: move-money-between-accounts.
 *
 * The journey somebody actually walks: the ledger, the sheet, the one amount, the second amount
 * that only appears across currencies, the same-account mistake, the two rows the transfer
 * becomes, correcting it, removing it, and what a viewer sees.
 *
 * Everything renders from tools/ui/fixtures/demo-data.ts (ADR-0013) — no backend, no database,
 * no real money anywhere near a screenshot.
 */

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await mockApi(page, { session: 'owner' });
});

test('@doc transfers: moving money between your own accounts', async ({ page, doc }) => {
  doc.guide('move-money-between-accounts');

  await page.goto('/transactions');
  await expect(page.getByRole('heading', { name: 'Transactions', level: 1 })).toBeVisible();
  await doc.capture(
    'move-money-button',
    'The Transactions screen with the Move money button, beside Record a transaction, circled.',
    {
      fullPage: true,
      highlight: page.getByRole('button', { name: 'Move money' }),
      step: 2,
    },
  );

  await page.getByRole('button', { name: 'Move money' }).click();
  const dialog = page.getByRole('dialog');
  await expect(
    dialog.getByRole('heading', { name: 'Move money between your accounts' }),
  ).toBeVisible();
  await doc.capture(
    'sheet',
    'The Move money between your accounts window as it opens: a note saying a transfer has no category and nobody to pay, then Out of, Into, Amount that left, Date and Note.',
  );

  // The guide tells people an archived account is not offered here; this is what makes that
  // sentence true rather than hopeful. "Old Joint Account" is archived in the fixtures.
  await expect(dialog.getByLabel(/Out of/)).not.toContainText('Old Joint Account');
  await expect(dialog.getByLabel(/Into/)).not.toContainText('Old Joint Account');

  await dialog.getByLabel(/Out of/).selectOption({ label: 'Everyday Checking (USD)' });
  await doc.capture(
    'out-of',
    'Out of set to Everyday Checking (USD), with the Out of list circled. Every account in the list is followed by its currency.',
    { highlight: dialog.getByLabel(/Out of/), step: 3 },
  );

  await dialog.getByLabel(/Into/).selectOption({ label: 'Emergency Fund (USD)' });
  await doc.capture(
    'into',
    'Into set to Emergency Fund (USD), with the Into list circled, below Out of set to Everyday Checking (USD).',
    { highlight: dialog.getByLabel(/Into/), step: 4 },
  );

  await dialog.getByLabel(/Amount that left/).fill('250.00');
  await doc.capture(
    'amount',
    'Amount that left holding 250.00, with the hint underneath saying it is in USD, the currency of the account it leaves, and that there is no minus sign.',
    { highlight: dialog.getByLabel(/Amount that left/), step: 5 },
  );

  // The window is taller than it looks: Note sits below the fold, behind the Save bar.
  await dialog.getByLabel('Note').scrollIntoViewIfNeeded();
  await doc.capture(
    'note',
    'The lower half of the window, reached by scrolling inside it: Date, then Note with a hint saying it is optional and both halves of the transfer get it.',
    { highlight: dialog.getByLabel('Note'), step: 7 },
  );

  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Money moved.' })).toBeVisible();
  await doc.capture('moved', 'The Transactions screen with a Money moved. banner above the list.');
});

test('@doc transfers: the second amount, across two currencies', async ({ page, doc }) => {
  doc.guide('move-money-between-accounts');

  await page.goto('/transactions');
  await page.getByRole('button', { name: 'Move money' }).click();
  const dialog = page.getByRole('dialog');

  await dialog.getByLabel(/Out of/).selectOption({ label: 'Everyday Checking (USD)' });
  await dialog.getByLabel(/Into/).selectOption({ label: 'Berlin Savings (EUR)' });
  await expect(dialog.getByLabel(/Amount that arrived/)).toBeVisible();
  await dialog.getByLabel(/Amount that left/).fill('200.00');
  await doc.capture(
    'cross-currency',
    'The window with Into set to Berlin Savings (EUR): a second box, Amount that arrived, has appeared below Amount that left, with a hint saying the two accounts are in different currencies and only your statement knows what arrived.',
    { highlight: dialog.getByLabel(/Amount that arrived/) },
  );

  await dialog.getByLabel(/Amount that arrived/).fill('183.40');

  // The sentence that says why the second figure is wanted is below the fold at this size.
  await dialog.locator('#transfer-received-hint').scrollIntoViewIfNeeded();
  await doc.capture(
    'cross-currency-hint',
    'The hint under Amount that arrived, read in full: these two accounts are in different currencies, so what arrived is not the same number as what left, and only your statement knows it — copy it from there, in EUR.',
  );
});

test('@doc transfers: a transfer is never waiting for a category', async ({ page, doc }) => {
  doc.guide('move-money-between-accounts');

  await page.goto('/transactions');
  await page.getByRole('button', { name: 'Needs a category' }).click();
  await expect(page.locator('.ledger').getByRole('listitem')).toHaveCount(1);
  await doc.capture(
    'needs-a-category',
    'The list narrowed to what still needs a category: one shop transaction, and neither half of the transfer.',
    { fullPage: true },
  );
});

test('@doc transfers: choosing the same account twice', async ({ page, doc }) => {
  doc.guide('move-money-between-accounts');

  await page.goto('/transactions');
  await page.getByRole('button', { name: 'Move money' }).click();
  const dialog = page.getByRole('dialog');

  await dialog.getByLabel(/Out of/).selectOption({ label: 'Everyday Checking (USD)' });
  await dialog.getByLabel(/Into/).selectOption({ label: 'Everyday Checking (USD)' });
  // Moving on, as somebody filling the form in does: the message shows once the field is left.
  await dialog.getByLabel(/Amount that left/).click();
  await expect(dialog).toContainText(/different account/i);
  await doc.capture(
    'same-account',
    'Out of and Into both set to Everyday Checking (USD), with a message under Into saying it has to be a different account.',
    { highlight: dialog.getByLabel(/Into/) },
  );
});

test('@doc transfers: a transfer in the list, and changing one', async ({ page, doc }) => {
  doc.guide('move-money-between-accounts');

  await page.goto('/transactions');
  const transferRows = page.locator('.ledger-row').filter({ hasText: 'Between your accounts' });
  await expect(transferRows).toHaveCount(2);
  await doc.capture(
    'two-rows',
    'The list showing the same transfer twice: Transfer in, $500.00 into Emergency Fund, and Transfer out, -$500.00 from Everyday Checking, both dated Aug 16, 2026 and both labelled Between your accounts with a Transfer badge.',
    { fullPage: true },
  );

  await doc.capture(
    'one-row',
    'One transfer row close up: Transfer out, -$500.00, Aug 16, 2026, Everyday Checking, Between your accounts, Transfer, with the note Transfer to Emergency Fund and Edit and Delete buttons.',
    { focus: transferRows.last() },
  );

  // The guide says filtering by one account leaves that account's half of the transfer. Asserted
  // rather than asserted-in-prose, so the sentence cannot quietly stop being true.
  await page.getByLabel('Account', { exact: true }).selectOption({ label: 'Everyday Checking' });
  await expect(transferRows).toHaveCount(1);
  await expect(transferRows.first()).toContainText('Transfer out');
  await page.getByRole('button', { name: 'Clear filters' }).click();
  await expect(transferRows).toHaveCount(2);

  await transferRows.last().getByRole('button', { name: 'Edit' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('heading', { name: 'Edit this transfer' })).toBeVisible();
  await doc.capture(
    'edit',
    'The Edit this transfer window: the two accounts named as Everyday Checking to Emergency Fund with no lists to change them, then Amount that left, Date and Note.',
  );
});

test('@doc transfers: removing a transfer', async ({ page, doc }) => {
  doc.guide('move-money-between-accounts');

  await page.goto('/transactions');
  const row = page.locator('.ledger-row').filter({ hasText: 'Between your accounts' }).last();
  await row.getByRole('button', { name: 'Delete' }).click();
  await expect(page.getByRole('dialog')).toContainText(/Both halves/i);
  await doc.capture(
    'delete-confirm',
    'The Delete this transfer? window, saying both halves go and both account balances go back to what they were, and that it cannot be undone.',
  );
});

test('@doc transfers: what a viewer sees', async ({ page, doc }) => {
  doc.guide('move-money-between-accounts');

  await mockApi(page, { session: 'viewer' });
  await page.goto('/transactions');
  await expect(
    page.locator('.ledger-row').filter({ hasText: 'Between your accounts' }).first(),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Move money' })).toHaveCount(0);
  await doc.capture(
    'viewer',
    'The Transactions screen as a viewer: the transfer rows are there with their amounts, and there is no Move money button and no buttons on any row.',
    { fullPage: true },
  );
});
