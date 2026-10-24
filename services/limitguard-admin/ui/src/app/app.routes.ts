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
        path: 'tenants/:tenantId',
        loadComponent: () => import('./tenants/tenant-shell').then((m) => m.TenantShell),
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'products' },
          {
            path: 'products',
            loadComponent: () => import('./products/product-list-page').then((m) => m.ProductListPage)
          },
          {
            path: 'channels',
            loadComponent: () => import('./channels/channel-list-page').then((m) => m.ChannelListPage)
          },
          {
            path: 'operations',
            loadComponent: () =>
              import('./operations/operation-type-list-page').then((m) => m.OperationTypeListPage)
          },
          ...[
            { path: 'configurations', title: 'Limit configurations' },
            { path: 'policies', title: 'Limit-change policies' }
          ].map(({ path, title }) => ({
            path,
            data: { title },
            loadComponent: () =>
              import('./tenants/tenant-area-placeholder').then((m) => m.TenantAreaPlaceholder)
          }))
        ]
      }
    ]
  },
  { path: '**', redirectTo: 'tenants' }
];
