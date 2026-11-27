import { Routes } from '@angular/router';

import { clinicGuard, sessionGuard } from './core/auth/guards';
import { configurationGuard } from './features/configuration/configuration-guards';
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
        path: 'configuracion',
        canActivate: [configurationGuard],
        loadChildren: () =>
          import('./features/configuration/configuration.routes').then(
            (m) => m.CONFIGURATION_ROUTES,
          ),
      },
      // The platform area moved into the configuration (M02-T09).
      { path: 'plataforma', redirectTo: 'configuracion/plataforma' },
    ],
  },
  { path: '**', redirectTo: '' },
];
