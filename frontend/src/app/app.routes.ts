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
    ],
  },
  { path: '**', redirectTo: '' },
];
