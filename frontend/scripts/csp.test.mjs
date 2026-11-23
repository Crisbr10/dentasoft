import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { buildMetaCsp, injectMetaCsp } from './csp.mjs';

const CONFIG = {
  apiUrl: 'https://api.dentasoft.test/base',
  authUrl: 'https://auth.dentasoft.test',
};

const INDEX = `<!doctype html>
<html lang="es">
<head>
  <meta charset="utf-8">
  <link rel="stylesheet" href="styles.css">
</head>
<body><script src="main.js" type="module"></script></body>
</html>`;

describe('buildMetaCsp', () => {
  it('limits connect-src to the origins of this build', () => {
    const policy = buildMetaCsp(CONFIG);
    assert.match(
      policy,
      /connect-src 'self' https:\/\/api\.dentasoft\.test https:\/\/auth\.dentasoft\.test;/,
    );
    assert.match(policy, /script-src 'self';/);
    assert.match(policy, /object-src 'none';/);
  });

  it('lists a shared origin once', () => {
    const policy = buildMetaCsp({
      apiUrl: 'https://dentasoft.test/api',
      authUrl: 'https://dentasoft.test/auth',
    });
    assert.match(policy, /connect-src 'self' https:\/\/dentasoft\.test;/);
  });

  it('changes with the variables of the build', () => {
    const policy = buildMetaCsp({
      apiUrl: 'https://api.other.test',
      authUrl: 'https://auth.other.test',
    });
    assert.match(
      policy,
      /connect-src 'self' https:\/\/api\.other\.test https:\/\/auth\.other\.test;/,
    );
    assert.doesNotMatch(policy, /dentasoft\.test/);
  });
});

describe('injectMetaCsp', () => {
  it('puts the policy first in <head>, before any <script> or <link>', () => {
    const html = injectMetaCsp(INDEX, buildMetaCsp(CONFIG));
    const head = html.slice(html.indexOf('<head>') + '<head>'.length);
    assert.match(head.trimStart(), /^<meta http-equiv="Content-Security-Policy" content="[^"]+">/);
    assert.ok(html.indexOf('Content-Security-Policy') < html.indexOf('<link'));
    assert.ok(html.indexOf('Content-Security-Policy') < html.indexOf('<script'));
  });

  it('refuses to add a second policy', () => {
    const html = injectMetaCsp(INDEX, buildMetaCsp(CONFIG));
    assert.throws(() => injectMetaCsp(html, buildMetaCsp(CONFIG)), /already has/);
  });

  it('fails when there is no <head>', () => {
    assert.throws(() => injectMetaCsp('<html></html>', 'default-src'), /no <head>/);
  });
});
