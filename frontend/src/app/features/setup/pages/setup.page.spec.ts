import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../../core/auth.service';
import { SetupService } from '../../../core/setup.service';
import { apiError, buttonNamed, fakeAuth, FakeAuth, settle, type as typeInto } from '../../../testing/helpers';
import { SetupPage } from './setup.page';

describe('SetupPage', () => {
  let auth: FakeAuth;
  let router: Router;
  const createFirstUser = vi.fn();

  async function render() {
    auth = fakeAuth(null);
    createFirstUser.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: SetupService, useValue: { createFirstUser } },
      ],
    });
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(SetupPage);
    await settle(fixture);
    return fixture;
  }

  function fill(root: HTMLElement): void {
    typeInto(root, '#setup-name', ' Alex Rivera ');
    typeInto(root, '#setup-email', ' alex@example.test ');
    typeInto(root, '#setup-password', 'a long fixture passphrase');
    typeInto(root, '#setup-household', 'Rivera Household');
  }

  it('tells the operator what the operator can see', async () => {
    const fixture = await render();

    expect(fixture.nativeElement.textContent).toContain(
      'you will be able to see everything anyone in your household records',
    );
  });

  it('does not submit an incomplete form, and says what is missing', async () => {
    const fixture = await render();

    buttonNamed(fixture.nativeElement, 'Create account').click();
    await settle(fixture);

    expect(createFirstUser).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelectorAll('.field-error').length).toBeGreaterThanOrEqual(4);
  });

  it('creates the account with trimmed values, signs in with the same credentials, and goes home', async () => {
    const fixture = await render();
    createFirstUser.mockReturnValue(of({}));
    fill(fixture.nativeElement);

    buttonNamed(fixture.nativeElement, 'Create account').click();
    await settle(fixture);

    expect(createFirstUser).toHaveBeenCalledWith({
      email: 'alex@example.test',
      displayName: 'Alex Rivera',
      password: 'a long fixture passphrase',
      householdName: 'Rivera Household',
      baseCurrency: 'USD',
    });
    expect(auth.login).toHaveBeenCalledWith({ email: 'alex@example.test', password: 'a long fixture passphrase' });
    expect(router.navigate).toHaveBeenCalledWith(['/']);
  });

  it('falls back to sign-in, with a note, if the automatic sign-in fails', async () => {
    const fixture = await render();
    createFirstUser.mockReturnValue(of({}));
    auth.login.mockRejectedValue(apiError(429, 'rate-limited'));
    fill(fixture.nativeElement);

    buttonNamed(fixture.nativeElement, 'Create account').click();
    await settle(fixture);

    expect(router.navigate).toHaveBeenCalledWith(['/login'], { queryParams: { reason: 'created' } });
  });

  it('explains a refusal and lets them try again', async () => {
    const fixture = await render();
    createFirstUser.mockReturnValue(throwError(() => apiError(400, 'password-unacceptable', { reason: 'breached' })));
    fill(fixture.nativeElement);

    buttonNamed(fixture.nativeElement, 'Create account').click();
    await settle(fixture);

    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('commonly used passwords');
    expect(buttonNamed(fixture.nativeElement, 'Create account').disabled).toBe(false);
    expect(auth.login).not.toHaveBeenCalled();
  });

  it('says so when setup has already been done', async () => {
    const fixture = await render();
    createFirstUser.mockReturnValue(throwError(() => apiError(409, 'setup-already-complete')));
    fill(fixture.nativeElement);

    buttonNamed(fixture.nativeElement, 'Create account').click();
    await settle(fixture);

    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('already been set up');
  });

  it('cannot be submitted twice while the first request is in flight', async () => {
    const fixture = await render();
    createFirstUser.mockReturnValue(new (await import('rxjs')).Subject());
    fill(fixture.nativeElement);

    buttonNamed(fixture.nativeElement, 'Create account').click();
    await settle(fixture);
    const busy = buttonNamed(fixture.nativeElement, 'Creating account');
    busy.click();
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));

    expect(busy.disabled).toBe(true);
    expect(createFirstUser).toHaveBeenCalledTimes(1);
  });
});
