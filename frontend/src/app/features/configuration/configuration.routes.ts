import { Route, Routes } from '@angular/router';

import { firstSectionGuard, sectionGuard } from './configuration-guards';
import { ConfigurationLayout } from './configuration-layout';
import { ConfigurationIndex, NoAccess, SectionPlaceholder } from './configuration-pages';
import { SECTIONS, SectionEntry, SectionId } from './configuration-sections';

/** The screens of each section; those still to come show a placeholder. */
const SCREENS: Record<SectionId, NonNullable<Route['loadComponent']>> = {
  CLINIC: () => SectionPlaceholder, // M01-T10
  USERS_AND_ROLES: () => SectionPlaceholder, // M02-T08
  PRACTITIONERS: () => SectionPlaceholder, // M02-T08
  PLATFORM: () => import('../platform/platform-home').then((m) => m.PlatformHome),
};

function sectionRoute(section: SectionEntry): Route {
  return {
    path: section.path,
    canActivate: [sectionGuard],
    data: { section: section.id, labelKey: section.labelKey },
    loadComponent: SCREENS[section.id],
  };
}

/** /configuracion (M02-T09). Guards are only a convenience; see configuration-guards.ts. */
export const CONFIGURATION_ROUTES: Routes = [
  {
    path: '',
    component: ConfigurationLayout,
    children: [
      {
        path: '',
        pathMatch: 'full',
        canActivate: [firstSectionGuard],
        component: ConfigurationIndex,
      },
      { path: 'sin-acceso', component: NoAccess },
      ...SECTIONS.map(sectionRoute),
    ],
  },
];
