import { mockApi } from '../../helpers/mock-api.js';
import { expect, test } from '../../helpers/ui-test.js';

/**
 * The weekly check-in: `docs/features/weekly-check-in.md`, UI section.
 *
 * Every figure comes from `tools/ui/fixtures/check-in-api.generated.ts`, which `DemoFixtureIT`
 * generates from the real backend by answering the canvas's own week. The canvas figures reconcile
 * (550 − 487 = 63, 480 + 63 = 543, 2,340 + 63 = 2,403), so this spec pins them: if the page shows
 * anything else, it is showing a number the application did not compute.
 *
 * What it also pins:
 *
 * - **Nothing is preselected in step 4**, although the canvas draws a tick.
 * - **The figure is the server's after every answer**, in a live region.
 * - **A phone shows one step at a time**, each ending by naming the next.
 * - **A `VIEWER` has no write control at all.**
 * - **Payees render in their own `dir="auto"` element.**
 */
test.describe('the weekly check-in, as an owner', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'owner' });
  });

  test('opens the current week, not started, with the items it would ask', async ({
    page,
    ui,
  }) => {
    await page.goto('/check-in');

    await expect(
      page.getByRole('heading', { level: 1, name: 'Check-in for week 34' }),
    ).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Not started yet' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Start check-in' })).toBeVisible();
    // Not started: the items are shown, and nothing can be answered until it is.
    await expect(page.getByRole('button', { name: 'Leave it' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Check-in' })).toBeVisible();

    await ui.shot('check-in-not-started');
    expect(await ui.a11y('check-in, not started')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('answers the canvas week and the figure settles to the canvas’s own', async ({
    page,
    ui,
    viewport,
  }) => {
    const phone = (viewport?.width ?? 1280) < 640;
    await page.goto('/check-in');
    await page.getByRole('button', { name: 'Start check-in' }).click();
    await expect(page.getByText('In progress')).toBeVisible();
    await expect(page.getByText('Your answers are kept as you go, on any device.')).toBeVisible();

    // Step 1: the gym charge three times its usual size.
    await expect(page.getByText('Mountain Strength Co')).toHaveAttribute('dir', 'auto');
    await page.getByRole('button', { name: 'Yes, prepaid' }).click();
    await expect(page.getByRole('button', { name: 'Yes, prepaid' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    if (phone) {
      await page.getByRole('button', { name: 'Next: categorize' }).click();
    }

    // Step 2, in the generator's order: Blue Bottle remembered, Whole Foods, the ride.
    const blueBottle = page.locator('li', { hasText: 'SQ *BLUE BOTTLE' });
    await blueBottle.getByRole('combobox').selectOption({ label: 'Eating Out' });
    await expect(blueBottle.getByRole('checkbox')).toBeEnabled();
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
    if (phone) {
      await ui.shot('check-in-phone-categorize');
      await page.getByRole('button', { name: 'Next: bills' }).click();
    }

    // Step 3: the repeating Comcast charge, mapped to Internet.
    await expect(page.getByText(/A repeating \$71\.99 has hit Chase •4412/)).toBeVisible();
    await page.getByRole('button', { name: 'Yes, map it' }).click();
    await expect(page.getByText(/Attached to Internet/)).toBeVisible();
    await expect(page.getByRole('table')).toBeVisible();
    if (phone) {
      await page.getByRole('button', { name: 'Next: next week' }).click();
    }

    // Step 4: $63 under, nothing preselected.
    await expect(page.getByText('You came in $63.00 under')).toBeVisible();
    const radios = page.getByRole('radio');
    await expect(radios).toHaveCount(3);
    for (const radio of await radios.all()) {
      await expect(radio).not.toBeChecked();
    }
    const figure = page.locator('[aria-live="polite"]');
    await expect(figure).toContainText('$480.00');
    await expect(figure).toContainText('≈ $68.57/day');
    await expect(page.getByText('Emergency fund · $2,340.00 → $2,403.00 of $6,000.00')).toBeVisible();
    await expect(page.getByText('Weekly target becomes $543.00.')).toBeVisible();
    await expect(page.getByText(/Budget Owl never moves money/).first()).toBeVisible();

    await page.getByRole('radio', { name: /Emergency fund/ }).check();
    await expect(page.getByRole('radio', { name: /Emergency fund/ })).toBeChecked();
    await expect(figure).toContainText('$480.00');

    await ui.shot('check-in-answered');
    expect(await ui.a11y('check-in, every step answered')).toBe(0);
    await ui.noOverflow();

    await page
      .getByRole('button', { name: phone ? 'Done for the week' : 'Finish check-in' })
      .click();
    await expect(page.getByRole('heading', { name: 'Check-in done' })).toBeVisible();
    await expect(page.getByText('Filed 3 charges.')).toBeVisible();
    await expect(page.getByText('Marked Internet paid and remembered the mapping.')).toBeVisible();
    await expect(page.getByText('Recorded $63.00 to Emergency fund.')).toBeVisible();
    await expect(page.getByText('Next week: $480.00.')).toBeVisible();

    await ui.shot('check-in-completed');
    expect(await ui.a11y('check-in, completed')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('shows every step on one page on a wide screen, one per screen on a phone', async ({
    page,
    ui,
    viewport,
  }) => {
    const phone = (viewport?.width ?? 1280) < 640;
    await page.goto('/check-in');
    await page.getByRole('button', { name: 'Start check-in' }).click();

    const sections = page.locator('section.step');
    await expect(sections).toHaveCount(4);
    if (phone) {
      await expect(page.locator('section.step:visible')).toHaveCount(1);
      await expect(page.getByRole('progressbar')).toBeVisible();
      await expect(page.getByRole('button', { name: 'Next: categorize' })).toBeVisible();
    } else {
      await expect(page.locator('section.step:visible')).toHaveCount(4);
      await expect(page.getByRole('navigation', { name: 'Steps' })).toBeVisible();
    }
    await ui.shot('check-in-draft');
    expect(await ui.a11y('check-in, a fresh draft')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('puts the check-in prompt at the top of the ledger', async ({ page, ui }) => {
    await page.goto('/transactions');
    const prompt = page.getByRole('complementary', { name: 'Weekly check-in' });
    await expect(prompt).toBeVisible();
    await expect(prompt).toContainText('3 charges waiting on a category.');
    await prompt.getByRole('link', { name: 'Start now' }).click();
    await expect(page).toHaveURL(/\/check-in$/);
    ui.noErrors();
  });

  test('lists payee rules on the categories page and offers Remove', async ({ page, ui }) => {
    await page.goto('/categories');
    await page.getByRole('button', { name: /Show payee rules/ }).click();
    await expect(page.getByText('files under Eating Out')).toBeVisible();
    await expect(page.getByRole('button', { name: /Remove/ })).toBeVisible();
    await ui.shot('categories-payee-rules');
    expect(await ui.a11y('categories, payee rules')).toBe(0);
    ui.noErrors();
  });
});

test.describe('the weekly check-in, as a viewer', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'viewer' });
  });

  test('reads a draft with every answer and figure and no control', async ({ page, ui }) => {
    await page.goto('/check-in/2026-W34');
    await expect(page.getByText('In progress')).toBeVisible();
    await expect(page.getByText('You can read this check-in.')).toBeVisible();
    await expect(page.locator('[aria-live="polite"]')).toContainText('$480.00');
    for (const name of ['Start check-in', 'Finish check-in', 'Discard this draft', 'Leave it']) {
      await expect(page.getByRole('button', { name })).toHaveCount(0);
    }
    await expect(page.getByRole('combobox')).toHaveCount(0);
    for (const radio of await page.getByRole('radio').all()) {
      await expect(radio).toBeDisabled();
    }
    await ui.shot('check-in-viewer');
    expect(await ui.a11y('check-in, as a viewer')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('sees payee rules with no Remove', async ({ page, ui }) => {
    await page.goto('/categories');
    await page.getByRole('button', { name: /Show payee rules/ }).click();
    await expect(page.getByText('files under Eating Out')).toBeVisible();
    await expect(page.getByRole('button', { name: /^Remove/ })).toHaveCount(0);
    ui.noErrors();
  });
});

test.describe('the weekly check-in, in the dark theme', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'owner' });
    await page.addInitScript(() => localStorage.setItem('budget-owl.theme', 'dark'));
  });

  test('the steps, the choices and the figure stay legible', async ({ page, ui }) => {
    await page.goto('/check-in');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await page.getByRole('button', { name: 'Start check-in' }).click();
    await page.getByRole('button', { name: 'Yes, prepaid' }).click();
    await expect(page.getByText('In progress')).toBeVisible();
    await ui.shot('check-in-draft-dark');
    expect(await ui.a11y('check-in, a draft in dark')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });
});
