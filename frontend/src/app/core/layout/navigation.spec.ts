import { CLINIC_ROLES } from '../clinic/active-clinic';
import { NAVIGATION, visibleNavigation } from './navigation';

function paths(items: { path: string }[]): string[] {
  return items.map((item) => item.path);
}

describe('visibleNavigation', () => {
  it.each(CLINIC_ROLES)('shows %s the clinic entries and not the platform', (role) => {
    expect(paths(visibleNavigation(NAVIGATION, role, false))).toEqual(['/']);
  });

  it('shows a platform administrator without an active clinic only the platform', () => {
    expect(paths(visibleNavigation(NAVIGATION, null, true))).toEqual(['/plataforma']);
  });

  it('shows a platform administrator who also works in a clinic both', () => {
    expect(paths(visibleNavigation(NAVIGATION, 'ADMIN_CLINICA', true))).toEqual([
      '/',
      '/plataforma',
    ]);
  });

  it('shows nothing without an active clinic or a platform role', () => {
    expect(visibleNavigation(NAVIGATION, null, false)).toEqual([]);
  });

  it('hides an entry from the roles it does not name', () => {
    const items = [
      { path: '/usuarios', labelKey: 'x', icon: 'x', clinicRoles: ['ADMIN_CLINICA'] as const },
    ];

    expect(paths(visibleNavigation(items, 'ADMIN_CLINICA', false))).toEqual(['/usuarios']);
    expect(visibleNavigation(items, 'RECEPCION', false)).toEqual([]);
  });
});
