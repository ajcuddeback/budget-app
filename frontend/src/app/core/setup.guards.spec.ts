import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { freshInstanceGuard, setupOpenGuard } from './setup.guards';
import { SetupStatus } from './setup.models';
import { SetupService } from './setup.service';

const route = {} as ActivatedRouteSnapshot;
const state = {} as RouterStateSnapshot;

function statusOf(setupComplete: boolean): SetupStatus {
  return { setupComplete, registrationOpen: false, passwordLoginEnabled: true, oidcEnabled: false };
}

function run(guard: typeof freshInstanceGuard, status: ReturnType<SetupService['status']>) {
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: SetupService, useValue: { status: () => status } }],
  });
  return TestBed.runInInjectionContext(() => guard(route, state)) as Promise<boolean | UrlTree>;
}

describe('setup guards', () => {
  const url = (tree: boolean | UrlTree) => TestBed.inject(Router).serializeUrl(tree as UrlTree);

  it('sends the sign-in screen to setup on a fresh instance', async () => {
    expect(url(await run(freshInstanceGuard, of(statusOf(false))))).toBe('/setup');
  });

  it('leaves sign-in alone on an instance that is set up', async () => {
    expect(await run(freshInstanceGuard, of(statusOf(true)))).toBe(true);
  });

  it('closes setup once the instance has users', async () => {
    expect(url(await run(setupOpenGuard, of(statusOf(true))))).toBe('/login');
  });

  it('opens setup on a fresh instance', async () => {
    expect(await run(setupOpenGuard, of(statusOf(false)))).toBe(true);
  });

  it('says nothing about the instance when it cannot be asked', async () => {
    expect(await run(freshInstanceGuard, throwError(() => new Error('down')))).toBe(true);
  });

  it('shows the setup screen when it cannot be asked, rather than a login that cannot work', async () => {
    expect(await run(setupOpenGuard, throwError(() => new Error('down')))).toBe(true);
  });
});
