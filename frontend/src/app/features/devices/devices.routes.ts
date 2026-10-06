import { Routes } from '@angular/router';

export const DEVICES_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/devices.page').then((m) => m.DevicesPage),
  },
];
