import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, test } from 'node:test';
import { pathToFileURL } from 'node:url';

const SOURCE = new URL('../content-access.js', import.meta.url);
const NOW = 1_800_000_000;
const DAY = 86400;
const dir = await mkdtemp(join(tmpdir(), 'cf-fn-'));
after(() => rm(dir, { recursive: true, force: true }));

// CloudFront 런타임의 'cloudfront' 모듈 대신 가짜 KVS를 넣어 같은 코드를 Node에서 돌린다.
// 런타임처럼 실행 중에는 Date.now()가 고정된다
async function load(store, { failRead = false, failExists = false } = {}) {
  const code = await readFile(SOURCE, 'utf8');
  assert.ok(Buffer.byteLength(code) <= 10 * 1024, 'CloudFront Functions code must be at most 10 KB');
  const patched = code.replace("import cf from 'cloudfront';", 'const cf = globalThis.__cf;') + '\nexport { handler };\n';
  const file = join(dir, `fn-${Math.random().toString(36).slice(2)}.mjs`);
  await writeFile(file, patched);
  globalThis.__cf = {
    kvs: () => ({
      async get(key, options) {
        if (failRead) throw new Error('read failed');
        if (!(key in store)) throw new Error('key not found');
        assert.equal(options.format, 'json');
        return JSON.parse(store[key]);
      },
      async exists(key) {
        if (failExists) throw new Error('exists failed');
        return key in store;
      },
    }),
  };
  const { handler } = await import(pathToFileURL(file).href);
  return async (event) => {
    const realNow = Date.now;
    Date.now = () => NOW * 1000;
    try {
      return await handler(event);
    } finally {
      Date.now = realNow;
    }
  };
}

const policy = (rights, rev = 14) => JSON.stringify({ rev, rights });
const request = (uri, country) => ({
  request: { uri, headers: country ? { 'cloudfront-viewer-country': { value: country } } : {} },
});
const denied = (response) => response.headers?.['x-playback-denied']?.value;
const rev = (response) => response.headers?.['x-policy-rev']?.value;

test('allowed country inside the window passes the request through', async () => {
  const handler = await load({ 'policy:movie-100': policy({ KR: [NOW - DAY, NOW + DAY] }) });
  const event = request('/content/movie-100/v2/master.m3u8', 'KR');

  assert.equal(await handler(event), event.request);
});

test('country without rights is GEO_BLOCKED with the rev it read', async () => {
  const handler = await load({ 'policy:movie-100': policy({ KR: [NOW - DAY, NOW + DAY] }) });

  const response = await handler(request('/content/movie-100/v2/360p_00001.ts', 'US'));

  assert.equal(response.statusCode, 403);
  assert.equal(response.statusDescription, 'Forbidden');
  assert.equal(denied(response), 'GEO_BLOCKED');
  assert.equal(rev(response), '14');
  assert.equal(response.headers['cache-control'].value, 'no-store');
});

test('missing country header is GEO_BLOCKED', async () => {
  const handler = await load({ 'policy:movie-100': policy({ KR: [NOW - DAY, NOW + DAY] }) });

  assert.equal(denied(await handler(request('/content/movie-100/v2/master.m3u8'))), 'GEO_BLOCKED');
});

test('notBefore is inclusive and notAfter is exclusive', async () => {
  const handler = await load({
    'policy:starts-now': policy({ KR: [NOW, NOW + DAY] }),
    'policy:ends-now': policy({ KR: [NOW - DAY, NOW] }),
    'policy:ends-next-second': policy({ KR: [NOW - DAY, NOW + 1] }),
    'policy:later': policy({ KR: [NOW + 1, NOW + DAY] }),
  });
  const starts = request('/content/starts-now/v1/master.m3u8', 'KR');
  const last = request('/content/ends-next-second/v1/master.m3u8', 'KR');

  assert.equal(await handler(starts), starts.request);
  assert.equal(await handler(last), last.request);
  assert.equal(denied(await handler(request('/content/ends-now/v1/master.m3u8', 'KR'))), 'NOT_AVAILABLE');
  assert.equal(denied(await handler(request('/content/later/v1/master.m3u8', 'KR'))), 'NOT_AVAILABLE');
});

test('missing policy key is POLICY_UNAVAILABLE', async () => {
  const handler = await load({});

  const response = await handler(request('/content/movie-100/v2/master.m3u8', 'KR'));

  assert.equal(response.statusCode, 403);
  assert.equal(denied(response), 'POLICY_UNAVAILABLE');
  assert.equal(rev(response), undefined);
});

test('read failures and broken values fail closed with 503', async () => {
  const broken = await load({
    'policy:not-json': '{',
    'policy:null': 'null',
    'policy:no-rights': JSON.stringify({ rev: 3 }),
    'policy:array-rights': JSON.stringify({ rev: 3, rights: [[NOW - DAY, NOW + DAY]] }),
    'policy:text-window': policy({ KR: ['2026-01-01', NOW + DAY] }),
    'policy:short-window': policy({ KR: [NOW - DAY] }),
  });
  for (const id of ['not-json', 'null', 'no-rights', 'array-rights', 'text-window', 'short-window']) {
    const response = await broken(request(`/content/${id}/v1/master.m3u8`, 'KR'));
    assert.equal(response.statusCode, 503, id);
    assert.equal(response.statusDescription, 'Service Unavailable', id);
    assert.equal(denied(response), undefined, id);
  }

  const unreachable = await load({ 'policy:movie-100': policy({}) }, { failRead: true, failExists: true });
  const response = await unreachable(request('/content/movie-100/v2/master.m3u8', 'KR'));
  assert.equal(response.statusCode, 503);
  assert.equal(rev(response), undefined);
});

test('paths outside /content/{id}/v{n}/{file} are refused before reading the policy', async () => {
  const handler = await load({ 'policy:movie-100': policy({ KR: [NOW - DAY, NOW + DAY] }) });

  for (const uri of [
    '/content/movie-100/master.m3u8',
    '/content/movie-100/v0/master.m3u8',
    '/content/movie-100/v01/master.m3u8',
    '/content/Movie-100/v1/master.m3u8',
    '/content/movie-100/v1/../../movie-200/v1/master.m3u8',
    '/content/movie-100/v1/sub/master.m3u8',
    '/content/-movie/v1/master.m3u8',
    '/content/movie-100/v1/..',
    '/content/movie%2D100/v1/master.m3u8',
  ]) {
    const response = await handler(request(uri, 'KR'));
    assert.equal(response.statusCode, 403, uri);
    assert.equal(denied(response), 'POLICY_UNAVAILABLE', uri);
    assert.equal(rev(response), undefined, uri);
  }
});
