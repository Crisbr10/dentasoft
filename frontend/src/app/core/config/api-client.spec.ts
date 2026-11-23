import { TestBed } from '@angular/core/testing';

import { ApiConfiguration } from '../../api/api-configuration';
import { provideApiClient } from './api-client';
import { RuntimeConfigStore } from './runtime-config';

describe('provideApiClient', () => {
  it('points the generated client at the API URL of config.json', () => {
    TestBed.configureTestingModule({
      providers: [
        provideApiClient(),
        {
          provide: RuntimeConfigStore,
          useValue: {
            config: {
              apiUrl: 'https://api.dentasoft.test',
              authUrl: 'https://auth.dentasoft.test',
            },
          },
        },
      ],
    });

    expect(TestBed.inject(ApiConfiguration).rootUrl).toBe('https://api.dentasoft.test');
  });
});
