import { DOCUMENT } from '@angular/common';
import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { switchMap, take, throwError, catchError } from 'rxjs';

import { Session } from '../auth/session';
import { ActiveClinic } from '../clinic/active-clinic';
import { RuntimeConfigStore } from '../config/runtime-config';

/** The header the clinic API reads the active clinic from (ADR 0015). */
export const CLINIC_HEADER = 'X-Clinic-Id';

export const CLINIC_API = '/api/v1/clinic';

export const CLINIC_SELECTOR_ROUTE = '/clinica';

/**
 * The path of a request inside the API, or null when it does not go to the API. The URL is
 * parsed first (relative ones against the page) and its exact origin compared with apiUrl's,
 * never its text by prefix: a host that merely starts with the API's name, or user info that
 * looks like it before an "@", is another server (see api-interceptor.spec.ts).
 */
export function apiPath(url: string, apiUrl: string, pageUrl: string): string | null {
  let target: URL;
  try {
    target = new URL(url, pageUrl);
  } catch {
    return null;
  }
  const api = new URL(apiUrl);
  if (target.origin !== api.origin) {
    return null;
  }
  // Same server: the path must also be under apiUrl's own path, if it has one.
  const base = api.pathname.replace(/\/+$/, '');
  if (base && target.pathname !== base && !target.pathname.startsWith(`${base}/`)) {
    return null;
  }
  return target.pathname.slice(base.length) || '/';
}

/** The clinic API, where every request names its clinic. */
export function isClinicApi(path: string): boolean {
  return path === CLINIC_API || path.startsWith(`${CLINIC_API}/`);
}

/**
 * Adds the access token to the API's requests only, and the active clinic to the clinic API's
 * only. A 401 renews the token once and repeats the request; if it cannot be renewed, the user
 * goes back to the login. A 403 from the clinic API checks /me again and, if the clinic can no
 * longer be used, goes to the clinic selector; otherwise it is a role the user lacks.
 */
export const apiInterceptor: HttpInterceptorFn = (request, next) => {
  // config.json itself is read through HttpClient, before there is any API URL.
  const apiUrl = inject(RuntimeConfigStore).peek()?.apiUrl;
  const path = apiUrl ? apiPath(request.url, apiUrl, inject(DOCUMENT).baseURI) : null;
  if (path === null) {
    return next(request);
  }
  const oidc = inject(OidcSecurityService);
  const session = inject(Session);
  const clinic = inject(ActiveClinic);
  const router = inject(Router);
  const clinicApi = isClinicApi(path);
  const send = (token: string) => next(withHeaders(request, token, clinicApi ? clinic.id() : null));

  return oidc.getAccessToken().pipe(
    take(1),
    switchMap((token) => send(token)),
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        return session.renew().pipe(
          switchMap((token) => {
            if (token) {
              return send(token);
            }
            session.expired();
            return throwError(() => error);
          }),
        );
      }
      if (error instanceof HttpErrorResponse && error.status === 403 && clinicApi) {
        void clinic.revalidate().then(
          (usable) => usable || router.navigateByUrl(CLINIC_SELECTOR_ROUTE),
          () => router.navigateByUrl(CLINIC_SELECTOR_ROUTE),
        );
      }
      return throwError(() => error);
    }),
  );
};

function withHeaders(
  request: HttpRequest<unknown>,
  token: string,
  clinicId: string | null,
): HttpRequest<unknown> {
  const headers: Record<string, string> = {};
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  if (clinicId) {
    headers[CLINIC_HEADER] = clinicId;
  }
  return request.clone({ setHeaders: headers });
}
