import { EnvironmentProviders, inject, provideAppInitializer } from '@angular/core';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';

/**
 * SVG icons served from the build (src/assets/icons), never from an external domain.
 * They come from Material Icons (Apache 2.0). To add one, copy its SVG there and add its name here.
 */
export const ICONS = [
  'menu',
  'home',
  'check-circle',
  'error',
  'refresh',
  'logout',
  'swap',
  'business',
] as const;

export function provideIcons(): EnvironmentProviders {
  return provideAppInitializer(() => {
    const registry = inject(MatIconRegistry);
    const sanitizer = inject(DomSanitizer);
    for (const name of ICONS) {
      // Constant same-origin paths, so trusting them is safe.
      registry.addSvgIcon(
        name,
        sanitizer.bypassSecurityTrustResourceUrl(`assets/icons/${name}.svg`),
      );
    }
  });
}
