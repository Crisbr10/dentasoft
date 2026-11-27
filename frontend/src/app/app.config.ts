import { registerLocaleData } from '@angular/common';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import localeEs from '@angular/common/locales/es';
import {
  ApplicationConfig,
  DEFAULT_CURRENCY_CODE,
  LOCALE_ID,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { AUTH_INTERCEPTORS, provideAuthentication } from './core/auth/provide-authentication';
import { provideApiClient } from './core/config/api-client';
import { provideRuntimeConfig } from './core/config/runtime-config';
import { provideI18n } from './core/i18n/i18n';
import { provideIcons } from './core/icons/icons';

registerLocaleData(localeEs);

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withFetch(), withInterceptors(AUTH_INTERCEPTORS)),
    provideRuntimeConfig(),
    provideAuthentication(),
    provideApiClient(),
    provideI18n(),
    provideIcons(),
    { provide: LOCALE_ID, useValue: 'es' },
    { provide: DEFAULT_CURRENCY_CODE, useValue: 'EUR' },
  ],
};
