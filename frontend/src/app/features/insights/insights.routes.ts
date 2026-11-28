import { Routes } from '@angular/router';

export const INSIGHTS_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/insights.page').then((m) => m.InsightsPage),
  },
];
