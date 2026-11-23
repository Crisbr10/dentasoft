import { Provider, inject } from '@angular/core';

import { ApiConfiguration } from '../../api/api-configuration';
import { RuntimeConfigStore } from './runtime-config';

/**
 * Points the generated API client (src/app/api) at the API URL of config.json. The client
 * is generated without a root URL (scripts/api-gen.mjs), so this provider is what sets it.
 * It is created on first use, after config.json has been loaded at startup.
 */
export function provideApiConfiguration(): Provider {
  return {
    provide: ApiConfiguration,
    useFactory: () => {
      const configuration = new ApiConfiguration();
      configuration.rootUrl = inject(RuntimeConfigStore).config.apiUrl;
      return configuration;
    },
  };
}
