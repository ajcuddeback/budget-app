import { Routes } from '@angular/router';

/**
 * Public by necessity: an invited person has no account to authenticate with. What authorises
 * them is the token in the path, which the server checks, rate-limits and spends.
 */
export const JOIN_ROUTES: Routes = [
  {
    path: ':token',
    loadComponent: () => import('./pages/join.page').then((m) => m.JoinPage),
  },
];
