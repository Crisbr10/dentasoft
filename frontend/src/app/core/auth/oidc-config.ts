import { DOCUMENT } from '@angular/common';
import {
  EnvironmentProviders,
  InjectionToken,
  inject,
  makeEnvironmentProviders,
} from '@angular/core';
import {
  AbstractSecurityStorage,
  DefaultSessionStorageService,
  LogLevel,
  OpenIdConfiguration,
  StsConfigHttpLoader,
  StsConfigLoader,
  provideAuth,
} from 'angular-auth-oidc-client';
import { from, map } from 'rxjs';

import { RuntimeConfig, RuntimeConfigStore } from '../config/runtime-config';

/** The Keycloak realm and the public client of the web app (infra/keycloak/realm-dentasoft.json). */
export const REALM = 'dentasoft';
export const CLIENT_ID = 'dentasoft-web';

/**
 * Where the browser is: its origin is the app's own URL (Keycloak only accepts
 * DENTASOFT_URLS_APP as redirect), and assign() leaves the app for Keycloak. A token so tests
 * can replace it.
 */
export const BROWSER_LOCATION = new InjectionToken<Pick<Location, 'origin' | 'assign'>>(
  'BROWSER_LOCATION',
  { factory: () => inject(DOCUMENT).location },
);

/**
 * Authorization code flow with PKCE. The token is renewed with the refresh token, never in a
 * Keycloak iframe: no silentRenewUrl and no checkSession, so the CSP keeps frame-src 'none'
 * (scripts/csp.mjs). No offline_access: the refresh token must end with the Keycloak session.
 */
export function buildOidcConfig(config: RuntimeConfig, appOrigin: string): OpenIdConfiguration {
  const home = `${appOrigin}/`;
  return {
    authority: `${config.authUrl}/realms/${REALM}`,
    clientId: CLIENT_ID,
    redirectUrl: home,
    postLogoutRedirectUri: home,
    responseType: 'code',
    scope: 'openid profile email',
    useRefreshToken: true,
    silentRenew: true,
    renewTimeBeforeTokenExpiresInSeconds: 30,
    startCheckSession: false,
    disableRefreshTokenOfflineAccessScopeWarning: true,
    // Keycloak does not repeat the nonce in the ID token it returns on refresh.
    ignoreNonceAfterRefresh: true,
    // The name and email come from the ID token; no extra call to the userinfo endpoint.
    autoUserInfo: false,
    // The library's own interceptor matches URLs by prefix; api-interceptor.ts adds the token.
    secureRoutes: [],
    logLevel: LogLevel.Warn,
  };
}

/**
 * Login with Keycloak. The configuration waits for config.json, and the tokens live in
 * sessionStorage, never in localStorage: they end with the tab.
 */
export function provideOidc(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideAuth({
      loader: {
        provide: StsConfigLoader,
        useFactory: () => {
          const store = inject(RuntimeConfigStore);
          const origin = inject(BROWSER_LOCATION).origin;
          return new StsConfigHttpLoader(
            from(store.load()).pipe(map((config) => buildOidcConfig(config, origin))),
          );
        },
      },
    }),
    { provide: AbstractSecurityStorage, useClass: DefaultSessionStorageService },
  ]);
}
