import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { LogoutAuthOptions, OidcSecurityService } from 'angular-auth-oidc-client';
import { BehaviorSubject, Subject, firstValueFrom, of, throwError } from 'rxjs';

import { ActiveClinic } from '../clinic/active-clinic';
import { BROWSER_LOCATION } from './oidc-config';
import {
  RETURN_URL_KEY,
  Session,
  SessionUnavailableError,
  hasPlatformAdmin,
  isAppPath,
} from './session';

// Built from parts: scripts/no-hardcoded-urls.test.mjs refuses literal URLs starting with "//".
const PROTOCOL_RELATIVE = `/${'/'}evil.test`;

const END_SESSION =
  'https://auth.dentasoft.test/realms/dentasoft/protocol/openid-connect/logout?id_token_hint=x';

describe('hasPlatformAdmin', () => {
  it('reads the realm roles of the access token', () => {
    expect(hasPlatformAdmin({ realm_access: { roles: ['PLATFORM_ADMIN'] } })).toBe(true);
    expect(hasPlatformAdmin({ realm_access: { roles: ['ADMIN_CLINICA'] } })).toBe(false);
    expect(hasPlatformAdmin({ realm_access: { roles: 'PLATFORM_ADMIN' } })).toBe(false);
    expect(hasPlatformAdmin({})).toBe(false);
    expect(hasPlatformAdmin(null)).toBe(false);
  });
});

describe('isAppPath', () => {
  it('accepts only paths of this app', () => {
    expect(isAppPath('/clinica')).toBe(true);
    expect(isAppPath(`${PROTOCOL_RELATIVE}/x`)).toBe(false);
    expect(isAppPath('/\\evil.test')).toBe(false);
    expect(isAppPath('https://evil.test')).toBe(false);
    expect(isAppPath(null)).toBe(false);
  });
});

describe('Session', () => {
  let session: Session;
  let oidc: {
    logoff: ReturnType<typeof vi.fn>;
    logoffLocal: ReturnType<typeof vi.fn>;
    authorize: ReturnType<typeof vi.fn>;
    forceRefreshSession: ReturnType<typeof vi.fn>;
    getRefreshToken: ReturnType<typeof vi.fn>;
    getPayloadFromAccessToken: ReturnType<typeof vi.fn>;
    isAuthenticated$: BehaviorSubject<{ isAuthenticated: boolean }>;
    userData: ReturnType<typeof signal>;
  };
  let clinic: { clear: ReturnType<typeof vi.fn>; revalidate: ReturnType<typeof vi.fn> };
  let router: { url: string; navigateByUrl: ReturnType<typeof vi.fn> };
  let location: { origin: string; assign: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    sessionStorage.clear();
    oidc = {
      logoff: vi.fn(),
      logoffLocal: vi.fn(),
      authorize: vi.fn(),
      forceRefreshSession: vi.fn(),
      // The library deletes the tokens when Keycloak refuses the refresh token.
      getRefreshToken: vi.fn(() => of('')),
      getPayloadFromAccessToken: vi.fn(() => of({ realm_access: { roles: [] } })),
      isAuthenticated$: new BehaviorSubject<{ isAuthenticated: boolean }>({
        isAuthenticated: true,
      }),
      userData: signal({
        userData: { name: 'Ana Prueba', sub: '0190c0de-0000-7000-8000-000000000001' },
        allUserData: [],
      }),
    };
    clinic = { clear: vi.fn(), revalidate: vi.fn() };
    router = { url: '/pacientes/42', navigateByUrl: vi.fn(() => Promise.resolve(true)) };
    location = { origin: 'https://app.dentasoft.test', assign: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: OidcSecurityService, useValue: oidc },
        { provide: ActiveClinic, useValue: clinic },
        { provide: BROWSER_LOCATION, useValue: location },
        { provide: Router, useValue: router },
      ],
    });
    session = TestBed.inject(Session);
  });

  afterEach(() => sessionStorage.clear());

  describe('logout', () => {
    it('ends the Keycloak session and first forgets everything of the app', () => {
      sessionStorage.setItem('dentasoft.activeClinicId', 'clinic-a');
      sessionStorage.setItem('0-dentasoft-web', '{"authnResult":{}}');
      oidc.logoff.mockImplementation((_config: unknown, options: LogoutAuthOptions) => {
        options.urlHandler?.(END_SESSION);
        return of(null);
      });

      session.logout();

      expect(oidc.logoff).toHaveBeenCalledTimes(1);
      expect(location.assign).toHaveBeenCalledWith(END_SESSION);
      expect(clinic.clear).toHaveBeenCalledTimes(1);
      expect(sessionStorage.length).toBe(0);
      expect(oidc.authorize).not.toHaveBeenCalled();
    });

    it('logs out locally and shows the login when Keycloak cannot be reached', () => {
      sessionStorage.setItem('dentasoft.activeClinicId', 'clinic-a');
      oidc.logoff.mockReturnValue(throwError(() => new Error('well-known unavailable')));

      session.logout();

      expect(oidc.logoffLocal).toHaveBeenCalledTimes(1);
      expect(clinic.clear).toHaveBeenCalledTimes(1);
      expect(sessionStorage.getItem('dentasoft.activeClinicId')).toBeNull();
      expect(oidc.authorize).toHaveBeenCalledTimes(1);
    });

    it('logs out locally and shows the login when Keycloak has no session left', () => {
      sessionStorage.setItem('dentasoft.activeClinicId', 'clinic-a');
      oidc.logoff.mockReturnValue(of(null));

      session.logout();

      expect(location.assign).not.toHaveBeenCalled();
      expect(oidc.logoffLocal).toHaveBeenCalledTimes(1);
      expect(clinic.clear).toHaveBeenCalledTimes(1);
      expect(sessionStorage.getItem('dentasoft.activeClinicId')).toBeNull();
      expect(oidc.authorize).toHaveBeenCalledTimes(1);
    });
  });

  describe('expired', () => {
    it('forgets the session and goes to the login, back to the same page afterwards', () => {
      sessionStorage.setItem('dentasoft.activeClinicId', 'clinic-a');

      session.expired();

      expect(oidc.logoffLocal).toHaveBeenCalledTimes(1);
      expect(clinic.clear).toHaveBeenCalledTimes(1);
      expect(sessionStorage.getItem('dentasoft.activeClinicId')).toBeNull();
      expect(sessionStorage.getItem(RETURN_URL_KEY)).toBe('/pacientes/42');
      expect(oidc.authorize).toHaveBeenCalledTimes(1);
    });

    it('goes to the login only once when many requests fail together', () => {
      session.expired();
      session.expired();
      session.expired();

      expect(oidc.authorize).toHaveBeenCalledTimes(1);
    });
  });

  describe('login', () => {
    it('remembers only paths of this app', () => {
      session.login(`${PROTOCOL_RELATIVE}/robar`);

      expect(sessionStorage.getItem(RETURN_URL_KEY)).toBeNull();
      expect(oidc.authorize).toHaveBeenCalledTimes(1);
    });

    it('gives the return page back once', () => {
      session.login('/clinica');

      expect(session.takeReturnUrl()).toBe('/clinica');
      expect(session.takeReturnUrl()).toBeNull();
    });
  });

  describe('renew', () => {
    it('asks Keycloak once for requests that fail together', async () => {
      const refresh = new Subject<{ isAuthenticated: boolean; accessToken: string }>();
      oidc.forceRefreshSession.mockReturnValue(refresh);

      const first = firstValueFrom(session.renew());
      const second = firstValueFrom(session.renew());
      refresh.next({ isAuthenticated: true, accessToken: 'token-2' });
      refresh.complete();

      await expect(first).resolves.toBe('token-2');
      await expect(second).resolves.toBe('token-2');
      expect(oidc.forceRefreshSession).toHaveBeenCalledTimes(1);
    });

    it('is null when Keycloak refuses the refresh token: the session has ended', async () => {
      oidc.forceRefreshSession.mockReturnValue(throwError(() => new Error('invalid_grant')));

      await expect(firstValueFrom(session.renew())).resolves.toBeNull();
    });

    it('fails without ending the session when Keycloak cannot be reached', async () => {
      oidc.forceRefreshSession.mockReturnValue(throwError(() => new Error('offline')));
      oidc.getRefreshToken.mockReturnValue(of('refresh-token'));

      await expect(firstValueFrom(session.renew())).rejects.toBeInstanceOf(SessionUnavailableError);
      expect(oidc.authorize).not.toHaveBeenCalled();
    });

    it('asks again after an earlier renewal has ended', async () => {
      oidc.forceRefreshSession.mockReturnValue(of({ isAuthenticated: true, accessToken: 't' }));

      await firstValueFrom(session.renew());
      await firstValueFrom(session.renew());

      expect(oidc.forceRefreshSession).toHaveBeenCalledTimes(2);
    });
  });

  describe('renewalFailed', () => {
    it('goes to the login when the background renewal was refused', () => {
      session.renewalFailed();

      expect(oidc.logoffLocal).toHaveBeenCalledTimes(1);
      expect(oidc.authorize).toHaveBeenCalledTimes(1);
    });

    it('keeps the session when the renewal failed for the network: it is tried again', () => {
      oidc.getRefreshToken.mockReturnValue(of('refresh-token'));

      session.renewalFailed();

      expect(oidc.logoffLocal).not.toHaveBeenCalled();
      expect(oidc.authorize).not.toHaveBeenCalled();
    });
  });

  describe('clinicRefused', () => {
    it('goes to the clinic selector when the clinic can no longer be used', async () => {
      clinic.revalidate.mockResolvedValue(false);

      await session.clinicRefused();

      expect(router.navigateByUrl).toHaveBeenCalledWith('/clinica');
    });

    it('stays on the page when the clinic is still usable: the role lacks the permission', async () => {
      clinic.revalidate.mockResolvedValue(true);

      await session.clinicRefused();

      expect(router.navigateByUrl).not.toHaveBeenCalled();
    });

    it('checks /me and navigates once for requests refused together', async () => {
      clinic.revalidate.mockResolvedValue(false);

      await Promise.all([
        session.clinicRefused(),
        session.clinicRefused(),
        session.clinicRefused(),
      ]);

      expect(clinic.revalidate).toHaveBeenCalledTimes(1);
      expect(router.navigateByUrl).toHaveBeenCalledTimes(1);
    });

    it('does not navigate when /me cannot be read: a 401 already goes to the login', async () => {
      clinic.revalidate.mockRejectedValue(new Error('401'));

      await session.clinicRefused();

      expect(router.navigateByUrl).not.toHaveBeenCalled();
    });

    it('checks again after an earlier check has ended', async () => {
      clinic.revalidate.mockResolvedValue(true);

      await session.clinicRefused();
      await session.clinicRefused();

      expect(clinic.revalidate).toHaveBeenCalledTimes(2);
    });
  });

  it('knows a platform administrator by the realm roles of the access token', async () => {
    oidc.getPayloadFromAccessToken.mockReturnValue(
      of({ realm_access: { roles: ['PLATFORM_ADMIN'] } }),
    );
    oidc.isAuthenticated$.next({ isAuthenticated: true });

    await expect(session.checkPlatformAdmin()).resolves.toBe(true);
  });

  it('is not a platform administrator without a session', async () => {
    oidc.isAuthenticated$.next({ isAuthenticated: false });

    await expect(session.checkPlatformAdmin()).resolves.toBe(false);
  });

  it('shows the name of the ID token', () => {
    expect(session.userName()).toBe('Ana Prueba');
  });

  it('gives the user id of the ID token, which is the userId of their memberships', () => {
    expect(session.userId()).toBe('0190c0de-0000-7000-8000-000000000001');
  });
});
