import { DEMO_PROBLEMS } from '../../fixtures/demo-data.js';
import { mockApi } from '../../helpers/mock-api.js';
import { expect, test } from '../../helpers/ui-test.js';

test.describe('household settings, as an owner', () => {
    test.beforeEach(async ({ page }) => {
        await mockApi(page, { session: 'owner' });
    });

    test('shows the household, its members and their roles', async ({ page, ui }) => {
        await page.goto('/household');

        await expect(page.getByRole('heading', { name: 'Household', level: 1 })).toBeVisible();
        await expect(page.getByLabel('Household name')).toHaveValue('Rivera Household');
        await expect(page.getByLabel('Base currency')).toHaveValue('USD');
        const members = page.getByRole('list').filter({ hasText: 'Sam Rivera' });
        await expect(members.getByRole('listitem')).toHaveCount(3);
        await expect(members.getByText('You', { exact: true })).toBeVisible();

        await ui.shot('household-owner');
        expect(await ui.a11y('household, owner')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('renames the household', async ({ page, ui }) => {
        await page.goto('/household');

        await page.getByLabel('Household name').fill('The Riveras');
        await page.getByRole('button', { name: 'Save' }).click();

        await expect(page.getByText('Household saved.')).toBeVisible();
        await ui.shot('household-saved');
    });

    test('changes another member\'s role, and offers none for the owner themselves', async ({ page }) => {
        const calls = await mockApi(page, { session: 'owner' });
        await page.goto('/household');

        await expect(page.getByLabel('Role for Alex Rivera')).toHaveCount(0);
        await page.getByLabel('Role for Jordan Rivera').selectOption('MEMBER');

        await expect.poll(() => calls.some((c) => c.method === 'PATCH' && c.body && (c.body as { role?: string }).role === 'MEMBER')).toBe(true);
        await expect(page.getByLabel('Role for Jordan Rivera')).toHaveValue('MEMBER');
    });

    test('asks before removing someone, and the dialog is a real modal', async ({ page, ui }) => {
        await page.goto('/household');

        await page.getByRole('button', { name: /Remove\s+Sam Rivera/ }).click();

        const dialog = page.getByRole('dialog', { name: 'Remove Sam Rivera?' });
        await expect(dialog).toBeVisible();
        await ui.shot('household-remove-confirm');
        expect(await ui.a11y('household, remove confirm')).toBe(0);

        await dialog.getByRole('button', { name: 'Remove' }).click();
        await expect(page.getByRole('listitem').filter({ hasText: 'Sam Rivera' })).toHaveCount(0);
    });

    test('Escape cancels the removal and focus returns to the button', async ({ page }) => {
        await page.goto('/household');
        const opener = page.getByRole('button', { name: /Remove\s+Sam Rivera/ });
        await opener.click();
        await expect(page.getByRole('dialog')).toBeVisible();

        await page.keyboard.press('Escape');

        await expect(page.getByRole('dialog')).toHaveCount(0);
        await expect(opener).toBeFocused();
        await expect(page.getByRole('listitem').filter({ hasText: 'Sam Rivera' })).toHaveCount(1);
    });

    test('the last owner is told why they cannot leave', async ({ page }) => {
        await page.goto('/household');
        await expect(page.getByText('You are the only owner.')).toBeVisible();
        await expect(page.getByRole('button', { name: 'Leave' })).toHaveCount(0);
    });

    test('a refused change is explained beside the list and the list is unchanged', async ({ page, ui }) => {
        await page.route('**/api/households/current/members/*', (route) =>
            route.request().method() === 'PATCH'
                ? route.fulfill({ status: 409, contentType: 'application/problem+json', body: JSON.stringify(DEMO_PROBLEMS.lastOwner) })
                : route.fallback(),
        );
        await page.goto('/household');

        await page.getByLabel('Role for Sam Rivera').selectOption('VIEWER');

        await expect(page.getByRole('alert')).toContainText('A household must keep at least one owner');
        await expect(page.getByLabel('Role for Sam Rivera')).toHaveValue('MEMBER');
        await ui.shot('household-last-owner');
    });

    test('creates an invitation link, built from this browser\'s own origin', async ({ page, ui }) => {
        await page.goto('/household');

        await page.getByRole('button', { name: 'Invite someone' }).click();
        const dialog = page.getByRole('dialog', { name: 'Invite someone' });
        await ui.shot('invite-form');
        expect(await ui.a11y('invite, form')).toBe(0);

        await dialog.getByLabel('Their email address').fill('riley@example.test');
        await dialog.getByRole('radio', { name: /Viewer/ }).check();
        await dialog.getByRole('button', { name: 'Create link' }).click();

        const link = dialog.getByLabel('Invitation link');
        await expect(link).toHaveValue('http://localhost:4200/join/DEMO-TOKEN-NOT-A-REAL-CREDENTIAL');
        await expect(dialog.getByText(/Treat this link like a password/)).toBeVisible();
        await ui.shot('invite-link');
        expect(await ui.a11y('invite, link')).toBe(0);

        await dialog.getByRole('button', { name: 'Revoke link' }).click();
        await expect(dialog.getByText('This link has been revoked')).toBeVisible();
        await ui.shot('invite-revoked');
    });

    test('shows a rate-limit or server failure inside the invite dialog', async ({ page, ui }) => {
        await page.route('**/api/households/current/invitations', (route) =>
            route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify(DEMO_PROBLEMS.internal) }),
        );
        await page.goto('/household');
        await page.getByRole('button', { name: 'Invite someone' }).click();
        const dialog = page.getByRole('dialog');
        await dialog.getByLabel('Their email address').fill('riley@example.test');
        await dialog.getByRole('button', { name: 'Create link' }).click();

        await expect(dialog.getByRole('alert')).toContainText('Something went wrong on our side');
        await expect(dialog.getByRole('alert')).toContainText('Reference: demo0000000000000000000000000007');
        await ui.shot('invite-error');
    });
});

test.describe('household settings, as a member', () => {
    test('is read-only, offers no invitations, and lets them leave', async ({ page, ui }) => {
        await mockApi(page, { session: 'member' });
        await page.goto('/household');

        await expect(page.getByLabel('Household name')).toBeDisabled();
        await expect(page.getByRole('button', { name: 'Invite someone' })).toHaveCount(0);
        await expect(page.getByText('Only an owner can change these.')).toBeVisible();
        await expect(page.getByRole('button', { name: 'Leave' })).toBeVisible();

        await ui.shot('household-member');
        expect(await ui.a11y('household, member')).toBe(0);
        await ui.noOverflow();
    });
});

test.describe('household settings, in the dark theme', () => {
    test('members and the invitation dialog stay legible', async ({ page, ui }) => {
        await mockApi(page, { session: 'owner' });
        await page.addInitScript(() => localStorage.setItem('budget-owl.theme', 'dark'));
        await page.goto('/household');
        await expect(page.getByLabel('Household name')).toHaveValue('Rivera Household');
        await ui.shot('household-dark');
        expect(await ui.a11y('household, dark')).toBe(0);

        await page.getByRole('button', { name: 'Invite someone' }).click();
        const dialog = page.getByRole('dialog');
        await dialog.getByLabel('Their email address').fill('riley@example.test');
        await dialog.getByRole('button', { name: 'Create link' }).click();
        await expect(dialog.getByLabel('Invitation link')).toBeVisible();
        await ui.shot('invite-link-dark');
        expect(await ui.a11y('invite, dark')).toBe(0);
    });
});
