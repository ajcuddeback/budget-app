import { Routes } from '@angular/router';

export const DEBT_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/debt-plan.page').then((m) => m.DebtPlanPage),
  },
  {
    // The debt's **id**, not a slug: a slug would need a second unique key per household and
    // would leak the debt's name into browser history and server logs.
    path: ':id',
    title: 'title.debtDetail',
    loadComponent: () => import('./pages/debt-detail.page').then((m) => m.DebtDetailPage),
  },
];
