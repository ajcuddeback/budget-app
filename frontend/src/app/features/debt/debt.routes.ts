import { Routes } from '@angular/router';

export const DEBT_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/debt-plan.page').then((m) => m.DebtPlanPage),
  },
];
