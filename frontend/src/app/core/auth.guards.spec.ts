import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { fakeAuth } from '../testing/helpers';
import { authGuard, guestGuard } from './auth.guards';
import { AuthService } from './auth.service';

const route = {} as ActivatedRouteSnapshot;
const state = {} as RouterStateSnapshot;

describe('route guards', () => {
  function run(guard: typeof authGuard, signedIn: boolean) {
    const auth = fakeAuth(signedIn ? undefined : null);
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    return { auth, result: TestBed.runInInjectionContext(() => guard(route, state)) as Promise<boolean | UrlTree> };
  }

  it('lets a signed-in user through a private route', async () => {
    const { result, auth } = run(authGuard, true);

    expect(await result).toBe(true);
    expect(auth.ensureLoaded).toHaveBeenCalled();
  });

  it('sends a signed-out visitor from a private route to sign-in', async () => {
    const { result } = run(authGuard, false);

    expect(TestBed.inject(Router).serializeUrl((await result) as UrlTree)).toBe('/login');
  });

  it('lets a signed-out visitor see a guest page', async () => {
    expect(await run(guestGuard, false).result).toBe(true);
  });

  it('sends a signed-in user from a guest page home', async () => {
    const { result } = run(guestGuard, true);

    expect(TestBed.inject(Router).serializeUrl((await result) as UrlTree)).toBe('/');
  });
});
