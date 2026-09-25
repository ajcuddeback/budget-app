import { expect, test } from '../../helpers/ui-test.js';

/**
 * Slice 1 has no features, so what there is to validate is the shell: that the design tokens
 * actually reach the page, that dark mode works rather than merely existing, and that the layout
 * survives a phone.
 */
test.describe('app shell', () => {
    test('renders in the light theme', async ({ page, ui }) => {
        await page.goto('/');

        await expect(page.getByRole('heading', { name: 'Budget Owl', level: 1 })).toBeVisible();
        await expect(page.getByRole('heading', { name: 'Nothing to budget yet' })).toBeVisible();

        await ui.shot('shell-light');

        expect(await ui.a11y('app shell, light')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('the tokens reach the page rather than sitting in a file', async ({ page }) => {
        await page.goto('/');

        // The failure this catches is a stylesheet that never loaded: the page would still render,
        // just unstyled and with the browser default background, and every other assertion here
        // would still pass.
        const ground = await page.evaluate(() =>
            getComputedStyle(document.body).backgroundColor,
        );
        expect(ground).toBe('rgb(245, 234, 216)'); // --color-bg, the warm cream

        const heading = await page.evaluate(() => {
            const h1 = document.querySelector('h1');
            return h1 ? getComputedStyle(h1).fontFamily : '';
        });
        expect(heading).toContain('Caprasimo');
    });

    test('switches to dark and stays legible', async ({ page, ui }) => {
        await page.goto('/');
        await page.getByRole('button', { name: /dark theme/i }).click();

        await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');

        const ground = await page.evaluate(() =>
            getComputedStyle(document.body).backgroundColor,
        );
        expect(ground).toBe('rgb(26, 22, 19)'); // #1a1613 — warm near-black, never grey

        await ui.shot('shell-dark');

        // Contrast is checked here too: a dark theme that passes in light and fails in dark is
        // the normal way dark mode ships broken.
        expect(await ui.a11y('app shell, dark')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('remembers the choice across a reload', async ({ page }) => {
        await page.goto('/');
        await page.getByRole('button', { name: /dark theme/i }).click();
        await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');

        await page.reload();

        await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    });
});
