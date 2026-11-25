import { Routes } from '@angular/router';

export const IMPORT_ROUTES: Routes = [
  {
    // `?accountId=` preselects an account. It is only a starting choice: the page offers only the
    // household's open accounts, and the server resolves the id against the caller's household.
    path: '',
    loadComponent: () => import('./pages/import.page').then((m) => m.ImportPage),
  },
];
