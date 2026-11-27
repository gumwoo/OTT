// E5·E6: 한 위치에서 같은 URL을 일정 간격으로 요청해 허용·차단·오류를 JSONL로 남긴다. 계산은 e5-report.mjs
// usage: node measure/policy-probe.mjs --url <https://<host>/content/test-*/v1/master.m3u8> --location jakarta
//        --key <테스트 개인키 PEM> --key-pair-id <ID> [--interval 1000] [--duration 600] [--label e5] [--out results/e5.jsonl]
// 엣지에 실제로 닿는 국가가 그 위치여야 한다. 실행 위치(리전 VM 등)는 결과에 location으로 남긴다

import { appendFile, mkdir, readFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { cookieHeader, signedCookies, versionResource } from './lib/cloudfront-cookie.mjs';

const POLICY_CODES = new Set(['GEO_BLOCKED', 'NOT_AVAILABLE', 'POLICY_UNAVAILABLE']);

// 사유 코드 없는 403은 서명·만료 실패라 차단으로 세지 않는다 (design.md §11 T2)
export function classify(status, deniedCode) {
  if (status === 200) return 'allow';
  if (status === 403 && POLICY_CODES.has(deniedCode)) return 'block';
  return 'error';
}

function parseArgs(argv) {
  const args = { interval: 1000, duration: 600, timeout: 10000, label: 'unlabeled', location: 'unknown', out: null };
  for (let i = 2; i < argv.length; i += 2) args[argv[i].replace(/^--/, '')] = argv[i + 1];
  for (const required of ['url', 'key', 'key-pair-id']) {
    if (!args[required]) throw new Error(`--${required} is required`);
  }
  args.interval = Number(args.interval);
  args.duration = Number(args.duration);
  args.timeout = Number(args.timeout);
  return args;
}

async function once(url, headers, base, timeoutMs) {
  const started = performance.now();
  try {
    const res = await fetch(url, { headers, redirect: 'manual', signal: AbortSignal.timeout(timeoutMs) });
    await res.arrayBuffer();
    const denied = res.headers.get('x-playback-denied');
    return {
      ...base,
      at: new Date().toISOString(),
      status: res.status,
      outcome: classify(res.status, denied),
      denied,
      rev: res.headers.get('x-policy-rev'),
      pop: res.headers.get('x-amz-cf-pop'),
      xCache: res.headers.get('x-cache'),
      cfId: res.headers.get('x-amz-cf-id'),
      ms: Math.round((performance.now() - started) * 10) / 10
    };
  } catch (err) {
    return {
      ...base,
      at: new Date().toISOString(),
      status: null,
      outcome: 'error',
      error: err?.name === 'TimeoutError' ? 'TIMEOUT' : String(err?.cause?.code ?? err?.message ?? err),
      ms: Math.round((performance.now() - started) * 10) / 10
    };
  }
}

export async function probe({ url, headers, interval, durationSeconds, timeoutMs = 10000, maxRequests = Infinity, base, write }) {
  const started = Date.now();
  const counts = { allow: 0, block: 0, error: 0 };
  let next = started;
  for (let i = 0; i < maxRequests && Date.now() - started < durationSeconds * 1000; i++) {
    const record = await once(url, headers, { ...base, seq: i }, timeoutMs);
    counts[record.outcome]++;
    await write(record);
    next = nextSlot(next, interval);
    const wait = next - Date.now();
    if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
  }
  return counts;
}

// 시작 시각 기준으로 간격을 잡되, 늦어진 만큼 몰아서 보내지 않고 지금부터 다시 센다
export function nextSlot(previous, interval, now = Date.now()) {
  return Math.max(previous + interval, now);
}

async function main() {
  const args = parseArgs(process.argv);
  const cookies = signedCookies({
    resource: versionResource(args.url),
    // 측정이 끝날 때까지 만료되지 않게. 만료 403은 오류로 세지만 측정을 망친다
    expiresAt: Math.floor(Date.now() / 1000) + args.duration + 600,
    keyPairId: args['key-pair-id'],
    privateKeyPem: await readFile(args.key, 'utf8')
  });
  const headers = { 'user-agent': 'ott-policy-probe/0.1', cookie: cookieHeader(cookies) };
  const base = { probeId: randomUUID(), location: args.location, label: args.label, intervalMs: args.interval };
  if (args.out) await mkdir(dirname(args.out), { recursive: true });
  const write = async (record) => {
    const line = JSON.stringify(record);
    if (args.out) await appendFile(args.out, line + '\n');
    else console.log(line);
  };
  const counts = await probe({ url: args.url, headers, interval: args.interval, durationSeconds: args.duration, timeoutMs: args.timeout, base, write });
  console.error(JSON.stringify({ probeId: base.probeId, location: args.location, ...counts }));
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => {
    console.error(err.message);
    process.exit(1);
  });
}
