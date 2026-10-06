import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { SetupService } from './setup.service';
import { SetupStatus } from './setup.models';

async function readStatus(): Promise<SetupStatus | null> {
  try {
    return await firstValueFrom(inject(SetupService).status());
  } catch {
    // Could not ask (server down). Say nothing about the instance and let the page explain.
    return null;
  }
}

/** A fresh instance has nobody to sign in as: the sign-in screen sends the visitor to setup. */
export const freshInstanceGuard: CanActivateFn = async () => {
  const router = inject(Router);
  const status = await readStatus();
  return status && !status.setupComplete ? router.createUrlTree(['/setup']) : true;
};

/**
 * Setup is only reachable while the instance is fresh. This is a courtesy: the server refuses a
 * second first user in the same transaction as the insert, whatever the client does.
 */
export const setupOpenGuard: CanActivateFn = async () => {
  const router = inject(Router);
  const status = await readStatus();
  return status?.setupComplete ? router.createUrlTree(['/login']) : true;
};
