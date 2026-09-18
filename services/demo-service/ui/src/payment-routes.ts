import { Routes } from '@angular/router';

export const paymentRoutes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./app/payment-simulator/payment-simulator-page').then((module) => module.PaymentSimulatorPage),
  },
  { path: '**', redirectTo: '' },
];
