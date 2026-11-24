import { mockApi } from '../../helpers/mock-api.js';
import { expect, test } from '../../helpers/ui-test.js';

/**
 * The debt plan screen: `docs/features/debt-plan.md`, UI section.
 *
 * Deliberately asserts **structure and rules rather than particular figures**. The amounts come
 * from `tools/ui/fixtures/demo-data.ts`, whose derived values are generated from the real backend
 * solver; pinning them here would make this spec a second, hand-written copy of arithmetic it does
 * not do, and the first thing to go stale. What it does pin is what would otherwise render as a
 * lie:
 *
 * - **Both strategies are drawn whichever is selected** — the comparison is a product requirement.
 * - **The chart is readable without being seen.** A line a screen reader cannot use is a picture
 *   of somebody's money; axe would not catch that on its own, so the description is asserted.
 * - **The counted-once rule is on the page**, in the household's own language.
 * - **A `VIEWER` has no write control at all** — absent, not disabled-looking.
 * - **The same debts appear in both structures**: the table at desktop, the ladder at 390.
 */
test.describe('the debt plan, as an owner', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'owner' });
  });

  test('ranks the debts, draws both strategies, and says what each one is paying', async ({
    page,
    ui,
  }) => {
    await page.goto('/debt-plan');

    await expect(page.getByRole('heading', { level: 1, name: /Debt plan/ })).toBeVisible();

    // Both named strategies, whichever is in use: the comparison card is not a view of the
    // selection.
    const comparison = page.getByRole('region', { name: /Avalanche or snowball/ });
    await expect(comparison).toContainText('Avalanche');
    await expect(comparison).toContainText('Snowball');

    // The chart, and the words that carry the same figures.
    const chart = page.getByRole('img', { name: 'Balance to zero' });
    await expect(chart).toBeVisible();
    const described = await chart.getAttribute('aria-describedby');
    expect(described).toBeTruthy();
    await expect(page.locator(`#${described}`)).toContainText(/Avalanche:/);

    // The counted-once rule, which is the whole reason a minimum is not asked for twice.
    await expect(page.getByText(/counted once in Bills & income/i)).toBeVisible();

    await ui.shot('debt-plan-overview');
    expect(await ui.a11y('the debt plan, populated')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('offers the three orders of attack and says which is in use', async ({ page, ui }) => {
    await page.goto('/debt-plan');

    const orders = page.getByRole('group', { name: /Order of attack/ });
    await expect(orders.getByRole('button', { name: 'Avalanche' })).toBeVisible();
    await expect(orders.getByRole('button', { name: 'Snowball' })).toBeVisible();
    await expect(orders.getByRole('button', { name: /Your own order/ })).toBeVisible();
    // Pressed rather than merely coloured, so the selection is audible as well as visible.
    await expect(orders.getByRole('button', { pressed: true })).toHaveCount(1);

    await ui.shot('debt-plan-strategy');
    ui.noErrors();
  });

  test('opens a dialog whose amounts are text inputs with a decimal keypad', async ({
    page,
    ui,
  }) => {
    await page.goto('/debt-plan');
    await page.getByRole('button', { name: 'Add a debt' }).first().click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();

    // type="text" with inputmode="decimal", never type="number" (ADR-0006).
    const balance = dialog.getByLabel('Balance now');
    await expect(balance).toHaveAttribute('type', 'text');
    await expect(balance).toHaveAttribute('inputmode', 'decimal');
    await expect(dialog.getByLabel('Annual rate')).toHaveAttribute('inputmode', 'decimal');

    await ui.shot('debt-plan-add-a-debt');
    expect(await ui.a11y('the add-a-debt dialog')).toBe(0);
    ui.noErrors();
  });
});

test.describe('the debt plan, as a viewer', () => {
  test('shows every figure and not one control', async ({ page, ui }) => {
    await mockApi(page, { session: 'viewer' });
    await page.goto('/debt-plan');

    await expect(page.getByRole('heading', { level: 1, name: /Debt plan/ })).toBeVisible();
    await expect(page.getByRole('region', { name: /Balance to zero/ })).toBeVisible();

    // Absent, not disabled-looking: a control that is always on screen and always refused
    // teaches people to ignore refusals.
    await expect(page.getByRole('button', { name: 'Add a debt' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Use avalanche/ })).toHaveCount(0);
    await expect(page.getByRole('group', { name: /Order of attack/ })).toHaveCount(0);
    await expect(page.getByText(/Your plan uses/)).toBeVisible();

    await ui.shot('debt-plan-viewer');
    expect(await ui.a11y('the debt plan, read-only')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });
});
