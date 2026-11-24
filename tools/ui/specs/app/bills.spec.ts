import { mockApi } from '../../helpers/mock-api.js';
import { DEMO_INCOME, DEMO_PERIOD_SUMMARY_SHORT } from '../../fixtures/demo-data.js';
import { expect, test } from '../../helpers/ui-test.js';

/**
 * The Bills & Income screen: `docs/features/bills-and-income.md`, UI section.
 *
 * Five of these are the feature's rules rather than UI taste, and are here so a later redesign
 * cannot quietly drop them:
 *
 * - **What is LEFT is the headline**, with its three terms beneath it. That one subtraction is the
 *   only reason the page exists.
 * - **A short month shows a negative figure.** Clamping it would hide exactly what somebody needs
 *   to know in the month they most need to know it.
 * - **A weekly bill is one row per payment**, each independently markable. A screen that rendered
 *   one row per bill would let a household tick off one Friday out of four and nothing else.
 * - **A bill paid for other than its planned amount shows both figures.** The difference is the
 *   useful number (ADR-0025) and one figure cannot carry it.
 * - **Overdue is shown without a job having run** (ADR-0032), so a marked-up date is enough.
 */

/**
 * The same month, for a household whose income does not cover its plan.
 *
 * <b>Both routes, not just the summary.</b> Overriding the headline alone produced a capture
 * saying "Income expected $1,800" above an Income block totalling $3,640 — a screenshot that
 * contradicts itself, which is a poor thing to leave in a repository whose user guide is captured
 * from these runs (ADR-0012).
 */
async function beShort(page: import('@playwright/test').Page): Promise<void> {
  await page.route('**/api/periods/*/summary', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(DEMO_PERIOD_SUMMARY_SHORT),
    }),
  );
  // Served outright rather than rewritten from the mock's own answer: the mock already holds
  // every /api/** route, so fetching through it returns nothing to rewrite.
  await page.route('**/api/income?*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        content: [
          {
            ...DEMO_INCOME[0],
            amount: DEMO_PERIOD_SUMMARY_SHORT.income,
            expectedForPeriod: DEMO_PERIOD_SUMMARY_SHORT.income,
          },
        ],
        total: DEMO_PERIOD_SUMMARY_SHORT.income,
        currency: 'USD',
      }),
    }),
  );
}

/** One bill's card, by name — the bills block is a list, and each bill is an item in it. */
const billNamed = (page: import('@playwright/test').Page, name: string) =>
  page
    .getByRole('region', { name: 'Your bills' })
    .getByRole('listitem')
    .filter({ hasText: name })
    .first();

test.describe('bills and income, as an owner', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'owner' });
  });

  test('leads with what is left, and shows the three terms under it', async ({ page, ui }) => {
    await page.goto('/bills');

    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();

    // 3,640 income − 2,301.10 bills − 700 set aside − 303 of debt minimums = 335.90. The fourth
    // term is new (debt-plan.md, decision 2) and it changed a figure that had already shipped:
    // only the minimums NOT already carried by a bill row come off, so the car loan's 342 is in
    // the bills term and not here, and nothing is counted twice. Every figure derives from the
    // same fixtures the blocks below render, so a headline disagreeing with them is a real defect.
    const whatsLeft = page.getByRole('region', { name: /What's left/ });
    await expect(whatsLeft).toContainText('$335.90');

    await expect(whatsLeft).toContainText('$3,640.00');
    await expect(whatsLeft).toContainText('$2,301.10');
    await expect(whatsLeft).toContainText('$700.00');
    await expect(whatsLeft).toContainText('$303.00');
    // The secondary line, not the headline.
    await expect(whatsLeft).toContainText('$608.00');

    await ui.shot('bills-overview');
    expect(await ui.a11y('bills and income, populated')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('shows a short month as negative rather than clamping it to zero', async ({ page, ui }) => {
    // The same month for a household whose income does not cover its plan.
    await beShort(page);
    await page.goto('/bills');

    const whatsLeft = page.getByRole('region', { name: /What's left/ });
    await expect(whatsLeft).toContainText('-$1,504.10');
    await expect(whatsLeft).toContainText(/short/i);

    await ui.shot('bills-short-month');
    expect(await ui.a11y('bills and income, a short month')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('gives a weekly bill one row per payment, each markable on its own', async ({
    page,
    ui,
  }) => {
    await page.goto('/bills');

    const cleaner = billNamed(page, 'Cleaner');
    // Four Fridays in August 2026: the 7th, 14th, 21st and 28th.
    await expect(cleaner.getByRole('listitem')).toHaveCount(4);
    // Two paid, two still to pay — and the ones left are the ones that have not happened.
    await expect(cleaner.getByRole('button', { name: /Mark paid/ })).toHaveCount(2);

    await ui.shot('bills-weekly-occurrences');
    expect(await ui.a11y('a weekly bill')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('shows both figures when a bill was paid for other than its plan', async ({ page, ui }) => {
    await page.goto('/bills');

    const phone = billNamed(page, 'Phone');
    // $163.10 paid against a $120 plan that allows $22 of variation, so the app says so rather
    // than letting it pass as ordinary.
    await expect(phone).toContainText('$163.10');
    await expect(phone).toContainText('$120.00');

    await ui.shot('bills-paid-a-surprise');
    ui.noErrors();
  });

  test('says a bill is overdue without anything having swept it', async ({ page, ui }) => {
    await page.goto('/bills');

    const council = billNamed(page, 'Council tax');
    await expect(council.getByRole('listitem')).toHaveCount(1);
    await expect(council).toContainText(/Overdue/i);

    await ui.shot('bills-overdue');
    ui.noErrors();
  });

  test('lists a bill that falls in no part of this month, and says why', async ({ page, ui }) => {
    await page.goto('/bills');

    // An irregular bill projects nothing by definition, and a yearly one is simply elsewhere.
    // Both are still bills the household has, so hiding them would read as having lost them.
    await expect(billNamed(page, 'Vet')).toBeVisible();
    await expect(billNamed(page, 'Car insurance')).toBeVisible();

    await ui.shot('bills-nothing-this-month');
    ui.noErrors();
  });

  test('marks one occurrence paid and leaves the others alone', async ({ page, ui }) => {
    const calls = await mockApi(page, { session: 'owner' });
    await page.goto('/bills');

    const cleaner = billNamed(page, 'Cleaner');
    // The first row that still has a "Mark paid" on it: the first Friday nobody has settled.
    const row = cleaner.getByRole('listitem').filter({ hasText: 'Mark paid' }).first();
    const shown = await row.locator('.occurrence-date').innerText();
    await row.getByRole('button', { name: /Mark paid/ }).click();

    await expect
      .poll(() => calls.some((call) => call.method === 'PUT' && call.path.includes('/occurrences/')))
      .toBe(true);
    const recorded = calls.filter((call) => call.method === 'PUT');
    expect(recorded).toHaveLength(1);
    // The date on the row that was tapped — not the bill, and not the month. Read from the DOM
    // rather than hard-coded, so this asserts the mapping rather than a date in a fixture.
    const day = Number(/\b(\d{1,2})\b,/.exec(shown)?.[1] ?? /(\d{1,2})/.exec(shown)?.[1]);
    expect(recorded[0].path).toMatch(new RegExp(`/occurrences/\\d{4}-\\d{2}-0?${day}$`));

    await ui.shot('bills-marked-paid');
    ui.noErrors();
  });

  test('shows income as what the month actually holds, not a monthly average', async ({
    page,
    ui,
  }) => {
    await page.goto('/bills');

    const weekend = page
      .getByRole('region', { name: 'Income' })
      .getByRole('listitem')
      .filter({ hasText: 'Weekend work' });
    // $220 twice is $440 in August. A screen that showed $220 would be describing a different
    // month from the one the household is in.
    await expect(weekend).toContainText('$440.00');

    await ui.shot('bills-income');
    expect(await ui.a11y('the income block')).toBe(0);
    ui.noErrors();
  });

  test('links to the envelopes screen rather than rebuilding it', async ({ page, ui }) => {
    await page.goto('/bills');

    const envelopes = page.getByRole('region', { name: /Envelopes/ });
    await expect(envelopes).toContainText('$700.00');
    await expect(envelopes.getByRole('link')).toHaveAttribute('href', '/envelopes');

    await ui.shot('bills-envelopes-block');
    ui.noErrors();
  });

  test('adds a bill without asking for anything the schedule does not use', async ({
    page,
    ui,
  }) => {
    await page.goto('/bills');

    await page.getByRole('button', { name: /Add a bill|Add your first bill/ }).first().click();
    const dialog = page.getByRole('dialog');
    // Exact, because getByLabel matches a substring case-insensitively and would otherwise also
    // find the "The amount varies" checkbox — which has no inputmode and never would.
    const amount = dialog.getByLabel('Amount', { exact: true });
    await expect(amount).toHaveAttribute('inputmode', 'decimal');
    // Deliberately not type="number": that hands back a double (ADR-0006).
    await expect(amount).toHaveAttribute('type', 'text');

    // A monthly bill asks for a day of the month and for nothing a monthly bill cannot use:
    // sending a weekday with it is a 400, and a form that collected one would be unusable.
    await expect(dialog.getByLabel(/Day of the month/i)).toBeVisible();
    await expect(dialog.getByLabel(/Weekday/i)).toHaveCount(0);

    await ui.shot('bills-add-dialog');
    expect(await ui.a11y('the add-a-bill dialog')).toBe(0);
    ui.noErrors();
  });
});

test.describe('bills and income, as a viewer', () => {
  test('shows every figure and offers nothing to change', async ({ page, ui }) => {
    await mockApi(page, { session: 'viewer' });
    await page.goto('/bills');

    // A VIEWER reads a household's whole financial plan. It must not be able to alter one figure.
    await expect(page.locator('body')).toContainText('$335.90');
    await expect(page.getByRole('button', { name: /Mark paid/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Add a bill/ })).toHaveCount(0);
    // Owed is a balance and reads the same for everybody; it is not part of the subtraction.
    await expect(page.getByRole('region', { name: 'Owed' })).toContainText('$26,480.00');

    await ui.shot('bills-viewer');
    expect(await ui.a11y('bills and income, as a viewer')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });
});

test.describe('bills and income, in the dark theme', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('budget-owl.theme', 'dark'));
    await mockApi(page, { session: 'owner' });
  });

  test('reads correctly in the dark, including the figures that mean trouble', async ({
    page,
    ui,
  }) => {
    await page.goto('/bills');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');

    // The two places colour carries meaning. Both are marked by words as well — "Overdue", and
    // the sign — but a colour that contradicted them would still be worse than no colour.
    await expect(page.getByRole('region', { name: 'Your bills' })).toContainText(/Overdue/i);

    await ui.shot('bills-overview-dark');
    expect(await ui.a11y('bills and income, dark')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('shows a short month in the dark without the colour reading as success', async ({
    page,
    ui,
  }) => {
    await beShort(page);
    await page.goto('/bills');

    const whatsLeft = page.getByRole('region', { name: /What's left/ });
    await expect(whatsLeft).toContainText('-$1,504.10');

    await ui.shot('bills-short-month-dark');
    expect(await ui.a11y('bills and income, a short month in dark')).toBe(0);
    ui.noErrors();
  });
});
