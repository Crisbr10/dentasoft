import { Routes } from '@angular/router';

import { clinicGuard, platformAdminGuard, sessionGuard } from './core/auth/guards';
import { Shell } from './core/layout/shell';

// Guards are only a convenience; the backend checks every request (see core/auth/guards.ts).
export const routes: Routes = [
  {
    path: '',
    component: Shell,
    canActivate: [sessionGuard],
    children: [
      {
        path: '',
        pathMatch: 'full',
        canActivate: [clinicGuard],
        loadComponent: () => import('./features/home/home').then((m) => m.Home),
      },
      {
        path: 'clinica',
        loadComponent: () =>
          import('./features/clinic-selector/clinic-selector').then((m) => m.ClinicSelector),
      },
      {
        path: 'plataforma',
        canActivate: [platformAdminGuard],
        loadComponent: () =>
          import('./features/platform/platform-home').then((m) => m.PlatformHome),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
