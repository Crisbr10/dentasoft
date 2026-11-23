import { TranslocoTestingModule } from '@jsverse/transloco';

import es from '../assets/i18n/es.json';

/** Transloco with the real Spanish file, so tests check the texts users see. */
export function translocoTesting() {
  return TranslocoTestingModule.forRoot({
    langs: { es },
    translocoConfig: { availableLangs: ['es'], defaultLang: 'es', fallbackLang: 'es' },
    preloadLangs: true,
  });
}

export { es };
