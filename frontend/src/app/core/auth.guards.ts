import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Client-side route guards are a courtesy, not a control: they keep a signed-out visitor from
 * seeing an empty page, and the server re-checks every request regardless (ADR-0008).
 */
export const authGuard: CanActivateFn = async () => {
  // Everything is injected before the first `await`: past one, there is no injection context.
  const auth = inject(AuthService);
  const router = inject(Router);
  await auth.ensureLoaded();
  return auth.isAuthenticated() ? true : router.createUrlTree(['/login']);
};

/** Pages for people who are not signed in (login, setup) bounce signed-in people home. */
export const guestGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  await auth.ensureLoaded();
  return auth.isAuthenticated() ? router.createUrlTree(['/']) : true;
};

/**
 * The operator's screen. A courtesy like the others: the masthead hides the link, this refuses the
 * route, and `AdminService` refuses the request — which is the only one of the three that matters.
 *
 * A signed-in non-administrator goes home rather than to sign-in: they are authenticated, there is
 * nothing to sign in as, and bouncing them to a login form would say otherwise.
 */
export const instanceAdminGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  await auth.ensureLoaded();
  if (!auth.isAuthenticated()) {
    return router.createUrlTree(['/login']);
  }
  return auth.isInstanceAdmin() ? true : router.createUrlTree(['/']);
};
