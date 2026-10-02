import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { AuthService } from './core/auth.service';
import { CurrentUser } from './core/auth.models';

const OWNER: CurrentUser = {
  id: 'u1',
  email: 'alex@example.test',
  displayName: 'Alex',
  instanceAdmin: true,
  household: {
    householdId: 'h1',
    name: 'Rivera Household',
    baseCurrency: 'USD',
    role: 'OWNER',
    membershipId: 'm1',
    displayCurrency: null,
    locale: null,
  },
};

describe('App', () => {
  beforeEach(async () => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('creates the app', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('names the product in the header', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.brand')?.textContent).toContain('Budget Owl');
  });

  it('stamps the theme on the root element so the tokens can key off it', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    expect(['light', 'dark']).toContain(document.documentElement.getAttribute('data-theme'));
  });

  it('toggles between light and dark', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const before = document.documentElement.getAttribute('data-theme');

    fixture.nativeElement.querySelector('button').click();
    await fixture.whenStable();

    expect(document.documentElement.getAttribute('data-theme')).not.toBe(before);
  });

  it('offers no household navigation to someone who is signed out', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('nav')).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Sign out');
  });

  it('offers navigation and sign out once signed in, and signing out asks the server', async () => {
    const auth = TestBed.inject(AuthService);
    const logout = vi.spyOn(auth, 'logout').mockResolvedValue();
    // Reaching the private state through the public path a login takes.
    (auth as unknown as { current: { set(user: CurrentUser): void } }).current.set(OWNER);

    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    const links = Array.from<HTMLAnchorElement>(fixture.nativeElement.querySelectorAll('nav a')).map(
      (link) => link.textContent?.trim(),
    );
    expect(links).toEqual(['Household', 'Devices', 'Preferences']);

    const signOut = Array.from<HTMLButtonElement>(
      fixture.nativeElement.querySelectorAll('button'),
    ).find((button) => button.textContent?.includes('Sign out'));
    signOut?.click();
    expect(logout).toHaveBeenCalled();
  });
});
