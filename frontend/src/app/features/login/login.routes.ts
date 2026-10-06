import { Routes } from '@angular/router';
import { guestGuard } from '../../core/auth.guards';
import { freshInstanceGuard } from '../../core/setup.guards';

export const LOGIN_ROUTES: Routes = [
  {
    path: '',
    canActivate: [guestGuard, freshInstanceGuard],
    loadComponent: () => import('./pages/login.page').then((m) => m.LoginPage),
  },
];
