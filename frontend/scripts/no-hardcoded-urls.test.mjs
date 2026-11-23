// No domain or URL written in src/ (ADR 0006). The URLs come from config.json, which
// scripts/runtime-config.mjs generates from environment variables. Mirrors
// NoHardcodedUrlsTests in the backend.
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'src');
// Absolute URLs (http, https, ws, wss), protocol-relative URLs in a string or a call
// ('//host', "//localhost", (//[::1]) and the current domain. Comments such as
// "//this.http.get()" are not URLs.
const FORBIDDEN =
  /\b(?:https?|wss?):\/\/|(?<=['"`(=]|, )\/\/(?:[a-z0-9-]+\.[a-z0-9]|localhost|\[)|crisbr\.es/i;
// XML namespaces are identifiers, not addresses the app connects to.
const ALLOWED = /https?:\/\/www\.w3\.org\/[\w./-]*/gi;
// Tests may only use the reserved .test top-level domain (AGENTS.md). A URL with "@"
// anywhere (https://a.test:pw@evil.example) is never allowed: its real host follows it.
const ALLOWED_IN_TESTS = /https?:\/\/[\w.-]+\.test(?![\w.-])(?![^\s'"`]*@)[^\s'"`]*/gi;

function isTestFile(path) {
  return path.endsWith('.spec.ts') || path.startsWith('testing/');
}

// The generated API client (pnpm api:gen) has links to documentation in its comments,
// which are not addresses the app connects to. Its code lines are still checked.
const COMMENT_LINE = /^\s*(?:\/\/|\/\*|\*)/;

function isGeneratedComment(path, line) {
  return path.startsWith('app/api/') && COMMENT_LINE.test(line);
}

function findHardcodedUrls(root) {
  const findings = [];
  for (const entry of readdirSync(root, { recursive: true, withFileTypes: true })) {
    if (!entry.isFile()) continue;
    const file = join(entry.parentPath, entry.name);
    const path = relative(root, file).replaceAll('\\', '/');
    readFileSync(file, 'utf8')
      .split(/\r?\n/)
      .forEach((line, index) => {
        if (isGeneratedComment(path, line)) return;
        let checked = line.replace(ALLOWED, '');
        if (isTestFile(path)) checked = checked.replace(ALLOWED_IN_TESTS, '');
        if (FORBIDDEN.test(checked)) {
          findings.push(`${path}:${index + 1}: ${line.trim()}`);
        }
      });
  }
  return findings;
}

describe('no hardcoded URLs', () => {
  it('src/ contains no fixed domain or URL', () => {
    assert.deepEqual(findHardcodedUrls(SRC), []);
  });

  it('detects fixed URLs and domains, but not XML namespaces or .test domains in tests', () => {
    const dir = mkdtempSync(join(tmpdir(), 'dentasoft-urls-'));
    try {
      const write = (path, content) => {
        mkdirSync(dirname(join(dir, path)), { recursive: true });
        writeFileSync(join(dir, path), content);
      };
      write('app/api.ts', "const api = 'https://api.dentasoft.test';\n");
      write('app/dev.ts', "const api = 'http://localhost:8080';\n");
      write('app/host.ts', "const host = 'dentasoft.crisbr.es';\n");
      write('assets/icon.svg', '<svg xmlns="http://www.w3.org/2000/svg"></svg>\n');
      write(
        'app/api.spec.ts',
        "const ok = 'https://api.dentasoft.test/x';\nconst ko = 'https://api.test.example.org';\n",
      );
      write('testing/helper.ts', "const ok = 'https://auth.dentasoft.test';\n");
      write('app/ws.ts', "const ws = 'wss://realtime.example.org';\n");
      write('app/cdn.ts', "const cdn = '//cdn.example.org/x.js';\n");
      write('app/comment.ts', '// A comment, not a URL.\n');
      write(
        'app/userinfo.spec.ts',
        [
          "const ko = 'https://a.test@evil.example';",
          "const ko = 'https://a.test:pw@evil.example';",
          "const ko = 'https://a.test:8080@evil.example';",
        ].join('\n'),
      );
      write('app/local.ts', "const a = '//localhost:8080/x';\nconst b = '//127.0.0.1:8080';\n");
      write('app/commented.ts', '//this.http.get(x);\nconst a = b; //c.d\n');
      write('app/page.html', '<link href=//cdn.example.org/x.css>\n');
      write(
        'app/api/request-builder.ts',
        [
          ' * See https://github.com/angular/angular/issues/18261',
          "  rootUrl: string = 'http://localhost:8080';",
        ].join('\n'),
      );
      write('app/other/comment.ts', ' * See https://docs.example.org\n');

      assert.deepEqual(findHardcodedUrls(dir).sort(), [
        "app/api.spec.ts:2: const ko = 'https://api.test.example.org';",
        "app/api.ts:1: const api = 'https://api.dentasoft.test';",
        "app/api/request-builder.ts:2: rootUrl: string = 'http://localhost:8080';",
        "app/cdn.ts:1: const cdn = '//cdn.example.org/x.js';",
        "app/dev.ts:1: const api = 'http://localhost:8080';",
        "app/host.ts:1: const host = 'dentasoft.crisbr.es';",
        "app/local.ts:1: const a = '//localhost:8080/x';",
        "app/local.ts:2: const b = '//127.0.0.1:8080';",
        'app/other/comment.ts:1: * See https://docs.example.org',
        'app/page.html:1: <link href=//cdn.example.org/x.css>',
        "app/userinfo.spec.ts:1: const ko = 'https://a.test@evil.example';",
        "app/userinfo.spec.ts:2: const ko = 'https://a.test:pw@evil.example';",
        "app/userinfo.spec.ts:3: const ko = 'https://a.test:8080@evil.example';",
        "app/ws.ts:1: const ws = 'wss://realtime.example.org';",
      ]);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
