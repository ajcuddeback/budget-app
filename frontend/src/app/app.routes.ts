import { Routes } from '@angular/router';

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
  { path: '**', redirectTo: 'login' },
];
