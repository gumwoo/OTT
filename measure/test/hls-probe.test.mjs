import { spawn } from 'node:child_process';
import http from 'node:http';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';

const PROBE = new URL('../hls-probe.mjs', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const MASTER = '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=928000,RESOLUTION=640x360\n360p.m3u8\n';
const MEDIA = '#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg0.ts\n#EXTINF:6.0,\nseg1.ts\n#EXT-X-ENDLIST\n';

// 시나리오는 경로 첫 부분으로 고른다: /ok/, /seg404/, /cut/, /reset/
const server = http.createServer((req, res) => {
  const [, scenario] = req.url.split('/');
  if (req.url.endsWith('master.m3u8')) return res.end(MASTER);
  if (req.url.endsWith('360p.m3u8')) return res.end(MEDIA);
  if (req.url.endsWith('seg1.ts')) {
    if (scenario === 'seg404') {
      res.statusCode = 404;
      return res.end();
    }
    if (scenario === 'cut') {
      res.writeHead(200, { 'content-length': '100000' });
      res.write(Buffer.alloc(1000));
      return setTimeout(() => res.socket.destroy(), 20);
    }
    if (scenario === 'reset') return req.socket.destroy();
  }
  res.setHeader('x-cache', 'Miss from cloudfront');
  res.end(Buffer.alloc(2048));
});

let base;
let outDir;

before(async () => {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
  outDir = await mkdtemp(join(tmpdir(), 'hls-probe-'));
});

after(() => server.close());

function probe(url, name) {
  const out = join(outDir, `${name}.jsonl`);
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [PROBE, '--url', url, '--out', out], { stdio: 'ignore' });
    child.on('exit', async (code) => {
      const lines = (await readFile(out, 'utf8')).trim().split('\n').map((l) => JSON.parse(l));
      resolve({ code, records: lines.filter((l) => l.type !== 'summary'), summary: lines.at(-1) });
    });
  });
}

test('success', async () => {
  const { code, summary } = await probe(`${base}/ok/master.m3u8`, 'ok');
  assert.equal(code, 0);
  assert.equal(summary.ok, true);
  assert.equal(summary.requests, 4);
  assert.equal(summary.failedRequests, 0);
  assert.equal(summary.misses, 2);
  assert.equal(typeof summary.startupProxyMs, 'number');
});

for (const [scenario, reason] of [
  ['seg404', 'UNEXPECTED_STATUS'],
  ['cut', 'BODY_READ_ERROR'],
  ['reset', 'NETWORK_ERROR'],
]) {
  test(`segment failure: ${scenario}`, async () => {
    const { code, records, summary } = await probe(`${base}/${scenario}/master.m3u8`, scenario);
    assert.equal(code, 1);
    assert.equal(summary.ok, false);
    assert.equal(summary.failure.reason, reason);
    assert.equal(summary.failure.path, `/${scenario}/seg1.ts`);
    assert.equal(summary.failedRequests, 1);
    assert.equal(records.at(-1).ok, false);
    assert.equal(summary.startupProxyMs, null);
  });
}

test('connection refused on master', async () => {
  const closed = http.createServer();
  await new Promise((resolve) => closed.listen(0, '127.0.0.1', resolve));
  const { port } = closed.address();
  await new Promise((resolve) => closed.close(resolve));

  const { code, records, summary } = await probe(`http://127.0.0.1:${port}/x/master.m3u8`, 'refused');
  assert.equal(code, 1);
  assert.equal(summary.failure.reason, 'NETWORK_ERROR');
  assert.equal(records.length, 1);
  assert.equal(records[0].status, null);
});
