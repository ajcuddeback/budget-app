import { expect, test } from '../../helpers/doc-capture.js';
import { mockApi } from '../../helpers/mock-api.js';

/**
 * Screens for the slice 14 guide: do-your-weekly-check-in.
 *
 * Every figure comes out of the GENERATED check-in fixtures (`check-in-api.generated.ts`), which
 * `DemoFixtureIT` builds by answering the canvas's own week against the real application: a $550
 * target, $487 spent, $63 under, $480 next week or $543 rolled over, and the emergency fund going
 * from $2,340 to $2,403. Each answer here is the one the generator gave, in its order; the mock
 * refuses anything else, so a capture cannot show a figure the application did not compute.
 */

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await mockApi(page, { session: 'owner' });
});

test('@doc the weekly check-in, start to finish', async ({ page, doc }) => {
  doc.guide('do-your-weekly-check-in');

  await page.goto('/transactions');
  const prompt = page.getByRole('complementary', { name: 'Weekly check-in' });
  await expect(prompt).toBeVisible();
  await doc.capture(
    'prompt',
    'The Weekly check-in card at the top of Transactions: due Sunday, 3 charges waiting on a category, with a Start now button.',
    { highlight: prompt, step: 1 },
  );

  await prompt.getByRole('link', { name: 'Start now' }).click();
  const start = page.getByRole('button', { name: 'Start check-in' });
  await expect(start).toBeVisible();
  await doc.capture(
    'not-started',
    'Check-in for week 34, not started yet, with the Start check-in button.',
    { highlight: start, step: 2 },
  );

  await start.click();
  await expect(page.getByText('In progress')).toBeVisible();
  const whatsNew = page.locator('#step-whats-new');
  await page.getByRole('button', { name: 'Yes, prepaid' }).click();
  await expect(page.getByRole('button', { name: 'Yes, prepaid' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await doc.capture(
    'whats-new',
    "Step 1, What's new: the paycheck from Northwind Systems landed as expected, and a $126.00 charge from Mountain Strength Co, three times the usual gym bill, answered Yes, prepaid.",
    { focus: whatsNew },
  );

  const categorize = page.locator('#step-categorize');
  const blueBottle = page.locator('li', { hasText: 'SQ *BLUE BOTTLE' });
  await blueBottle.getByRole('combobox').selectOption({ label: 'Eating Out' });
  await blueBottle.getByRole('checkbox', { name: 'Always file this payee here' }).check();
  await expect(blueBottle.getByRole('checkbox')).toBeChecked();
  await page
    .locator('li', { hasText: 'Whole Foods' })
    .getByRole('combobox')
    .selectOption({ label: 'Groceries' });
  await page
    .locator('li', { hasText: 'UBER *TRIP' })
    .getByRole('combobox')
    .selectOption({ label: 'Transport' });
  await expect(page.getByText('Spent $487.00 so far this week')).toBeVisible();
  await doc.capture(
    'categorize',
    'Step 2, Categorize: three charges given a category, Always file this payee here ticked for SQ *BLUE BOTTLE, and the week in one line: spent $487.00 so far.',
    { focus: categorize },
  );

  const bills = page.locator('#step-bills');
  await page.getByRole('button', { name: 'Yes, map it' }).click();
  await expect(page.getByText(/Attached to Internet/)).toBeVisible();
  await doc.capture(
    'bills',
    'Step 3, Bills: a repeating $71.99 from COMCAST XFINITY mapped to the Internet bill, then the table of this month’s bills and $640.99 still to leave this month.',
    { focus: bills },
  );

  const nextWeek = page.locator('#step-next-week');
  await page.getByRole('radio', { name: /Emergency fund/ }).check();
  await expect(page.getByRole('radio', { name: /Emergency fund/ })).toBeChecked();
  await doc.capture(
    'next-week',
    'Step 4, Next week: you came in $63.00 under, the surplus put towards Emergency fund ($2,340.00 → $2,403.00 of $6,000.00), and next week you can spend $480.00, about $68.57 a day.',
    { focus: nextWeek },
  );

  const finish = page.getByRole('button', { name: 'Finish check-in' });
  await doc.capture('finish', 'The Finish check-in button at the foot of Next week.', {
    highlight: finish,
    step: 3,
  });

  await finish.click();
  await expect(page.getByRole('heading', { name: 'Check-in done' })).toBeVisible();
  await doc.capture(
    'done',
    'Check-in done: filed 3 charges, remembered 1 payee, marked Gym prepaid, marked Internet paid and remembered the mapping, recorded $63.00 to Emergency fund, and next week $480.00.',
    { fullPage: true },
  );
});

test('@doc payee rules on the categories page', async ({ page, doc }) => {
  doc.guide('do-your-weekly-check-in');

  await page.goto('/categories');
  await page.getByRole('button', { name: /Show payee rules/ }).click();
  const rules = page.locator('section.card[aria-labelledby="payee-rules-heading"]');
  await expect(rules.getByText('files under Eating Out')).toBeVisible();
  await doc.capture(
    'payee-rules',
    'Payee rules on the Categories page: SQ *BLUE BOTTLE files under Eating Out, with a Remove button.',
    { focus: rules },
  );
});
