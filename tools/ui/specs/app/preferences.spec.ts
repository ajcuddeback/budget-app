import { mockApi } from '../../helpers/mock-api.js';
import { expect, test } from '../../helpers/ui-test.js';

test.describe('own preferences', () => {
    test('chooses a display currency and a locale, and saves them', async ({ page, ui }) => {
        const calls = await mockApi(page, { session: 'owner' });
        await page.goto('/preferences');

        await expect(page.getByRole('heading', { name: 'Your preferences', level: 1 })).toBeVisible();
        await ui.shot('preferences-default');
        expect(await ui.a11y('preferences, default')).toBe(0);

        await page.getByLabel('Show amounts in').selectOption('EUR');
        await page.getByLabel('Language and number format').selectOption('de-DE');
        await page.getByRole('button', { name: 'Save' }).click();

        await expect(page.getByText('Preferences saved.')).toBeVisible();
        const patch = calls.find((c) => c.method === 'PATCH' && c.path.endsWith('/members/me'));
        expect(patch?.body).toEqual({ displayCurrency: 'EUR', locale: 'de-DE' });
        await ui.shot('preferences-saved');
        await ui.noOverflow();
        ui.noErrors();
    });

    test('"follow the default" is sent as null, which is a meaning rather than a gap', async ({ page }) => {
        const calls = await mockApi(page, { session: 'member' });
        await page.goto('/preferences');
        await expect(page.getByLabel('Show amounts in')).toHaveValue('EUR');

        await page.getByLabel('Show amounts in').selectOption('');
        await page.getByLabel('Language and number format').selectOption('');
        await page.getByRole('button', { name: 'Save' }).click();

        await expect(page.getByText('Preferences saved.')).toBeVisible();
        const patch = calls.find((c) => c.method === 'PATCH' && c.path.endsWith('/members/me'));
        expect(patch?.body).toEqual({ displayCurrency: null, locale: null });
    });

    test('a user with no household sees the invitation prompt', async ({ page, ui }) => {
        await mockApi(page, { session: 'no-household' });
        await page.goto('/preferences');

        await expect(page.getByRole('heading', { name: 'You are not part of a household yet' })).toBeVisible();
        await ui.shot('preferences-no-membership');
    });

    test('a right-to-left locale mirrors the layout', async ({ page, ui }) => {
        await mockApi(page, { session: 'owner' });
        await page.goto('/preferences');
        await page.getByLabel('Language and number format').selectOption('ar-EG');
        await page.getByRole('button', { name: 'Save' }).click();

        // The profile read back after saving carries the locale, and the app applies it: the
        // document turns right-to-left, with no translation file needed for that.
        await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
        await expect(page.locator('html')).toHaveAttribute('lang', 'ar-EG');

        await ui.shot('preferences-rtl');
        await ui.noOverflow();
    });
});
