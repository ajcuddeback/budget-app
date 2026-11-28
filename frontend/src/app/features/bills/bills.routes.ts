import { Routes } from '@angular/router';

export const BILLS_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/bills.page').then((m) => m.BillsPage),
  },
];
