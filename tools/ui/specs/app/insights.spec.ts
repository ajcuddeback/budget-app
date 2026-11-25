import type { Page } from '@playwright/test';
import { mockApi } from '../../helpers/mock-api.js';
import { expect, test } from '../../helpers/ui-test.js';

/**
 * Insights: `docs/features/reporting.md`, UI section.
 *
 * Every figure on this page is a GENERATED report, summed by the real application from the demo
 * ledger (`DemoFixtureIT`). So this spec asserts **structure and rules**, not particular figures:
 *
 * - both cards, both viewports, light and dark, with axe clean and no overflow;
 * - **a real table behind the chart**, one row per month;
 * - **the two lines differ by dash and weight**, not colour alone — and a greyscale capture is taken
 *   so somebody reading the screenshots can check it with their own eyes;
 * - the change of each category **in words**;
 * - the empty state and the currency switcher, from households of their own;
 * - a `VIEWER` sees exactly what an owner sees.
 *
 * The browser's clock is the demo instant, 20 August 2026: the page builds its ranges from the
 * device's own date, and the generated reports are for exactly the ranges asked on that day. A
 * range nobody generated is a loud 501 from the mock, which `ui.noErrors()` fails on.
 */
const DEMO_NOW = new Date('2026-08-20T12:00:00Z');

async function open(page: Page): Promise<void> {
  await page.clock.setFixedTime(DEMO_NOW);
  await page.goto('/insights');
  await expect(page.getByRole('heading', { level: 1, name: 'Insights' })).toBeVisible();
}

const incomeCard = (page: Page) => page.locator('section[aria-labelledby="insights-income-heading"]');
const whereCard = (page: Page) => page.locator('section[aria-labelledby="insights-where-heading"]');

test.describe('insights, as an owner', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'owner' });
  });

  test('shows income against spending and where last month went', async ({ page, ui }) => {
    await open(page);

    await expect(page.getByRole('heading', { name: 'Income vs spending' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Where July 2026 went' })).toBeVisible();
    // The chart is readable without being seen.
    const chart = page.getByRole('img', { name: 'Income and spending by month' });
    await expect(chart).toBeVisible();
    await expect(page.locator('#insights-income-chart-description')).toContainText('Income:');
    await expect(page.locator('#insights-income-chart-description')).toContainText('Spending:');
    // Last month's rows, each with its change in words.
    await expect(whereCard(page).getByText('Groceries')).toBeVisible();
    await expect(whereCard(page).getByText(/down \d+%|up \d+%|flat/).first()).toBeVisible();
    await expect(whereCard(page).getByText('Archived')).toBeVisible();

    await ui.shot('insights');
    expect(await ui.a11y()).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('draws the two lines differently in dash and weight, not colour alone', async ({ page, ui }) => {
    await open(page);

    const lines = incomeCard(page).locator('polyline');
    await expect(lines).toHaveCount(2);
    const styles = await lines.evaluateAll((els) =>
      els.map((el) => {
        const style = getComputedStyle(el);
        return { dash: style.strokeDasharray, width: style.strokeWidth };
      }),
    );
    expect(styles[0].dash).toBe('none');
    expect(styles[1].dash).not.toBe('none');
    expect(styles[0].width).not.toBe(styles[1].width);

    // For the person reading the screenshots: the chart with colour removed. If the two lines
    // cannot be told apart in this image, WCAG 1.4.1 fails whatever the stylesheet says.
    await page.addStyleTag({ content: 'html { filter: grayscale(1); }' });
    await ui.shot('insights-greyscale');
    ui.noErrors();
  });

  test('has a real table behind the chart, one row per month', async ({ page, ui }) => {
    await open(page);

    await page.getByText('Show the figures').click();
    const table = incomeCard(page).getByRole('table');
    await expect(table).toBeVisible();
    await expect(table.locator('caption')).toContainText('in USD');
    await expect(table.locator('tbody tr')).toHaveCount(12);
    await expect(table.getByRole('rowheader', { name: /August 2026 \(so far\)/ })).toBeVisible();

    await ui.shot('insights-figures');
    expect(await ui.a11y()).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('re-asks for a shorter range when a chip is pressed', async ({ page, ui }) => {
    await open(page);

    await page.getByRole('button', { name: 'Last 3 months' }).click();
    await expect(page.getByRole('button', { name: 'Last 3 months' })).toHaveAttribute('aria-pressed', 'true');
    await page.getByText('Show the figures').click();
    await expect(incomeCard(page).locator('tbody tr')).toHaveCount(3);

    await ui.shot('insights-three-months');
    ui.noErrors();
  });

  test('steps between months, and opens a category to what is inside it', async ({ page, ui }) => {
    await open(page);

    await page.getByRole('button', { name: 'Previous month' }).click();
    await expect(page.getByRole('heading', { name: 'Where June 2026 went' })).toBeVisible();
    await page.getByRole('button', { name: 'Next month' }).click();
    await page.getByRole('button', { name: 'Next month' }).click();
    await expect(page.getByRole('heading', { name: 'Where August 2026 has gone so far' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Next month' })).toBeDisabled();
    await expect(whereCard(page).getByText(/Needs a category/)).toBeVisible();

    await page.getByRole('button', { name: "Show what's in Bills" }).click();
    await expect(whereCard(page).getByText('Rent')).toBeVisible();

    await ui.shot('insights-august-so-far');
    expect(await ui.a11y()).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('downloads the figures as a CSV file named from the periods only', async ({ page, ui }) => {
    await open(page);

    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: /Download CSV of income and spending/ }).click();
    expect((await download).suggestedFilename()).toBe('budget-owl-income-and-spending-2025-09-2026-08.csv');
    ui.noErrors();
  });

  test('points the "needs a category" link at the ledger’s queue', async ({ page, ui }) => {
    await open(page);

    await incomeCard(page).getByRole('link', { name: 'Give them a category' }).click();
    await expect(page).toHaveURL(/\/transactions\?filter=uncategorised$/);
    await expect(page.getByRole('button', { name: /Needs a category/ })).toHaveAttribute('aria-pressed', 'true');
    ui.noErrors();
  });
});

test.describe('insights, in the dark theme', () => {
  test('keeps both cards legible and the lines apart', async ({ page, ui }) => {
    await page.addInitScript(() => localStorage.setItem('budget-owl.theme', 'dark'));
    await mockApi(page, { session: 'owner' });
    await open(page);
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');

    await ui.shot('insights-dark');
    expect(await ui.a11y()).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });
});

test.describe('insights, as a viewer', () => {
  test('shows every figure an owner sees', async ({ page, ui }) => {
    await mockApi(page, { session: 'viewer' });
    await open(page);

    await expect(page.getByRole('heading', { name: 'Income vs spending' })).toBeVisible();
    await expect(page.getByRole('img', { name: 'Income and spending by month' })).toBeVisible();
    await expect(page.getByRole('button', { name: /Download CSV/ }).first()).toBeVisible();

    await ui.shot('insights-viewer');
    ui.noErrors();
  });
});

test.describe('insights, with nothing recorded', () => {
  test('says so, rather than drawing a flat line and a zero average', async ({ page, ui }) => {
    await mockApi(page, { session: 'owner', reports: 'empty' });
    await open(page);

    await expect(page.getByText('Nothing recorded between September 2025 and August 2026 yet.')).toBeVisible();
    await expect(page.getByText(/logged straight into an envelope/)).toBeVisible();
    await expect(page.getByRole('img', { name: 'Income and spending by month' })).toHaveCount(0);
    await expect(page.getByText('Avg kept')).toHaveCount(0);

    await ui.shot('insights-empty');
    expect(await ui.a11y()).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });
});

test.describe('insights, with two currencies', () => {
  test('switches between two complete reports and never combines them', async ({ page, ui }) => {
    await mockApi(page, { session: 'owner', reports: 'two-currencies' });
    await open(page);

    const usd = page.getByRole('button', { name: 'USD', exact: true });
    await expect(usd).toHaveAttribute('aria-pressed', 'true');
    await page.getByRole('button', { name: 'EUR', exact: true }).click();
    await expect(page.getByRole('button', { name: 'EUR', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('#insights-income-chart-description')).toContainText('€');

    await ui.shot('insights-two-currencies');
    expect(await ui.a11y()).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });
});
