import { mockApi } from '../../helpers/mock-api.js';
import { expect, test } from '../../helpers/ui-test.js';

test.describe('logged-in devices', () => {
    test.beforeEach(async ({ page }) => {
        await mockApi(page, { session: 'owner' });
    });

    test('lists this browser, another browser and a phone, in words a person recognises', async ({ page, ui }) => {
        await page.goto('/devices');

        await expect(page.getByRole('heading', { name: 'Devices', level: 1 })).toBeVisible();
        const rows = page.getByRole('listitem');
        await expect(rows).toHaveCount(3);
        await expect(rows.filter({ hasText: 'Chrome on Linux' })).toContainText('This device');
        await expect(rows.filter({ hasText: 'Safari on macOS' })).toHaveCount(1);
        await expect(rows.filter({ hasText: "Alex's Pixel" })).toContainText('Mobile app');

        await ui.shot('devices');
        expect(await ui.a11y('devices')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('signs another device out straight away', async ({ page, ui }) => {
        await page.goto('/devices');

        await page.getByRole('button', { name: /Sign out\s+Safari/ }).click();

        await expect(page.getByRole('listitem').filter({ hasText: 'Safari on macOS' })).toHaveCount(0);
        await expect(page.getByText('That device has been signed out.')).toBeVisible();
        await ui.shot('devices-revoked');
    });

    test('warns before signing out the browser being used, then goes to sign-in', async ({ page, ui }) => {
        await page.goto('/devices');

        await page.getByRole('button', { name: /Sign out here/ }).click();
        const dialog = page.getByRole('dialog', { name: 'Sign out of this browser?' });
        await expect(dialog).toBeVisible();
        await ui.shot('devices-confirm-current');

        await dialog.getByRole('button', { name: 'Sign out here' }).click();
        await expect(page).toHaveURL(/\/login/);
    });
});

test.describe('logged-in devices, in the dark theme', () => {
    test('stays legible', async ({ page, ui }) => {
        await mockApi(page, { session: 'owner' });
        await page.addInitScript(() => localStorage.setItem('budget-owl.theme', 'dark'));
        await page.goto('/devices');
        await expect(page.getByRole('listitem')).toHaveCount(3);
        await ui.shot('devices-dark');
        expect(await ui.a11y('devices, dark')).toBe(0);
    });
});
