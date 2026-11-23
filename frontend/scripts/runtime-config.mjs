// Generates public/config.json from environment variables (ADR 0006).
// The app reads the API and Keycloak URLs from that file at startup, so no domain is
// written in src/. Development defaults to localhost; any other build fails when a
// variable is missing.
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const VARIABLES = {
  apiUrl: 'DENTASOFT_URLS_API',
  authUrl: 'DENTASOFT_URLS_AUTH',
};

export const DEV_DEFAULTS = {
  apiUrl: 'http://localhost:8080',
  authUrl: 'http://localhost:8180',
};

const DEFAULT_OUTPUT = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  'public',
  'config.json',
);

/**
 * Builds the runtime configuration from the given environment.
 *
 * @param {Record<string, string | undefined>} env environment variables
 * @param {{ dev?: boolean }} options `dev` allows localhost defaults and plain http
 * @returns {{ apiUrl: string, authUrl: string }}
 */
export function buildRuntimeConfig(env, { dev = false } = {}) {
  const config = {};
  const errors = [];
  for (const [key, variable] of Object.entries(VARIABLES)) {
    const raw = env[variable]?.trim() || (dev ? DEV_DEFAULTS[key] : '');
    if (!raw) {
      errors.push(`${variable} is not set`);
      continue;
    }
    try {
      config[key] = normalizeUrl(raw, { dev });
    } catch (error) {
      errors.push(`${variable}: ${error.message}`);
    }
  }
  if (errors.length > 0) {
    throw new Error(`Invalid runtime configuration:\n- ${errors.join('\n- ')}`);
  }
  return config;
}

function normalizeUrl(raw, { dev }) {
  let url;
  try {
    url = new URL(raw);
  } catch {
    throw new Error('must be an absolute URL');
  }
  const allowedProtocols = dev ? ['https:', 'http:'] : ['https:'];
  if (!allowedProtocols.includes(url.protocol)) {
    throw new Error(dev ? 'must use http or https' : 'must use https outside development');
  }
  if (url.username || url.password || url.search || url.hash) {
    throw new Error('must not contain credentials, a query string or a fragment');
  }
  // WHATWG URL accepts characters such as ; " , in a hostname; they would break the CSP.
  const isName = /^[a-z0-9-]+(\.[a-z0-9-]+)*$/.test(url.hostname);
  const isIpv6 = /^\[[0-9a-f:.]+\]$/.test(url.hostname);
  if (!isName && !isIpv6) {
    throw new Error(
      'must have a hostname made of letters, digits, dots and hyphens, or an IPv6 address',
    );
  }
  return url.href.replace(/\/+$/, '');
}

export function writeRuntimeConfig(config, outputFile = DEFAULT_OUTPUT) {
  mkdirSync(dirname(outputFile), { recursive: true });
  writeFileSync(outputFile, `${JSON.stringify(config, null, 2)}\n`);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  try {
    const dev = process.argv.includes('--dev');
    const config = buildRuntimeConfig(process.env, { dev });
    writeRuntimeConfig(config);
    console.log(`config.json written (${dev ? 'development' : 'build'})`);
  } catch (error) {
    console.error(error.message);
    console.error('See frontend/README.md, section "Configuración".');
    process.exit(1);
  }
}
