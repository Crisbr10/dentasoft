// Adds a Content-Security-Policy <meta> to the built index.html, limiting connect-src to
// the API and Keycloak URLs of this build. vercel.json cannot read environment variables,
// so its header policy names no domain. The browser enforces both policies: a request
// must satisfy each one. frame-ancestors only works in the header (see vercel.json).
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const BROWSER_DIR = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  'dist',
  'dentasoft',
  'browser',
);

/**
 * @param {{ apiUrl: string, authUrl: string }} config runtime configuration
 * @returns {string} the policy for the <meta> element
 */
export function buildMetaCsp({ apiUrl, authUrl }) {
  const origins = [...new Set([new URL(apiUrl).origin, new URL(authUrl).origin])];
  return [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self'",
    `connect-src 'self' ${origins.join(' ')}`,
    // The token is renewed with the refresh token, never in a Keycloak iframe (M02-T07).
    "frame-src 'none'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ].join('; ');
}

/**
 * Inserts the CSP <meta> as the first element of <head>, before any <script> or <link>.
 *
 * @param {string} html the built index.html
 * @param {string} policy the policy from buildMetaCsp
 * @returns {string}
 */
export function injectMetaCsp(html, policy) {
  if (/http-equiv=["']?Content-Security-Policy/i.test(html)) {
    throw new Error('index.html already has a Content-Security-Policy <meta>');
  }
  const head = /<head(\s[^>]*)?>/i.exec(html);
  if (!head) {
    throw new Error('index.html has no <head>');
  }
  const end = head.index + head[0].length;
  const content = policy.replaceAll('&', '&amp;').replaceAll('"', '&quot;');
  const meta = `<meta http-equiv="Content-Security-Policy" content="${content}">`;
  return `${html.slice(0, end)}\n  ${meta}${html.slice(end)}`;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  try {
    const config = JSON.parse(readFileSync(resolve(BROWSER_DIR, 'config.json'), 'utf8'));
    const indexFile = resolve(BROWSER_DIR, 'index.html');
    writeFileSync(indexFile, injectMetaCsp(readFileSync(indexFile, 'utf8'), buildMetaCsp(config)));
    console.log('Content-Security-Policy <meta> added to index.html');
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
