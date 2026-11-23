import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { withoutServers } from './api-gen.mjs';

describe('withoutServers', () => {
  it('removes the server URLs and keeps the rest of the document', () => {
    const document = {
      openapi: '3.1.0',
      info: { title: 'Dentasoft API', version: 'v1' },
      servers: [{ url: 'https://api.dentasoft.test' }],
      paths: { '/api/v1/x': {} },
    };

    assert.deepEqual(withoutServers(document), {
      openapi: '3.1.0',
      info: { title: 'Dentasoft API', version: 'v1' },
      paths: { '/api/v1/x': {} },
    });
  });

  it('rejects a response that is not an OpenAPI document', () => {
    for (const value of [null, 'nope', { error: 'Not Found' }]) {
      assert.throws(() => withoutServers(value), /not an OpenAPI document/);
    }
  });
});
