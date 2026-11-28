import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { FormControl } from '@angular/forms';
import { issuerUrl } from '../../../shared/forms/validators';
import { InstanceSettings, accountStatusKey } from './admin.models';
import { AdminService } from './admin.service';
import {
  SigningInFormValue,
  buildSettingsPatch,
  differingSettings,
  settingsPatchIsEmpty,
} from './settings-patch';

/** A fixture string, not a credential. It exists to be followed through the patch builder. */
const TYPED_SECRET = 'a fixture value standing in for a client secret';

const SETTINGS: InstanceSettings = {
  registrationOpen: false,
  passwordLoginEnabled: true,
  passwordLoginLocked: false,
  oidcEnabled: true,
  oidcIssuerUri: 'https://id.example.test',
  oidcClientId: 'budget-owl',
  oidcClientSecretSet: true,
  oidcProvisioningEnabled: false,
  oidcOwnerLoginAt: '2026-11-01T08:00:00Z',
  updatedAt: '2026-11-01T08:00:00Z',
  version: 4,
};

const unchanged = (settings: InstanceSettings = SETTINGS): SigningInFormValue => ({
  registrationOpen: settings.registrationOpen,
  passwordLoginEnabled: settings.passwordLoginEnabled,
  oidcEnabled: settings.oidcEnabled,
  oidcIssuerUri: settings.oidcIssuerUri ?? '',
  oidcClientId: settings.oidcClientId ?? '',
  // As the form holds it on load: there is no stored secret to put in the control.
  oidcClientSecret: '',
  oidcProvisioningEnabled: settings.oidcProvisioningEnabled,
});

describe('buildSettingsPatch', () => {
  it('sends the version and nothing else when nothing was touched', () => {
    const patch = buildSettingsPatch(SETTINGS, unchanged());

    expect(patch).toEqual({ version: 4 });
    expect(settingsPatchIsEmpty(patch)).toBe(true);
  });

  it('never mentions the client secret unless one was typed', () => {
    const patch = buildSettingsPatch(SETTINGS, {
      ...unchanged(),
      registrationOpen: true,
    });

    // Present-and-blank means "clear it" to the server (`AdminRequests`), so an untouched form
    // must leave the field out entirely rather than send an empty string.
    expect(Object.keys(patch)).not.toContain('oidcClientSecret');
    expect(patch).toEqual({ version: 4, registrationOpen: true });
  });

  it('sends a typed secret exactly as it was typed', () => {
    const patch = buildSettingsPatch(SETTINGS, {
      ...unchanged(),
      oidcClientSecret: TYPED_SECRET,
    });

    expect(patch.oidcClientSecret).toBe(TYPED_SECRET);
  });

  it('treats a secret of nothing but spaces as nothing typed', () => {
    const patch = buildSettingsPatch(SETTINGS, { ...unchanged(), oidcClientSecret: '   ' });

    // The server reads a blank secret as "clear it". A stray space in the field is not a request
    // to remove the instance's only way of talking to its provider.
    expect(Object.keys(patch)).not.toContain('oidcClientSecret');
  });

  it('trims the issuer and the client id, and omits them when they did not move', () => {
    expect(
      buildSettingsPatch(SETTINGS, {
        ...unchanged(),
        oidcIssuerUri: '  https://id.example.test  ',
      }),
    ).toEqual({ version: 4 });

    expect(
      buildSettingsPatch(SETTINGS, { ...unchanged(), oidcIssuerUri: 'https://login.example.test' }),
    ).toEqual({ version: 4, oidcIssuerUri: 'https://login.example.test' });
  });

  it('says only that the provider is off when it is being turned off', () => {
    const patch = buildSettingsPatch(SETTINGS, {
      ...unchanged(),
      oidcEnabled: false,
      // Still in the controls, and deliberately not sent: the server clears the configuration
      // and switches password login back on, so describing the provider would be fiction.
      oidcIssuerUri: 'https://login.example.test',
      oidcClientSecret: TYPED_SECRET,
      oidcProvisioningEnabled: true,
    });

    expect(patch).toEqual({ version: 4, oidcEnabled: false });
  });

  it('carries the provisioning choice while the provider stays on', () => {
    expect(buildSettingsPatch(SETTINGS, { ...unchanged(), oidcProvisioningEnabled: true })).toEqual(
      { version: 4, oidcProvisioningEnabled: true },
    );
  });

  it('sends both halves of "configure it and hide passwords" in one request', () => {
    const fresh: InstanceSettings = {
      ...SETTINGS,
      oidcEnabled: false,
      oidcIssuerUri: null,
      oidcClientId: null,
      oidcClientSecretSet: false,
      oidcOwnerLoginAt: null,
      passwordLoginLocked: true,
    };

    expect(
      buildSettingsPatch(fresh, {
        ...unchanged(fresh),
        oidcEnabled: true,
        oidcIssuerUri: 'https://id.example.test',
        oidcClientId: 'budget-owl',
        oidcClientSecret: TYPED_SECRET,
      }),
    ).toEqual({
      version: 4,
      oidcEnabled: true,
      oidcIssuerUri: 'https://id.example.test',
      oidcClientId: 'budget-owl',
      oidcClientSecret: TYPED_SECRET,
    });
  });
});

describe('differingSettings', () => {
  it('names the fields two copies disagree about', () => {
    expect(
      differingSettings(SETTINGS, { ...SETTINGS, registrationOpen: true, version: 5 }),
    ).toEqual(['registrationOpen']);
  });

  it('notices a replaced secret without reading one', () => {
    const before = { ...SETTINGS, oidcClientSecretSet: false };

    expect(differingSettings(before, SETTINGS)).toEqual(['oidcClientSecretSet']);
  });

  it('finds nothing when only the version moved', () => {
    expect(differingSettings(SETTINGS, { ...SETTINGS, version: 9 })).toEqual([]);
  });
});

describe('issuerUrl', () => {
  const check = (value: string) => issuerUrl(new FormControl(value));

  it('accepts an https address', () => {
    expect(check('https://id.example.test/realms/owl')).toBeNull();
  });

  it('accepts http only for a provider on this machine', () => {
    expect(check('http://localhost:8080/realms/owl')).toBeNull();
    expect(check('http://127.0.0.1:8080')).toBeNull();
    // The client secret is sent to this host, so over the network it has to be https.
    expect(check('http://id.example.test')).toEqual({ issuerUrl: true });
  });

  it('rejects anything that is not a URL', () => {
    expect(check('id.example.test')).toEqual({ issuerUrl: true });
    expect(check('javascript:alert(1)')).toEqual({ issuerUrl: true });
  });

  it('says nothing about an empty field — that is the required validator job', () => {
    expect(check('')).toBeNull();
    expect(check('   ')).toBeNull();
  });
});

describe('accountStatusKey', () => {
  it('names the statuses the server sends', () => {
    expect(accountStatusKey('ACTIVE')).toBe('admin.status.ACTIVE');
    expect(accountStatusKey('DISABLED')).toBe('admin.status.DISABLED');
  });

  it('falls back rather than rendering a blank for a status it has not heard of', () => {
    expect(accountStatusKey('LOCKED_OUT')).toBe('admin.status.unknown');
  });
});

describe('AdminService', () => {
  let service: AdminService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AdminService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the instance, the settings and the accounts from their own routes', () => {
    service.instance().subscribe();
    const instance = http.expectOne('/api/admin/instance');
    expect(instance.request.method).toBe('GET');
    instance.flush({});

    service.settings().subscribe();
    const settings = http.expectOne('/api/admin/settings');
    expect(settings.request.method).toBe('GET');
    settings.flush({});

    // `/users`, not `/accounts`: an account here is a person who can sign in, and `accounts` is
    // already the word in this app for a place money sits.
    service.accounts().subscribe();
    const accounts = http.expectOne('/api/admin/users');
    expect(accounts.request.method).toBe('GET');
    accounts.flush({ content: [], total: 0 });
  });

  it('sends a changed secret in the body of a PATCH, never in the URL', () => {
    service.updateSettings({ version: 4, oidcClientSecret: TYPED_SECRET }).subscribe();

    const request = http.expectOne('/api/admin/settings');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ version: 4, oidcClientSecret: TYPED_SECRET });
    // A query parameter lands in browser history, a proxy log and a referrer header.
    expect(request.request.urlWithParams).toBe('/api/admin/settings');
    expect(request.request.params.keys()).toHaveLength(0);
    request.flush({});
  });
});
