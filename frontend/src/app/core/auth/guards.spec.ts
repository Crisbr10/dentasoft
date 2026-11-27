import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  GuardResult,
  MaybeAsync,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { BehaviorSubject, firstValueFrom, isObservable } from 'rxjs';

import { CLINIC_ROLES, ClinicRole } from '../clinic/active-clinic';
import { ActiveClinic } from '../clinic/active-clinic';
import { clinicGuard, platformAdminGuard, roleGuard, sessionGuard } from './guards';
import { Session } from './session';

interface FakeClinic {
  id: ReturnType<typeof signal<string | null>>;
  role: ReturnType<typeof signal<ClinicRole | null>>;
  clinics: ReturnType<typeof signal<unknown[]>>;
  invitations: ReturnType<typeof signal<unknown[]>>;
  ensureLoaded: ReturnType<typeof vi.fn>;
}

describe('guards', () => {
  let authenticated: BehaviorSubject<{ isAuthenticated: boolean }>;
  let session: {
    login: ReturnType<typeof vi.fn>;
    takeReturnUrl: ReturnType<typeof vi.fn>;
    checkPlatformAdmin: ReturnType<typeof vi.fn>;
  };
  let clinic: FakeClinic;

  beforeEach(() => {
    authenticated = new BehaviorSubject<{ isAuthenticated: boolean }>({ isAuthenticated: true });
    session = {
      login: vi.fn(),
      takeReturnUrl: vi.fn(() => null),
      checkPlatformAdmin: vi.fn(() => Promise.resolve(false)),
    };
    clinic = {
      id: signal<string | null>('a'),
      role: signal<ClinicRole | null>('ODONTOLOGO'),
      clinics: signal<unknown[]>([{ clinicId: 'a' }]),
      invitations: signal<unknown[]>([]),
      ensureLoaded: vi.fn(() => Promise.resolve()),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: OidcSecurityService, useValue: { isAuthenticated$: authenticated } },
        { provide: Session, useValue: session },
        { provide: ActiveClinic, useValue: clinic },
      ],
    });
  });

  async function run(
    guard: (route: ActivatedRouteSnapshot, state: RouterStateSnapshot) => MaybeAsync<GuardResult>,
    data: Record<string, unknown> = {},
    url = '/pacientes/42',
  ): Promise<GuardResult> {
    const route = { data } as unknown as ActivatedRouteSnapshot;
    const state = { url } as RouterStateSnapshot;
    const result = TestBed.runInInjectionContext(() => guard(route, state));
    return isObservable(result) ? firstValueFrom(result) : await result;
  }

  function path(result: GuardResult): string {
    return TestBed.inject(Router).serializeUrl(result as UrlTree);
  }

  describe('sessionGuard', () => {
    it('sends a user without a session to the login, remembering the page', async () => {
      authenticated.next({ isAuthenticated: false });

      expect(await run(sessionGuard)).toBe(false);
      expect(session.login).toHaveBeenCalledWith('/pacientes/42');
    });

    it('lets a signed-in user through', async () => {
      expect(await run(sessionGuard)).toBe(true);
      expect(session.login).not.toHaveBeenCalled();
    });

    it('takes the user back to the page the login started from', async () => {
      session.takeReturnUrl.mockReturnValue('/pacientes/42');

      expect(path(await run(sessionGuard, {}, '/'))).toBe('/pacientes/42');
    });
  });

  describe('clinicGuard', () => {
    it('lets the user through with an active clinic', async () => {
      expect(await run(clinicGuard)).toBe(true);
    });

    it('sends the user to the selector when no clinic is active', async () => {
      clinic.id.set(null);
      clinic.clinics.set([{ clinicId: 'a' }, { clinicId: 'b' }]);

      expect(path(await run(clinicGuard))).toBe('/clinica');
    });

    it('sends a platform administrator without clinics to the platform area', async () => {
      clinic.id.set(null);
      clinic.clinics.set([]);
      session.checkPlatformAdmin.mockResolvedValue(true);

      expect(path(await run(clinicGuard))).toBe('/plataforma');
    });

    it('shows a user without clinics the selector and its notice', async () => {
      clinic.id.set(null);
      clinic.clinics.set([]);

      expect(path(await run(clinicGuard))).toBe('/clinica');
    });

    it('shows a platform administrator with invitations the selector, to accept them', async () => {
      clinic.id.set(null);
      clinic.clinics.set([]);
      clinic.invitations.set([{ id: 'inv-1' }]);
      session.checkPlatformAdmin.mockResolvedValue(true);

      expect(path(await run(clinicGuard))).toBe('/clinica');
    });

    it('sends the user to the selector, which can try again, when /me cannot be read', async () => {
      clinic.ensureLoaded.mockRejectedValue(new Error('network'));

      expect(path(await run(clinicGuard))).toBe('/clinica');
    });
  });

  describe('roleGuard', () => {
    it.each(CLINIC_ROLES)('lets %s through only where the route allows it', async (role) => {
      clinic.role.set(role);

      expect(await run(roleGuard, { roles: [role] })).toBe(true);
      const others = CLINIC_ROLES.filter((other) => other !== role);
      expect(path(await run(roleGuard, { roles: others }))).toBe('/');
    });

    it('lets no one through without an active clinic', async () => {
      clinic.role.set(null);

      expect(path(await run(roleGuard, { roles: CLINIC_ROLES }))).toBe('/');
    });

    it('lets no one through when the route names no roles', async () => {
      expect(path(await run(roleGuard))).toBe('/');
    });
  });

  describe('platformAdminGuard', () => {
    it('lets a platform administrator through', async () => {
      session.checkPlatformAdmin.mockResolvedValue(true);

      expect(await run(platformAdminGuard)).toBe(true);
    });

    it.each(CLINIC_ROLES)(
      'decides by the realm role, never by the clinic role %s',
      async (role) => {
        clinic.role.set(role);

        session.checkPlatformAdmin.mockResolvedValue(false);
        expect(path(await run(platformAdminGuard))).toBe('/');
        session.checkPlatformAdmin.mockResolvedValue(true);
        expect(await run(platformAdminGuard)).toBe(true);
      },
    );
  });
});
