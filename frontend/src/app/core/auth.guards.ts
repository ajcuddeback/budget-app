import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Client-side route guards are a courtesy, not a control: they keep a signed-out visitor from
 * seeing an empty page, and the server re-checks every request regardless (ADR-0008).
 */
export const authGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  await auth.ensureLoaded();
  return auth.isAuthenticated() ? true : inject(Router).createUrlTree(['/login']);
};

/** Pages for people who are not signed in (login, setup) bounce signed-in people home. */
export const guestGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  await auth.ensureLoaded();
  return auth.isAuthenticated() ? inject(Router).createUrlTree(['/']) : true;
};
