import { CLINIC_ROLES, ClinicRole } from '../clinic/active-clinic';

/**
 * An entry of the side menu and who sees it: the roles in the active clinic, or the platform
 * administration. Hiding an entry is not access control; the backend checks every request.
 */
export interface NavItem {
  readonly path: string;
  readonly labelKey: string;
  readonly icon: string;
  /** Active only on this exact path (the home page), not on the pages below it. */
  readonly exact?: boolean;
  readonly clinicRoles?: readonly ClinicRole[];
  readonly platform?: boolean;
}

export const NAVIGATION: readonly NavItem[] = [
  { path: '/', labelKey: 'layout.nav.home', icon: 'home', exact: true, clinicRoles: CLINIC_ROLES },
  { path: '/plataforma', labelKey: 'layout.nav.platform', icon: 'business', platform: true },
];

/** The entries the user may use with their role in the active clinic and their realm roles. */
export function visibleNavigation(
  items: readonly NavItem[],
  role: ClinicRole | null,
  platformAdmin: boolean,
): NavItem[] {
  return items.filter(
    (item) =>
      (item.platform === true && platformAdmin) ||
      (role !== null && (item.clinicRoles ?? []).includes(role)),
  );
}
