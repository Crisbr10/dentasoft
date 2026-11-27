import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationInitStatus } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { of } from 'rxjs';

import { provideApiClient } from '../config/api-client';
import { RUNTIME_CONFIG_FILE, provideRuntimeConfig } from '../config/runtime-config';
import { provideAuthentication } from './provide-authentication';

describe('provideAuthentication', () => {
  it('starts the app when config.json arrives after the initializers have begun', async () => {
    const checkAuth = vi.fn(() => of({ isAuthenticated: false }));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRuntimeConfig(),
        provideApiClient(),
        provideAuthentication(),
        {
          provide: OidcSecurityService,
          useValue: {
            checkAuth,
            isAuthenticated$: of({ isAuthenticated: false }),
            userData: () => ({ userData: null, allUserData: [] }),
          },
        },
      ],
    });
    // Injecting anything runs the app initializers, as bootstrapApplication does.
    const status = TestBed.inject(ApplicationInitStatus);
    TestBed.inject(HttpTestingController)
      .expectOne(RUNTIME_CONFIG_FILE)
      .flush({ apiUrl: 'https://api.dentasoft.test', authUrl: 'https://auth.dentasoft.test' });

    await expect(status.donePromise).resolves.toBeUndefined();
    expect(checkAuth).toHaveBeenCalledTimes(1);
  });
});
