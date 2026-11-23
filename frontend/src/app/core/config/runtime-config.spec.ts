import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { RUNTIME_CONFIG_FILE, RuntimeConfigStore, parseRuntimeConfig } from './runtime-config';

describe('parseRuntimeConfig', () => {
  it('reads the API and Keycloak URLs without a trailing slash', () => {
    expect(
      parseRuntimeConfig({
        apiUrl: 'https://api.dentasoft.test/',
        authUrl: 'https://auth.dentasoft.test',
      }),
    ).toEqual({ apiUrl: 'https://api.dentasoft.test', authUrl: 'https://auth.dentasoft.test' });
  });

  it('rejects a file that is not an object', () => {
    expect(() => parseRuntimeConfig('nope')).toThrow('config.json is not a JSON object');
    expect(() => parseRuntimeConfig(null)).toThrow('config.json is not a JSON object');
  });

  it('rejects missing or relative URLs', () => {
    expect(() => parseRuntimeConfig({ authUrl: 'https://auth.dentasoft.test' })).toThrow(
      'config.json: apiUrl must be an absolute URL',
    );
    expect(() =>
      parseRuntimeConfig({ apiUrl: 'https://api.dentasoft.test', authUrl: '/auth' }),
    ).toThrow('config.json: authUrl must be an absolute URL');
  });
});

describe('RuntimeConfigStore', () => {
  let store: RuntimeConfigStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(RuntimeConfigStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads config.json relative to the app, not from a fixed domain', async () => {
    const loading = store.load();
    http
      .expectOne(RUNTIME_CONFIG_FILE)
      .flush({ apiUrl: 'https://api.other.test', authUrl: 'https://auth.other.test' });
    await loading;

    expect(store.config).toEqual({
      apiUrl: 'https://api.other.test',
      authUrl: 'https://auth.other.test',
    });
  });

  it('fails to start when config.json cannot be read', async () => {
    const loading = store.load();
    http.expectOne(RUNTIME_CONFIG_FILE).flush('', { status: 404, statusText: 'Not Found' });

    await expect(loading).rejects.toBeTruthy();
    expect(() => store.config).toThrow('Runtime configuration is not loaded');
  });
});
