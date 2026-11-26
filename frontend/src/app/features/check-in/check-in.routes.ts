import { Routes } from '@angular/router';

export const CHECK_IN_ROUTES: Routes = [
  {
    // The week containing the server's today. The browser's clock decides nothing.
    path: '',
    loadComponent: () => import('./pages/check-in.page').then((m) => m.CheckInPage),
  },
  {
    // `2026-W34`: the ISO week, never anything that names a payee or an amount.
    path: ':week',
    loadComponent: () => import('./pages/check-in.page').then((m) => m.CheckInPage),
  },
];
