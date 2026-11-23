import { formatDate, formatNumber } from '@angular/common';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';

import { appConfig } from './app.config';
import { DEFAULT_LANG, provideI18n } from './core/i18n/i18n';

describe('appConfig', () => {
  it('uses the Spanish locale for dates and numbers', () => {
    expect(appConfig.providers).toContainEqual({ provide: LOCALE_ID, useValue: 'es' });
    expect(formatNumber(12345.67, 'es', '1.2-2')).toBe('12.345,67');
    expect(formatDate('2026-11-23T10:00:00Z', 'EEEE', 'es', 'UTC')).toBe('lunes');
  });

  it('uses Spanish as the default and fallback language', () => {
    TestBed.configureTestingModule({ providers: [provideI18n()] });
    const transloco = TestBed.inject(TranslocoService);

    expect(DEFAULT_LANG).toBe('es');
    expect(transloco.getDefaultLang()).toBe('es');
    expect(transloco.config.fallbackLang).toBe('es');
    expect(transloco.getAvailableLangs()).toEqual(['es']);
  });
});
