import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { OWNER, OWNER_MEMBERSHIP, apiError, buttonNamed, fakeAuth, FakeAuth, settle } from '../../testing/helpers';
import { HouseholdService } from '../household/data/household.service';
import { PreferencesFormComponent } from './components/preferences-form.component';
import { PreferencesPage } from './pages/preferences.page';

describe('PreferencesFormComponent', () => {
  async function render(displayCurrency: string | null, locale: string | null) {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const fixture = TestBed.createComponent(PreferencesFormComponent);
    fixture.componentRef.setInput('membership', { ...OWNER_MEMBERSHIP, displayCurrency, locale });
    await settle(fixture);
    return fixture;
  }

  it('starts from the saved preferences, with "follow" selected when there are none', async () => {
    const fixture = await render(null, null);
    const root = fixture.nativeElement as HTMLElement;

    expect((root.querySelector('#pref-currency') as HTMLSelectElement).value).toBe('');
    expect((root.querySelector('#pref-currency') as HTMLSelectElement).options[0].textContent).toContain('Follow the household (USD)');
    expect((root.querySelector('#pref-locale') as HTMLSelectElement).value).toBe('');
  });

  it('sends both fields, with "follow" as null — a meaning, not a gap', async () => {
    const fixture = await render('EUR', 'de-DE');
    const saved = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    const root = fixture.nativeElement as HTMLElement;

    buttonNamed(root, 'Save').click();
    expect(saved).toHaveBeenLastCalledWith({ displayCurrency: 'EUR', locale: 'de-DE' });

    for (const id of ['#pref-currency', '#pref-locale']) {
      const select = root.querySelector(id) as HTMLSelectElement;
      select.value = '';
      select.dispatchEvent(new Event('change'));
    }
    buttonNamed(root, 'Save').click();
    expect(saved).toHaveBeenLastCalledWith({ displayCurrency: null, locale: null });
  });

  it('keeps a saved locale that is not on the list, and shows locales by their own names', async () => {
    const fixture = await render(null, 'eo-001');
    const select = fixture.nativeElement.querySelector('#pref-locale') as HTMLSelectElement;

    expect(select.options[1].value).toBe('eo-001');
    expect(Array.from(select.options).some((o) => /Deutsch/.test(o.textContent ?? ''))).toBe(true);
  });

  it('ignores a submit while saving', async () => {
    const fixture = await render(null, null);
    fixture.componentRef.setInput('busy', true);
    const saved = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    await settle(fixture);

    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));

    expect(saved).not.toHaveBeenCalled();
  });

  it('will not send a malformed currency', async () => {
    const fixture = await render(null, null);
    const saved = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    fixture.componentInstance.form.controls.displayCurrency.setValue('EURO');

    buttonNamed(fixture.nativeElement, 'Save').click();

    expect(saved).not.toHaveBeenCalled();
  });
});

describe('PreferencesPage', () => {
  let auth: FakeAuth;
  const service = { updateOwnPreferences: vi.fn() };

  async function render(user = OWNER) {
    auth = fakeAuth(user);
    service.updateOwnPreferences.mockReset();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: AuthService, useValue: auth }, { provide: HouseholdService, useValue: service }],
    });
    const fixture = TestBed.createComponent(PreferencesPage);
    await settle(fixture);
    return fixture;
  }

  it('saves, re-reads the profile so the language applies, and confirms', async () => {
    const fixture = await render();
    service.updateOwnPreferences.mockReturnValue(of({}));

    buttonNamed(fixture.nativeElement, 'Save').click();
    await settle(fixture);

    expect(service.updateOwnPreferences).toHaveBeenCalledWith({ displayCurrency: null, locale: null });
    expect(auth.refresh).toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Preferences saved.');
  });

  it('explains a failure and does not claim it saved', async () => {
    const fixture = await render();
    service.updateOwnPreferences.mockReturnValue(throwError(() => apiError(400, 'validation-failed')));

    buttonNamed(fixture.nativeElement, 'Save').click();
    await settle(fixture);

    expect(fixture.nativeElement.querySelector('[role=alert]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Preferences saved.');
  });

  it('shows the invitation prompt for a user with no household', async () => {
    const fixture = await render({ ...OWNER, household: null });

    expect(fixture.nativeElement.textContent).toContain('You are not part of a household yet');
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
  });
});
