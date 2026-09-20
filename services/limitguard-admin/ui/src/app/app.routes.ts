import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';
import { AdminShell } from './shell/admin-shell';

export const routes: Routes = [
  {
    path: '',
    component: AdminShell,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'tenants' },
      {
        path: 'tenants',
        loadComponent: () => import('./tenants/tenant-list-page').then((m) => m.TenantListPage)
      },
      {
        path: 'tenants/:tenantId/products',
        loadComponent: () => import('./products/product-list-page').then((m) => m.ProductListPage)
      }
    ]
  },
  { path: '**', redirectTo: 'tenants' }
];
