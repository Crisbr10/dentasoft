// Generates the API client in src/app/api from the backend's OpenAPI document (pnpm api:gen).
// The generated code must not contain any URL (ADR 0006): the document lists the server URL
// in `servers`, and ng-openapi-gen would write it as the default root URL. So the document is
// fetched, `servers` is removed, and the result is passed to ng-openapi-gen. The app sets the
// root URL at runtime from config.json (see core/config/api-configuration.ts).
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

/** Where the backend started with `mvnw spring-boot:run` (local profile) serves the document. */
export const DEFAULT_API_DOCS_URL = 'http://localhost:8080/v3/api-docs';

const FRONTEND = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/**
 * Returns a copy of the OpenAPI document without `servers`, so no URL ends up in the client.
 *
 * @param {unknown} document parsed OpenAPI document
 * @returns {Record<string, unknown>}
 */
export function withoutServers(document) {
  if (typeof document !== 'object' || document === null || !('openapi' in document)) {
    throw new Error('The response is not an OpenAPI document');
  }
  const { servers: _servers, ...rest } = /** @type {Record<string, unknown>} */ (document);
  return rest;
}

async function main() {
  const url = process.env.DENTASOFT_API_DOCS_URL?.trim() || DEFAULT_API_DOCS_URL;
  let response;
  try {
    response = await fetch(url, { signal: AbortSignal.timeout(20_000) });
  } catch (error) {
    throw new Error(
      `Cannot read ${url}: start the backend (cd backend; .\\mvnw.cmd spring-boot:run) or set DENTASOFT_API_DOCS_URL`,
      { cause: error },
    );
  }
  if (!response.ok) {
    throw new Error(`Cannot read ${url}: HTTP ${response.status}`);
  }
  const document = withoutServers(await response.json());

  const dir = mkdtempSync(join(tmpdir(), 'dentasoft-api-gen-'));
  try {
    const input = join(dir, 'api-docs.json');
    writeFileSync(input, JSON.stringify(document));
    // Run its CLI with this same Node, without a shell, so no path needs quoting.
    const cli = join(FRONTEND, 'node_modules', 'ng-openapi-gen', 'lib', 'index.js');
    const result = spawnSync(
      process.execPath,
      [cli, '--config', 'ng-openapi-gen.json', '--input', input],
      { cwd: FRONTEND, stdio: 'inherit' },
    );
    if (result.status !== 0) {
      throw new Error(`ng-openapi-gen failed with exit code ${result.status}`);
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
}
