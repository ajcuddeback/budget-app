import { Routes } from '@angular/router';

export const PREFERENCES_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/preferences.page').then((m) => m.PreferencesPage),
  },
];
