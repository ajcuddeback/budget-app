import { DEMO_INSTANCE_SETTINGS_OIDC } from '../../fixtures/demo-data.js';
import { mockApi } from '../../helpers/mock-api.js';
import { expect, test, type Page } from '../../helpers/ui-test.js';

/**
 * The operator's screen (docs/features/admin-console.md).
 *
 * It had never been opened in a browser before this spec existed, and three of the things it is
 * written around are invisible to a unit test:
 *
 *  - **The client secret field must show nothing.** Not dots, not a placeholder standing in for a
 *    value the client does not have — the API never returns one. So the field is asserted empty on
 *    load and after a save, and the PATCH body is asserted to have no `oidcClientSecret` key at all
 *    when nobody typed into it: the server reads a present-and-blank secret as "clear it".
 *  - **A control that cannot work is not rendered.** While the lockout applies there is no
 *    password-login switch, only the rule. That has to *read* as an explanation rather than as a
 *    broken screen, which is a judgement made from the screenshot.
 *  - **The consequence of opening registration is a block in the flow**, wired to the checkbox with
 *    `aria-describedby` — never a tooltip, because the words are the control.
 *
 * Every state is captured in both themes here and in both viewports by the Playwright projects, so
 * the dark theme is checked at the same time as the light one rather than in a later pass.
 */

const THEMES = ['light', 'dark'] as const;

/** An obviously fake value typed into the secret field. Never a real credential. */
const TYPED_SECRET = 'demo-client-secret-placeholder';

/** The settings of an instance with a provider configured and an owner's sign-in on record. */
const WITH_PROVIDER = { ...DEMO_INSTANCE_SETTINGS_OIDC };

/** The one PATCH the page sent, so a spec can say what did — and did not — travel. */
function settingsPatch(calls: { method: string; path: string; body?: unknown }[]) {
  const patch = calls.find(
    (call) => call.method === 'PATCH' && call.path === '/api/admin/settings',
  );
  return (patch?.body ?? null) as Record<string, unknown> | null;
}

async function waitForAdminPage(page: Page): Promise<void> {
  await expect(page.getByRole('heading', { name: 'Administration', level: 1 })).toBeVisible();
}

for (const theme of THEMES) {
  test.describe(`the operator's screen, ${theme} theme`, () => {
    test.beforeEach(async ({ page }) => {
      await page.addInitScript((mode) => localStorage.setItem('budget-owl.theme', mode), theme);
    });

    test('shows the instance, how people sign in, and who can', async ({ page, ui }) => {
      await mockApi(page, { session: 'owner' });
      await page.goto('/admin');
      await waitForAdminPage(page);

      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);

      // This instance.
      await expect(page.getByRole('heading', { name: 'This instance' })).toBeVisible();
      await expect(page.getByText('0.1.0')).toBeVisible();
      // The facts are a <dl>, so the administrator can be asked for as the definition it is
      // rather than as whichever element happens to hold the name.
      const administrator = page.getByRole('definition').filter({ hasText: 'Alex Rivera' });
      await expect(administrator).toContainText('alex@example.test');
      await expect(page.getByRole('definition').filter({ hasText: /^4$/ })).toBeVisible();

      // Signing in.
      await expect(page.getByRole('heading', { name: 'Signing in' })).toBeVisible();
      await expect(page.getByLabel('Let anyone create an account')).not.toBeChecked();
      await expect(page.getByLabel('Offer sign-in through my provider')).not.toBeChecked();
      await expect(page.getByRole('button', { name: 'Save' })).toBeEnabled();

      // Accounts: four rows, the administrator marked on exactly one of them.
      const accounts = page.getByRole('list').filter({ hasText: 'Riley Fitzgerald-Okonkwo' });
      await expect(accounts.getByRole('listitem')).toHaveCount(4);
      await expect(accounts.getByText('Administrator')).toHaveCount(1);
      await expect(accounts.getByText('You', { exact: true })).toHaveCount(1);
      await expect(accounts.getByText('Disabled')).toHaveCount(1);
      // Every row says when the account joined. The date's wording is the locale's business, so
      // this asks that each row has one rather than asserting a format.
      await expect(accounts.getByText(/^Joined /)).toHaveCount(4);

      // A standing guard, not a behaviour: no figure of anybody's money belongs on this screen
      // (ADR-0026), and the day one is added this is what says so.
      await expect(page.getByRole('main')).not.toContainText(/[$€£]/);

      await ui.shot(`admin-overview-${theme}`);
      expect(await ui.a11y(`admin, overview, ${theme}`)).toBe(0);
      await ui.noOverflow();
      ui.noErrors();
    });

    test('offers no password-login switch while the lockout applies, and says why', async ({
      page,
      ui,
    }) => {
      // The default instance: no provider, so the database would refuse to hide password login.
      await mockApi(page, { session: 'owner' });
      await page.goto('/admin');
      await waitForAdminPage(page);

      await expect(
        page.getByLabel('Offer sign-in with an email address and a password'),
      ).toHaveCount(0);
      await expect(page.getByRole('checkbox')).toHaveCount(2); // registration and the provider
      await expect(page.getByText('Email and password sign-in is on.')).toBeVisible();
      await expect(page.getByText('This cannot be turned off yet')).toBeVisible();
      await expect(
        page.getByText(/An owner has to complete a sign-in through your provider first/),
      ).toBeVisible();

      await ui.shot(`admin-password-locked-${theme}`);
      expect(await ui.a11y(`admin, password login locked, ${theme}`)).toBe(0);
      await ui.noOverflow();
      ui.noErrors();
    });

    test('offers the switch once a provider is proved, and names the way back in', async ({
      page,
      ui,
    }) => {
      await mockApi(page, { session: 'owner', adminSettings: WITH_PROVIDER });
      await page.goto('/admin');
      await waitForAdminPage(page);

      const passwordSwitch = page.getByLabel('Offer sign-in with an email address and a password');
      await expect(passwordSwitch).toBeChecked();
      await expect(page.getByText('This cannot be turned off yet')).toHaveCount(0);

      await passwordSwitch.uncheck();

      // Named before anything is sent: the browser is no help once the browser is the thing you
      // cannot get in through.
      await expect(page.getByText(/deploy\/restore-password-login\.sh/)).toBeVisible();

      await ui.shot(`admin-password-offered-${theme}`);
      expect(await ui.a11y(`admin, password login offered, ${theme}`)).toBe(0);
      await ui.noOverflow();
      ui.noErrors();
    });

    test('never shows a stored secret, and sends one only when it was typed', async ({
      page,
      ui,
    }) => {
      const calls = await mockApi(page, { session: 'owner', adminSettings: WITH_PROVIDER });
      await page.goto('/admin');
      await waitForAdminPage(page);

      // Empty on load, and nothing stands in for the value this screen does not have.
      const secret = page.getByLabel('Client secret');
      await expect(secret).toHaveValue('');
      await expect(secret).toHaveAttribute('type', 'password');
      await expect(secret).toHaveAttribute('autocomplete', 'new-password');
      await expect(page.getByText('A secret is set.')).toBeVisible();

      // Something unrelated is saved. The secret must not travel at all: a present-and-blank
      // `oidcClientSecret` is how the API is told to CLEAR the stored one.
      await page.getByLabel('Let anyone create an account').check();
      await page.getByRole('button', { name: 'Save' }).click();
      await expect(page.getByText('Saved.')).toBeVisible();

      const unrelated = settingsPatch(calls);
      expect(unrelated).toEqual({ registrationOpen: true, version: WITH_PROVIDER.version });
      expect(Object.hasOwn(unrelated ?? {}, 'oidcClientSecret')).toBe(false);

      // Still empty after the save, for the same reason it was empty on load.
      await expect(secret).toHaveValue('');
      await ui.shot(`admin-secret-empty-${theme}`);

      await secret.fill(TYPED_SECRET);
      await page.getByRole('button', { name: 'Save' }).click();
      await expect(page.getByText('Saved.')).toBeVisible();

      const withSecret = calls.filter(
        (call) => call.method === 'PATCH' && call.path === '/api/admin/settings',
      );
      expect(withSecret).toHaveLength(2);
      expect(withSecret[1].body).toEqual({
        oidcClientSecret: TYPED_SECRET,
        version: WITH_PROVIDER.version + 1,
      });
      await expect(secret).toHaveValue('');

      expect(await ui.a11y(`admin, secret field, ${theme}`)).toBe(0);
      await ui.noOverflow();
      ui.noErrors();
    });

    test('a save that lost a race keeps what was typed and names what moved', async ({
      page,
      ui,
    }) => {
      const calls = await mockApi(page, {
        session: 'owner',
        adminSettings: WITH_PROVIDER,
        adminConflict: {
          registrationOpen: true,
          oidcIssuerUri: 'https://login.example.test/realms/household',
          oidcProvisioningEnabled: true,
        },
      });
      await page.goto('/admin');
      await waitForAdminPage(page);

      await page.getByLabel('Client ID').fill('budget-owl-web');
      await page.getByRole('button', { name: 'Save' }).click();

      const notice = page.getByRole('alert').filter({ hasText: 'Somebody else saved first' });
      await expect(notice).toBeVisible();
      await expect(notice.getByRole('listitem')).toHaveCount(3);
      await expect(notice).toContainText('Registration');
      await expect(notice).toContainText('Issuer URL');
      await expect(notice).toContainText('Account creation through the provider');
      // Field names only. A notice that rendered values would be the one place in the console
      // that put a secret on screen.
      await expect(notice).not.toContainText('https://login.example.test');

      // Nothing was discarded, and nothing was written.
      await expect(page.getByLabel('Client ID')).toHaveValue('budget-owl-web');
      await expect(page.getByText('Saved.')).toHaveCount(0);

      await ui.shot(`admin-stale-version-${theme}`);
      expect(await ui.a11y(`admin, stale version, ${theme}`)).toBe(0);
      await ui.noOverflow();

      // "Save my changes anyway" goes out against the copy they have now been shown, never the
      // one that lost the race.
      await page.getByRole('button', { name: 'Save my changes anyway' }).click();
      await expect(page.getByText('Saved.')).toBeVisible();

      const sent = calls
        .filter((call) => call.method === 'PATCH' && call.path === '/api/admin/settings')
        .map((call) => call.body as Record<string, unknown>);
      expect(sent).toHaveLength(2);
      expect(sent[0]['version']).toBe(WITH_PROVIDER.version);
      expect(sent[1]['version']).toBe(WITH_PROVIDER.version + 1);
      expect(sent[1]['oidcClientId']).toBe('budget-owl-web');
      ui.noErrors();
    });

    test('turning the provider off is confirmed, and sends only that', async ({ page, ui }) => {
      const calls = await mockApi(page, { session: 'owner', adminSettings: WITH_PROVIDER });
      await page.goto('/admin');
      await waitForAdminPage(page);

      await page.getByLabel('Offer sign-in through my provider').uncheck();
      await expect(
        page.getByText(/Turning this off also switches email and password sign-in back on/),
      ).toBeVisible();
      await ui.shot(`admin-oidc-off-warning-${theme}`);

      await page.getByRole('button', { name: 'Save' }).click();

      const dialog = page.getByRole('dialog', { name: 'Turn off single sign-on?' });
      await expect(dialog).toBeVisible();
      await expect(dialog).toContainText('Email and password sign-in will be switched back on');
      await ui.shot(`admin-oidc-off-confirm-${theme}`);
      expect(await ui.a11y(`admin, provider off confirmation, ${theme}`)).toBe(0);

      await dialog.getByRole('button', { name: 'Turn it off' }).click();
      await expect(page.getByText('Saved.')).toBeVisible();

      // The server clears the rest and switches password login back on, so describing any of it
      // here would be fiction.
      expect(settingsPatch(calls)).toEqual({
        oidcEnabled: false,
        version: WITH_PROVIDER.version,
      });

      // And the screen now shows what is true: no provider, so the lockout is back.
      await expect(page.getByText('Email and password sign-in is on.')).toBeVisible();
      await expect(page.getByLabel('Issuer URL')).toHaveCount(0);
      await ui.shot(`admin-oidc-off-saved-${theme}`);
      await ui.noOverflow();
      ui.noErrors();
    });

    test('is not offered or reachable for anybody but the administrator', async ({ page, ui }) => {
      const calls = await mockApi(page, { session: 'member' });
      await page.goto('/transactions');

      const nav = page.getByRole('navigation');
      await expect(nav.getByRole('link', { name: 'Preferences' })).toBeVisible();
      await expect(nav.getByRole('link', { name: 'Admin' })).toHaveCount(0);
      await ui.shot(`admin-nav-without-link-${theme}`);

      await page.goto('/admin');

      // The guard sends a signed-in non-administrator home rather than to sign-in: they are
      // signed in, and there is nothing to sign in as.
      await expect.poll(() => new URL(page.url()).pathname).toBe('/transactions');
      await expect(page.getByRole('heading', { name: 'Administration' })).toHaveCount(0);
      expect(calls.filter((call) => call.path.startsWith('/api/admin'))).toEqual([]);

      await ui.shot(`admin-refused-${theme}`);
      await ui.noOverflow();
      ui.noErrors();
    });

    test('says what opening registration means, in the flow rather than in a tooltip', async ({
      page,
      ui,
    }) => {
      await mockApi(page, { session: 'owner' });
      await page.goto('/admin');
      await waitForAdminPage(page);

      const registration = page.getByLabel('Let anyone create an account');
      // The locator is derived from the accessibility wiring, which is also the thing under test:
      // the checkbox has to point at the words, and the words have to be on the page.
      const describedBy = await registration.getAttribute('aria-describedby');
      expect(describedBy).toBeTruthy();
      const consequence = page.locator(`#${describedBy}`);

      await expect(consequence).toBeVisible();
      await expect(consequence).toContainText(
        'anybody who can reach this server over the network can create themselves an account',
      );
      // Not a tooltip: no hover needed, nothing hidden in a title attribute, and it is a block
      // below the control rather than a bubble beside it.
      await expect(registration).not.toHaveAttribute('title', /.+/);
      const control = await registration.boundingBox();
      const words = await consequence.boundingBox();
      expect(words?.height ?? 0).toBeGreaterThan(20);
      expect(words?.y ?? 0).toBeGreaterThan(control?.y ?? 0);

      await registration.check();
      await expect(page.getByText('Saving now will open registration.')).toBeVisible();

      await ui.shot(`admin-registration-open-${theme}`);
      expect(await ui.a11y(`admin, opening registration, ${theme}`)).toBe(0);
      await ui.noOverflow();
      ui.noErrors();
    });
  });
}
