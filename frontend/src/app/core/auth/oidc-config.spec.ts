import { TestBed } from '@angular/core/testing';
import {
  AbstractSecurityStorage,
  DefaultSessionStorageService,
  StsConfigLoader,
} from 'angular-auth-oidc-client';
import { firstValueFrom } from 'rxjs';

import { RuntimeConfigStore } from '../config/runtime-config';
import { BROWSER_LOCATION, CLIENT_ID, buildOidcConfig, provideOidc } from './oidc-config';

const RUNTIME = { apiUrl: 'https://api.dentasoft.test', authUrl: 'https://auth.dentasoft.test' };

describe('buildOidcConfig', () => {
  const config = buildOidcConfig(RUNTIME, 'https://app.dentasoft.test');

  it('uses the authorization code flow with PKCE against the dentasoft realm', () => {
    expect(config.authority).toBe('https://auth.dentasoft.test/realms/dentasoft');
    expect(config.clientId).toBe(CLIENT_ID);
    expect(config.responseType).toBe('code');
    expect(config.disablePkce).not.toBe(true);
    expect(config.redirectUrl).toBe('https://app.dentasoft.test/');
    expect(config.postLogoutRedirectUri).toBe('https://app.dentasoft.test/');
  });

  it('renews the token with the refresh token and opens no Keycloak iframe', () => {
    expect(config.useRefreshToken).toBe(true);
    expect(config.silentRenew).toBe(true);
    expect(config.silentRenewUrl).toBeUndefined();
    expect(config.startCheckSession).toBe(false);
  });

  it('asks for no offline token, so the session ends with Keycloak’s', () => {
    expect(config.scope?.split(' ')).not.toContain('offline_access');
  });

  it('leaves adding the token to api-interceptor.ts', () => {
    expect(config.secureRoutes).toEqual([]);
  });
});

describe('provideOidc', () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideOidc(),
        { provide: RuntimeConfigStore, useValue: { load: () => Promise.resolve(RUNTIME) } },
        {
          provide: BROWSER_LOCATION,
          useValue: { origin: 'https://app.dentasoft.test', assign: vi.fn() },
        },
      ],
    });
  });

  afterEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });

  it('keeps the tokens in sessionStorage, never in localStorage', () => {
    const storage = TestBed.inject(AbstractSecurityStorage);
    storage.write('authnResult', '{"access_token":"token"}');

    expect(storage).toBeInstanceOf(DefaultSessionStorageService);
    expect(sessionStorage.getItem('authnResult')).toContain('token');
    expect(localStorage.length).toBe(0);
  });

  it('builds the configuration from config.json once it is loaded', async () => {
    const [config] = await firstValueFrom(TestBed.inject(StsConfigLoader).loadConfigs());

    expect(config.authority).toBe('https://auth.dentasoft.test/realms/dentasoft');
    expect(config.redirectUrl).toBe('https://app.dentasoft.test/');
  });
});
