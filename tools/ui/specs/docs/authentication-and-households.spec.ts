import { DEMO_ME_MEMBER, DEMO_TOKENS } from '../../fixtures/demo-data.js';
import { expect, test } from '../../helpers/doc-capture.js';
import { mockApi } from '../../helpers/mock-api.js';

/**
 * Screens for the slice 2 guides: first-run setup, signing in, inviting, joining, the member
 * list, devices and display preferences.
 *
 * Everything renders from tools/ui/fixtures/demo-data.ts. The one "password" typed is a fixture
 * string that opens nothing (ADR-0013). A second mockApi() call replaces the first — the later
 * route wins — which is how a spec switches from the default signed-in owner to a visitor.
 */

test.beforeEach(async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 });
});

test('@doc create your account on a new instance', async ({ page, doc }) => {
    doc.guide('create-your-account');
    await mockApi(page, { session: 'anonymous', freshInstance: true });
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Set up Budget Owl', level: 1 })).toBeVisible();
    await doc.capture('setup-form', 'The Set up Budget Owl screen with an empty form.', { fullPage: true });

    await page.getByLabel('Your name').fill('Alex Rivera');
    await page.getByLabel('Email address').fill('alex@example.test');
    await page.getByLabel('Password').fill('a long fixture passphrase');
    await page.getByLabel('Household name').fill('Rivera Household');
    await doc.capture('setup-filled', 'The setup form filled in.', {
        fullPage: true,
        highlight: page.getByRole('button', { name: 'Create account' }),
        step: 7,
    });

    await page.getByLabel('Password').fill('short');
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page.getByText('Use at least 12 characters.')).toBeVisible();
    await doc.capture('setup-password-short', 'The password field asking for at least 12 characters.', { fullPage: true });

    await page.getByLabel('Password').fill('a long fixture passphrase');
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page).toHaveURL(/\/household$/);
    await expect(page.getByRole('heading', { name: 'Household', level: 1 })).toBeVisible();
    await doc.capture('setup-done', 'The Household screen you land on after creating your account.', { fullPage: true });
});

test('@doc sign in', async ({ page, doc }) => {
    doc.guide('sign-in');
    await mockApi(page, { session: 'anonymous' });
    await page.goto('/login');
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    await doc.capture('login-form', 'The sign-in screen.', { fullPage: true });

    await page.getByLabel('Email address').fill('alex@example.test');
    await page.getByLabel('Password').fill('wrong-password');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await doc.capture('login-wrong-password', 'The message shown when the email address or password is wrong.', { fullPage: true });
});

test('@doc sign in, rate limited and expired', async ({ page, doc }) => {
    doc.guide('sign-in');
    await mockApi(page, { session: 'anonymous' });
    await page.route('**/api/auth/login', (route) =>
        route.fulfill({
            status: 429,
            contentType: 'application/problem+json',
            headers: { 'Retry-After': '120' },
            body: JSON.stringify({ type: 'https://budgetapp.dev/errors/rate-limited', title: 'Too many attempts', status: 429, code: 'rate-limited', params: { retryAfterSeconds: 120 }, correlationId: 'demo0000000000000000000000000006' }),
        }),
    );
    await page.goto('/login');
    await page.getByLabel('Email address').fill('alex@example.test');
    await page.getByLabel('Password').fill('anything');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('alert')).toContainText('Wait 2 minutes');
    await doc.capture('login-too-many', 'The message asking you to wait before trying to sign in again.', { fullPage: true });

    await page.goto('/login?reason=expired');
    await expect(page.getByText('Your session ended. Sign in again to continue.')).toBeVisible();
    await doc.capture('login-expired', 'The sign-in screen explaining that your session ended.', { fullPage: true });
});

test('@doc invite someone to your household', async ({ page, doc }) => {
    doc.guide('invite-someone');
    await page.goto('/household');
    await expect(page.getByRole('heading', { name: 'Household', level: 1 })).toBeVisible();
    await doc.capture('household', 'The Household screen as an owner, with the Invite someone button.', {
        fullPage: true,
        highlight: page.getByRole('button', { name: 'Invite someone' }),
        step: 1,
    });

    await page.getByRole('button', { name: 'Invite someone' }).click();
    const dialog = page.getByRole('dialog', { name: 'Invite someone' });
    await expect(dialog).toBeVisible();
    await doc.capture('invite-form', 'The Invite someone window, before anything is filled in.');

    await dialog.getByLabel('Their email address').fill('riley@example.test');
    await dialog.getByRole('radio', { name: /Viewer/ }).check();
    await doc.capture('invite-filled', 'The Invite someone window with an email address and the Viewer role chosen.', {
        highlight: dialog.getByRole('button', { name: 'Create link' }),
        step: 4,
    });

    await dialog.getByRole('button', { name: 'Create link' }).click();
    await expect(dialog.getByLabel('Invitation link')).toBeVisible();
    await doc.capture('invite-link', 'The invitation link, with the warning to treat it like a password.', {
        highlight: dialog.getByText(/Treat this link like a password/),
    });

    await dialog.getByRole('button', { name: 'Copy link' }).click();
    await doc.capture('invite-copied', 'The Invite someone window just after selecting Copy link, with "Link copied." beside the button.', {
        highlight: dialog.getByRole('button', { name: 'Copy link' }),
        step: 5,
    });

    await dialog.getByRole('button', { name: 'Revoke link' }).click();
    await expect(dialog.getByText('This link has been revoked')).toBeVisible();
    await doc.capture('invite-revoked', 'The Invite someone window after the link has been revoked.');
});

test('@doc join a household', async ({ page, doc }) => {
    doc.guide('join-a-household');
    await mockApi(page, { session: 'anonymous' });
    await page.goto(`/join/${DEMO_TOKENS.newAccount}`);
    await expect(page.locator('#join-disclosure')).toBeVisible();
    await doc.capture('join-form', 'The invitation screen, with the notice about who can see what you record.', {
        fullPage: true,
        highlight: page.locator('#join-disclosure'),
        step: 2,
    });

    await page.getByRole('button', { name: 'Accept and join' }).click();
    await expect(page.getByText('This field is required.').first()).toBeVisible();
    await doc.capture('join-needs-details', 'The invitation screen asking for a name and password.', { fullPage: true });

    await page.getByLabel('Your name').fill('Riley Okafor');
    await page.getByLabel('Choose a password').fill('a long fixture passphrase');
    await page.getByRole('button', { name: 'Accept and join' }).click();
    await expect(page.getByRole('heading', { name: 'Welcome to Rivera Household' })).toBeVisible();
    await doc.capture('join-done', 'The welcome screen after joining.', { fullPage: true });

    await page.getByRole('link', { name: 'Sign in' }).click();
    await expect(page.getByText('You have joined the household. Sign in to continue.')).toBeVisible();
    await doc.capture('join-sign-in', 'The Sign in screen after joining, with a message that you have joined the household.', { fullPage: true });

    await page.goto(`/join/${DEMO_TOKENS.valid}`);
    await expect(page.getByRole('button', { name: 'Accept and join' })).toBeVisible();
    await doc.capture('join-existing', 'The invitation screen as someone who already has an account on this instance.', {
        fullPage: true,
        highlight: page.getByRole('button', { name: 'Accept and join' }),
        step: 4,
    });
    await page.getByRole('button', { name: 'Accept and join' }).click();
    await expect(page.getByRole('heading', { name: 'Welcome to Rivera Household' })).toBeVisible();
    await doc.capture('join-existing-done', 'The welcome screen for someone who already had an account.', { fullPage: true });

    await page.goto(`/join/${DEMO_TOKENS.unusable}`);
    await page.getByRole('button', { name: 'Accept and join' }).click();
    await expect(page.getByRole('heading', { name: 'This invitation cannot be used' })).toBeVisible();
    await doc.capture('join-unusable', 'The message shown when an invitation link has expired, been used, or been cancelled.', { fullPage: true });
});

test('@doc join a household while signed in', async ({ page, doc }) => {
    doc.guide('join-a-household');
    await page.goto(`/join/${DEMO_TOKENS.valid}`);
    await expect(page.getByRole('heading', { name: 'You are already signed in' })).toBeVisible();
    await doc.capture('join-signed-in', 'The screen asking you to sign out before you accept an invitation.', { fullPage: true });
});

test('@doc see and manage the people in your household', async ({ page, doc }) => {
    doc.guide('manage-members');
    await page.goto('/household');
    await expect(page.getByLabel('Household name')).toHaveValue('Rivera Household');
    await doc.capture('members-owner', 'The Household screen as an owner, listing three people and their roles.', {
        fullPage: true,
        highlight: page.getByLabel('Role for Jordan Rivera'),
        step: 1,
    });

    await page.getByLabel('Role for Jordan Rivera').selectOption('MEMBER');
    await expect(page.getByLabel('Role for Jordan Rivera')).toHaveValue('MEMBER');
    await doc.capture('members-role-changed', 'Jordan Rivera now shown with the Member role.', { fullPage: true });

    await page.getByRole('button', { name: /Remove\s+Sam Rivera/ }).click();
    const dialog = page.getByRole('dialog', { name: 'Remove Sam Rivera?' });
    await expect(dialog).toBeVisible();
    await doc.capture('members-remove-confirm', 'The window asking you to confirm removing Sam Rivera.');
});

test('@doc household settings as the only owner', async ({ page, doc }) => {
    doc.guide('manage-members');
    await page.goto('/household');
    await expect(page.getByText('You are the only owner.')).toBeVisible();
    await doc.capture('members-only-owner', 'The note shown to the only owner, explaining why there is no Leave button.', {
        fullPage: true,
        highlight: page.getByText('You are the only owner.'),
        step: 1,
    });
});

test('@doc leave a household as a member', async ({ page, doc }) => {
    doc.guide('manage-members');
    await mockApi(page, { session: 'member' });
    // The fixture member prefers German number and date formats, which would make this one screen
    // look different from the rest of the guide. Answer "who am I" with the same person on the
    // household's default format.
    await page.route('**/api/auth/me', (route) =>
        route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ ...DEMO_ME_MEMBER, household: { ...DEMO_ME_MEMBER.household, displayCurrency: null, locale: null } }),
        }),
    );
    await page.goto('/household');
    await expect(page.getByRole('button', { name: 'Leave' })).toBeVisible();
    await doc.capture('members-member-view', 'The Household screen as a member: settings are read-only and a Leave button is shown.', {
        fullPage: true,
        highlight: page.getByRole('button', { name: 'Leave' }),
        step: 1,
    });
    await page.getByRole('button', { name: 'Leave' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await doc.capture('members-leave-confirm', 'The window asking you to confirm leaving the household.');
});

test('@doc your signed-in devices', async ({ page, doc }) => {
    doc.guide('your-devices');
    await page.goto('/devices');
    await expect(page.getByRole('heading', { name: 'Devices', level: 1 })).toBeVisible();
    await doc.capture('devices', 'The Devices screen listing this browser, another browser, and a phone.', { fullPage: true });

    await page.getByRole('button', { name: /Sign out\s+Safari/ }).click();
    await expect(page.getByText('That device has been signed out.')).toBeVisible();
    await doc.capture('devices-revoked', 'The Devices screen after signing the other browser out.', { fullPage: true });

    await page.getByRole('button', { name: /Sign out here/ }).click();
    await expect(page.getByRole('dialog', { name: 'Sign out of this browser?' })).toBeVisible();
    await doc.capture('devices-confirm-current', 'The window warning that you are about to sign out of the browser you are using.');
});

test('@doc your display currency and language', async ({ page, doc }) => {
    doc.guide('your-display-settings');
    await page.goto('/preferences');
    await expect(page.getByRole('heading', { name: 'Your preferences', level: 1 })).toBeVisible();
    await doc.capture('preferences', 'The Your preferences screen.', { fullPage: true });

    await page.getByLabel('Show amounts in').selectOption('EUR');
    await page.getByLabel('Language and number format').selectOption('de-DE');
    await doc.capture('preferences-chosen', 'Euro and German chosen, before saving.', {
        fullPage: true,
        highlight: page.getByRole('button', { name: 'Save' }),
        step: 3,
    });
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Preferences saved.')).toBeVisible();
    await doc.capture('preferences-saved', 'The preferences screen confirming that they were saved.', { fullPage: true });
});

test('@doc what you see with no household', async ({ page, doc }) => {
    doc.guide('manage-members');
    await mockApi(page, { session: 'no-household' });
    await page.goto('/household');
    await expect(page.getByRole('heading', { name: 'You are not part of a household yet' })).toBeVisible();
    await doc.capture('members-no-household', 'The screen shown to someone who is signed in but belongs to no household.', { fullPage: true });
});
