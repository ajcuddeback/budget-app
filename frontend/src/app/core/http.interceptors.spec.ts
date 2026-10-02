import { HttpClient, provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';
import { ApiError } from './api-error';
import { apiErrorInterceptor, credentialsInterceptor } from './http.interceptors';

describe('HTTP interceptors', () => {
  let http: HttpClient;
  let controller: HttpTestingController;
  const sessionEnded = vi.fn().mockResolvedValue(undefined);

  beforeEach(() => {
    sessionEnded.mockClear();
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: { sessionEnded } },
        provideHttpClient(
          withInterceptors([credentialsInterceptor, apiErrorInterceptor]),
          withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
        ),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    controller.verify();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  it('sends the session cookie to this instance\'s API, and only there', () => {
    http.get('/api/auth/me').subscribe();
    http.get('/i18n/de.json').subscribe();
    http.get('https://elsewhere.test/api/x').subscribe();

    expect(controller.expectOne('/api/auth/me').request.withCredentials).toBe(true);
    expect(controller.expectOne('/i18n/de.json').request.withCredentials).toBe(false);
    expect(controller.expectOne('https://elsewhere.test/api/x').request.withCredentials).toBe(false);
  });

  it('puts the CSRF token on a write, under the name the server reads', () => {
    document.cookie = 'XSRF-TOKEN=fixture-token; path=/';

    http.post('/api/auth/login', {}).subscribe();

    expect(controller.expectOne('/api/auth/login').request.headers.get('X-XSRF-TOKEN')).toBe('fixture-token');
  });

  it('does not send the CSRF token on a read, or to another origin', () => {
    document.cookie = 'XSRF-TOKEN=fixture-token; path=/';

    http.get('/api/auth/me').subscribe();
    http.post('https://elsewhere.test/api/x', {}).subscribe();

    expect(controller.expectOne('/api/auth/me').request.headers.has('X-XSRF-TOKEN')).toBe(false);
    expect(controller.expectOne('https://elsewhere.test/api/x').request.headers.has('X-XSRF-TOKEN')).toBe(false);
  });

  it('turns a failure into a typed error, so callers never see a raw response', async () => {
    const result = new Promise<unknown>((resolve) => http.get('/api/households/current').subscribe({ error: resolve }));

    controller.expectOne('/api/households/current').flush(
      { code: 'not-a-member', title: 'English', password: 'must-not-travel' },
      { status: 403, statusText: 'Forbidden' },
    );
    const error = await result;

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 403, code: 'not-a-member' });
    expect(JSON.stringify(error)).not.toContain('must-not-travel');
  });

  it('sends a signed-in user whose session was revoked to sign-in', async () => {
    const result = new Promise((resolve) => http.get('/api/households/current').subscribe({ error: resolve }));

    controller.expectOne('/api/households/current').flush({ code: 'not-authenticated' }, { status: 401, statusText: 'x' });
    await result;

    expect(sessionEnded).toHaveBeenCalledTimes(1);
  });

  it.each([
    '/api/auth/me',
    '/api/auth/login',
    '/api/auth/logout',
    '/api/setup/status',
    '/api/invitations/abc/accept',
  ])('does not treat a 401 from %s as an expired session', async (url) => {
    const result = new Promise((resolve) => http.get(url).subscribe({ error: resolve }));

    controller.expectOne(url).flush({}, { status: 401, statusText: 'x' });
    await result;

    expect(sessionEnded).not.toHaveBeenCalled();
  });

  it('leaves a failure on a non-API request exactly as it was', async () => {
    const result = new Promise<unknown>((resolve) => http.get('/i18n/zz.json').subscribe({ error: resolve }));

    controller.expectOne('/i18n/zz.json').flush('', { status: 404, statusText: 'x' });

    expect(await result).not.toBeInstanceOf(ApiError);
  });
});
