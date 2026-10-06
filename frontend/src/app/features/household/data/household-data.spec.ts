import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { HouseholdService } from './household.service';
import { buildInviteLink } from './invite-link';

describe('HouseholdService', () => {
  let service: HouseholdService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(HouseholdService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('never puts a household id in a path: the server resolves it from membership', () => {
    service.current().subscribe();
    service.update({ name: 'N', baseCurrency: 'USD' }).subscribe();

    expect(http.expectOne((r) => r.method === 'GET').request.url).toBe('/api/households/current');
    expect(http.expectOne((r) => r.method === 'PUT').request.body).toEqual({ name: 'N', baseCurrency: 'USD' });
  });

  it('unwraps the page of members', () => {
    let members: unknown;
    service.members().subscribe((m) => (members = m));

    const request = http.expectOne((r) => r.url === '/api/households/current/members');
    expect(request.request.params.get('size')).toBe('200');
    request.flush({ content: [{ id: 'a' }], page: 0, size: 200, totalElements: 1, totalPages: 1 });

    expect(members).toEqual([{ id: 'a' }]);
  });

  it('addresses members by membership id, encoded, and sends only the role', () => {
    service.changeRole('m 1', 'VIEWER').subscribe();
    service.removeMember('m/2').subscribe();

    const patch = http.expectOne('/api/households/current/members/m%201');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({ role: 'VIEWER' });
    expect(http.expectOne('/api/households/current/members/m%2F2').request.method).toBe('DELETE');
  });

  it('creates and revokes invitations, and replaces own preferences in full', () => {
    service.createInvitation({ email: 'r@example.test', role: 'MEMBER' }).subscribe();
    service.revokeInvitation('i-1').subscribe();
    service.updateOwnPreferences({ displayCurrency: null, locale: 'de-DE' }).subscribe();

    expect(http.expectOne('/api/households/current/invitations').request.method).toBe('POST');
    expect(http.expectOne('/api/households/current/invitations/i-1').request.method).toBe('DELETE');
    const prefs = http.expectOne('/api/households/current/members/me');
    expect(prefs.request.method).toBe('PATCH');
    expect(prefs.request.body).toEqual({ displayCurrency: null, locale: 'de-DE' });
  });
});

describe('buildInviteLink', () => {
  it('builds the absolute link from the browser\'s own origin', () => {
    expect(buildInviteLink('/join/abc', 'https://budget.home.test')).toBe('https://budget.home.test/join/abc');
    expect(buildInviteLink('/join/abc', 'http://192.168.1.20:4200')).toBe('http://192.168.1.20:4200/join/abc');
  });

  it.each(['//evil.test/join/abc', 'https://evil.test/join/abc', 'join/abc', '', '/\\evil.test/x'])(
    'refuses a path that could point anywhere else: %s',
    (path) => {
      expect(buildInviteLink(path, 'https://budget.home.test')).toBeNull();
    },
  );

  it('refuses an origin that is not one', () => {
    expect(buildInviteLink('/join/abc', 'not an origin')).toBeNull();
  });
});
