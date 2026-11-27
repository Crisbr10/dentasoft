import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { es, translocoTesting } from '../../../testing/i18n';
import { ApiConfiguration } from '../../api/api-configuration';
import { MeResponse } from '../../api/models/me-response';
import { Session } from '../../core/auth/session';
import { ActiveClinic } from '../../core/clinic/active-clinic';
import { ClinicSelector } from './clinic-selector';

const API = 'https://api.dentasoft.test';
const ME = `${API}/api/v1/me`;

const NORTE = {
  clinicId: 'a',
  name: 'Clínica Norte',
  role: 'ODONTOLOGO',
  suspended: false,
} as const;
const SUR = { clinicId: 'b', name: 'Clínica Sur', role: 'RECEPCION', suspended: false } as const;
const CERRADA = {
  clinicId: 's',
  name: 'Clínica Cerrada',
  role: 'ADMIN_CLINICA',
  suspended: true,
} as const;
const INVITATION = {
  id: 'inv-1',
  clinicId: 'c',
  name: 'Clínica Este',
  role: 'HIGIENISTA',
} as const;

describe('ClinicSelector', () => {
  let fixture: ComponentFixture<ClinicSelector>;
  let http: HttpTestingController;
  let session: { checkPlatformAdmin: ReturnType<typeof vi.fn> };
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    sessionStorage.clear();
    session = { checkPlatformAdmin: vi.fn(() => Promise.resolve(false)) };
    TestBed.configureTestingModule({
      imports: [ClinicSelector, translocoTesting()],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ApiConfiguration, useValue: { rootUrl: API } },
        { provide: Session, useValue: session },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(ClinicSelector);
  });

  afterEach(() => {
    http.verify();
    sessionStorage.clear();
  });

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  /** Waits until the page no longer says it is loading: the component awaits promises. */
  async function loaded(): Promise<void> {
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(root().textContent).not.toContain(es.clinicSelector.loading);
    });
  }

  async function show(me: MeResponse): Promise<HTMLElement> {
    fixture.detectChanges();
    http.expectOne(ME).flush(me);
    await loaded();
    return root();
  }

  function clinicRow(root: HTMLElement, id: string): HTMLElement {
    return root.querySelector(`[data-clinic="${id}"]`) as HTMLElement;
  }

  it('lists the clinics with the role and marks the suspended ones, which cannot be chosen', async () => {
    const root = await show({ clinics: [NORTE, SUR, CERRADA], invitations: [] });

    expect(clinicRow(root, 'a').textContent).toContain('Clínica Norte');
    expect(clinicRow(root, 'a').textContent).toContain(es.roles.ODONTOLOGO);
    expect(clinicRow(root, 'a').querySelector('button')?.textContent).toContain(
      es.clinicSelector.choose,
    );
    expect(clinicRow(root, 's').textContent).toContain(es.clinicSelector.suspended);
    expect(clinicRow(root, 's').querySelector('button')).toBeNull();
  });

  it('enters the chosen clinic and starts again from the home page', async () => {
    const root = await show({ clinics: [NORTE, SUR], invitations: [] });

    clinicRow(root, 'b').querySelector('button')?.click();

    expect(TestBed.inject(ActiveClinic).id()).toBe('b');
    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith('/'));
  });

  it('marks the active clinic instead of offering it', async () => {
    const root = await show({ clinics: [NORTE, SUR], invitations: [] });
    TestBed.inject(ActiveClinic).select('a');
    await fixture.whenStable();

    expect(clinicRow(root, 'a').textContent).toContain(es.clinicSelector.current);
    expect(clinicRow(root, 'a').querySelector('button')).toBeNull();
  });

  it('accepts a pending invitation and shows the clinic it gives', async () => {
    const root = await show({ clinics: [NORTE], invitations: [INVITATION] });
    const invitation = root.querySelector('[data-invitation="inv-1"]') as HTMLElement;

    expect(invitation.textContent).toContain('Clínica Este');
    expect(invitation.textContent).toContain(es.roles.HIGIENISTA);
    invitation.querySelector('button')?.click();
    http
      .expectOne(`${ME}/invitations/inv-1/acceptance`)
      .flush(null, { status: 204, statusText: 'No Content' });
    await vi.waitFor(() =>
      http.expectOne(ME).flush({
        clinics: [
          NORTE,
          { clinicId: 'c', name: 'Clínica Este', role: 'HIGIENISTA', suspended: false },
        ],
        invitations: [],
      }),
    );
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(clinicRow(root, 'c').textContent).toContain('Clínica Este');
    });
    expect(root.querySelector('[data-invitation]')).toBeNull();
  });

  it('says so when an invitation cannot be accepted', async () => {
    const root = await show({ clinics: [], invitations: [INVITATION] });

    root.querySelector<HTMLButtonElement>('[data-invitation="inv-1"] button')?.click();
    http
      .expectOne(`${ME}/invitations/inv-1/acceptance`)
      .flush(null, { status: 404, statusText: 'Not Found' });

    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(root.querySelector('[role="alert"]')?.textContent).toContain(
        es.clinicSelector.acceptError,
      );
    });
  });

  it('tells a user without clinics or invitations what to do', async () => {
    const root = await show({ clinics: [], invitations: [] });

    expect(root.textContent).toContain(es.clinicSelector.empty);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('sends a platform administrator without clinics to the platform area, not to the notice', async () => {
    session.checkPlatformAdmin.mockResolvedValue(true);
    fixture.detectChanges();
    http.expectOne(ME).flush({ clinics: [], invitations: [] });

    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith('/plataforma'));
    await fixture.whenStable();
    expect(root().textContent).not.toContain(es.clinicSelector.empty);
  });

  it('lets the user try again when the clinics cannot be read', async () => {
    fixture.detectChanges();
    http.expectOne(ME).flush(null, { status: 500, statusText: 'Server Error' });
    await loaded();

    expect(root().textContent).toContain(es.clinicSelector.loadError);
    root().querySelector('button')?.click();
    http.expectOne(ME).flush({ clinics: [NORTE, SUR], invitations: [] });
    await loaded();

    expect(clinicRow(root(), 'a')).not.toBeNull();
  });
});
