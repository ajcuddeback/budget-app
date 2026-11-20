import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guards';

export const routes: Routes = [
  {
    path: 'setup',
    title: 'title.setup',
    loadChildren: () => import('./features/setup/setup.routes').then((m) => m.SETUP_ROUTES),
  },
  {
    path: 'login',
    title: 'title.login',
    loadChildren: () => import('./features/login/login.routes').then((m) => m.LOGIN_ROUTES),
  },
  {
    path: 'join',
    title: 'title.join',
    loadChildren: () => import('./features/join/join.routes').then((m) => m.JOIN_ROUTES),
  },
  {
    path: '',
    canActivate: [authGuard],
    children: [
      // The ledger is the screen somebody opens the app to use, so it is what '/' lands on.
      { path: '', pathMatch: 'full', redirectTo: 'transactions' },
      {
        path: 'transactions',
        title: 'title.transactions',
        loadChildren: () =>
          import('./features/transactions/transactions.routes').then((m) => m.TRANSACTIONS_ROUTES),
      },
      {
        path: 'bills',
        title: 'title.bills',
        loadChildren: () => import('./features/bills/bills.routes').then((m) => m.BILLS_ROUTES),
      },
      {
        path: 'envelopes',
        title: 'title.envelopes',
        loadChildren: () =>
          import('./features/envelopes/envelopes.routes').then((m) => m.ENVELOPES_ROUTES),
      },
      {
        path: 'categories',
        title: 'title.categories',
        loadChildren: () =>
          import('./features/categories/categories.routes').then((m) => m.CATEGORIES_ROUTES),
      },
      {
        path: 'accounts',
        title: 'title.accounts',
        loadChildren: () =>
          import('./features/accounts/accounts.routes').then((m) => m.ACCOUNTS_ROUTES),
      },
      {
        path: 'household',
        title: 'title.household',
        loadChildren: () =>
          import('./features/household/household.routes').then((m) => m.HOUSEHOLD_ROUTES),
      },
      {
        path: 'devices',
        title: 'title.devices',
        loadChildren: () =>
          import('./features/devices/devices.routes').then((m) => m.DEVICES_ROUTES),
      },
      {
        path: 'preferences',
        title: 'title.preferences',
        loadChildren: () =>
          import('./features/preferences/preferences.routes').then((m) => m.PREFERENCES_ROUTES),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
