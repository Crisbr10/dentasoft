// No domain or URL written in src/ (ADR 0006). The URLs come from config.json, which
// scripts/runtime-config.mjs generates from environment variables. Mirrors
// NoHardcodedUrlsTests in the backend.
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

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
// which are not addresses the app connects to. So in its TypeScript files a line is skipped
// only when it holds no code at all: the TypeScript parser finds every token (strings and
// template literals included) and a line that touches one is checked as usual. A line that
// mixes a comment and code, such as `/* x */ const a = '…'`, is therefore still checked.
const GENERATED_CLIENT = /^app\/api\/.+\.ts$/;

/** Start and end offsets of every code token of a TypeScript source, comments excluded. */
function codeSpans(path, source) {
  const file = ts.createSourceFile(path, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const spans = [];
  const visit = (node) => {
    if (node.kind >= ts.SyntaxKind.FirstJSDocNode && node.kind <= ts.SyntaxKind.LastJSDocNode) {
      return;
    }
    const children = node.getChildren(file);
    if (children.length > 0) {
      children.forEach(visit);
      return;
    }
    const start = node.getStart(file);
    if (node.getEnd() > start) spans.push([start, node.getEnd()]);
  };
  visit(file);
  return spans;
}

/** Lines of `source` with their offsets, so they can be matched against token offsets. */
function linesWithOffsets(source) {
  const lines = [];
  const separator = /\r?\n/g;
  let start = 0;
  for (const match of source.matchAll(separator)) {
    const number = lines.length + 1;
    lines.push({ text: source.slice(start, match.index), number, start, end: match.index });
    start = match.index + match[0].length;
  }
  lines.push({ text: source.slice(start), number: lines.length + 1, start, end: source.length });
  return lines;
}

function isCommentOnly(line, spans) {
  return (
    line.text.trim() !== '' && !spans.some(([start, end]) => start < line.end && end > line.start)
  );
}

function findHardcodedUrls(root) {
  const findings = [];
  for (const entry of readdirSync(root, { recursive: true, withFileTypes: true })) {
    if (!entry.isFile()) continue;
    const file = join(entry.parentPath, entry.name);
    const path = relative(root, file).replaceAll('\\', '/');
    const source = readFileSync(file, 'utf8');
    const spans = GENERATED_CLIENT.test(path) ? codeSpans(path, source) : null;
    linesWithOffsets(source)
      .filter((line) => !(spans && isCommentOnly(line, spans)))
      .forEach(({ text: line, number }) => {
        let checked = line.replace(ALLOWED, '');
        if (isTestFile(path)) checked = checked.replace(ALLOWED_IN_TESTS, '');
        if (FORBIDDEN.test(checked)) {
          findings.push(`${path}:${number}: ${line.trim()}`);
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
      write('app/other/comment.ts', '/**\n * See https://docs.example.org\n */\n');

      assert.deepEqual(findHardcodedUrls(dir).sort(), [
        "app/api.spec.ts:2: const ko = 'https://api.test.example.org';",
        "app/api.ts:1: const api = 'https://api.dentasoft.test';",
        "app/cdn.ts:1: const cdn = '//cdn.example.org/x.js';",
        "app/dev.ts:1: const api = 'http://localhost:8080';",
        "app/host.ts:1: const host = 'dentasoft.crisbr.es';",
        "app/local.ts:1: const a = '//localhost:8080/x';",
        "app/local.ts:2: const b = '//127.0.0.1:8080';",
        'app/other/comment.ts:2: * See https://docs.example.org',
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

  it('skips only the comment-only lines of the generated client, never a line with code', () => {
    const dir = mkdtempSync(join(tmpdir(), 'dentasoft-urls-'));
    try {
      const write = (path, content) => {
        mkdirSync(dirname(join(dir, path)), { recursive: true });
        writeFileSync(join(dir, path), content);
      };
      write(
        'app/api/client.ts',
        [
          '/**',
          ' * See https://github.com/angular/angular/issues/18261',
          ' */',
          '// See https://docs.example.org',
          "/* x */ const a = 'http://evil.example';",
          'const b = 1; /* https://evil.example */',
          'const t = `',
          'https://evil.example`;',
          "*/ const c = 'https://evil.example';",
          'const d = 2',
          "  * Number('https://evil.example');",
        ].join('\r\n'),
      );
      write(
        'app/api/fn/nested.ts',
        "// See https://docs.example.org\nconst e = 'https://evil.example';\n",
      );
      write('app/api/notes.md', 'See https://docs.example.org\n');

      assert.deepEqual(
        findHardcodedUrls(dir).sort(),
        [
          "app/api/client.ts:5: /* x */ const a = 'http://evil.example';",
          'app/api/client.ts:6: const b = 1; /* https://evil.example */',
          'app/api/client.ts:8: https://evil.example`;',
          "app/api/client.ts:9: */ const c = 'https://evil.example';",
          "app/api/client.ts:11: * Number('https://evil.example');",
          "app/api/fn/nested.ts:2: const e = 'https://evil.example';",
          'app/api/notes.md:1: See https://docs.example.org',
        ].sort(),
      );
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
