import { TestBed } from '@angular/core/testing';

import { ApiConfiguration } from '../../api/api-configuration';
import { provideApiConfiguration } from './api-configuration';
import { RuntimeConfigStore } from './runtime-config';

describe('provideApiConfiguration', () => {
  it('points the generated client at the API URL of config.json', () => {
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(),
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
