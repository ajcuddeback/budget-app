import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { apiError, buttonNamed, fakeAuth, FakeAuth, settle } from '../../testing/helpers';
import { Device } from './data/device.models';
import { summariseUserAgent } from './data/device-label';
import { DevicesService } from './data/devices.service';
import { DevicesPage } from './pages/devices.page';

const CHROME_LINUX = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36';

const HERE: Device = { id: 's-here', kind: 'SESSION', label: CHROME_LINUX, createdAt: '2026-10-02T08:15:00Z', lastUsedAt: '2026-10-02T09:40:00Z', expiresAt: null, current: true };
const MAC: Device = { id: 's-mac', kind: 'SESSION', label: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15', createdAt: '2026-09-30T19:02:00Z', lastUsedAt: null, expiresAt: null, current: false };
const PHONE: Device = { id: 't-phone', kind: 'BEARER', label: "Alex's Pixel", createdAt: '2026-08-14T10:00:00Z', lastUsedAt: '2026-10-02T06:30:00Z', expiresAt: null, current: false };

describe('summariseUserAgent', () => {
  it.each([
    [CHROME_LINUX, { browser: 'Chrome', system: 'Linux' }],
    [MAC.label, { browser: 'Safari', system: 'macOS' }],
    ['Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/139.0 Safari/537.36 Edg/139.0', { browser: 'Edge', system: 'Windows' }],
    ['Mozilla/5.0 (Windows NT 10.0) Chrome/139 Safari/537.36 OPR/110', { browser: 'Opera', system: 'Windows' }],
    ['Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0', { browser: 'Firefox', system: 'Linux' }],
    ['Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 Chrome/139 Mobile Safari/537.36', { browser: 'Chrome', system: 'Android' }],
    ['Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 Version/17.5 Mobile Safari/604.1', { browser: 'Safari', system: 'iOS' }],
    ['Mozilla/5.0 (X11; CrOS x86_64) Chrome/139', { browser: 'Chrome', system: 'ChromeOS' }],
  ])('recognises %s', (agent, expected) => {
    expect(summariseUserAgent(agent)).toEqual(expected);
  });

  it('returns nothing rather than guess', () => {
    expect(summariseUserAgent('Web browser')).toBeNull();
    expect(summariseUserAgent("Alex's Pixel")).toBeNull();
    expect(summariseUserAgent('')).toBeNull();
  });
});

describe('DevicesService', () => {
  it('lists the page and revokes by encoded handle', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const http = TestBed.inject(HttpTestingController);
    const service = TestBed.inject(DevicesService);
    let devices: unknown;

    service.list().subscribe((d) => (devices = d));
    http.expectOne((r) => r.url === '/api/auth/devices').flush({ content: [PHONE], page: 0, size: 200, totalElements: 1, totalPages: 1 });
    service.revoke('s-ab/c').subscribe();

    expect(devices).toEqual([PHONE]);
    expect(http.expectOne('/api/auth/devices/s-ab%2Fc').request.method).toBe('DELETE');
    http.verify();
  });
});

describe('DevicesPage', () => {
  let auth: FakeAuth;
  const service = { list: vi.fn(), revoke: vi.fn() };

  async function render() {
    auth = fakeAuth();
    service.list.mockReset();
    service.revoke.mockReset();
    service.list.mockReturnValue(of([HERE, MAC, PHONE]));
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: AuthService, useValue: auth }, { provide: DevicesService, useValue: service }],
    });
    const fixture = TestBed.createComponent(DevicesPage);
    await settle(fixture);
    return fixture;
  }

  it('lists browsers in words a person recognises, marks this one, and calls a phone by its own name', async () => {
    const fixture = await render();
    const rows = Array.from<HTMLElement>(fixture.nativeElement.querySelectorAll('.rows > li'));

    expect(rows).toHaveLength(3);
    expect(rows[0].textContent).toContain('Chrome on Linux');
    expect(rows[0].textContent).toContain('This device');
    expect(rows[1].textContent).toContain('Safari on macOS');
    expect(rows[2].textContent).toContain("Alex's Pixel");
    expect(rows[2].textContent).toContain('Mobile app');
    expect(rows[1].textContent).not.toContain('Last used');
  });

  it('says so when nothing is signed in', async () => {
    const fixture = await render();
    fixture.componentInstance['devices'].set([]);
    await settle(fixture);

    expect(fixture.nativeElement.textContent).toContain('Nothing is signed in.');
  });

  it('signs another device out at once and says so', async () => {
    const fixture = await render();
    service.revoke.mockReturnValue(of(undefined));

    (fixture.nativeElement.querySelectorAll('.rows > li')[1].querySelector('button') as HTMLButtonElement).click();
    await settle(fixture);

    expect(service.revoke).toHaveBeenCalledWith('s-mac');
    expect(fixture.nativeElement.querySelectorAll('.rows > li')).toHaveLength(2);
    expect(fixture.nativeElement.textContent).toContain('That device has been signed out.');
    expect(fixture.nativeElement.querySelector('dialog')).toBeNull();
  });

  it('warns before signing out the browser in use, then ends the session', async () => {
    const fixture = await render();
    service.revoke.mockReturnValue(of(undefined));

    (fixture.nativeElement.querySelectorAll('.rows > li')[0].querySelector('button') as HTMLButtonElement).click();
    await settle(fixture);
    expect(fixture.nativeElement.querySelector('dialog').textContent).toContain('Sign out of this browser?');
    expect(service.revoke).not.toHaveBeenCalled();

    fixture.nativeElement.querySelector('dialog .btn-danger').click();
    await settle(fixture);

    expect(service.revoke).toHaveBeenCalledWith('s-here');
    expect(auth.sessionEnded).toHaveBeenCalled();
  });

  it('lets the user back out of signing themselves out', async () => {
    const fixture = await render();
    (fixture.nativeElement.querySelectorAll('.rows > li')[0].querySelector('button') as HTMLButtonElement).click();
    await settle(fixture);

    buttonNamed(fixture.nativeElement.querySelector('dialog'), 'Cancel').click();
    await settle(fixture);

    expect(fixture.nativeElement.querySelector('dialog')).toBeNull();
    expect(service.revoke).not.toHaveBeenCalled();
  });

  it('explains a failed revocation and keeps the device listed', async () => {
    const fixture = await render();
    service.revoke.mockReturnValue(throwError(() => apiError(404, 'not-found')));

    (fixture.nativeElement.querySelectorAll('.rows > li')[1].querySelector('button') as HTMLButtonElement).click();
    await settle(fixture);

    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('could not be found');
    expect(fixture.nativeElement.querySelectorAll('.rows > li')).toHaveLength(3);
  });

  it('shows a failure to load, and retries', async () => {
    const fixture = await render();
    service.list.mockReturnValueOnce(throwError(() => apiError(500, 'internal-error')));
    fixture.componentInstance['load']();
    await settle(fixture);
    expect(fixture.nativeElement.querySelector('[role=alert]')).not.toBeNull();

    buttonNamed(fixture.nativeElement, 'Try again').click();
    await settle(fixture);

    expect(fixture.nativeElement.querySelectorAll('.rows > li')).toHaveLength(3);
  });
});
