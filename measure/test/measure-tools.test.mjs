import assert from 'node:assert/strict';
import { generateKeyPairSync, verify } from 'node:crypto';
import http from 'node:http';
import { after, before, test } from 'node:test';
import { Session, percentile, probe as apiProbe, summarize } from '../api-probe.mjs';
import { report } from '../e5-report.mjs';
import { cloudFrontBase64, cookieHeader, signedCookies, versionResource } from '../lib/cloudfront-cookie.mjs';
import { classify, nextSlot, probe as policyProbe } from '../policy-probe.mjs';

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const pem = privateKey.export({ type: 'pkcs8', format: 'pem' });
const fromCloudFront = (value) => Buffer.from(value.replace(/-/g, '+').replace(/_/g, '=').replace(/~/g, '/'), 'base64');

test('signed cookies carry a SHA-256 custom policy that verifies with the public key', () => {
  const cookies = signedCookies({
    resource: 'https://d1.cloudfront.net/content/test-geo/v1/*',
    expiresAt: 1_800_000_000,
    keyPairId: 'K2TEST',
    privateKeyPem: pem
  });

  const policy = fromCloudFront(cookies['CloudFront-Policy']);
  assert.equal(
    policy.toString(),
    '{"Statement":[{"Resource":"https://d1.cloudfront.net/content/test-geo/v1/*","Condition":{"DateLessThan":{"AWS:EpochTime":1800000000}}}]}'
  );
  assert.ok(verify('sha256', policy, publicKey, fromCloudFront(cookies['CloudFront-Signature'])));
  assert.equal(cookies['CloudFront-Hash-Algorithm'], 'SHA256');
  assert.equal(cookies['CloudFront-Key-Pair-Id'], 'K2TEST');
  assert.match(cookieHeader(cookies), /^CloudFront-Policy=[A-Za-z0-9_~-]+; CloudFront-Signature=[A-Za-z0-9_~-]+; CloudFront-Key-Pair-Id=K2TEST; CloudFront-Hash-Algorithm=SHA256$/);
});

test('cookie values use the CloudFront base64 alphabet', () => {
  assert.equal(cloudFrontBase64(Buffer.from([0xfb, 0xff, 0xfe])), '-~~-');
  assert.equal(cloudFrontBase64(Buffer.from('a')), 'YQ__');
});

test('the resource covers the version directory of the master playlist', () => {
  assert.equal(
    versionResource('https://d1.cloudfront.net/content/test-e2/runs/r1/v1/master.m3u8'),
    'https://d1.cloudfront.net/content/test-e2/runs/r1/v1/*'
  );
});

test('only policy reason codes count as blocks', () => {
  assert.equal(classify(200, null), 'allow');
  assert.equal(classify(403, 'GEO_BLOCKED'), 'block');
  assert.equal(classify(403, 'POLICY_UNAVAILABLE'), 'block');
  // 서명·만료 실패
  assert.equal(classify(403, null), 'error');
  assert.equal(classify(503, null), 'error');
});

const T1 = Date.parse('2026-11-27T10:00:00Z');
const at = (offsetSeconds) => new Date(T1 + offsetSeconds * 1000).toISOString();
const rec = (location, offset, outcome, denied = null) => ({ location, probeId: `${location}-1`, intervalMs: 2000, at: at(offset), outcome, denied, rev: denied ? '15' : null });
const OPTIONS = { t0: T1 - 30_000, t1: T1, windowSeconds: 4, budgetSeconds: 4, codes: new Set(['GEO_BLOCKED', 'NOT_AVAILABLE']) };

test('E5 report finds first block, last allow and convergence per location', () => {
  const records = [
    rec('seoul', -5, 'allow'),
    rec('seoul', 1, 'allow'),
    rec('seoul', 3, 'block', 'GEO_BLOCKED'),
    rec('seoul', 5, 'allow'),
    rec('seoul', 7, 'block', 'GEO_BLOCKED'),
    rec('seoul', 9, 'block', 'GEO_BLOCKED'),
    rec('seoul', 11, 'block', 'GEO_BLOCKED'),
    rec('seoul', 13, 'block', 'GEO_BLOCKED'),
    rec('jakarta', 2, 'error'),
    rec('jakarta', 4, 'block', 'GEO_BLOCKED'),
    rec('jakarta', 6, 'block', 'GEO_BLOCKED')
  ];

  const result = report(records, OPTIONS);

  assert.equal(result.seoul.pipelineDelayS, 30);
  assert.equal(result.seoul.firstBlockDelayS, 3);
  assert.equal(result.seoul.endToEndRevocationDelayS, 33);
  assert.equal(result.seoul.lastAllow, at(5));
  // 3초 차단 뒤 5초 허용이 끼어 있어 7초부터 4초(W) 연속 차단 → 11초
  assert.equal(result.seoul.observedConvergenceS, 11);
  // 예산 4초가 지난 뒤의 허용 1건 (5초)
  assert.equal(result.seoul.sloViolations, 1);
  assert.equal(result.seoul.firstBlockRev, '15');
  assert.equal(result.jakarta.errorsAfterT1, 1);
  assert.equal(result.jakarta.firstBlockDelayS, 4);
  // 마지막 관측이 6초라 W(4초)를 다 보지 못했다
  assert.equal(result.jakarta.observedConvergenceS, null);
  assert.equal(result.jakarta.lastAllow, null);
  assert.equal(result.seoul.medianIntervalMs, 2000);
});

test('convergence needs a block observed at the end of the window', () => {
  const blocks = (offsets) => offsets.map((o) => rec('x', o, 'block', 'GEO_BLOCKED'));
  const converged = (records) => report(records, OPTIONS).x.observedConvergenceS;

  // W(4초) 직후의 관측이 허용이면 수렴이 아니다
  assert.equal(converged([...blocks([1, 3]), rec('x', 5, 'allow')]), null);
  // 오류도 마찬가지
  assert.equal(converged([...blocks([1, 3]), rec('x', 5, 'error')]), null);
  // 관측 공백(요청 간격 2초의 두 배 초과)이 있으면 그 사이를 본 것이 아니다
  assert.equal(converged(blocks([1, 9])), null);
  assert.equal(converged(blocks([1, 3, 5])), 5);
  // 키 삭제로 인한 차단은 codes에 없으면 따로 센다
  assert.equal(report([rec('x', 1, 'block', 'POLICY_UNAVAILABLE')], OPTIONS).x.otherBlocksAfterT1, 1);
});

test('late requests do not burst to catch up', () => {
  assert.equal(nextSlot(1000, 500, 1200), 1500);
  assert.equal(nextSlot(1000, 500, 4000), 4000);
});

test('percentiles and the longest failure streak', () => {
  assert.equal(percentile([10, 20, 30, 40], 50), 20);
  assert.equal(percentile([10, 20, 30, 40], 95), 40);
  assert.equal(percentile([], 50), null);

  const summary = summarize([
    { at: 'a', ok: true, status: 200, ms: 10 },
    { at: 'b', ok: false, status: 502, ms: 1 },
    { at: 'c', ok: false, status: null, ms: 1 },
    { at: 'd', ok: true, status: 200, ms: 30 },
    { at: 'e', ok: false, status: 503, ms: 1 }
  ]);
  assert.equal(summary.successRate, 40);
  assert.deepEqual(summary.longestFailure, { from: 'b', to: 'c', count: 2 });
  assert.deepEqual(summary.statuses, { 200: 2, 502: 1, network: 1, 503: 1 });
});

// 가짜 api·엣지: 로그인 흐름과, 세 번째 재생 요청부터 차단으로 바뀌는 정책
let base;
let playbackCalls = 0;
let edgeCalls = 0;
const server = http.createServer((req, res) => {
  const cookie = req.headers.cookie ?? '';
  if (req.url === '/api/csrf') {
    const token = cookie.includes('SESSION=s1') ? 'after' : 'before';
    res.setHeader('set-cookie', `XSRF-TOKEN=${token}; Path=/`);
    return res.end();
  }
  if (req.url === '/api/auth/login') {
    if (req.headers['x-xsrf-token'] !== 'before') return void ((res.statusCode = 403), res.end());
    res.setHeader('set-cookie', 'SESSION=s1; Path=/api; HttpOnly');
    return res.end('{}');
  }
  if (req.url.startsWith('/api/contents/coffee-run/playback')) {
    playbackCalls++;
    if (req.headers['x-xsrf-token'] !== 'after' || !cookie.includes('SESSION=s1')) {
      res.statusCode = 403;
      return res.end('{"code":"CSRF_INVALID"}');
    }
    if (playbackCalls === 2) {
      res.statusCode = 503;
      return res.end('{"code":"UNAVAILABLE"}');
    }
    res.setHeader('set-cookie', ['CloudFront-Policy=p; Path=/content/coffee-run/v1/', 'CloudFront-Signature=s; Path=/content/coffee-run/v1/']);
    return res.end('{"manifestUrl":"/content/coffee-run/v1/master.m3u8"}');
  }
  if (req.url.startsWith('/content/test-geo/')) {
    edgeCalls++;
    if (!cookie.includes('CloudFront-Signature=')) return void ((res.statusCode = 403), res.end());
    if (edgeCalls >= 3) {
      res.statusCode = 403;
      res.setHeader('x-playback-denied', 'GEO_BLOCKED');
      res.setHeader('x-policy-rev', '15');
      return res.end();
    }
    return res.end('#EXTM3U');
  }
  res.statusCode = 404;
  res.end();
});

before(async () => {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

test('api probe logs in with CSRF and counts failed playback starts', async () => {
  const session = new Session(base);
  await session.login('viewer@local.test', 'not-a-real-password');
  const records = [];

  const summary = await apiProbe({
    session,
    contentId: 'coffee-run',
    interval: 10,
    durationSeconds: 30,
    maxRequests: 4,
    base: { label: 't' },
    write: async (r) => records.push(r)
  });

  assert.equal(summary.requests, 4);
  assert.equal(records[1].status, 503);
  assert.equal(records[1].code, 'UNAVAILABLE');
  assert.equal(records[0].ok, true);
  assert.equal(summary.succeeded, summary.requests - 1);
});

test('policy probe records allow then block with the rev it saw', async () => {
  const records = [];
  const cookies = signedCookies({ resource: `${base}/content/test-geo/v1/*`, expiresAt: 1_800_000_000, keyPairId: 'K', privateKeyPem: pem });

  const counts = await policyProbe({
    url: `${base}/content/test-geo/v1/master.m3u8`,
    headers: { cookie: cookieHeader(cookies) },
    interval: 10,
    durationSeconds: 30,
    maxRequests: 4,
    base: { location: 'local' },
    write: async (r) => records.push(r)
  });

  assert.deepEqual(records.slice(0, 3).map((r) => r.outcome), ['allow', 'allow', 'block']);
  assert.equal(records[2].denied, 'GEO_BLOCKED');
  assert.equal(records[2].rev, '15');
  assert.equal(counts.allow, 2);
});
