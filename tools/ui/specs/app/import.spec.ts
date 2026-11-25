import type { Page } from '@playwright/test';
import {
  DEMO_IMPORT_PREVIEW_DIFFERENT_BALANCE,
  DEMO_IMPORT_PREVIEW_HOSTILE,
  DEMO_IMPORT_PREVIEW_OTHER_ACCOUNT,
  DEMO_PAIRING_LEDGER,
} from '../../fixtures/demo-data.js';
import { mockApi } from '../../helpers/mock-api.js';
import { expect, test } from '../../helpers/ui-test.js';

/**
 * Importing a bank statement, and pairing two rows into a transfer:
 * `docs/features/file-import.md`, UI section and rule 12.
 *
 * The rules here are the feature's, not UI taste:
 *
 * - **The preview is the confirmation.** It says how many rows, into which account, before
 *   anything is written, and the button carries both.
 * - **Suspected duplicates are left out unless ticked** (decision 3).
 * - **A file that looks like another account's is named as such**, with the chosen account's
 *   last four digits, and a bank balance that disagrees with ours is said out loud.
 * - **Anything from the file is text.** A payee carrying markup or a right-to-left override is
 *   rendered as characters and reorders nothing around it.
 * - **Nothing is preselected when pairing**, and the loss of category and payee is said first.
 */

/** A small statement. The mock never reads it: the preview comes from the fixtures. */
const OFX_FILE = {
  name: 'checking-aug.qfx',
  mimeType: 'application/octet-stream',
  buffer: Buffer.from('OFXHEADER:100\nDATA:OFXSGML\n<OFX></OFX>\n'),
};

const CSV_FILE = {
  name: 'checking-aug.csv',
  mimeType: 'text/csv',
  buffer: Buffer.from('Posted,Description,Debit,Credit,Balance\n'),
};

async function previewStatement(page: Page, file = OFX_FILE): Promise<void> {
  await page.goto('/import');
  await page.getByLabel('Account').selectOption({ label: 'Everyday Checking •4417' });
  await page.getByLabel('Statement file').setInputFiles(file);
  await page.getByRole('button', { name: 'Preview the import' }).click();
}

test.describe('importing a statement, as an owner', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'owner' });
  });

  test('is reached from the ledger and from each account', async ({ page, ui }) => {
    await page.goto('/transactions');
    await expect(page.getByRole('link', { name: 'Import a statement' })).toBeVisible();

    await page.goto('/accounts');
    await page.getByRole('link', { name: /Import\s+Emergency Fund/ }).click();
    await expect(page).toHaveURL(/\/import\?accountId=acc-0002/);
    // The account row's action preselects that account; it is only a starting choice.
    await expect(page.getByLabel('Account')).toHaveValue('acc-0002');

    await ui.shot('import-empty');
    expect(await ui.a11y('the import page, before a file')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('previews a statement before anything is written', async ({ page, ui }) => {
    const calls = await mockApi(page, { session: 'owner' });
    await previewStatement(page);

    const preview = page.getByRole('region', { name: /What will be added/ });
    await expect(preview).toBeVisible();
    await expect(preview).toContainText('Your bank says the balance is $4,449.10');
    await expect(preview).toContainText('They match.');
    await expect(
      page.getByRole('button', { name: 'Import 6 transactions into Everyday Checking •4417' }),
    ).toBeEnabled();

    // The preview wrote nothing, and the request carried the file but never its name.
    expect(calls.some((call) => call.method === 'POST' && call.path === '/api/imports')).toBe(
      false,
    );
    const sent = calls.find((call) => call.path === '/api/imports/preview');
    expect(JSON.stringify(sent?.body)).not.toContain('checking-aug');
    expect(sent?.headers?.['content-type']).toContain('application/json');

    await ui.shot('import-preview');
    expect(await ui.a11y('the import preview')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('leaves a suspected duplicate out unless it is ticked', async ({ page, ui }) => {
    const calls = await mockApi(page, { session: 'owner' });
    await previewStatement(page);

    const suspected = page.getByRole('checkbox', { name: /Include .*Greenfield Market/ });
    await expect(suspected).not.toBeChecked();
    await suspected.check();
    await expect(
      page.getByRole('button', { name: 'Import 7 transactions into Everyday Checking •4417' }),
    ).toBeVisible();

    await ui.shot('import-suspected-ticked');
    await page.getByRole('button', { name: /Import 7 transactions/ }).click();
    await expect(
      page.getByText('Imported 7 transactions into Everyday Checking •4417.'),
    ).toBeVisible();

    const commit = calls.find((call) => call.method === 'POST' && call.path === '/api/imports');
    const body = commit?.body as Record<string, unknown>;
    expect(body['suspectedDuplicates']).toBe('EXCLUDE');
    expect(body['overrides']).toEqual([{ row: 3, include: true }]);
    expect(typeof body['previewDigest']).toBe('string');

    await ui.shot('import-done');
    expect(await ui.a11y('the import, done')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('undo names the count and the account, and asks first', async ({ page, ui }) => {
    await previewStatement(page);
    await page.getByRole('button', { name: /Import 6 transactions/ }).click();
    await page.getByRole('button', { name: 'Undo this import' }).click();

    const dialog = page.getByRole('dialog', { name: 'Undo this import?' });
    await expect(dialog).toContainText(
      'removes the 6 transactions this import added to Everyday Checking',
    );
    await expect(dialog).toContainText('lose that link');

    await ui.shot('import-undo-confirm');
    expect(await ui.a11y('the undo confirmation')).toBe(0);
    await dialog.getByRole('button', { name: 'Undo this import' }).click();
    await expect(page.getByText('Import undone. 6 transactions removed.')).toBeVisible();
    ui.noErrors();
  });

  test('says so when the bank balance and ours disagree', async ({ page, ui }) => {
    await page.route('**/api/imports/preview', (route) =>
      route.fulfill({ status: 200, json: DEMO_IMPORT_PREVIEW_DIFFERENT_BALANCE }),
    );
    await previewStatement(page);

    await expect(page.getByText(/Your bank says the balance is \$4,512\.60/)).toBeVisible();
    await expect(page.getByText(/Those two differ/)).toBeVisible();

    await ui.shot('import-balance-differs');
    ui.noErrors();
  });

  test('warns, naming the account, when the file looks like a different one', async ({
    page,
    ui,
  }) => {
    await page.route('**/api/imports/preview', (route) =>
      route.fulfill({ status: 200, json: DEMO_IMPORT_PREVIEW_OTHER_ACCOUNT }),
    );
    await previewStatement(page);

    await expect(
      page.getByText(/looks like it belongs to a different account from Everyday Checking •4417/),
    ).toBeVisible();

    await ui.shot('import-other-account');
    expect(await ui.a11y('the different-account warning')).toBe(0);
    ui.noErrors();
  });

  test('renders hostile payees from a file as text, reordering nothing', async ({ page, ui }) => {
    await page.route('**/api/imports/preview', (route) =>
      route.fulfill({ status: 200, json: DEMO_IMPORT_PREVIEW_HOSTILE }),
    );
    await previewStatement(page);

    const sample = page.getByRole('region', { name: 'The first rows to be added' });
    await expect(sample).toContainText('<img src=x onerror="window.__owned=1">');
    await expect(sample.locator('img')).toHaveCount(0);
    expect(
      await page.evaluate(() => (window as unknown as { __owned?: number }).__owned),
    ).toBeUndefined();
    // Each payee is isolated, so the override inside one cannot flip the amount beside it.
    await expect(sample.locator('bdi[dir="auto"]').first()).toBeVisible();

    await ui.shot('import-hostile-payees');
    ui.noErrors();
  });

  test('asks how to read a CSV, and applies it only on Update preview', async ({ page, ui }) => {
    const calls = await mockApi(page, { session: 'owner' });
    await previewStatement(page, CSV_FILE);

    const mapping = page.getByRole('region', { name: /How to read it/ });
    await expect(mapping).toBeVisible();
    await expect(page.getByLabel(/Column 1 · Posted/)).toHaveValue('DATE');
    await expect(page.getByLabel(/Column 3 · Debit/)).toHaveValue('DEBIT');
    // Shown, not applied: nothing to import until the person confirms the mapping.
    await expect(page.getByRole('button', { name: /^Import \d+ transactions/ })).toHaveCount(0);

    await ui.shot('import-csv-mapping');
    expect(await ui.a11y('the CSV mapping step')).toBe(0);
    await ui.noOverflow();

    await page.getByRole('button', { name: 'Update preview' }).click();
    await expect(page.getByRole('button', { name: /Import 6 transactions/ })).toBeVisible();
    const previews = calls.filter((call) => call.path === '/api/imports/preview');
    const options = (previews.at(-1)?.body as { options?: Record<string, unknown> }).options;
    expect(options?.['columns']).toEqual({
      '0': 'DATE',
      '1': 'PAYEE',
      '2': 'DEBIT',
      '3': 'CREDIT',
    });

    await ui.shot('import-csv-preview');
    ui.noErrors();
  });
});

test.describe('importing a statement, as a viewer', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'viewer' });
  });

  test('says plainly that viewers cannot import, and offers no way in', async ({ page, ui }) => {
    await page.goto('/transactions');
    await expect(page.getByRole('link', { name: 'Import a statement' })).toHaveCount(0);

    await page.goto('/import');
    await expect(
      page.getByRole('heading', { name: "Viewers can't import statements" }),
    ).toBeVisible();
    await expect(page.getByLabel('Statement file')).toHaveCount(0);

    await ui.shot('import-viewer');
    expect(await ui.a11y('the import page for a viewer')).toBe(0);
    ui.noErrors();
  });
});

test.describe('importing a statement, in the dark theme', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'owner' });
    await page.addInitScript(() => localStorage.setItem('budget-owl.theme', 'dark'));
  });

  test('the preview and the CSV mapping stay legible', async ({ page, ui }) => {
    await previewStatement(page);
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expect(page.getByRole('region', { name: /What will be added/ })).toBeVisible();
    await ui.shot('import-preview-dark');
    expect(await ui.a11y('the import preview in dark')).toBe(0);

    await page.getByRole('button', { name: 'Start over' }).click();
    await page.getByLabel('Statement file').setInputFiles(CSV_FILE);
    await page.getByRole('button', { name: 'Preview the import' }).click();
    await expect(page.getByRole('region', { name: /How to read it/ })).toBeVisible();
    await ui.shot('import-csv-mapping-dark');
    expect(await ui.a11y('the CSV mapping in dark')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });
});

test.describe('import: pairing two rows into a transfer', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'owner' });
  });

  test('preselects nothing and says what pairing costs before the button', async ({ page, ui }) => {
    const calls = await mockApi(page, { session: 'owner' });
    await page.route(
      (url) => url.pathname === '/api/transactions',
      (route) => route.fulfill({ status: 200, json: DEMO_PAIRING_LEDGER }),
    );
    await page.goto('/transactions');
    const row = page.locator('.ledger-row').filter({ hasText: 'ONLINE TRANSFER TO SAVINGS' });
    await row.getByRole('button', { name: 'This is a transfer…' }).click();

    const dialog = page.getByRole('dialog', { name: /Pair this with the other half/ });
    await expect(dialog).toBeVisible();
    const radios = dialog.getByRole('radio');
    // Only rows that went the other way, in another account, and are not already a transfer.
    await expect(radios).toHaveCount(1);
    await expect(radios.first()).toHaveAccessibleName(/TRANSFER FROM CHK 4417/);
    for (const radio of await radios.all()) {
      await expect(radio).not.toBeChecked();
    }
    await expect(dialog.getByRole('button', { name: 'Pair these two rows' })).toBeDisabled();
    await expect(dialog).toContainText('lose their category and payee');

    await ui.shot('import-pair-sheet');
    expect(await ui.a11y('the pairing sheet')).toBe(0);
    await ui.noOverflow();

    await radios.first().check();
    await dialog.getByRole('button', { name: 'Pair these two rows' }).click();
    await expect(page.getByText('Paired into a transfer.')).toBeVisible();
    const pair = calls.find((call) => call.path === '/api/transactions/txn-0020/pair');
    expect(pair?.body).toMatchObject({
      version: expect.any(Number),
      withVersion: expect.any(Number),
    });
    ui.noErrors();
  });

  test('splits a transfer back into two rows after saying they lose their category', async ({
    page,
    ui,
  }) => {
    await page.goto('/transactions');
    const leg = page.locator('.ledger-row').filter({ hasText: 'Between your accounts' }).first();
    await leg.getByRole('button', { name: 'Split into two rows' }).click();

    const dialog = page.getByRole('dialog', { name: 'Split this transfer into two rows?' });
    await expect(dialog).toContainText('no category and no payee');
    await ui.shot('import-split-confirm');
    expect(await ui.a11y('the split confirmation')).toBe(0);

    await dialog.getByRole('button', { name: 'Split' }).click();
    await expect(page.getByText('Split into two rows. Both need a category.')).toBeVisible();
    ui.noErrors();
  });

  test('offers a viewer neither pairing nor splitting', async ({ page }) => {
    await mockApi(page, { session: 'viewer' });
    await page.goto('/transactions');
    await expect(page.locator('.ledger-row').first()).toBeVisible();

    await expect(page.getByRole('button', { name: 'This is a transfer…' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Split into two rows' })).toHaveCount(0);
  });
});
