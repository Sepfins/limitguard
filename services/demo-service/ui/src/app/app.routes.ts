import { Routes } from '@angular/router';
import { BankShell } from './shell/bank-shell';

export const routes: Routes = [
  {
    path: '',
    component: BankShell,
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'home' },
      {
        path: 'home',
        loadComponent: () => import('./pages/home-page').then((module) => module.HomePage)
      },
      {
        path: 'accounts',
        loadComponent: () => import('./pages/accounts-page').then((module) => module.AccountsPage)
      },
      {
        path: 'cards',
        loadComponent: () => import('./pages/cards-page').then((module) => module.CardsPage)
      },
      {
        path: 'activity',
        loadComponent: () => import('./pages/activity-page').then((module) => module.ActivityPage)
      },
      {
        path: 'limits',
        loadComponent: () => import('./pages/limits-page').then((module) => module.LimitsPage)
      }
    ]
  },
  { path: '**', redirectTo: 'home' }
];
