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

import { Api } from '../../api/api';
import { clinicSections } from '../../api/fn/configuration-sections-controller/clinic-sections';
import { VisibleSection } from '../../api/models/visible-section';
import { Session } from '../../core/auth/session';
import { CLINIC_ROLES, ClinicRole } from '../../core/clinic/active-clinic';
import { ActiveClinic } from '../../core/clinic/active-clinic';
import { configurationGuard, firstSectionGuard, sectionGuard } from './configuration-guards';
import { SECTIONS, SectionId } from './configuration-sections';

/** What the backend answers to each role (docs/plan/configuracion.md, ConfigurationSectionTests). */
const VIEWER: VisibleSection[] = [
  { section: 'CLINIC', access: 'VIEW' },
  { section: 'PRACTITIONERS', access: 'VIEW' },
];
const MATRIX: Record<ClinicRole, VisibleSection[]> = {
  ADMIN_CLINICA: [
    { section: 'CLINIC', access: 'EDIT' },
    { section: 'USERS_AND_ROLES', access: 'EDIT' },
    { section: 'PRACTITIONERS', access: 'EDIT' },
  ],
  ODONTOLOGO: VIEWER,
  HIGIENISTA: VIEWER,
  RECEPCION: VIEWER,
  FACTURACION: VIEWER,
};
const PLATFORM: VisibleSection[] = [{ section: 'PLATFORM', access: 'EDIT' }];

describe('configuration guards', () => {
  let clinicId: ReturnType<typeof signal<string | null>>;
  let role: ClinicRole;
  let platformAdmin: boolean;
  let invoke: ReturnType<typeof vi.fn>;
  let ensureClinics: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    clinicId = signal<string | null>('a');
    role = 'ADMIN_CLINICA';
    platformAdmin = false;
    invoke = vi.fn((fn: unknown) =>
      Promise.resolve(fn === clinicSections ? MATRIX[role] : PLATFORM),
    );
    ensureClinics = vi.fn(() => Promise.resolve());
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: Api, useValue: { invoke } },
        { provide: ActiveClinic, useValue: { id: clinicId, ensureLoaded: ensureClinics } },
        {
          provide: Session,
          useValue: { checkPlatformAdmin: () => Promise.resolve(platformAdmin) },
        },
      ],
    });
  });

  async function run(
    guard: (route: ActivatedRouteSnapshot, state: RouterStateSnapshot) => MaybeAsync<GuardResult>,
    data: Record<string, unknown> = {},
  ): Promise<GuardResult> {
    const route = { data } as unknown as ActivatedRouteSnapshot;
    const state = { url: '/configuracion' } as RouterStateSnapshot;
    return (await TestBed.runInInjectionContext(() => guard(route, state))) as GuardResult;
  }

  function path(result: GuardResult): string {
    return TestBed.inject(Router).serializeUrl(result as UrlTree);
  }

  async function opens(section: SectionId): Promise<boolean | string> {
    const result = await run(sectionGuard, { section });
    return result === true ? true : path(result);
  }

  describe('sectionGuard', () => {
    it.each(CLINIC_ROLES)('lets %s open exactly the sections of its row', async (clinicRole) => {
      role = clinicRole;
      const seen = new Set(MATRIX[clinicRole].map((section) => section.section));

      for (const section of SECTIONS) {
        expect(await opens(section.id)).toBe(
          seen.has(section.id) ? true : '/configuracion/sin-acceso',
        );
      }
    });

    it('lets a platform administrator without a clinic open only the platform', async () => {
      clinicId.set(null);
      platformAdmin = true;

      for (const section of SECTIONS) {
        expect(await opens(section.id)).toBe(
          section.id === 'PLATFORM' ? true : '/configuracion/sin-acceso',
        );
      }
    });

    it('sends the user to the index of the area when the sections cannot be read', async () => {
      invoke.mockRejectedValue(new Error('offline'));

      expect(await opens('CLINIC')).toBe('/configuracion');
    });
  });

  describe('firstSectionGuard', () => {
    it('opens the first section the user sees', async () => {
      role = 'RECEPCION';

      expect(path(await run(firstSectionGuard))).toBe('/configuracion/clinica');
    });

    it('opens the platform for a platform administrator without a clinic', async () => {
      clinicId.set(null);
      platformAdmin = true;

      expect(path(await run(firstSectionGuard))).toBe('/configuracion/plataforma');
    });

    it('sends a user who sees no section to the «sin acceso» page', async () => {
      invoke.mockResolvedValue([]);

      expect(path(await run(firstSectionGuard))).toBe('/configuracion/sin-acceso');
    });

    it('stays on the index, which shows the error, when the sections cannot be read', async () => {
      invoke.mockRejectedValue(new Error('offline'));

      expect(await run(firstSectionGuard)).toBe(true);
    });
  });

  describe('configurationGuard', () => {
    it('lets a member of the active clinic in', async () => {
      expect(await run(configurationGuard)).toBe(true);
    });

    it('lets a platform administrator without a clinic in', async () => {
      clinicId.set(null);
      platformAdmin = true;

      expect(await run(configurationGuard)).toBe(true);
    });

    it('sends anybody else to choose a clinic', async () => {
      clinicId.set(null);

      expect(path(await run(configurationGuard))).toBe('/clinica');
    });

    it('sends the user to the clinic selector when their clinics cannot be read', async () => {
      ensureClinics.mockRejectedValue(new Error('offline'));

      expect(path(await run(configurationGuard))).toBe('/clinica');
    });
  });
});
