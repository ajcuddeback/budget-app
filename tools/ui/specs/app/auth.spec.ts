import { DEMO_PROBLEMS } from '../../fixtures/demo-data.js';
import { mockApi } from '../../helpers/mock-api.js';
import { expect, test } from '../../helpers/ui-test.js';

/**
 * First-run setup and sign-in, against fixtures only (ADR-0013): nothing here reaches a server, and
 * the one "password" typed is a fixture string that opens nothing.
 */
test.describe('first-run setup', () => {
    test('a fresh instance sends a visitor to create the first account', async ({ page, ui }) => {
        await mockApi(page, { session: 'anonymous', freshInstance: true });
        await page.goto('/');

        await expect(page).toHaveURL(/\/setup$/);
        await expect(page.getByRole('heading', { name: 'Set up Budget Owl', level: 1 })).toBeVisible();
        // The operator is told what the operator can see — the other half of the join disclosure.
        await expect(page.getByText(/you will be able to see everything anyone in your household records/i)).toBeVisible();

        await ui.shot('setup-empty');
        expect(await ui.a11y('setup, empty')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('shows what is wrong with the form instead of sending it', async ({ page, ui }) => {
        const calls = await mockApi(page, { session: 'anonymous', freshInstance: true });
        await page.goto('/setup');

        await page.getByRole('button', { name: 'Create account' }).click();

        await expect(page.getByText('This field is required.').first()).toBeVisible();
        await page.getByLabel('Password').fill('short');
        await page.getByLabel('Password').blur();
        await expect(page.getByText('Use at least 12 characters.')).toBeVisible();

        await ui.shot('setup-validation');
        expect(await ui.a11y('setup, validation errors')).toBe(0);
        expect(calls.some((c) => c.method === 'POST')).toBe(false);
    });

    test('creates the account and lands signed in on the household', async ({ page, ui }) => {
        await mockApi(page, { session: 'anonymous', freshInstance: true });
        await page.goto('/setup');

        await page.getByLabel('Your name').fill('Alex Rivera');
        await page.getByLabel('Email address').fill('alex@example.test');
        await page.getByLabel('Password').fill('correct horse battery staple');
        await page.getByLabel('Household name').fill('Rivera Household');
        await page.getByRole('button', { name: 'Create account' }).click();

        await expect(page).toHaveURL(/\/household$/);
        await expect(page.getByRole('heading', { name: 'Household', level: 1 })).toBeVisible();
        await ui.shot('setup-done');
        ui.noErrors();
    });
});

test.describe('sign in', () => {
    test('signs in and lands on the household', async ({ page, ui }) => {
        await mockApi(page, { session: 'anonymous' });
        await page.goto('/login');

        await ui.shot('login-empty');
        await page.getByLabel('Email address').fill('alex@example.test');
        await page.getByLabel('Password').fill('a fixture password');
        await page.getByRole('button', { name: 'Sign in' }).click();

        await expect(page).toHaveURL(/\/household$/);
        await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
        ui.noErrors();
    });

    test('a wrong password is explained in one sentence and the field is cleared', async ({ page, ui }) => {
        await mockApi(page, { session: 'anonymous' });
        await page.goto('/login');

        await page.getByLabel('Email address').fill('alex@example.test');
        await page.getByLabel('Password').fill('wrong-password');
        await page.getByRole('button', { name: 'Sign in' }).click();

        await expect(page.getByRole('alert')).toHaveText(/The email address or password is not right\./);
        await expect(page.getByLabel('Password')).toHaveValue('');
        await expect(page).toHaveURL(/\/login$/);

        await ui.shot('login-wrong-password');
        expect(await ui.a11y('login, wrong password')).toBe(0);
        await ui.noOverflow();
    });

    test('says how long to wait when it is rate limited', async ({ page, ui }) => {
        await mockApi(page, { session: 'anonymous' });
        await page.route('**/api/auth/login', (route) =>
            route.fulfill({
                status: 429,
                contentType: 'application/problem+json',
                headers: { 'Retry-After': '120' },
                body: JSON.stringify(DEMO_PROBLEMS.rateLimited),
            }),
        );
        await page.goto('/login');
        await page.getByLabel('Email address').fill('alex@example.test');
        await page.getByLabel('Password').fill('anything');
        await page.getByRole('button', { name: 'Sign in' }).click();

        await expect(page.getByRole('alert')).toHaveText(/Wait 2 minutes and try again/);
        await ui.shot('login-rate-limited');
    });

    test('says so when the server cannot be reached', async ({ page, ui }) => {
        await mockApi(page, { session: 'anonymous' });
        await page.route('**/api/auth/login', (route) => route.abort('connectionrefused'));
        await page.goto('/login');
        await page.getByLabel('Email address').fill('alex@example.test');
        await page.getByLabel('Password').fill('anything');
        await page.getByRole('button', { name: 'Sign in' }).click();

        await expect(page.getByRole('alert')).toHaveText(/could not be reached/);
        await ui.shot('login-unreachable');
    });

    test('sends the CSRF token on the write, and never keeps a credential in storage', async ({ page, context }) => {
        const calls = await mockApi(page, { session: 'anonymous' });
        // What Spring's CookieCsrfTokenRepository issues on the first safe request.
        await context.addCookies([{ name: 'XSRF-TOKEN', value: 'fixture-csrf-value', url: 'http://localhost:4200' }]);
        await page.goto('/login');
        await page.getByLabel('Email address').fill('alex@example.test');
        await page.getByLabel('Password').fill('a fixture password');
        await page.getByRole('button', { name: 'Sign in' }).click();
        await expect(page).toHaveURL(/\/household$/);

        const login = calls.find((c) => c.method === 'POST' && c.path === '/api/auth/login');
        expect(login?.headers?.['x-xsrf-token']).toBe('fixture-csrf-value');

        // The client holds no credential. The only thing it ever stores is the theme.
        const stored = await page.evaluate(() => ({
            local: Object.keys(localStorage),
            session: Object.keys(sessionStorage),
        }));
        expect(stored.session).toEqual([]);
        expect(stored.local.filter((key) => key !== 'budget-owl.theme')).toEqual([]);
    });

    test('a signed-out visitor to a private page is sent to sign in', async ({ page }) => {
        await mockApi(page, { session: 'anonymous' });
        await page.goto('/devices');
        await expect(page).toHaveURL(/\/login$/);
    });

    test('an expired session routes to sign-in with an explanation, not a crash', async ({ page, ui }) => {
        await mockApi(page, { session: 'owner' });
        await page.goto('/household');
        await expect(page.getByRole('heading', { name: 'Household', level: 1 })).toBeVisible();

        // The server stops honouring the session — idle timeout, or revoked from another device.
        await page.route(/\/api\/households\/current\/members(\?|$)/, (route) =>
            route.fulfill({
                status: 401,
                contentType: 'application/problem+json',
                body: JSON.stringify(DEMO_PROBLEMS.notAuthenticated),
            }),
        );
        await page.getByRole('link', { name: 'Devices' }).click();
        await page.getByRole('link', { name: 'Household' }).click();

        await expect(page).toHaveURL(/\/login\?reason=expired$/);
        await expect(page.getByText('Your session ended. Sign in again to continue.')).toBeVisible();
        await ui.shot('login-session-expired');
        expect(await ui.a11y('login, session expired')).toBe(0);
    });

    test('a signed-in user with no household sees an invitation prompt, not an error', async ({ page, ui }) => {
        await mockApi(page, { session: 'no-household' });
        await page.goto('/household');

        await expect(page.getByRole('heading', { name: 'You are not part of a household yet' })).toBeVisible();
        await expect(page.getByRole('alert')).toHaveCount(0);

        await ui.shot('household-no-membership');
        expect(await ui.a11y('household, no membership')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });
});

test.describe('sign in, in the dark theme', () => {
    test('the error state stays legible', async ({ page, ui }) => {
        await mockApi(page, { session: 'anonymous' });
        await page.addInitScript(() => localStorage.setItem('budget-owl.theme', 'dark'));
        await page.goto('/login');
        await page.getByLabel('Email address').fill('alex@example.test');
        await page.getByLabel('Password').fill('wrong-password');
        await page.getByRole('button', { name: 'Sign in' }).click();
        await expect(page.getByRole('alert')).toBeVisible();
        await ui.shot('login-error-dark');
        expect(await ui.a11y('login, dark, error')).toBe(0);
    });
});
