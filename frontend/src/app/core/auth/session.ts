import { Injectable, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import {
  Observable,
  catchError,
  finalize,
  firstValueFrom,
  map,
  of,
  shareReplay,
  switchMap,
  take,
  throwError,
} from 'rxjs';

import { ActiveClinic } from '../clinic/active-clinic';
import { BROWSER_LOCATION } from './oidc-config';
import { CLINIC_SELECTOR_ROUTE } from './routes';

/** The realm role of the platform administration (ADR 0011); clinic roles come from /me. */
export const PLATFORM_ADMIN = 'PLATFORM_ADMIN';

/** The page to go back to after the Keycloak login. */
export const RETURN_URL_KEY = 'dentasoft.returnUrl';

/** True when the access token's realm roles (realm_access.roles) include PLATFORM_ADMIN. */
export function hasPlatformAdmin(payload: unknown): boolean {
  const realmAccess = (payload as { realm_access?: { roles?: unknown } } | null)?.realm_access;
  const roles = realmAccess?.roles;
  return Array.isArray(roles) && roles.includes(PLATFORM_ADMIN);
}

/** Keycloak or the network could not be reached: the session may still be valid. */
export class SessionUnavailableError extends Error {
  constructor() {
    super('The session could not be renewed now');
    this.name = 'SessionUnavailableError';
  }
}

/** Only paths of this app: never a protocol-relative URL to another site after the login. */
export function isAppPath(url: string | null): url is string {
  return url !== null && url.startsWith('/') && !url.startsWith('//') && !url.startsWith('/\\');
}

/**
 * The Keycloak session as the app uses it: login, logout, a renewal after a 401 and the
 * return to the login page when the session has ended.
 */
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly oidc = inject(OidcSecurityService);
  private readonly clinic = inject(ActiveClinic);
  private readonly router = inject(Router);
  private readonly location = inject(BROWSER_LOCATION);
  private renewing: Observable<string | null> | null = null;
  private checkingClinic: Promise<void> | null = null;
  private leaving = false;

  private readonly platformAdmin$ = this.oidc.isAuthenticated$.pipe(
    switchMap(({ isAuthenticated }) =>
      isAuthenticated ? this.oidc.getPayloadFromAccessToken() : of(null),
    ),
    map(hasPlatformAdmin),
  );

  /** For the menu. Guards use checkPlatformAdmin(), which waits for the token to be read. */
  readonly isPlatformAdmin = toSignal(this.platformAdmin$, { initialValue: false });

  checkPlatformAdmin(): Promise<boolean> {
    return firstValueFrom(this.platformAdmin$);
  }

  /** From the ID token, shown in the header. */
  readonly userName = computed(() => {
    const data = this.oidc.userData().userData as { name?: unknown } | null;
    return typeof data?.name === 'string' ? data.name : null;
  });

  /**
   * The Keycloak `sub` of the ID token, which is the `userId` of the user's memberships (ADR 0015).
   * Only for convenience in the interface, such as not offering actions on one's own row.
   */
  readonly userId = computed(() => {
    const data = this.oidc.userData().userData as { sub?: unknown } | null;
    return typeof data?.sub === 'string' ? data.sub : null;
  });

  /** Goes to the Keycloak login and, after it, back to returnUrl. */
  login(returnUrl: string): void {
    if (isAppPath(returnUrl)) {
      sessionStorage.setItem(RETURN_URL_KEY, returnUrl);
    }
    this.oidc.authorize();
  }

  /** The page the login started from, once. */
  takeReturnUrl(): string | null {
    const url = sessionStorage.getItem(RETURN_URL_KEY);
    sessionStorage.removeItem(RETURN_URL_KEY);
    return isAppPath(url) ? url : null;
  }

  /**
   * Ends the Keycloak session too (end_session_endpoint). Before leaving, it forgets
   * everything of the app: sessionStorage and the state in memory (active clinic, /me).
   */
  logout(): void {
    let redirected = false;
    // No end-session URL (no session left) or Keycloak unreachable: never stay signed in here.
    const logoutHere = () => {
      if (!redirected) {
        this.oidc.logoffLocal();
        this.forget();
        this.oidc.authorize();
      }
    };
    this.oidc
      .logoff(undefined, {
        urlHandler: (url) => {
          redirected = true;
          this.forget();
          this.location.assign(url);
        },
      })
      .subscribe({ complete: logoutHere, error: logoutHere });
  }

  /**
   * The session has ended (the refresh token was refused): back to the login, where the user
   * left, instead of a page whose requests all fail. Only once, however many requests fail.
   */
  expired(): void {
    if (this.leaving) {
      return;
    }
    this.leaving = true;
    const back = this.router.url;
    this.oidc.logoffLocal();
    this.forget();
    this.login(back);
  }

  /**
   * A new access token from the refresh token, or null when Keycloak refused it: the session
   * has ended. When Keycloak or the network could not be reached it fails with
   * SessionUnavailableError instead, and the session goes on. Requests that fail together
   * share one renewal.
   */
  renew(): Observable<string | null> {
    this.renewing ??= this.oidc.forceRefreshSession().pipe(
      map((result) => (result?.isAuthenticated && result.accessToken ? result.accessToken : null)),
      catchError(() =>
        this.hasRefreshToken().pipe(
          switchMap((kept) => (kept ? throwError(() => new SessionUnavailableError()) : of(null))),
        ),
      ),
      finalize(() => (this.renewing = null)),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.renewing;
  }

  /**
   * After a failed renewal in the background (SilentRenewFailed): back to the login only when
   * the session has ended. If the refresh token is still there, the failure was the network or
   * a timeout, and the library tries again on its next check.
   */
  renewalFailed(): void {
    this.hasRefreshToken().subscribe((kept) => kept || this.expired());
  }

  /**
   * After a 403 from the clinic API: reads /me again and, if the clinic can no longer be used,
   * goes to the clinic selector. Requests refused together share one check and one navigation.
   * If /me cannot be read, nothing happens here: a 401 already sends the user to the login.
   */
  clinicRefused(): Promise<void> {
    this.checkingClinic ??= this.clinic
      .revalidate()
      .then(
        async (usable) => {
          if (!usable) {
            await this.router.navigateByUrl(CLINIC_SELECTOR_ROUTE);
          }
        },
        () => undefined,
      )
      .finally(() => (this.checkingClinic = null));
    return this.checkingClinic;
  }

  /**
   * The library deletes the stored tokens when Keycloak refuses the refresh token, and keeps
   * them when the network fails or times out: that tells an ended session from an outage.
   */
  private hasRefreshToken(): Observable<boolean> {
    return this.oidc.getRefreshToken().pipe(
      take(1),
      map((token) => Boolean(token)),
      catchError(() => of(false)),
    );
  }

  private forget(): void {
    this.clinic.clear();
    sessionStorage.clear();
  }
}
