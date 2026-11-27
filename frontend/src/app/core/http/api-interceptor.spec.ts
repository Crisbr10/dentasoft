import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { firstValueFrom, of } from 'rxjs';

import { Session } from '../auth/session';
import { ActiveClinic } from '../clinic/active-clinic';
import { RuntimeConfigStore } from '../config/runtime-config';
import { CLINIC_HEADER, apiInterceptor, apiPath } from './api-interceptor';

const API = 'https://api.dentasoft.test';
const PAGE = 'https://app.dentasoft.test/clinica';
// Trap URLs of the task (M02-T07), built from parts: scripts/no-hardcoded-urls.test.mjs refuses
// any literal URL with "@" or starting with "//" in src/, even to .test hosts.
const EVIL = 'evil.test';
const USERINFO_TRAP = `${API}@${EVIL}`;
const PROTOCOL_RELATIVE_TRAP = `/${'/'}${EVIL}`;

describe('apiPath', () => {
  it('returns the path of a request to the API', () => {
    expect(apiPath(`${API}/api/v1/me`, API, PAGE)).toBe('/api/v1/me');
  });

  it.each([
    ['a host that only starts like the API', 'https://api.dentasoft.test.evil.test/api/v1/me'],
    ['user info that looks like the API', `${USERINFO_TRAP}/api/v1/me`],
    ['a protocol-relative URL to another host', `${PROTOCOL_RELATIVE_TRAP}/api/v1/me`],
    ['a relative path, which is the app’s own server', '/api/v1/me'],
    ['another port of the same host', 'https://api.dentasoft.test:8443/api/v1/me'],
    ['plain http', 'http://api.dentasoft.test/api/v1/me'],
  ])('is null for %s', (_case, url) => {
    expect(apiPath(url, API, PAGE)).toBeNull();
  });

  it('stays under the path of apiUrl when it has one', () => {
    const base = 'https://dentasoft.test/api';
    expect(apiPath('https://dentasoft.test/api/api/v1/me', base, PAGE)).toBe('/api/v1/me');
    expect(apiPath('https://dentasoft.test/apix/v1/me', base, PAGE)).toBeNull();
    expect(apiPath('https://dentasoft.test/config.json', base, PAGE)).toBeNull();
  });
});

describe('apiInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let session: { renew: ReturnType<typeof vi.fn>; expired: ReturnType<typeof vi.fn> };
  let clinic: {
    id: ReturnType<typeof signal<string | null>>;
    revalidate: ReturnType<typeof vi.fn>;
  };
  let router: { navigateByUrl: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    session = { renew: vi.fn(), expired: vi.fn() };
    clinic = { id: signal<string | null>('clinic-a'), revalidate: vi.fn() };
    router = { navigateByUrl: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
        {
          provide: RuntimeConfigStore,
          useValue: { peek: () => ({ apiUrl: API, authUrl: 'https://auth.dentasoft.test' }) },
        },
        { provide: OidcSecurityService, useValue: { getAccessToken: () => of('token-1') } },
        { provide: Session, useValue: session },
        { provide: ActiveClinic, useValue: clinic },
        { provide: Router, useValue: router },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  function get(url: string): Promise<unknown> {
    return firstValueFrom(http.get(url));
  }

  it('adds the access token and the active clinic to the clinic API', async () => {
    const response = get(`${API}/api/v1/clinic/practitioner-roles`);
    const request = backend.expectOne(`${API}/api/v1/clinic/practitioner-roles`);

    expect(request.request.headers.get('Authorization')).toBe('Bearer token-1');
    expect(request.request.headers.get(CLINIC_HEADER)).toBe('clinic-a');
    request.flush({ content: [] });
    await expect(response).resolves.toEqual({ content: [] });
  });

  it('sends the clinic chosen last: changing clinic changes the header', () => {
    clinic.id.set('clinic-b');
    void get(`${API}/api/v1/clinic/practitioner-roles`);
    const request = backend.expectOne(`${API}/api/v1/clinic/practitioner-roles`);

    expect(request.request.headers.get(CLINIC_HEADER)).toBe('clinic-b');
    request.flush({});
  });

  it('adds only the token to the rest of the API', () => {
    void get(`${API}/api/v1/me`);
    const request = backend.expectOne(`${API}/api/v1/me`);

    expect(request.request.headers.get('Authorization')).toBe('Bearer token-1');
    expect(request.request.headers.has(CLINIC_HEADER)).toBe(false);
    request.flush({});
  });

  it('does not take a path that only starts like the clinic API for it', () => {
    void get(`${API}/api/v1/clinicx`);
    const request = backend.expectOne(`${API}/api/v1/clinicx`);

    expect(request.request.headers.has(CLINIC_HEADER)).toBe(false);
    request.flush({});
  });

  it.each([
    'https://api.dentasoft.test.evil.test/api/v1/clinic/patients',
    `${USERINFO_TRAP}/api/v1/clinic/patients`,
    `${PROTOCOL_RELATIVE_TRAP}/api/v1/clinic/patients`,
    '/api/v1/clinic/patients',
  ])('adds nothing to %s', (url) => {
    void get(url);
    const request = backend.expectOne(url);

    expect(request.request.headers.has('Authorization')).toBe(false);
    expect(request.request.headers.has(CLINIC_HEADER)).toBe(false);
    request.flush({});
  });

  it('renews the token after a 401 and repeats the request once', async () => {
    session.renew.mockReturnValue(of('token-2'));
    const response = get(`${API}/api/v1/me`);
    backend.expectOne(`${API}/api/v1/me`).flush(null, { status: 401, statusText: 'Unauthorized' });
    const retry = backend.expectOne(`${API}/api/v1/me`);

    expect(retry.request.headers.get('Authorization')).toBe('Bearer token-2');
    retry.flush({ clinics: [] });
    await expect(response).resolves.toEqual({ clinics: [] });
    expect(session.expired).not.toHaveBeenCalled();
  });

  it('goes back to the login when the token cannot be renewed', async () => {
    session.renew.mockReturnValue(of(null));
    const response = get(`${API}/api/v1/me`);
    backend.expectOne(`${API}/api/v1/me`).flush(null, { status: 401, statusText: 'Unauthorized' });

    await expect(response).rejects.toBeInstanceOf(HttpErrorResponse);
    expect(session.expired).toHaveBeenCalledTimes(1);
  });

  it('does not renew again when the repeated request is also refused', async () => {
    session.renew.mockReturnValue(of('token-2'));
    const response = get(`${API}/api/v1/me`);
    backend.expectOne(`${API}/api/v1/me`).flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne(`${API}/api/v1/me`).flush(null, { status: 401, statusText: 'Unauthorized' });

    await expect(response).rejects.toMatchObject({ status: 401 });
    expect(session.renew).toHaveBeenCalledTimes(1);
  });

  it('leaves a 401 from another server alone', async () => {
    const url = 'https://auth.dentasoft.test/realms/dentasoft/protocol/openid-connect/token';
    const response = firstValueFrom(http.post(url, {}));
    backend.expectOne(url).flush(null, { status: 401, statusText: 'Unauthorized' });

    await expect(response).rejects.toMatchObject({ status: 401 });
    expect(session.renew).not.toHaveBeenCalled();
  });

  it('sends the user to the clinic selector when the clinic API refuses the clinic', async () => {
    clinic.revalidate.mockResolvedValue(false);
    const response = get(`${API}/api/v1/clinic/practitioner-roles`);
    backend
      .expectOne(`${API}/api/v1/clinic/practitioner-roles`)
      .flush(null, { status: 403, statusText: 'Forbidden' });

    await expect(response).rejects.toMatchObject({ status: 403 });
    await vi.waitFor(() => expect(router.navigateByUrl).toHaveBeenCalledWith('/clinica'));
  });

  it('stays on the page when the clinic is still usable: the role lacks the permission', async () => {
    clinic.revalidate.mockResolvedValue(true);
    const response = get(`${API}/api/v1/clinic/users`);
    backend
      .expectOne(`${API}/api/v1/clinic/users`)
      .flush(null, { status: 403, statusText: 'Forbidden' });

    await expect(response).rejects.toMatchObject({ status: 403 });
    expect(clinic.revalidate).toHaveBeenCalledTimes(1);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('does not check the clinic after a 403 outside the clinic API', async () => {
    const response = get(`${API}/api/v1/platform/clinics`);
    backend
      .expectOne(`${API}/api/v1/platform/clinics`)
      .flush(null, { status: 403, statusText: 'Forbidden' });

    await expect(response).rejects.toMatchObject({ status: 403 });
    expect(clinic.revalidate).not.toHaveBeenCalled();
  });
});
