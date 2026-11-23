import { HttpClient } from '@angular/common/http';
import { EnvironmentProviders, Injectable, inject, isDevMode } from '@angular/core';
import { Translation, TranslocoLoader, provideTransloco } from '@jsverse/transloco';

export const DEFAULT_LANG = 'es';

@Injectable({ providedIn: 'root' })
export class TranslocoHttpLoader implements TranslocoLoader {
  private readonly http = inject(HttpClient);

  getTranslation(lang: string) {
    return this.http.get<Translation>(`assets/i18n/${lang}.json`);
  }
}

/** Transloco with Spanish as the default and fallback language (ADR 0003, ADR 0008). */
export function provideI18n(): EnvironmentProviders[] {
  return provideTransloco({
    config: {
      availableLangs: [DEFAULT_LANG],
      defaultLang: DEFAULT_LANG,
      fallbackLang: DEFAULT_LANG,
      missingHandler: { useFallbackTranslation: true },
      reRenderOnLangChange: true,
      prodMode: !isDevMode(),
    },
    loader: TranslocoHttpLoader,
  });
}
