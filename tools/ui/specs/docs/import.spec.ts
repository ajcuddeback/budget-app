import {
  DEMO_IMPORT_PREVIEW_OTHER_ACCOUNT,
  DEMO_PAIRING_LEDGER,
} from '../../fixtures/demo-data.js';
import { expect, test } from '../../helpers/doc-capture.js';
import { mockApi } from '../../helpers/mock-api.js';

/**
 * Screens for the slice 12 guides: import-a-bank-statement and pair-two-rows-into-a-transfer.
 *
 * The journey somebody actually walks: from the ledger to the import page, choosing the account
 * and the file, reading the preview (the counts, the bank's balance, the row that looks typed, the
 * line that could not be read), importing, undoing; a CSV's "how to read it" step; the warning for
 * a file from another account; and then, in the ledger, pairing two rows into a transfer and
 * splitting it again.
 *
 * Everything renders from tools/ui/fixtures/demo-data.ts (ADR-0013). The file chosen below is a
 * few bytes the mock never reads: the preview comes from the fixtures, so no statement — real or
 * invented — is anywhere near a screenshot.
 */

const OFX_FILE = {
  name: 'checking-august.qfx',
  mimeType: 'application/octet-stream',
  buffer: Buffer.from('OFXHEADER:100\n<OFX></OFX>\n'),
};

const CSV_FILE = {
  name: 'checking-august.csv',
  mimeType: 'text/csv',
  buffer: Buffer.from('Posted,Description,Debit,Credit,Balance\n'),
};

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await mockApi(page, { session: 'owner' });
});

test('@doc import: importing a bank statement', async ({ page, doc }) => {
  doc.guide('import-a-bank-statement');

  await page.goto('/transactions');
  await expect(page.getByRole('heading', { name: 'Transactions', level: 1 })).toBeVisible();
  await doc.capture(
    'ledger-button',
    'The Transactions screen with the Import a statement button, beside Move money, circled.',
    { highlight: page.getByRole('link', { name: 'Import a statement' }), step: 1 },
  );

  await page.getByRole('link', { name: 'Import a statement' }).click();
  await expect(page.getByRole('heading', { name: 'Import a statement', level: 1 })).toBeVisible();
  await page.getByLabel('Account').selectOption({ label: 'Everyday Checking •4417' });
  await doc.capture(
    'account',
    'The Import a statement screen: Account set to Everyday Checking •4417 and circled, then Statement file inside a dashed box, then Format.',
    { highlight: page.getByLabel('Account'), step: 2 },
  );

  await page.getByLabel('Statement file').setInputFiles(OFX_FILE);
  await doc.capture(
    'file',
    'Statement file holding checking-august.qfx, circled. Underneath: OFX, QFX, QIF or CSV, up to 5 MB, and Selected: 1 kB. Format has filled itself in as OFX or QFX.',
    { highlight: page.getByLabel('Statement file'), step: 3 },
  );

  await page.getByRole('button', { name: 'Preview the import' }).click();
  const preview = page.getByRole('region', { name: /What will be added/ });
  await expect(preview).toBeVisible();
  await doc.capture(
    'summary',
    'The summary: 6 new, 2 already in your ledger, 1 looks like one you typed, 1 could not be read; statement dates Aug 18 to Aug 31, 2026; money in $2,150.00 and out -$181.05; $2,480.15 in Budget Owl now and $4,449.10 after this import; and "Your bank says the balance is $4,449.10. After this import, Budget Owl will say $4,449.10. They match."',
    { focus: page.getByRole('region', { name: 'Summary' }) },
  );

  await doc.capture(
    'needs-a-look',
    'Needs a look: an unticked box reading "Include Aug 20, 2026, Greenfield Market, -$92.15" with "Looks like the one already in your ledger on Aug 20, 2026" beneath, Include all and Leave all out buttons, two rows already in your ledger, and Line 10, "The date can\'t be read", saying 20260231.',
    { focus: page.getByRole('region', { name: 'Needs a look' }) },
  );

  await doc.capture(
    'sample',
    'The first rows to be added, laid out like the Transactions list: Greenfield Market -$54.30, City Transit -$2.75, Northwind Systems $2,150.00 and more, each marked Needs a category.',
    { focus: page.getByRole('region', { name: 'The first rows to be added' }) },
  );

  const confirm = page.getByRole('button', {
    name: 'Import 6 transactions into Everyday Checking •4417',
  });
  await doc.capture(
    'confirm',
    'The button at the bottom of the preview, circled: Import 6 transactions into Everyday Checking •4417, beside Start over.',
    { highlight: confirm, step: 6 },
  );

  await confirm.click();
  await expect(page.getByText('Imported 6 transactions into Everyday Checking •4417.')).toBeVisible();
  await doc.capture(
    'done',
    'Imported 6 transactions into Everyday Checking •4417. They\'re in Needs a category, ready for you to sort. Below: See them in the ledger, Import another file and Undo this import.',
  );

  await page.getByRole('button', { name: 'Undo this import' }).click();
  const undo = page.getByRole('dialog', { name: 'Undo this import?' });
  await expect(undo).toBeVisible();
  await doc.capture(
    'undo-confirm',
    'The Undo this import? window: it removes the 6 transactions this import added to Everyday Checking, links to a bill, a debt payment or an envelope go with them, and rows paired into a transfer stay. Cancel and Undo this import buttons.',
  );
  await undo.getByRole('button', { name: 'Undo this import' }).click();
  await expect(page.getByText('Import undone. 6 transactions removed.')).toBeVisible();
  // The history is re-read after the undo; capture it once it has caught up.
  await expect(page.getByText('Nothing left to undo')).toBeVisible();
  await doc.capture(
    'history',
    'Recent imports, listing Everyday Checking · OFX or QFX with nothing left to undo, and an earlier Travel Card · QIF import with an Undo button.',
    { focus: page.getByRole('region', { name: 'Recent imports' }) },
  );
});

test('@doc import: a CSV file, and a file from another account', async ({ page, doc }) => {
  doc.guide('import-a-bank-statement');

  await page.goto('/import');
  await page.getByLabel('Account').selectOption({ label: 'Everyday Checking •4417' });
  await page.getByLabel('Statement file').setInputFiles(CSV_FILE);
  await page.getByRole('button', { name: 'Preview the import' }).click();
  const mapping = page.getByRole('region', { name: /How to read it/ });
  await expect(mapping).toBeVisible();
  await doc.capture(
    'csv-mapping',
    'How to read it, for a CSV file: Budget Owl\'s guesses for date format (Month/day/year), separators, and Money out appears as A separate column; then each column with what it holds — Posted is Date, Description is Payee, Debit is Money out, Credit is Money in, Balance is Not used — above the first five rows of the file, and an Update preview button.',
    { focus: mapping },
  );

  await page.route('**/api/imports/preview', (route) =>
    route.fulfill({ status: 200, json: DEMO_IMPORT_PREVIEW_OTHER_ACCOUNT }),
  );
  // Choosing another file starts again; the CSV's answers go with it.
  await page.getByLabel('Statement file').setInputFiles(OFX_FILE);
  await page.getByRole('button', { name: 'Preview the import' }).click();
  const warning = page.getByText(/looks like it belongs to a different account/);
  await expect(warning).toBeVisible();
  await doc.capture(
    'other-account',
    'A warning at the top of the preview: This file looks like it belongs to a different account from Everyday Checking •4417. Check you chose the right one before importing.',
    { highlight: warning },
  );
});

test('@doc import: pairing two rows into a transfer', async ({ page, doc }) => {
  doc.guide('pair-two-rows-into-a-transfer');

  // The ledger with the two imported rows that are one transfer at the top.
  await page.route(
    (url) => url.pathname === '/api/transactions',
    (route) => route.fulfill({ status: 200, json: DEMO_PAIRING_LEDGER }),
  );
  await page.goto('/transactions');
  const row = page.locator('.ledger-row').filter({ hasText: 'ONLINE TRANSFER TO SAVINGS' });
  await doc.capture(
    'row-button',
    'Two rows at the top of the Transactions list: TRANSFER FROM CHK 4417, $300.00, Aug 19, 2026, Emergency Fund, and ONLINE TRANSFER TO SAVINGS, -$300.00, Aug 18, 2026, Everyday Checking, whose This is a transfer… button is circled.',
    { highlight: row.getByRole('button', { name: 'This is a transfer…' }), step: 2 },
  );

  await row.getByRole('button', { name: 'This is a transfer…' }).click();
  const sheet = page.getByRole('dialog', { name: /Pair this with the other half/ });
  await expect(sheet).toBeVisible();
  await doc.capture(
    'sheet',
    'The Pair this with the other half of a transfer window: This row, Aug 18, 2026 · Everyday Checking · ONLINE TRANSFER TO SAVINGS · -$300.00; Other account, From and To; and Rows to choose from, with Aug 19, 2026 · Emergency Fund · TRANSFER FROM CHK 4417 · $300.00 not ticked. Pair these two rows is greyed out.',
  );

  await sheet.getByRole('radio', { name: /TRANSFER FROM CHK 4417/ }).check();
  await doc.capture(
    'chosen',
    'The Emergency Fund row ticked; underneath, a box saying both rows will lose their category and payee and stop counting as income or spending; and Pair these two rows, circled, ready to select.',
    { highlight: sheet.getByRole('button', { name: 'Pair these two rows' }), step: 5 },
  );

  await sheet.getByRole('button', { name: 'Pair these two rows' }).click();
  await expect(page.getByText('Paired into a transfer.')).toBeVisible();
  await doc.capture(
    'paired',
    'The banner Paired into a transfer., as it appears above the list.',
    { focus: page.getByRole('status').filter({ hasText: 'Paired into a transfer.' }) },
  );

  const leg = page.locator('.ledger-row').filter({ hasText: 'Between your accounts' }).first();
  await doc.capture(
    'split-button',
    'A transfer row with Edit, Split into two rows (circled) and Delete.',
    { highlight: leg.getByRole('button', { name: 'Split into two rows' }) },
  );
  await leg.getByRole('button', { name: 'Split into two rows' }).click();
  const split = page.getByRole('dialog', { name: 'Split this transfer into two rows?' });
  await expect(split).toBeVisible();
  await doc.capture(
    'split-confirm',
    'The Split this transfer into two rows? window: both rows stay with their amounts and dates but come back with no category and no payee, and count as income and spending again. Cancel and Split buttons.',
  );
});
