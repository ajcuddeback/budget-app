import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { OWNER, PASSPHRASE } from '../testing/helpers';
import { AuthService } from './auth.service';
import { apiErrorInterceptor, credentialsInterceptor } from './http.interceptors';
import { I18nService } from './i18n/i18n.service';

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([credentialsInterceptor, apiErrorInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
  });

  afterEach(() => http.verify());

  it('knows nobody is signed in when the server says 401, and asks only once', async () => {
    const first = auth.ensureLoaded();
    const second = auth.ensureLoaded();
    http.expectOne('/api/auth/me').flush({ code: 'not-authenticated' }, { status: 401, statusText: 'x' });
    await Promise.all([first, second]);

    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.loaded()).toBe(true);
    await auth.ensureLoaded();
  });

  it('holds the profile of whoever the server says is signed in', async () => {
    const loading = auth.ensureLoaded();
    http.expectOne('/api/auth/me').flush(OWNER);
    await loading;

    expect(auth.user()?.displayName).toBe('Alex Rivera');
    expect(auth.membership()?.role).toBe('OWNER');
    expect(auth.isOwner()).toBe(true);
  });

  it('treats a signed-in user with no household as having no membership', async () => {
    const loading = auth.refresh();
    http.expectOne('/api/auth/me').flush({ ...OWNER, household: undefined });
    await loading;

    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.membership()).toBeNull();
    expect(auth.isOwner()).toBe(false);
  });

  it('assumes signed out, not signed in, when the first answer never comes', async () => {
    const loading = auth.ensureLoaded();
    http.expectOne('/api/auth/me').error(new ProgressEvent('error'));
    await loading;

    expect(auth.isAuthenticated()).toBe(false);
  });

  it('does not sign the UI out because a later refresh hit a network blip', async () => {
    const first = auth.refresh();
    http.expectOne('/api/auth/me').flush(OWNER);
    await first;
    const second = auth.refresh();
    http.expectOne('/api/auth/me').error(new ProgressEvent('error'));
    await second;

    expect(auth.isAuthenticated()).toBe(true);
  });

  it('does sign the UI out when a later refresh says 401', async () => {
    const first = auth.refresh();
    http.expectOne('/api/auth/me').flush(OWNER);
    await first;
    const second = auth.refresh();
    http.expectOne('/api/auth/me').flush({}, { status: 401, statusText: 'x' });
    await second;

    expect(auth.isAuthenticated()).toBe(false);
  });

  it('logs in with credentials, takes the profile, and applies the member\'s language', async () => {
    const use = vi.spyOn(TestBed.inject(I18nService), 'use').mockResolvedValue();
    const login = auth.login({ email: 'alex@example.test', password: PASSPHRASE });
    const request = http.expectOne('/api/auth/login');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.method).toBe('POST');
    request.flush({ ...OWNER, household: { ...OWNER.household, locale: 'de-DE' } });
    await login;

    expect(auth.isAuthenticated()).toBe(true);
    expect(use).toHaveBeenCalledWith('de-DE');
  });

  it('rejects a login with a typed error and stays signed out', async () => {
    const login = auth.login({ email: 'a@example.test', password: 'nope' });
    http.expectOne('/api/auth/login').flush(
      { code: 'authentication-failed' },
      { status: 401, statusText: 'x' },
    );

    await expect(login).rejects.toMatchObject({ status: 401, code: 'authentication-failed' });
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('never writes a credential to browser storage', async () => {
    const before = { local: localStorage.length, session: sessionStorage.length };
    const login = auth.login({ email: 'alex@example.test', password: PASSPHRASE });
    http.expectOne('/api/auth/login').flush(OWNER);
    await login;

    expect({ local: localStorage.length, session: sessionStorage.length }).toEqual(before);
    for (const store of [localStorage, sessionStorage]) {
      for (let i = 0; i < store.length; i++) {
        const key = store.key(i) ?? '';
        expect(`${key}=${store.getItem(key)}`).not.toContain(PASSPHRASE);
      }
    }
  });

  it('signs out here whatever the server answers, and goes to sign-in', async () => {
    const first = auth.refresh();
    http.expectOne('/api/auth/me').flush(OWNER);
    await first;

    const out = auth.logout();
    http.expectOne('/api/auth/logout').flush({}, { status: 401, statusText: 'x' });
    await out;

    expect(auth.isAuthenticated()).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('can sign out without leaving the page', async () => {
    const out = auth.logout({ redirect: false });
    http.expectOne('/api/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    await out;

    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('routes to sign-in with a reason when the session ends, and only if someone was signed in', async () => {
    await auth.sessionEnded();
    expect(router.navigate).not.toHaveBeenCalled();

    const loading = auth.refresh();
    http.expectOne('/api/auth/me').flush(OWNER);
    await loading;
    await auth.sessionEnded();

    expect(auth.isAuthenticated()).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/login'], { queryParams: { reason: 'expired' } });
  });
});
