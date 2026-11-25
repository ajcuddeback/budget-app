import { expect, test } from '../../helpers/doc-capture.js';
import { mockApi } from '../../helpers/mock-api.js';
import { DEMO_PERIOD_SUMMARY_SHORT } from '../../fixtures/demo-data.js';
import type { Page } from '@playwright/test';

/**
 * Screens for the slice 7 guides: see-whats-left-this-month, add-a-bill, mark-a-bill-paid and
 * add-your-income.
 *
 * The page opens on the CURRENT month and the mock projects each schedule into it, so the dates
 * in these shots move with the calendar while the cases stay put: a weekly bill with a row per
 * week, an overdue one, one paid for more than was budgeted, an irregular one and a retired one.
 */

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await mockApi(page, { session: 'owner' });
});

const bill = (page: Page, name: string) => page.locator('.bill').filter({ hasText: name });

const whatsLeft = (page: Page) =>
  page.locator('section').filter({ hasText: "What's left this month" }).first();

test('@doc the bills page, and the figure it leads with', async ({ page, doc }) => {
  doc.guide('see-whats-left-this-month');

  await page.goto('/bills');
  await expect(page.getByRole('heading', { name: 'Bills & income', level: 1 })).toBeVisible();

  await doc.capture(
    'page',
    'The Bills & income page: the month picker, what is left this month, income, bills and envelopes.',
    { fullPage: true },
  );

  await doc.capture(
    'whats-left',
    'The What’s left this month card: one figure, with income expected, bills this month, set aside in envelopes and still to pay beneath it.',
    { focus: whatsLeft(page) },
  );

  await doc.capture(
    'month',
    'The month picker at the top of the page, with Previous month and Next month either side of the month name.',
    { highlight: page.getByRole('navigation', { name: 'Choose a month' }), step: 2 },
  );

  await doc.capture(
    'envelopes',
    'The Envelopes card at the foot of the page: what is set aside this month, and an Open envelopes button.',
    { focus: page.locator('section').filter({ hasText: 'Set aside this month' }).first() },
  );

  // Slice 10 added a fourth term to the subtraction and this card beside it. Captured here
  // because the guide has to explain a figure that moved under a reader who saw it last month.
  await doc.capture(
    'owed',
    'The Owed card: owed in all, the minimums a month, how many debts, and the note saying which part of the minimums comes off what is left.',
    { focus: page.locator('section.card').filter({ hasText: 'Owed in all' }).first() },
  );
});

test('@doc the bills page in a month that does not add up', async ({ page, doc }) => {
  doc.guide('see-whats-left-this-month');

  // Registered after the mock, so it wins: the same household in a month where the income does
  // not cover the plan. A screen that clamped this to zero would hide exactly what matters.
  await page.route('**/api/periods/*/summary', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(DEMO_PERIOD_SUMMARY_SHORT),
    }),
  );

  await page.goto('/bills');
  await expect(page.getByText('short this month')).toBeVisible();
  await doc.capture(
    'short',
    'The same card showing a negative figure labelled short this month, with a note under it.',
    { focus: whatsLeft(page) },
  );
});

test('@doc the bills page as a viewer', async ({ page, doc }) => {
  doc.guide('see-whats-left-this-month');
  await mockApi(page, { session: 'viewer' });

  await page.goto('/bills');
  await expect(page.getByRole('heading', { name: 'Bills & income', level: 1 })).toBeVisible();
  await doc.capture(
    'viewer',
    'The page for someone whose role is Viewer: every figure and every bill, and nothing to click.',
    { fullPage: true },
  );
});

test('@doc adding a bill', async ({ page, doc }) => {
  doc.guide('add-a-bill');

  await page.goto('/bills');
  await doc.capture('bills-block', 'The Your bills card, with its totals and the Add a bill button.', {
    highlight: page.getByRole('button', { name: 'Add a bill' }),
    step: 2,
  });

  await page.getByRole('button', { name: 'Add a bill' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Name').fill('Season ticket');
  await dialog.getByLabel('Amount', { exact: true }).fill('94');
  await doc.capture(
    'form',
    'The Add a bill window with a name and an amount filled in, and When it is due beneath them.',
    { step: 3 },
  );

  const schedule = dialog.getByRole('group', { name: 'When it is due' });
  await dialog.getByLabel('How often').selectOption('MONTHLY');
  await dialog.getByLabel('Day of the month').selectOption('5');
  await doc.capture(
    'monthly',
    'When it is due set to Every month, with Day of the month set to 5 and a note about shorter months.',
    { highlight: schedule, step: 5 },
  );

  await dialog.getByLabel('How often').selectOption('FORTNIGHTLY');
  await dialog.getByLabel('Day of the week').selectOption('FRIDAY');
  await doc.capture(
    'fortnightly',
    'When it is due set to Every other week, showing Day of the week and a Counting from date.',
    { highlight: schedule },
  );

  await dialog.getByLabel('How often').selectOption('IRREGULAR');
  await doc.capture(
    'irregular',
    'When it is due set to Irregular, with a note that it has no set date and you record each payment as it happens.',
    { highlight: schedule },
  );

  await dialog.getByLabel('How often').selectOption('MONTHLY');
  await dialog.getByLabel('Day of the month').selectOption('5');
  await dialog.getByLabel('The amount varies').check();
  await doc.capture('varies', 'The amount varies ticked, with a Give or take box underneath it.', {
    highlight: dialog.getByLabel('Give or take'),
    step: 8,
  });
});

test('@doc retiring, restoring and deleting a bill', async ({ page, doc }) => {
  doc.guide('add-a-bill');

  await page.goto('/bills');
  const phone = bill(page, 'Phone');
  await doc.capture(
    'bill-actions',
    'The Phone bill, with Edit, Retire and Delete beside its name and budgeted amount.',
    { highlight: phone.getByRole('button', { name: 'Retire' }), step: 2 },
  );

  await phone.getByRole('button', { name: 'Retire' }).click();
  await doc.capture(
    'retire-confirm',
    'The Retire Phone? window, saying it stops being listed in new months and keeps what you have recorded.',
    { step: 3 },
  );
  await page.getByRole('button', { name: 'Cancel' }).click();

  const retired = page.locator('.row.retired').filter({ hasText: 'Old gym' });
  await doc.capture(
    'retired-list',
    'The Retired bills list at the foot of the Your bills card, with a Restore button.',
    { highlight: retired.getByRole('button', { name: 'Restore' }) },
  );

  await bill(page, 'Council tax').getByRole('button', { name: 'Delete' }).click();
  await doc.capture(
    'delete-confirm',
    'The Delete Council tax? window, saying your transactions are kept because a bill is a plan, not a payment.',
    {},
  );
});

test('@doc marking a bill paid', async ({ page, doc }) => {
  doc.guide('mark-a-bill-paid');

  await page.goto('/bills');
  const cleaner = bill(page, 'Cleaner');
  await doc.capture(
    'weekly',
    'The Cleaner bill: one row for each week of the month, each with its own date, status and Mark paid button.',
    { highlight: cleaner, step: 2 },
  );

  const third = cleaner.locator('.occurrence').nth(2);
  await doc.capture('mark-paid', 'The third week of the Cleaner bill, with its Mark paid button ringed.', {
    highlight: third.getByRole('button', { name: 'Mark paid' }),
    step: 3,
  });

  await third.getByRole('button', { name: 'Mark paid' }).click();
  await expect(page.getByText('Marked paid.')).toBeVisible();
  await doc.capture('banner', 'A banner at the top of the page reading Marked paid.', {
    focus: page.locator('.alert-success'),
  });
  await expect(cleaner.getByRole('button', { name: 'Mark paid' })).toHaveCount(1);
  await doc.capture(
    'marked',
    'The Cleaner bill with the third week now tagged Paid and You recorded this, and no Mark paid button left on that row.',
    { highlight: cleaner.locator('.occurrence').nth(2), step: 4 },
  );
});

test('@doc correcting what was recorded against a bill', async ({ page, doc }) => {
  doc.guide('mark-a-bill-paid');

  await page.goto('/bills');
  const cleaner = bill(page, 'Cleaner');
  const first = cleaner.locator('.occurrence').first();

  await doc.capture('correct-this', 'The first Cleaner row, with Correct this beneath it.', {
    highlight: first.getByText('Correct this'),
    step: 6,
  });

  await first.getByText('Correct this').click();
  await doc.capture(
    'correct-open',
    'Correct this opened, showing Paid a different amount, Skip, Mark as due and Forget.',
    {},
  );

  await first.getByRole('button', { name: 'Paid a different amount…' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Amount paid').fill('45.00');
  await doc.capture(
    'other-amount',
    'The What did you pay for Cleaner? window, with Amount paid and Date paid.',
    { step: 7 },
  );
  await dialog.getByRole('button', { name: 'Cancel' }).click();

  // Correct this is a <details> and stays open across the dialog, so clicking the summary again
  // would collapse it and hide Forget.
  await first.getByRole('button', { name: 'Forget' }).click();
  await doc.capture(
    'forget-confirm',
    'The Forget what was recorded? window, explaining it is not the same as marking the bill due.',
    {},
  );
});

test('@doc skipping a bill this month', async ({ page, doc }) => {
  doc.guide('mark-a-bill-paid');

  await page.goto('/bills');
  const cleaner = bill(page, 'Cleaner');
  const last = cleaner.locator('.occurrence').last();
  await last.getByText('Correct this').click();
  await last.getByRole('button', { name: 'Skip' }).click();
  await expect(page.getByText('Skipped for this month.')).toBeVisible();
  await doc.capture('skipped', 'The last row of the Cleaner bill, now tagged Skipped and You recorded this.', {
    highlight: cleaner.locator('.occurrence').last(),
  });
});

test('@doc a bill that is overdue, and one paid for more than budgeted', async ({ page, doc }) => {
  doc.guide('mark-a-bill-paid');

  await page.goto('/bills');
  await doc.capture('overdue', 'The Council tax bill, its row tagged Due and Overdue.', {
    focus: bill(page, 'Council tax'),
  });

  await doc.capture(
    'surprising',
    'The Phone bill row showing Budgeted and Paid side by side, tagged Not what was budgeted.',
    { focus: bill(page, 'Phone') },
  );

  await doc.capture('totals', 'The three totals above the bills list: bills this month, still to pay and overdue.', {
    focus: page.locator('section').filter({ hasText: 'Your bills' }).first().locator('.terms'),
  });

  const vet = bill(page, 'Vet');
  await doc.capture('irregular', 'The Vet bill, with no dates of its own and a Record a payment button.', {
    highlight: vet.getByRole('button', { name: 'Record a payment' }),
    step: 2,
  });

  await vet.getByRole('button', { name: 'Record a payment' }).click();
  await doc.capture(
    'irregular-form',
    'The Record a payment for Vet window, asking for the date it was due, the amount paid and the date paid.',
    { step: 3 },
  );
});

test('@doc adding an income source on the bills page', async ({ page, doc }) => {
  doc.guide('add-your-income');

  await page.goto('/bills');
  await doc.capture(
    'income-block',
    'The Income card: what is expected this month, each source, and the Add an income source button.',
    { highlight: page.getByRole('button', { name: 'Add an income source' }), step: 2 },
  );

  await page.getByRole('button', { name: 'Add an income source' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Name').fill('Pension');
  await dialog.getByLabel('Amount each time').fill('410');
  await dialog.getByLabel('How often').selectOption('MONTHLY');
  await dialog.getByLabel('Day of the month').selectOption('12');
  await doc.capture(
    'form',
    'The Add an income source window with a name, an amount each time, when it arrives and the account it lands in.',
    { step: 3 },
  );
  await dialog.getByRole('button', { name: 'Cancel' }).click();

  await doc.capture(
    'arrivals',
    'The Weekend work income source, showing how many arrivals the month has and what that comes to.',
    { focus: page.locator('.row').filter({ hasText: 'Weekend work' }).first() },
  );

  await doc.capture(
    'retired',
    'The Retired income sources list, with a Restore button beside the one that is retired.',
    { focus: page.locator('.row.retired').filter({ hasText: 'Old retainer' }) },
  );
});
