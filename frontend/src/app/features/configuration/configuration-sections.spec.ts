import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { ApiConfiguration } from '../../api/api-configuration';
import { Session } from '../../core/auth/session';
import { ActiveClinic } from '../../core/clinic/active-clinic';
import { ConfigurationSections } from './configuration-sections';

const API = 'https://api.dentasoft.test';
const CLINIC_SECTIONS = `${API}/api/v1/clinic/configuration/sections`;
const PLATFORM_SECTIONS = `${API}/api/v1/platform/configuration/sections`;

describe('ConfigurationSections', () => {
  let http: HttpTestingController;
  let clinicId: ReturnType<typeof signal<string | null>>;
  let platformAdmin: boolean;
  let sections: ConfigurationSections;

  beforeEach(() => {
    clinicId = signal<string | null>('a');
    platformAdmin = false;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ApiConfiguration, useValue: { rootUrl: API } },
        { provide: ActiveClinic, useValue: { id: clinicId } },
        {
          provide: Session,
          useValue: { checkPlatformAdmin: () => Promise.resolve(platformAdmin) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    sections = TestBed.inject(ConfigurationSections);
  });

  afterEach(() => http.verify());

  /** Lets the service ask, after it awaited the platform role. */
  function expectRequest(url: string) {
    return vi.waitFor(() => http.expectOne(url));
  }

  it('reads the sections of the active clinic, in menu order', async () => {
    const loading = sections.ensureLoaded();
    (await expectRequest(CLINIC_SECTIONS)).flush([
      { section: 'PRACTITIONERS', access: 'VIEW' },
      { section: 'CLINIC', access: 'VIEW' },
    ]);
    await loading;

    expect(sections.visible().map((entry) => [entry.path, entry.access])).toEqual([
      ['clinica', 'VIEW'],
      ['profesionales', 'VIEW'],
    ]);
    expect(sections.accessTo('USERS_AND_ROLES')).toBeNull();
    http.expectNone(PLATFORM_SECTIONS);
  });

  it('reads only the platform sections for a platform administrator without a clinic', async () => {
    clinicId.set(null);
    platformAdmin = true;

    const loading = sections.ensureLoaded();
    (await expectRequest(PLATFORM_SECTIONS)).flush([{ section: 'PLATFORM', access: 'EDIT' }]);
    await loading;

    expect(sections.accessTo('PLATFORM')).toBe('EDIT');
    http.expectNone(CLINIC_SECTIONS);
  });

  it('adds the platform sections for a platform administrator who works in a clinic', async () => {
    platformAdmin = true;

    const loading = sections.ensureLoaded();
    (await expectRequest(CLINIC_SECTIONS)).flush([{ section: 'CLINIC', access: 'EDIT' }]);
    (await expectRequest(PLATFORM_SECTIONS)).flush([{ section: 'PLATFORM', access: 'EDIT' }]);
    await loading;

    expect(sections.visible().map((entry) => entry.id)).toEqual(['CLINIC', 'PLATFORM']);
  });

  it('asks once for calls made at the same time, and not again for the same clinic', async () => {
    const first = sections.ensureLoaded();
    const second = sections.ensureLoaded();
    (await expectRequest(CLINIC_SECTIONS)).flush([]);
    await Promise.all([first, second]);

    await sections.ensureLoaded();
    http.expectNone(CLINIC_SECTIONS);
  });

  it('reads them again when the active clinic changes', async () => {
    const first = sections.ensureLoaded();
    (await expectRequest(CLINIC_SECTIONS)).flush([{ section: 'CLINIC', access: 'EDIT' }]);
    await first;

    clinicId.set('b');
    const second = sections.ensureLoaded();
    (await expectRequest(CLINIC_SECTIONS)).flush([{ section: 'CLINIC', access: 'VIEW' }]);
    await second;

    expect(sections.accessTo('CLINIC')).toBe('VIEW');
  });

  it('says when the read failed and tries again on the next call', async () => {
    const failing = sections.ensureLoaded();
    (await expectRequest(CLINIC_SECTIONS)).flush(null, { status: 500, statusText: 'Error' });
    await expect(failing).rejects.toBeTruthy();
    expect(sections.failed()).toBe(true);

    const retry = sections.ensureLoaded();
    (await expectRequest(CLINIC_SECTIONS)).flush([{ section: 'CLINIC', access: 'VIEW' }]);
    await retry;

    expect(sections.failed()).toBe(false);
    expect(sections.accessTo('CLINIC')).toBe('VIEW');
  });
});
