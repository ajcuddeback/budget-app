import { DEMO_INSTANCE_SETTINGS_OIDC, DEMO_ME_MEMBER } from '../../fixtures/demo-data.js';
import { expect, test } from '../../helpers/doc-capture.js';
import { mockApi } from '../../helpers/mock-api.js';

/**
 * Screens for the slice 9 guides: the Admin screen — who can sign in to this server, and how.
 *
 * Everything renders from tools/ui/fixtures/demo-data.ts (ADR-0013). The one "client secret"
 * typed here is an obviously fictional placeholder that opens nothing, and the screen never
 * reads a stored secret back anyway, so no capture can contain one.
 *
 * Two states are only reachable by starting the mock somewhere specific, which is why the
 * provider states take `adminSettings`:
 *  - the password-login switch exists only once a provider is configured AND an owner's sign-in
 *    through it is on record, because the database refuses to hide password sign-in before that;
 *  - "rotating the secret brings password sign-in back" can only be shown from an instance where
 *    password sign-in was hidden in the first place (ADR-0034).
 */

/** An obviously fake value typed into the client secret box. Never a real credential. */
const TYPED_SECRET = 'demo-client-secret-placeholder';

/** A server pointed at a provider, with an owner's sign-in through it on record. */
const WITH_PROVIDER = { ...DEMO_INSTANCE_SETTINGS_OIDC };

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
});

test('@doc the Admin screen: see who can sign in to your server', async ({ page, doc }) => {
  doc.guide('see-who-can-sign-in');
  await page.goto('/accounts');
  await expect(page.getByRole('link', { name: 'Admin' })).toBeVisible();
  await doc.capture('admin-link', 'The Admin link at the end of the navigation bar.', {
    highlight: page.getByRole('link', { name: 'Admin' }),
    step: 1,
  });

  await page.getByRole('link', { name: 'Admin' }).click();
  await expect(page.getByRole('heading', { name: 'Administration', level: 1 })).toBeVisible();
  await doc.capture(
    'admin-page',
    'The whole Administration screen: This instance, Signing in, and Accounts.',
    { fullPage: true },
  );

  await doc.capture(
    'instance',
    'The This instance block: version, when the server was set up, who administers it, and how many accounts exist.',
    {
      focus: page
        .locator('section')
        .filter({ has: page.getByRole('heading', { name: 'This instance' }) }),
    },
  );

  await doc.capture(
    'accounts',
    'The Accounts block listing four people, one tagged Administrator, one tagged Disabled.',
    {
      focus: page
        .locator('section')
        .filter({ has: page.getByRole('heading', { name: 'Accounts' }) }),
    },
  );
});

test('@doc no Admin link for anybody else', async ({ page, doc }) => {
  doc.guide('see-who-can-sign-in');
  await mockApi(page, { session: 'member' });
  // The fixture member prefers German number and date formats, which would make this one screen
  // look unlike the rest of the guide. Same person, on the household's default format.
  await page.route('**/api/auth/me', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...DEMO_ME_MEMBER,
        household: { ...DEMO_ME_MEMBER.household, displayCurrency: null, locale: null },
      }),
    }),
  );
  await page.goto('/accounts');
  await expect(page.getByRole('link', { name: 'Accounts' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Admin' })).toHaveCount(0);
  await doc.capture(
    'no-admin-link',
    'The navigation bar as somebody who is not the administrator: no Admin link.',
    {
      focus: page.getByRole('navigation'),
    },
  );

  // Typing the address in by hand does not get there either. Not captured: it lands on the
  // transactions screen, which says nothing a reader can learn from a picture of it.
  await page.goto('/admin');
  await expect(page).toHaveURL(/\/transactions$/);
});

test('@doc Admin: let anyone create an account', async ({ page, doc }) => {
  doc.guide('choose-how-people-sign-in');
  await page.goto('/admin');
  await expect(page.getByRole('heading', { name: 'Signing in' })).toBeVisible();
  const signingIn = page
    .locator('section')
    .filter({ has: page.getByRole('heading', { name: 'Signing in' }) });
  await doc.capture(
    'signing-in',
    'The Signing in block on a server with no provider: Registration, Email and password, Single sign-on.',
    {
      focus: signingIn,
    },
  );

  // A ringed element is captured at viewport size, never cropped to its own group: the ring is
  // drawn in page coordinates, so a crop cuts it in half and loses the numbered badge entirely.
  await doc.capture(
    'registration-off',
    'The Registration box on the Signing in block, unticked, with the consequence of ticking it stated beneath.',
    {
      highlight: page.getByLabel('Let anyone create an account'),
      step: 2,
    },
  );

  await page.getByLabel('Let anyone create an account').check();
  await expect(page.getByText('Saving now will open registration.')).toBeVisible();
  await doc.capture(
    'registration-opening',
    'The Registration group with the box ticked, warning that saving now will open registration.',
    {
      focus: page
        .getByRole('group')
        .filter({ has: page.getByLabel('Let anyone create an account') }),
    },
  );

  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Saved.')).toBeVisible();
  await doc.capture('saved', 'The Signing in block confirming "Saved." above the settings.', {
    focus: signingIn,
  });
});

test('@doc Admin: email and password sign-in cannot be turned off yet', async ({ page, doc }) => {
  doc.guide('choose-how-people-sign-in');
  await page.goto('/admin');
  await expect(page.getByText('This cannot be turned off yet')).toBeVisible();
  await doc.capture(
    'password-locked',
    'The Email and password group with no switch, explaining that an owner has to sign in through the provider first.',
    {
      focus: page.getByRole('group', { name: 'Email and password' }),
    },
  );
});

test('@doc Admin: point the server at your own provider', async ({ page, doc }) => {
  doc.guide('choose-how-people-sign-in');
  await page.goto('/admin');
  await expect(page.getByRole('heading', { name: 'Signing in' })).toBeVisible();

  const ssoGroup = page
    .getByRole('group')
    .filter({ has: page.getByLabel('Offer sign-in through my provider') });
  await doc.capture(
    'sso-off',
    'The Single sign-on box on the Signing in block, unticked, with no provider fields showing.',
    {
      highlight: page.getByLabel('Offer sign-in through my provider'),
      step: 2,
    },
  );

  await page.getByLabel('Offer sign-in through my provider').check();
  await expect(page.getByLabel('Issuer URL')).toBeVisible();
  await doc.capture(
    'sso-fields',
    'The Single sign-on fields: Issuer URL, Client ID, Client secret saying "No secret set.", and the account-creation box.',
    {
      focus: ssoGroup,
    },
  );

  await page.getByLabel('Issuer URL').fill('https://id.example.test/realms/household');
  await page.getByLabel('Client ID').fill('budget-owl');
  await page.getByLabel('Client secret').fill(TYPED_SECRET);
  await page.getByLabel('Create an account for anybody who signs in through the provider').check();
  await doc.capture(
    'sso-filled',
    'The Single sign-on fields filled in, with the client secret hidden as dots.',
    {
      focus: ssoGroup,
    },
  );

  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Saved.')).toBeVisible();
  await expect(page.getByText('A secret is set.')).toBeVisible();
  await doc.capture(
    'sso-saved',
    'The saved Single sign-on settings: the client secret box empty again, with "A secret is set." beneath it.',
    {
      focus: ssoGroup,
    },
  );
});

test('@doc Admin: hide email and password sign-in once the provider works', async ({
  page,
  doc,
}) => {
  doc.guide('choose-how-people-sign-in');
  await mockApi(page, { session: 'owner', adminSettings: WITH_PROVIDER });
  await page.goto('/admin');
  await expect(page.getByRole('heading', { name: 'Signing in' })).toBeVisible();

  await doc.capture(
    'provider-proven',
    'The Single sign-on group on a server whose owner has signed in through the provider.',
    {
      focus: page
        .getByRole('group')
        .filter({ has: page.getByLabel('Offer sign-in through my provider') }),
    },
  );

  const passwordSwitch = page.getByLabel('Offer sign-in with an email address and a password');
  await expect(passwordSwitch).toBeChecked();
  await doc.capture(
    'password-switch',
    'The Email and password switch, now offered and ticked, on a server whose provider has been proved.',
    {
      highlight: passwordSwitch,
      step: 2,
    },
  );

  await passwordSwitch.uncheck();
  await expect(page.getByText(/deploy\/restore-password-login\.sh/)).toBeVisible();
  await doc.capture(
    'password-off-recovery',
    'The Email and password group after unticking the switch, naming the command that switches it back on from the server.',
    {
      focus: page.getByRole('group', { name: 'Email and password' }),
    },
  );
});

test('@doc Admin: replacing the client secret brings password sign-in back', async ({
  page,
  doc,
}) => {
  doc.guide('choose-how-people-sign-in');
  // A server where password sign-in is already hidden, so what rotation does is visible.
  await mockApi(page, {
    session: 'owner',
    adminSettings: { ...WITH_PROVIDER, passwordLoginEnabled: false },
  });
  await page.goto('/admin');
  await expect(page.getByLabel('Client secret')).toBeVisible();

  await doc.capture(
    'secret-field',
    'The Client secret box: empty, with "A secret is set." and the note that a stored secret is never shown back.',
    {
      focus: page.locator('.field').filter({ has: page.getByLabel('Client secret') }),
    },
  );

  await page.getByLabel('Client secret').fill(TYPED_SECRET);
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Saved.')).toBeVisible();
  await expect(page.getByText('Email and password sign-in is on.')).toBeVisible();
  await doc.capture(
    'secret-rotated',
    'After replacing the client secret: email and password sign-in is on again and cannot be turned off until an owner signs in through the provider.',
    {
      focus: page
        .locator('section')
        .filter({ has: page.getByRole('heading', { name: 'Signing in' }) }),
    },
  );
});

test('@doc Admin: turn single sign-on off again', async ({ page, doc }) => {
  doc.guide('choose-how-people-sign-in');
  await mockApi(page, { session: 'owner', adminSettings: WITH_PROVIDER });
  await page.goto('/admin');
  await expect(page.getByLabel('Offer sign-in through my provider')).toBeChecked();

  await page.getByLabel('Offer sign-in through my provider').uncheck();
  await expect(
    page.getByText(/Turning this off also switches email and password sign-in back on/),
  ).toBeVisible();
  await doc.capture(
    'sso-turning-off',
    'The Single sign-on group after unticking the box, warning that email and password sign-in comes back on.',
    {
      focus: page
        .getByRole('group')
        .filter({ has: page.getByLabel('Offer sign-in through my provider') }),
    },
  );

  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await doc.capture(
    'sso-off-confirm',
    'The window asking you to confirm turning single sign-on off.',
  );

  // What Cancel leaves behind. This spec used to assert the box stayed CLEARED, and captured a
  // picture of it, because that is what the screen did: nothing was sent, the server still had
  // the provider on, and the form went on showing it off with the issuer and client id hidden.
  // The screen has been fixed to put the box back, so the assertion is inverted and the capture
  // now shows a provider that is still configured.
  await page.getByRole('button', { name: 'Cancel' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByLabel('Offer sign-in through my provider')).toBeChecked();
  await expect(page.getByLabel('Issuer URL')).toBeVisible();
  await doc.capture(
    'sso-off-cancelled',
    "The Single sign-on group after cancelling: the box is ticked again and the provider's details are back.",
    {
      focus: page
        .getByRole('group')
        .filter({ has: page.getByLabel('Offer sign-in through my provider') }),
    },
  );
});

test('@doc Admin: saving with nothing changed', async ({ page, doc }) => {
  doc.guide('choose-how-people-sign-in');
  await page.goto('/admin');
  await expect(page.getByRole('heading', { name: 'Signing in' })).toBeVisible();

  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Nothing had changed, so nothing was saved.')).toBeVisible();
  await doc.capture(
    'nothing-changed',
    'The message shown when Save is selected and none of the settings differ from what is stored.',
    {
      focus: page
        .locator('section')
        .filter({ has: page.getByRole('heading', { name: 'Signing in' }) }),
    },
  );
});

test('@doc Admin: somebody else saved first', async ({ page, doc }) => {
  doc.guide('choose-how-people-sign-in');
  await mockApi(page, {
    session: 'owner',
    adminConflict: { registrationOpen: true },
  });
  await page.goto('/admin');
  await expect(page.getByRole('heading', { name: 'Signing in' })).toBeVisible();

  await page.getByLabel('Let anyone create an account').check();
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Somebody else saved first')).toBeVisible();
  await doc.capture(
    'conflict',
    'The notice saying somebody else saved first, naming which setting changed, with Take the latest and Save my changes anyway.',
    {
      focus: page
        .locator('section')
        .filter({ has: page.getByRole('heading', { name: 'Signing in' }) }),
    },
  );
});
