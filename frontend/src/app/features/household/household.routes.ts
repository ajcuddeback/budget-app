import { Routes } from '@angular/router';

export const HOUSEHOLD_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./pages/household-settings.page').then((m) => m.HouseholdSettingsPage),
  },
];
