// Like the guards in core/auth/guards.ts, these are only a convenience: they keep users away
// from sections they cannot use. Security is in the backend, which checks the role of every
// request (@PreAuthorize, ADR 0015). Never rely on a guard or a hidden menu entry to protect data.
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import {
  CLINIC_SELECTOR_ROUTE,
  CONFIGURATION_ROUTE,
  NO_ACCESS_ROUTE,
} from '../../core/auth/routes';
import { Session } from '../../core/auth/session';
import { ActiveClinic } from '../../core/clinic/active-clinic';
import { ConfigurationSections, SectionId } from './configuration-sections';

/**
 * The configuration area needs an active clinic or the platform role (PLATFORM_ADMIN has no
 * clinic). Anybody else chooses a clinic first.
 */
export const configurationGuard: CanActivateFn = async () => {
  const clinic = inject(ActiveClinic);
  const session = inject(Session);
  const router = inject(Router);
  try {
    await clinic.ensureLoaded();
  } catch {
    return router.parseUrl(CLINIC_SELECTOR_ROUTE);
  }
  if (clinic.id() || (await session.checkPlatformAdmin())) {
    return true;
  }
  return router.parseUrl(CLINIC_SELECTOR_ROUTE);
};

/**
 * The user sees the route's data.section. Otherwise, the «sin acceso» page. If the sections
 * cannot be read, the index page of the area says so and offers to try again.
 */
export const sectionGuard: CanActivateFn = async (route) => {
  const sections = inject(ConfigurationSections);
  const router = inject(Router);
  try {
    await sections.ensureLoaded();
  } catch {
    return router.parseUrl(CONFIGURATION_ROUTE);
  }
  const section = route.data['section'] as SectionId;
  return sections.accessTo(section) !== null || router.parseUrl(NO_ACCESS_ROUTE);
};

/** The index of the area opens the first section the user sees. */
export const firstSectionGuard: CanActivateFn = async () => {
  const sections = inject(ConfigurationSections);
  const router = inject(Router);
  try {
    await sections.ensureLoaded();
  } catch {
    // The index page shows the error.
    return true;
  }
  const first = sections.visible()[0];
  return router.parseUrl(first ? `${CONFIGURATION_ROUTE}/${first.path}` : NO_ACCESS_ROUTE);
};
