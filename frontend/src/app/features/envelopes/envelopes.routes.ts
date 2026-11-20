import { Routes } from '@angular/router';

export const ENVELOPES_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/envelopes.page').then((m) => m.EnvelopesPage),
  },
];
