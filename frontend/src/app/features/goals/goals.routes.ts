import { Routes } from '@angular/router';

export const GOALS_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/goals.page').then((m) => m.GoalsPage),
  },
  {
    // The goal's **id**, never a slug: a slug would leak the goal's name — the most disclosing
    // field this feature has — into browser history and server logs.
    path: ':id',
    title: 'title.goalDetail',
    loadComponent: () => import('./pages/goal-detail.page').then((m) => m.GoalDetailPage),
  },
];
