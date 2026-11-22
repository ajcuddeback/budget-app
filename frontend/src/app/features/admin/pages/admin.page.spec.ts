import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { CurrentUser } from '../../../core/auth.models';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import {
  FakeAuth,
  OWNER,
  apiError,
  buttonNamed,
  fakeAuth,
  settle,
  type as typeInto,
} from '../../../testing/helpers';
import {
  AdminAccount,
  InstanceInfo,
  InstanceSettings,
  UpdateSettingsRequest,
} from '../data/admin.models';
import { AdminService } from '../data/admin.service';
import { AdminPage } from './admin.page';

/** Not a credential: a fixture string, typed into the form to prove where it goes. */
const TYPED_SECRET = 'a fixture value standing in for a client secret';

const INSTANCE: InstanceInfo = {
  version: '0.9.0',
  setupCompletedAt: '2026-03-04T10:15:00Z',
  administratorId: 'u1',
  administratorEmail: 'alex@example.test',
  administratorName: 'Alex Rivera',
  accountCount: 2,
};

/** A fresh instance: password login only, nothing configured. */
const FRESH: InstanceSettings = {
  registrationOpen: false,
  passwordLoginEnabled: true,
  passwordLoginLocked: true,
  oidcEnabled: false,
  oidcIssuerUri: null,
  oidcClientId: null,
  oidcClientSecretSet: false,
  oidcProvisioningEnabled: false,
  oidcOwnerLoginAt: null,
  updatedAt: '2026-03-04T10:15:00Z',
  version: 7,
};

/** A provider configured, and an owner has proved it works — so the lockout no longer applies. */
const OIDC_ON: InstanceSettings = {
  ...FRESH,
  passwordLoginLocked: false,
  oidcEnabled: true,
  oidcIssuerUri: 'https://id.example.test',
  oidcClientId: 'budget-owl',
  oidcClientSecretSet: true,
  oidcProvisioningEnabled: false,
  oidcOwnerLoginAt: '2026-11-01T08:00:00Z',
};

const ADMIN_ACCOUNT: AdminAccount = {
  id: 'u1',
  email: 'alex@example.test',
  displayName: 'Alex Rivera',
  status: 'ACTIVE',
  instanceAdmin: true,
  createdAt: '2026-03-04T10:15:00Z',
};

const PARTNER: AdminAccount = {
  id: 'u2',
  email: 'sam@example.test',
  displayName: 'Sam Rivera',
  status: 'DISABLED',
  instanceAdmin: false,
  createdAt: '2026-05-20T18:30:00Z',
};

describe('AdminPage', () => {
  let auth: FakeAuth;
  const admin = {
    instance: vi.fn(),
    settings: vi.fn(),
    updateSettings: vi.fn(),
    accounts: vi.fn(),
  };

  async function render(
    options: {
      user?: CurrentUser;
      instance?: InstanceInfo;
      settings?: InstanceSettings;
      accounts?: readonly AdminAccount[];
      loadFails?: boolean;
    } = {},
  ): Promise<ComponentFixture<AdminPage>> {
    auth = fakeAuth(options.user ?? OWNER);
    for (const fn of Object.values(admin)) {
      fn.mockReset();
    }
    admin.instance.mockReturnValue(
      options.loadFails
        ? throwError(() => apiError(403, 'not-instance-admin'))
        : of(options.instance ?? INSTANCE),
    );
    admin.settings.mockReturnValue(of(options.settings ?? FRESH));
    admin.accounts.mockReturnValue(
      of({
        content: [...(options.accounts ?? [ADMIN_ACCOUNT, PARTNER])],
        total: (options.accounts ?? [ADMIN_ACCOUNT, PARTNER]).length,
      }),
    );

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: AdminService, useValue: admin },
      ],
    });
    await TestBed.inject(I18nService).use('en-GB');
    const fixture = TestBed.createComponent(AdminPage);
    await settle(fixture);
    return fixture;
  }

  const root = (fixture: ComponentFixture<AdminPage>): HTMLElement => fixture.nativeElement;
  const text = (fixture: ComponentFixture<AdminPage>): string =>
    (fixture.nativeElement as HTMLElement).textContent ?? '';

  function field(fixture: ComponentFixture<AdminPage>, selector: string): HTMLInputElement | null {
    return root(fixture).querySelector<HTMLInputElement>(selector);
  }

  /** A checkbox is toggled the way a person toggles one: click, and let the form hear about it. */
  function toggle(fixture: ComponentFixture<AdminPage>, selector: string): void {
    const box = field(fixture, selector);
    if (!box) {
      throw new Error(`no checkbox at ${selector}`);
    }
    box.checked = !box.checked;
    box.dispatchEvent(new Event('change', { bubbles: true }));
    box.dispatchEvent(new Event('input', { bubbles: true }));
  }

  async function save(fixture: ComponentFixture<AdminPage>): Promise<void> {
    buttonNamed(root(fixture), /^\s*Save\s*$/).click();
    await settle(fixture);
  }

  const lastPatch = (): UpdateSettingsRequest =>
    admin.updateSettings.mock.lastCall?.[0] as UpdateSettingsRequest;

  const dialog = (fixture: ComponentFixture<AdminPage>): HTMLElement => {
    const found = root(fixture).querySelector<HTMLElement>('dialog');
    if (!found) {
      throw new Error('no dialog is open');
    }
    return found;
  };

  // --------------------------------------------------------------------- 1. this instance

  it('reports what the operator came to check, and not one figure of anybody money', async () => {
    const fixture = await render();

    expect(text(fixture)).toContain('0.9.0');
    expect(text(fixture)).toContain('Alex Rivera');
    expect(text(fixture)).toContain('alex@example.test');
    // Two accounts: a count of people, never of their money.
    expect(root(fixture).querySelector('.facts')?.textContent).toContain('2');
    // Nothing on this screen is a currency figure (ADR-0026). If one ever appears, it is a defect
    // in the feature and not in this assertion.
    expect(text(fixture)).not.toMatch(/[$£€¥]/);
  });

  it('says when setup has no recorded date rather than showing an empty fact', async () => {
    const fixture = await render({ instance: { ...INSTANCE, setupCompletedAt: null } });

    expect(text(fixture)).toContain('Not recorded');
  });

  // ------------------------------------------------------------------- 2. the client secret

  it('leaves the client secret empty on load and sends it only once it is typed', async () => {
    const fixture = await render({ settings: OIDC_ON });

    expect(field(fixture, '#admin-oidc-secret')?.value).toBe('');

    // An unrelated change must not mention the secret: the server reads a present-but-blank one
    // as "clear it", so a form that always sent the field would wipe the stored secret.
    toggle(fixture, '#admin-registration');
    admin.updateSettings.mockReturnValue(of({ ...OIDC_ON, registrationOpen: true, version: 8 }));
    await save(fixture);

    expect(admin.updateSettings).toHaveBeenCalledTimes(1);
    expect(lastPatch()).toEqual({ version: 7, registrationOpen: true });
    expect(Object.keys(lastPatch())).not.toContain('oidcClientSecret');

    // Saved, and the input is empty again: there is still nothing to show in it.
    expect(field(fixture, '#admin-oidc-secret')?.value).toBe('');

    typeInto(root(fixture), '#admin-oidc-secret', TYPED_SECRET);
    admin.updateSettings.mockReturnValue(of({ ...OIDC_ON, registrationOpen: true, version: 9 }));
    await save(fixture);

    expect(lastPatch().oidcClientSecret).toBe(TYPED_SECRET);
    expect(lastPatch().version).toBe(8);
  });

  it('says a secret is set in words, with no value of one anywhere in the page', async () => {
    const fixture = await render({ settings: OIDC_ON });

    expect(text(fixture)).toContain('A secret is set.');
    expect(text(fixture)).not.toContain('No secret set.');

    // Not a row of dots standing in for a value this screen does not have, and not an attribute
    // carrying one either: the only thing the API says about the secret is that there is one.
    const secret = field(fixture, '#admin-oidc-secret');
    expect(secret?.value).toBe('');
    expect(secret?.getAttribute('value')).toBeNull();
    expect(root(fixture).innerHTML).not.toContain('•');
    expect(root(fixture).innerHTML).not.toContain('***');
  });

  it('says no secret is set when there is none', async () => {
    const fixture = await render({
      settings: { ...OIDC_ON, oidcClientSecretSet: false },
    });

    expect(text(fixture)).toContain('No secret set.');
  });

  it('refuses to submit a provider with no secret when none is stored', async () => {
    const fixture = await render({ settings: { ...FRESH, passwordLoginLocked: true } });

    toggle(fixture, '#admin-oidc');
    await settle(fixture);
    typeInto(root(fixture), '#admin-oidc-issuer', 'https://id.example.test');
    typeInto(root(fixture), '#admin-oidc-client-id', 'budget-owl');
    await save(fixture);

    // The server would refuse an incomplete provider; the person finds out before submitting.
    expect(admin.updateSettings).not.toHaveBeenCalled();
    expect(text(fixture)).toContain('This field is required.');
  });

  it('refuses an issuer that is not https, unless it is on this machine', async () => {
    const fixture = await render({ settings: OIDC_ON });

    typeInto(root(fixture), '#admin-oidc-issuer', 'http://id.example.test');
    await save(fixture);

    expect(admin.updateSettings).not.toHaveBeenCalled();
    expect(text(fixture)).toContain('starting with https://');

    typeInto(root(fixture), '#admin-oidc-issuer', 'http://localhost:8080/realms/owl');
    admin.updateSettings.mockReturnValue(of({ ...OIDC_ON, version: 8 }));
    await save(fixture);

    expect(lastPatch().oidcIssuerUri).toBe('http://localhost:8080/realms/owl');
  });

  // ------------------------------------------------------------------- 3. the lockout rule

  it('offers no way to turn password sign-in off while the lockout applies', async () => {
    const fixture = await render({ settings: FRESH });

    // No control at all, rather than one that answers 409: the rule is stated beside the setting
    // it governs (security-model.md).
    expect(field(fixture, '#admin-password-login')).toBeNull();
    expect(text(fixture)).toContain('This cannot be turned off yet');
    expect(text(fixture)).toContain('An owner has to complete a sign-in through your provider');
    expect(text(fixture)).toContain('Email and password sign-in is on.');
  });

  it('offers the switch once an owner has proved the other way in works', async () => {
    const fixture = await render({ settings: OIDC_ON });

    expect(field(fixture, '#admin-password-login')).not.toBeNull();
    expect(text(fixture)).toContain('An owner signed in through your provider on');
    expect(text(fixture)).not.toContain('This cannot be turned off yet');
  });

  it('says the provider is not proved yet while it is on and nobody has used it', async () => {
    const fixture = await render({
      settings: { ...OIDC_ON, passwordLoginLocked: true, oidcOwnerLoginAt: null },
    });

    expect(text(fixture)).toContain('No owner has signed in through your provider yet');
    expect(field(fixture, '#admin-password-login')).toBeNull();
  });

  it('names the way back in before password sign-in is hidden', async () => {
    const fixture = await render({ settings: OIDC_ON });

    toggle(fixture, '#admin-password-login');
    await settle(fixture);

    // The command exists (deploy/restore-password-login.sh) and the person is told about it at
    // the moment they are about to need it, not after.
    expect(text(fixture)).toContain('deploy/restore-password-login.sh');
  });

  it('explains a refusal that arrives anyway, and shows what is now true', async () => {
    const fixture = await render({ settings: OIDC_ON });
    toggle(fixture, '#admin-password-login');
    admin.updateSettings.mockReturnValue(throwError(() => apiError(409, 'password-login-locked')));
    // The state moved under the person: the provider was turned off in another tab.
    admin.settings.mockReturnValue(of({ ...FRESH, version: 8 }));
    await save(fixture);

    expect(text(fixture)).toContain('cannot be turned off yet');
    // And the form now shows the instance as it is, so the refused switch is not offered again.
    expect(field(fixture, '#admin-password-login')).toBeNull();
  });

  // ------------------------------------------------------- 4. turning the provider off

  it('says password sign-in comes back before turning the provider off is confirmed', async () => {
    const fixture = await render({ settings: OIDC_ON });

    toggle(fixture, '#admin-oidc');
    await settle(fixture);

    // Said as soon as the box is cleared, before anything is submitted.
    expect(text(fixture)).toContain(
      'Turning this off also switches email and password sign-in back on',
    );

    await save(fixture);

    // Nothing has been sent: the consequence is confirmed first.
    expect(admin.updateSettings).not.toHaveBeenCalled();
    expect(dialog(fixture).textContent).toContain('Turn off single sign-on?');
    expect(dialog(fixture).textContent).toContain('will be switched back on');

    admin.updateSettings.mockReturnValue(of({ ...FRESH, version: 8 }));
    buttonNamed(dialog(fixture), 'Turn it off').click();
    await settle(fixture);

    // Only the one field: the server clears the rest, so describing it would be fiction.
    expect(lastPatch()).toEqual({ version: 7, oidcEnabled: false });
  });

  it('sends nothing when the confirmation is cancelled, and puts the switch back', async () => {
    const fixture = await render({ settings: OIDC_ON });
    typeInto(root(fixture), '#admin-oidc-client-id', 'corrected-client-id');

    toggle(fixture, '#admin-oidc');
    await settle(fixture);
    await save(fixture);
    buttonNamed(dialog(fixture), 'Cancel').click();
    await settle(fixture);

    expect(admin.updateSettings).not.toHaveBeenCalled();
    expect(root(fixture).querySelector('dialog')).toBeNull();

    // "Nothing was sent" was all this asserted, and it passed while the screen went on showing
    // the provider as off. The server still has it on, and the issuer and client id fields are
    // hidden while the box is clear — so a cancelled confirmation looked like a lost
    // configuration, and the next Save re-opened the confirmation that had just been declined.
    const box = root(fixture).querySelector<HTMLInputElement>('#admin-oidc');
    expect(box?.checked).toBe(true);
    expect(text(fixture)).not.toContain('switches email and password sign-in back on');

    // And only that control moved: cancelling one thing does not discard the other.
    const clientId = root(fixture).querySelector<HTMLInputElement>('#admin-oidc-client-id');
    expect(clientId?.value).toBe('corrected-client-id');
  });

  // ----------------------------------------------------------------- 5. opening registration

  it('says what opening registration means, at the switch and in plain words', async () => {
    const fixture = await render();

    const note = root(fixture).querySelector('#admin-registration-note');
    expect(note?.textContent).toContain(
      'anybody who can reach this server over the network can create themselves an account',
    );
    // Beside the control, and the control points at it for a screen reader.
    expect(field(fixture, '#admin-registration')?.getAttribute('aria-describedby')).toBe(
      'admin-registration-note',
    );

    toggle(fixture, '#admin-registration');
    await settle(fixture);

    expect(text(fixture)).toContain('Saving now will open registration.');
  });

  // ----------------------------------------------------------------------- 6. conflicts

  it('keeps the form and explains itself when somebody else saved first', async () => {
    const fixture = await render({ settings: FRESH });
    admin.updateSettings.mockReturnValue(throwError(() => apiError(409, 'stale-version')));
    // Somebody else set a client secret while this page was open.
    admin.settings.mockReturnValue(of({ ...FRESH, oidcClientSecretSet: true, version: 8 }));

    toggle(fixture, '#admin-registration');
    await save(fixture);

    expect(text(fixture)).toContain('Somebody else saved first');
    expect(text(fixture)).toContain('nothing has been saved');
    // The field's name, never its value: a conflict notice is the last place a secret should be.
    expect(text(fixture)).toContain('What changed:');
    expect(text(fixture)).toContain('Client secret');
    expect(root(fixture).innerHTML).not.toContain('•');
    // What they chose is still on screen, and nothing has been written.
    expect(field(fixture, '#admin-registration')?.checked).toBe(true);
    expect(admin.updateSettings).toHaveBeenCalledTimes(1);
    // Nothing goes out again until they decide.
    expect(root(fixture).querySelector<HTMLButtonElement>('button[type=submit]')?.disabled).toBe(
      true,
    );
  });

  it('sends the loser of a conflict against the version it has shown them', async () => {
    const fixture = await render({ settings: FRESH });
    admin.updateSettings.mockReturnValueOnce(throwError(() => apiError(409, 'stale-version')));
    admin.settings.mockReturnValue(of({ ...FRESH, oidcClientSecretSet: true, version: 8 }));

    toggle(fixture, '#admin-registration');
    await save(fixture);

    admin.updateSettings.mockReturnValue(
      of({ ...FRESH, registrationOpen: true, oidcClientSecretSet: true, version: 9 }),
    );
    buttonNamed(root(fixture), 'Save my changes anyway').click();
    await settle(fixture);

    // Version 8 — the one they were shown — and only the field they changed, so the other
    // administrator's change is not silently undone.
    expect(lastPatch()).toEqual({ version: 8, registrationOpen: true });
    expect(text(fixture)).toContain('Saved.');
  });

  it('lets the loser of a conflict take the latest instead', async () => {
    const fixture = await render({ settings: FRESH });
    admin.updateSettings.mockReturnValueOnce(throwError(() => apiError(409, 'stale-version')));
    admin.settings.mockReturnValue(of({ ...FRESH, registrationOpen: true, version: 8 }));

    toggle(fixture, '#admin-registration');
    await save(fixture);
    buttonNamed(root(fixture), 'Take the latest').click();
    await settle(fixture);

    expect(text(fixture)).not.toContain('Somebody else saved first');
    expect(field(fixture, '#admin-registration')?.checked).toBe(true);
    expect(admin.updateSettings).toHaveBeenCalledTimes(1);
  });

  it('sends nothing when nothing has changed', async () => {
    const fixture = await render({ settings: OIDC_ON });

    await save(fixture);

    expect(admin.updateSettings).not.toHaveBeenCalled();
    expect(text(fixture)).toContain('Nothing had changed');
  });

  // ------------------------------------------------------------------------ 7. accounts

  it('lists who can sign in, read-only, and marks the one administrator', async () => {
    const fixture = await render();

    const rows = Array.from(root(fixture).querySelectorAll<HTMLElement>('.rows .row'));
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('Alex Rivera');
    expect(rows[0].textContent).toContain('Administrator');
    expect(rows[0].textContent).toContain('You');
    expect(rows[0].textContent).toContain('Active');
    expect(rows[1].textContent).toContain('Sam Rivera');
    expect(rows[1].textContent).toContain('Disabled');
    expect(rows[1].textContent).not.toContain('Administrator');
    // Read-only in this slice: no row offers an action.
    for (const row of rows) {
      expect(row.querySelector('button')).toBeNull();
      expect(row.querySelector('select')).toBeNull();
    }
  });

  it('says so when there is nobody to list', async () => {
    const fixture = await render({ accounts: [] });

    expect(text(fixture)).toContain('Nobody can sign in.');
  });

  // --------------------------------------------------------------------- 8. failures

  it('explains a failed load and offers to try again', async () => {
    const fixture = await render({ loadFails: true });

    // The guard and the nav both hide this screen, but the API is what refuses it — and a refusal
    // is a sentence, not a blank page.
    expect(text(fixture)).toContain('Only the person who administers this instance');

    admin.instance.mockReturnValue(of(INSTANCE));
    buttonNamed(root(fixture), 'Try again').click();
    await settle(fixture);

    expect(text(fixture)).toContain('0.9.0');
  });

  it('asks each of the three read endpoints once, and writes nothing on arrival', async () => {
    const fixture = await render();

    expect(admin.instance).toHaveBeenCalledTimes(1);
    expect(admin.settings).toHaveBeenCalledTimes(1);
    expect(admin.accounts).toHaveBeenCalledTimes(1);
    expect(admin.updateSettings).not.toHaveBeenCalled();
    expect(text(fixture)).toContain('Signing in');
  });
});
