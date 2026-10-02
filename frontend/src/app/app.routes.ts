import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guards';

export const routes: Routes = [
  {
    path: 'setup',
    title: 'title.setup',
    loadChildren: () => import('./features/setup/setup.routes').then((m) => m.SETUP_ROUTES),
  },
  {
    path: 'login',
    title: 'title.login',
    loadChildren: () => import('./features/login/login.routes').then((m) => m.LOGIN_ROUTES),
  },
  {
    path: 'join',
    title: 'title.join',
    loadChildren: () => import('./features/join/join.routes').then((m) => m.JOIN_ROUTES),
  },
  {
    path: '',
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'household' },
      {
        path: 'household',
        title: 'title.household',
        loadChildren: () =>
          import('./features/household/household.routes').then((m) => m.HOUSEHOLD_ROUTES),
      },
      {
        path: 'devices',
        title: 'title.devices',
        loadChildren: () =>
          import('./features/devices/devices.routes').then((m) => m.DEVICES_ROUTES),
      },
      {
        path: 'preferences',
        title: 'title.preferences',
        loadChildren: () =>
          import('./features/preferences/preferences.routes').then((m) => m.PREFERENCES_ROUTES),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
