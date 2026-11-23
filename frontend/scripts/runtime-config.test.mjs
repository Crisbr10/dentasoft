import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, it } from 'node:test';

import { buildRuntimeConfig, DEV_DEFAULTS, writeRuntimeConfig } from './runtime-config.mjs';

const CLINIC_A = {
  DENTASOFT_URLS_API: 'https://api.dentasoft-a.test',
  DENTASOFT_URLS_AUTH: 'https://auth.dentasoft-a.test',
};
const CLINIC_B = {
  DENTASOFT_URLS_API: 'https://api.other-domain.test/',
  DENTASOFT_URLS_AUTH: 'https://login.other-domain.test',
};

describe('buildRuntimeConfig', () => {
  it('points a build to another domain when only the variables change', () => {
    assert.deepEqual(buildRuntimeConfig(CLINIC_A), {
      apiUrl: 'https://api.dentasoft-a.test',
      authUrl: 'https://auth.dentasoft-a.test',
    });
    assert.deepEqual(buildRuntimeConfig(CLINIC_B), {
      apiUrl: 'https://api.other-domain.test',
      authUrl: 'https://login.other-domain.test',
    });
  });

  it('fails a build without variables and names each missing one', () => {
    assert.throws(
      () => buildRuntimeConfig({}),
      (error) =>
        error.message.includes('DENTASOFT_URLS_API is not set') &&
        error.message.includes('DENTASOFT_URLS_AUTH is not set'),
    );
  });

  it('treats blank variables as missing', () => {
    assert.throws(
      () => buildRuntimeConfig({ ...CLINIC_A, DENTASOFT_URLS_API: '  ' }),
      /DENTASOFT_URLS_API is not set/,
    );
  });

  it('uses localhost only in development', () => {
    assert.deepEqual(buildRuntimeConfig({}, { dev: true }), DEV_DEFAULTS);
  });

  it('prefers the variables over the development defaults', () => {
    assert.deepEqual(buildRuntimeConfig(CLINIC_A, { dev: true }), {
      apiUrl: 'https://api.dentasoft-a.test',
      authUrl: 'https://auth.dentasoft-a.test',
    });
  });

  it('rejects plain http outside development', () => {
    assert.throws(
      () => buildRuntimeConfig({ ...CLINIC_A, DENTASOFT_URLS_API: 'http://api.dentasoft-a.test' }),
      /DENTASOFT_URLS_API: must use https outside development/,
    );
  });

  it('rejects values that are not absolute URLs', () => {
    assert.throws(
      () => buildRuntimeConfig({ ...CLINIC_A, DENTASOFT_URLS_AUTH: 'auth.dentasoft-a.test' }),
      /DENTASOFT_URLS_AUTH: must be an absolute URL/,
    );
  });

  it('rejects credentials, query strings and fragments', () => {
    for (const value of [
      'https://user:secret@api.dentasoft-a.test',
      'https://api.dentasoft-a.test?x=1',
      'https://api.dentasoft-a.test#x',
    ]) {
      assert.throws(
        () => buildRuntimeConfig({ ...CLINIC_A, DENTASOFT_URLS_API: value }),
        /must not contain credentials, a query string or a fragment/,
      );
    }
  });

  it('rejects hostnames with characters that would break the CSP', () => {
    for (const value of ['https://a;b.test', 'https://ex%22ample.test', 'https://a,b.test']) {
      assert.throws(
        () => buildRuntimeConfig({ ...CLINIC_A, DENTASOFT_URLS_AUTH: value }),
        /DENTASOFT_URLS_AUTH: must have a hostname made of letters, digits, dots and hyphens/,
      );
    }
  });

  it('accepts ports, paths and localhost in development', () => {
    assert.deepEqual(
      buildRuntimeConfig(
        {
          DENTASOFT_URLS_API: 'https://api.dentasoft.test:8443/base/',
          DENTASOFT_URLS_AUTH: 'http://localhost:8180',
        },
        { dev: true },
      ),
      { apiUrl: 'https://api.dentasoft.test:8443/base', authUrl: 'http://localhost:8180' },
    );
  });

  it('accepts an IPv6 address', () => {
    const config = buildRuntimeConfig(
      { ...CLINIC_A, DENTASOFT_URLS_API: 'http://[::1]:8080' },
      { dev: true },
    );
    assert.equal(config.apiUrl, 'http://[::1]:8080');
  });
});

describe('writeRuntimeConfig', () => {
  it('writes the configuration as JSON', () => {
    const dir = mkdtempSync(join(tmpdir(), 'dentasoft-config-'));
    try {
      const file = join(dir, 'public', 'config.json');
      writeRuntimeConfig(buildRuntimeConfig(CLINIC_B), file);
      assert.deepEqual(JSON.parse(readFileSync(file, 'utf8')), {
        apiUrl: 'https://api.other-domain.test',
        authUrl: 'https://login.other-domain.test',
      });
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
