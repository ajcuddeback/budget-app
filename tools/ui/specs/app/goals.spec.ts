import { mockApi } from '../../helpers/mock-api.js';
import { expect, test } from '../../helpers/ui-test.js';

/**
 * The goals screens: `docs/features/goals.md`, UI section.
 *
 * Asserts **structure and rules rather than particular figures**: every amount and month comes from
 * `tools/ui/fixtures/goal-api.generated.ts`, which `DemoFixtureIT` generates from the real backend,
 * and pinning them here would make this spec a hand-written copy of arithmetic it does not do.
 * What it pins is what would otherwise render as a lie:
 *
 * - **"saved of target" is a sentence**, never the canvas's slash.
 * - **Every goal has its projection in words**, and none is blank.
 * - **The starting amount is labelled** and a withdrawal says so in words.
 * - **A `VIEWER` has no write control at all** — absent, not disabled.
 * - **The bar's dark-mode track** is the thing most likely to be wrong, so the dark theme is
 *   screenshotted at both viewports and read.
 */
test.describe('goals, as an owner', () => {
    test.beforeEach(async ({ page }) => {
        await mockApi(page, { session: 'owner' });
    });

    test('lists each goal with its bar, its figure and its projection in words', async ({
        page,
        ui,
    }) => {
        await page.goto('/goals');

        await expect(page.getByRole('heading', { level: 1, name: 'Goals' })).toBeVisible();
        await expect(page.getByText('Japan, November 2027')).toBeVisible();
        await expect(page.getByText(/\$890\.00 of \$3,500\.00/)).toBeVisible();
        await expect(page.getByText(/a month gets you there by November 2027/)).toBeVisible();
        await expect(page.getByText('Reached.')).toBeVisible();
        // A slash reads as a ratio; the row says "of".
        await expect(page.getByText(/\$890\.00 \/ \$3,500\.00/)).toHaveCount(0);
        // The nav carries Goals at both viewports (decision 8).
        await expect(page.getByRole('link', { name: 'Goals' })).toBeVisible();

        await ui.shot('goals-list');
        expect(await ui.a11y('goals, the list')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('keeps archived goals behind a toggle', async ({ page, ui }) => {
        await page.goto('/goals');
        await expect(page.getByText('Wedding gift')).toHaveCount(0);

        await page.getByRole('button', { name: 'Show archived goals' }).click();

        await expect(page.getByRole('heading', { name: 'Archived goals' })).toBeVisible();
        await expect(page.getByRole('link', { name: /Open.*Wedding gift/ })).toBeVisible();
        await ui.shot('goals-archived');
        expect(await ui.a11y('goals, archived shown')).toBe(0);
        ui.noErrors();
    });

    test('adds a goal with the server’s preview as the figures settle', async ({ page, ui }) => {
        await page.goto('/goals');
        await page.getByRole('button', { name: 'Add a goal' }).first().click();
        const dialog = page.getByRole('dialog', { name: 'Add a goal' });
        await expect(dialog).toBeVisible();

        await dialog.getByLabel('What it is for').fill('New bike');
        await dialog.getByLabel('How much it needs').fill('900');
        await expect(dialog.getByText('No monthly amount set.')).toBeVisible();
        await dialog.getByLabel('Month you want it by (optional)').fill('2027-06');
        await expect(dialog.getByText(/a month gets you there by June 2027/)).toBeVisible();
        await dialog.getByLabel('Each month (optional)').fill('75');
        await expect(dialog.getByText(/At \$75\.00 a month you reach it in/)).toBeVisible();

        await ui.shot('goals-add-dialog');
        expect(await ui.a11y('goals, the add dialog')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('shows one goal: progress, the plan with its scheduled step, and the history', async ({
        page,
        ui,
    }) => {
        await page.goto('/goals/gol-0001');

        await expect(page.getByRole('heading', { level: 1, name: 'Japan' })).toBeVisible();
        await expect(page.getByText('By November 2027', { exact: true })).toBeVisible();
        await expect(page.getByText(/a month from September 2026\./)).toBeVisible();
        await expect(page.getByRole('heading', { name: 'History' })).toBeVisible();
        await expect(page.getByRole('button', { name: 'Set money aside' })).toBeVisible();

        await ui.shot('goal-detail-japan');
        expect(await ui.a11y('one goal, Japan')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('labels the starting amount, the account’s balance and a withdrawal', async ({
        page,
        ui,
    }) => {
        await page.goto('/goals/gol-0002');

        await expect(page.getByText(/Already saved when you started tracking it/)).toBeVisible();
        await expect(page.getByText(/That is the account's balance, not this goal's\./)).toBeVisible();
        await expect(page.getByRole('row', { name: /Withdrawal/ })).toBeVisible();
        await expect(page.getByText(/There is no target month/)).toBeVisible();

        await ui.shot('goal-detail-fund');
        expect(await ui.a11y('one goal, the emergency fund')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('sets money aside, prefilled with what is still to come this month', async ({
        page,
        ui,
    }) => {
        await page.goto('/goals/gol-0001');
        await page.getByRole('button', { name: 'Set money aside' }).click();
        const dialog = page.getByRole('dialog', { name: 'Set money aside for Japan' });

        await expect(dialog.getByLabel('Amount')).toHaveValue('110');
        await expect(dialog.getByText(/still to come for August 2026/)).toBeVisible();
        await ui.shot('goal-set-aside');
        expect(await ui.a11y('goals, set money aside')).toBe(0);

        await dialog.getByRole('button', { name: 'Set aside' }).click();
        await expect(page.getByText('Money set aside.')).toBeVisible();
        ui.noErrors();
    });

    test('takes money out through its own dialog', async ({ page, ui }) => {
        await page.goto('/goals/gol-0002');
        await page.getByRole('button', { name: 'Take money out' }).click();
        const dialog = page.getByRole('dialog', { name: 'Take money out of Emergency fund' });

        await expect(dialog.getByLabel('Amount')).toHaveValue('');
        await dialog.getByLabel('Amount').fill('250');
        await ui.shot('goal-take-out');
        expect(await ui.a11y('goals, take money out')).toBe(0);
        ui.noErrors();
    });

    test('changes the plan with the server’s preview, from this month or the next', async ({
        page,
        ui,
    }) => {
        await page.goto('/goals/gol-0001');
        await page.getByRole('button', { name: 'Change' }).click();
        const dialog = page.getByRole('dialog', { name: 'Change the plan for Japan' });

        await expect(dialog.getByLabel('Each month')).toHaveValue('110');
        await expect(dialog.getByText(/At \$110\.00 a month you reach it in/)).toBeVisible();
        await dialog.getByLabel('Each month').fill('165');
        await expect(dialog.getByText(/At \$165\.00 a month you reach it in/)).toBeVisible();
        await ui.shot('goal-plan-dialog');
        expect(await ui.a11y('goals, change the plan')).toBe(0);

        await dialog.getByLabel(/Next month/).check();
        await expect(dialog.getByText(/stays as it is/)).toBeVisible();
        ui.noErrors();
    });

    test('edits a goal with the server’s preview', async ({ page, ui }) => {
        await page.goto('/goals/gol-0001');
        await page.getByRole('button', { name: 'Edit' }).click();
        const dialog = page.getByRole('dialog', { name: 'Edit goal' });

        await expect(dialog.getByLabel('How much it needs')).toHaveValue('3500');
        await expect(dialog.getByText(/a month gets you there by November 2027/)).toBeVisible();
        await dialog.getByLabel('How much it needs').fill('4000');
        await expect(dialog.getByText(/\$194\.38 a month gets you there/)).toBeVisible();
        await ui.shot('goal-edit-dialog');
        expect(await ui.a11y('goals, edit')).toBe(0);
        ui.noErrors();
    });

    test('shows the goals term on Bills & income', async ({ page, ui }) => {
        await page.goto('/bills');

        const left = page.getByRole('region', { name: "What's left this month" });
        await expect(left.getByText('Goals', { exact: true })).toBeVisible();
        await expect(left.getByText(/Goals count what you plan to set aside/)).toBeVisible();
        await ui.shot('bills-goals-term');
        ui.noErrors();
    });
});

test.describe('goals, as a viewer', () => {
    test.beforeEach(async ({ page }) => {
        await mockApi(page, { session: 'viewer' });
    });

    test('sees every figure and not one control', async ({ page, ui }) => {
        await page.goto('/goals');
        await expect(page.getByText(/\$890\.00 of \$3,500\.00/)).toBeVisible();
        await expect(page.getByRole('button', { name: 'Add a goal' })).toHaveCount(0);

        await page.goto('/goals/gol-0002');
        await expect(page.getByRole('heading', { name: 'History' })).toBeVisible();
        for (const name of ['Set money aside', 'Take money out', 'Edit', 'Change', 'Archive']) {
            await expect(page.getByRole('button', { name })).toHaveCount(0);
        }
        await ui.shot('goal-detail-viewer');
        expect(await ui.a11y('one goal, as a viewer')).toBe(0);
        ui.noErrors();
    });
});

test.describe('goals, in the dark theme', () => {
    test.beforeEach(async ({ page }) => {
        await mockApi(page, { session: 'owner' });
        await page.addInitScript(() => localStorage.setItem('budget-owl.theme', 'dark'));
    });

    test('the bars, their tracks and the figures stay legible', async ({ page, ui }) => {
        await page.goto('/goals');
        await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
        await expect(page.getByText('Japan, November 2027')).toBeVisible();
        await ui.shot('goals-list-dark');
        expect(await ui.a11y('goals, list in dark')).toBe(0);
        await ui.noOverflow();

        await page.goto('/goals/gol-0002');
        await expect(page.getByRole('row', { name: /Withdrawal/ })).toBeVisible();
        await ui.shot('goal-detail-dark');
        expect(await ui.a11y('one goal in dark')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });
});
