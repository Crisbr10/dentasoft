import { HttpInterceptorFn } from '@angular/common/http';
import {
  DestroyRef,
  EnvironmentProviders,
  inject,
  makeEnvironmentProviders,
  provideAppInitializer,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EventTypes, OidcSecurityService, PublicEventsService } from 'angular-auth-oidc-client';
import { filter, firstValueFrom } from 'rxjs';

import { apiInterceptor } from '../http/api-interceptor';
import { provideOidc } from './oidc-config';
import { Session } from './session';

/** Interceptors of the app, for provideHttpClient(withInterceptors(...)). */
export const AUTH_INTERCEPTORS: HttpInterceptorFn[] = [apiInterceptor];

/**
 * Login with Keycloak. At startup it finishes a login that comes back from Keycloak (the code
 * in the URL) or restores the session of this tab; if the refresh token is refused later, the
 * session has ended and the user goes back to the login.
 */
export function provideAuthentication(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideOidc(),
    provideAppInitializer(async () => {
      const oidc = inject(OidcSecurityService);
      const session = inject(Session);
      inject(PublicEventsService)
        .registerForEvents()
        .pipe(
          filter((event) => event.type === EventTypes.SilentRenewFailed),
          takeUntilDestroyed(inject(DestroyRef)),
        )
        .subscribe(() => session.expired());
      try {
        await firstValueFrom(oidc.checkAuth());
      } catch (error) {
        // Without a session, sessionGuard sends the user to the login. Only the error's type is
        // logged: its message could carry a token or a code from the URL.
        console.error('Login check failed', error instanceof Error ? error.name : typeof error);
      }
    }),
  ]);
}
