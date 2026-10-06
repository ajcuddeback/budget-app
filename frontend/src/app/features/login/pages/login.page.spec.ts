import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService } from '../../../core/auth.service';
import { PASSPHRASE, apiError, buttonNamed, fakeAuth, FakeAuth, settle, type as typeInto } from '../../../testing/helpers';
import { LoginPage } from './login.page';

describe('LoginPage', () => {
  let auth: FakeAuth;
  let router: Router;

  async function render(url = '/login') {
    auth = fakeAuth(null);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'login', component: LoginPage }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
      ],
    });
    router = TestBed.inject(Router);
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url, LoginPage);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    await settle(harness.fixture);
    return harness.fixture;
  }

  function signIn(root: HTMLElement, passphrase = PASSPHRASE): void {
    typeInto(root, '#login-email', ' alex@example.test ');
    typeInto(root, '#login-password', passphrase);
    buttonNamed(root, 'Sign in').click();
  }

  it('signs in with the trimmed email and goes home', async () => {
    const fixture = await render();

    signIn(fixture.nativeElement);
    await settle(fixture);

    expect(auth.login).toHaveBeenCalledWith({ email: 'alex@example.test', password: PASSPHRASE });
    expect(router.navigate).toHaveBeenCalledWith(['/']);
  });

  it('does not ask the server for an empty form', async () => {
    const fixture = await render();

    buttonNamed(fixture.nativeElement, 'Sign in').click();
    await settle(fixture);

    expect(auth.login).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('This field is required.');
  });

  it('gives the same sentence for every bad credential and clears the password', async () => {
    const fixture = await render();
    auth.login.mockRejectedValue(apiError(401, 'authentication-failed'));

    signIn(fixture.nativeElement, 'wrong');
    await settle(fixture);

    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain(
      'The email address or password is not right.',
    );
    expect((fixture.nativeElement.querySelector('#login-password') as HTMLInputElement).value).toBe('');
    expect((fixture.nativeElement.querySelector('#login-email') as HTMLInputElement).value).toBe('alex@example.test');
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('says how long to wait when it is rate limited, and when the server is away', async () => {
    const fixture = await render();
    auth.login.mockRejectedValueOnce(apiError(429, 'rate-limited', { retryAfterSeconds: 120 }));
    signIn(fixture.nativeElement);
    await settle(fixture);
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('Wait 2 minutes');

    auth.login.mockRejectedValueOnce(apiError(0));
    signIn(fixture.nativeElement);
    await settle(fixture);
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('could not be reached');
  });

  it.each([
    ['expired', 'Your session ended. Sign in again to continue.'],
    ['created', 'Your account is ready. Sign in to continue.'],
    ['joined', 'You have joined the household. Sign in to continue.'],
  ])('explains why the visitor is here when the reason is %s', async (reason, sentence) => {
    const fixture = await render(`/login?reason=${reason}`);

    expect(fixture.nativeElement.querySelector('[role=status]').textContent).toContain(sentence);
  });

  it('ignores a reason it does not know, rather than printing it', async () => {
    const fixture = await render('/login?reason=<script>alert(1)</script>');

    expect(fixture.nativeElement.querySelector('[role=status]')).toBeNull();
    expect(fixture.nativeElement.innerHTML).not.toContain('<script>');
  });

  it('labels both fields', async () => {
    const fixture = await render();

    expect(fixture.nativeElement.querySelector('label[for=login-email]').textContent).toContain('Email address');
    expect(fixture.nativeElement.querySelector('label[for=login-password]').textContent).toContain('Password');
    expect(fixture.nativeElement.querySelector('#login-password').getAttribute('autocomplete')).toBe('current-password');
  });
});
