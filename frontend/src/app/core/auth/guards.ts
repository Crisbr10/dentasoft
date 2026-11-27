// These guards are only a convenience: they keep users away from pages they cannot use and
// send them to the login, the clinic selector or the platform area. Security is in the backend:
// it checks the token, the active clinic against the membership and the role on every request
// (@PreAuthorize, ADR 0015). Never rely on a guard, a hidden button or a menu to protect data.
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { map, take } from 'rxjs';

import { ActiveClinic, ClinicRole } from '../clinic/active-clinic';
import { CLINIC_SELECTOR_ROUTE, PLATFORM_ROUTE } from './routes';
import { Session } from './session';

/** Signed in, or off to the Keycloak login; after it, back to the page the user asked for. */
export const sessionGuard: CanActivateFn = (_route, state) => {
  const oidc = inject(OidcSecurityService);
  const session = inject(Session);
  const router = inject(Router);
  return oidc.isAuthenticated$.pipe(
    take(1),
    map(({ isAuthenticated }) => {
      if (!isAuthenticated) {
        session.login(state.url);
        return false;
      }
      const back = session.takeReturnUrl();
      return back && back !== state.url ? router.parseUrl(back) : true;
    }),
  );
};

/**
 * A clinic is active. Otherwise the user chooses one in the selector, except a platform
 * administrator without clinics or invitations, who goes to the platform area.
 */
export const clinicGuard: CanActivateFn = async () => {
  const clinic = inject(ActiveClinic);
  const session = inject(Session);
  const router = inject(Router);
  try {
    await clinic.ensureLoaded();
  } catch {
    // The selector shows the error and lets the user try again.
    return router.parseUrl(CLINIC_SELECTOR_ROUTE);
  }
  if (clinic.id()) {
    return true;
  }
  const toPlatform = withoutClinics(clinic) && (await session.checkPlatformAdmin());
  return router.parseUrl(toPlatform ? PLATFORM_ROUTE : CLINIC_SELECTOR_ROUTE);
};

/**
 * The role in the active clinic is one of the route's data.roles. Place it after clinicGuard.
 * Without the role, the user goes to the home page.
 */
export const roleGuard: CanActivateFn = (route) => {
  const roles = (route.data['roles'] ?? []) as readonly ClinicRole[];
  const role = inject(ActiveClinic).role();
  return (role !== null && roles.includes(role)) || inject(Router).parseUrl('/');
};

/** PLATFORM_ADMIN in the token. Others go to the home page. */
export const platformAdminGuard: CanActivateFn = async () => {
  const router = inject(Router);
  return (await inject(Session).checkPlatformAdmin()) || router.parseUrl('/');
};

/** No clinic to work in and no invitation to accept. */
export function withoutClinics(clinic: ActiveClinic): boolean {
  return clinic.clinics().length === 0 && clinic.invitations().length === 0;
}
