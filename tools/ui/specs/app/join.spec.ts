import { DEMO_TOKENS } from '../../fixtures/demo-data.js';
import { mockApi } from '../../helpers/mock-api.js';
import { expect, test } from '../../helpers/ui-test.js';

/**
 * Following an invitation link.
 *
 * The first test here guards a SECURITY CONTROL, not a piece of copy (ADR-0026): before an invited
 * person's account exists, the screen must say — plainly, in the flow, above the button — that the
 * person running this instance can see everything they record. If that wording is removed, hidden
 * or buried, this fails. See docs/features/authentication-and-households.md, "Joining tells you
 * what you are joining".
 */
test.describe('accepting an invitation', () => {
    test.beforeEach(async ({ page }) => {
        await mockApi(page, { session: 'anonymous' });
    });

    test('tells the joiner that the operator can see everything, above the button', async ({ page, ui }) => {
        await page.goto(`/join/${DEMO_TOKENS.valid}`);

        const disclosure = page.locator('#join-disclosure');
        await expect(disclosure).toBeVisible();
        await expect(disclosure).toContainText('The person who runs this instance can see everything you record');
        await expect(disclosure).toContainText('every transaction, every balance, every note');

        // Above the button — in vertical position, not merely in the source.
        const button = page.getByRole('button', { name: 'Accept and join' });
        const disclosureBox = await disclosure.boundingBox();
        const buttonBox = await button.boundingBox();
        expect(disclosureBox).not.toBeNull();
        expect(buttonBox).not.toBeNull();
        expect(disclosureBox!.y + disclosureBox!.height).toBeLessThanOrEqual(buttonBox!.y);

        // Not small print: at least body size, and not faded out.
        const style = await disclosure.evaluate((el) => {
            const cs = getComputedStyle(el);
            return { size: parseFloat(cs.fontSize), opacity: cs.opacity };
        });
        expect(style.size).toBeGreaterThanOrEqual(16);
        expect(style.opacity).toBe('1');

        // A screen-reader user hears it when they reach the button.
        await expect(button).toHaveAttribute('aria-describedby', 'join-disclosure');

        await ui.shot('join-disclosure');
        expect(await ui.a11y('join, form')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('the disclosure is still on screen after a failed attempt', async ({ page, ui }) => {
        await page.goto(`/join/${DEMO_TOKENS.newAccount}`);

        await page.getByRole('button', { name: 'Accept and join' }).click();

        await expect(page.getByText('This field is required.').first()).toBeVisible();
        await expect(page.locator('#join-disclosure')).toBeVisible();
        await ui.shot('join-needs-account');
        expect(await ui.a11y('join, new account needs fields')).toBe(0);
    });

    test('a new person chooses a name and password and is told to sign in', async ({ page, ui }) => {
        await page.goto(`/join/${DEMO_TOKENS.newAccount}`);

        await page.getByLabel('Your name').fill('Riley Okafor');
        await page.getByLabel('Choose a password').fill('a long fixture passphrase');
        await page.getByRole('button', { name: 'Accept and join' }).click();

        await expect(page.getByRole('heading', { name: 'Welcome to Rivera Household' })).toBeVisible();
        await expect(page.getByText('Your account has been created.')).toBeVisible();
        await ui.shot('join-done');
        expect(await ui.a11y('join, done')).toBe(0);

        await page.getByRole('link', { name: 'Sign in' }).click();
        await expect(page.getByText('You have joined the household. Sign in to continue.')).toBeVisible();
    });

    test('an existing user leaves the fields empty and is added', async ({ page }) => {
        await page.goto(`/join/${DEMO_TOKENS.valid}`);

        await page.getByRole('button', { name: 'Accept and join' }).click();

        await expect(page.getByRole('heading', { name: 'Welcome to Rivera Household' })).toBeVisible();
        await expect(page.getByText('Your account has been created.')).toHaveCount(0);
    });

    test('an expired, used or revoked link gets one generic answer', async ({ page, ui }) => {
        await page.goto(`/join/${DEMO_TOKENS.unusable}`);

        await page.getByRole('button', { name: 'Accept and join' }).click();

        await expect(page.getByRole('heading', { name: 'This invitation cannot be used' })).toBeVisible();
        const alert = page.getByRole('alert');
        await expect(alert).toContainText('expired, been used already, or been cancelled');
        await ui.shot('join-unusable');
        expect(await ui.a11y('join, unusable')).toBe(0);
        await ui.noOverflow();
    });

    test('somebody who is signed in is asked to sign out first', async ({ page, ui }) => {
        await mockApi(page, { session: 'owner' });
        await page.goto(`/join/${DEMO_TOKENS.valid}`);

        await expect(page.getByRole('heading', { name: 'You are already signed in' })).toBeVisible();
        await ui.shot('join-signed-in');
        expect(await ui.a11y('join, signed in')).toBe(0);

        await page.getByRole('main').getByRole('button', { name: 'Sign out' }).click();

        // Stays on the invitation, now showing the form and its disclosure.
        await expect(page).toHaveURL(new RegExp(`/join/${DEMO_TOKENS.valid}$`));
        await expect(page.locator('#join-disclosure')).toBeVisible();
    });
});

test.describe('accepting an invitation, in the dark theme', () => {
    test('the disclosure holds its contrast and stays visible', async ({ page, ui }) => {
        await mockApi(page, { session: 'anonymous' });
        await page.addInitScript(() => localStorage.setItem('budget-owl.theme', 'dark'));
        await page.goto(`/join/${DEMO_TOKENS.valid}`);

        await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
        await expect(page.locator('#join-disclosure')).toBeVisible();
        await ui.shot('join-disclosure-dark');
        expect(await ui.a11y('join, dark')).toBe(0);
        await ui.noOverflow();
    });
});
