import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { ApiConfiguration } from '../../api/api-configuration';
import { MeResponse } from '../../api/models/me-response';
import { ACTIVE_CLINIC_KEY, ActiveClinic } from './active-clinic';

const API = 'https://api.dentasoft.test';
const ME = `${API}/api/v1/me`;

const CLINIC_A = {
  clinicId: 'a',
  name: 'Clínica Norte',
  role: 'ODONTOLOGO',
  suspended: false,
} as const;
const CLINIC_B = {
  clinicId: 'b',
  name: 'Clínica Sur',
  role: 'RECEPCION',
  suspended: false,
} as const;
const SUSPENDED = {
  clinicId: 's',
  name: 'Clínica Cerrada',
  role: 'ADMIN_CLINICA',
  suspended: true,
} as const;

describe('ActiveClinic', () => {
  let http: HttpTestingController;

  function create(): ActiveClinic {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ApiConfiguration, useValue: { rootUrl: API } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    return TestBed.inject(ActiveClinic);
  }

  async function load(clinic: ActiveClinic, me: MeResponse): Promise<void> {
    const loading = clinic.load();
    http.expectOne(ME).flush(me);
    await loading;
  }

  beforeEach(() => sessionStorage.clear());
  afterEach(() => {
    http.verify();
    sessionStorage.clear();
  });

  it('chooses the only clinic without asking and remembers it in the tab', async () => {
    const clinic = create();
    await load(clinic, { clinics: [CLINIC_A, SUSPENDED], invitations: [] });

    expect(clinic.id()).toBe('a');
    expect(clinic.role()).toBe('ODONTOLOGO');
    expect(sessionStorage.getItem(ACTIVE_CLINIC_KEY)).toBe('a');
  });

  it('chooses none when there are several', async () => {
    const clinic = create();
    await load(clinic, { clinics: [CLINIC_A, CLINIC_B], invitations: [] });

    expect(clinic.id()).toBeNull();
    expect(clinic.role()).toBeNull();
  });

  it('changes to another of the user’s clinics', async () => {
    const clinic = create();
    await load(clinic, { clinics: [CLINIC_A, CLINIC_B], invitations: [] });

    expect(clinic.select('b')).toBe(true);
    expect(clinic.id()).toBe('b');
    expect(clinic.role()).toBe('RECEPCION');
    expect(sessionStorage.getItem(ACTIVE_CLINIC_KEY)).toBe('b');
  });

  it('refuses a suspended clinic or one that is not the user’s', async () => {
    const clinic = create();
    await load(clinic, { clinics: [CLINIC_A, CLINIC_B, SUSPENDED], invitations: [] });

    expect(clinic.select('s')).toBe(false);
    expect(clinic.select('other')).toBe(false);
    expect(clinic.id()).toBeNull();
  });

  it('keeps the clinic of the tab after a reload while it is still the user’s', async () => {
    sessionStorage.setItem(ACTIVE_CLINIC_KEY, 'b');
    const clinic = create();
    await load(clinic, { clinics: [CLINIC_A, CLINIC_B], invitations: [] });

    expect(clinic.id()).toBe('b');
  });

  it('ignores a remembered clinic that has been suspended since', async () => {
    sessionStorage.setItem(ACTIVE_CLINIC_KEY, 's');
    const clinic = create();
    await load(clinic, { clinics: [CLINIC_A, CLINIC_B, SUSPENDED], invitations: [] });

    expect(clinic.id()).toBeNull();
    expect(sessionStorage.getItem(ACTIVE_CLINIC_KEY)).toBeNull();
  });

  it('says the clinic can no longer be used when the membership has ended', async () => {
    const clinic = create();
    await load(clinic, { clinics: [CLINIC_A, CLINIC_B], invitations: [] });
    clinic.select('b');

    const checking = clinic.revalidate();
    http.expectOne(ME).flush({ clinics: [CLINIC_A], invitations: [] });

    await expect(checking).resolves.toBe(false);
  });

  it('says the clinic can still be used when it is still listed', async () => {
    const clinic = create();
    await load(clinic, { clinics: [CLINIC_A, CLINIC_B], invitations: [] });
    clinic.select('b');

    const checking = clinic.revalidate();
    http.expectOne(ME).flush({ clinics: [CLINIC_A, CLINIC_B], invitations: [] });

    await expect(checking).resolves.toBe(true);
  });

  it('accepts an invitation with the endpoint of M02-T05 and reads /me again', async () => {
    const clinic = create();
    const invitation = {
      id: 'inv-1',
      clinicId: 'b',
      name: 'Clínica Sur',
      role: 'RECEPCION',
    } as const;
    await load(clinic, { clinics: [CLINIC_A], invitations: [invitation] });

    const accepting = clinic.accept('inv-1');
    const request = http.expectOne(`${ME}/invitations/inv-1/acceptance`);
    expect(request.request.method).toBe('POST');
    request.flush(null, { status: 204, statusText: 'No Content' });
    await vi.waitFor(() =>
      http.expectOne(ME).flush({ clinics: [CLINIC_A, CLINIC_B], invitations: [] }),
    );
    await accepting;

    expect(clinic.clinics().map((item) => item.clinicId)).toEqual(['a', 'b']);
    expect(clinic.invitations()).toEqual([]);
  });

  it('reads /me once for every caller', async () => {
    const clinic = create();
    const first = clinic.ensureLoaded();
    const second = clinic.ensureLoaded();
    http.expectOne(ME).flush({ clinics: [CLINIC_A], invitations: [] });

    await Promise.all([first, second]);
    expect(clinic.loaded()).toBe(true);
  });

  it('forgets the clinics and the choice', async () => {
    const clinic = create();
    await load(clinic, { clinics: [CLINIC_A], invitations: [] });

    clinic.clear();

    expect(clinic.loaded()).toBe(false);
    expect(clinic.id()).toBeNull();
    expect(sessionStorage.getItem(ACTIVE_CLINIC_KEY)).toBeNull();
  });
});
