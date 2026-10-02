import { Routes } from '@angular/router';
import { guestGuard } from '../../core/auth.guards';
import { setupOpenGuard } from '../../core/setup.guards';

export const SETUP_ROUTES: Routes = [
  {
    path: '',
    canActivate: [guestGuard, setupOpenGuard],
    loadComponent: () => import('./pages/setup.page').then((m) => m.SetupPage),
  },
];
