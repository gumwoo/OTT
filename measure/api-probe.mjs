// E4(Pod 장애 중 Cookie 발급)·E7(변환 중 API 지연): 로그인한 세션으로 재생 시작 API를 일정 간격으로 부른다
// usage: OTT_EMAIL=... OTT_PASSWORD=... node measure/api-probe.mjs --base https://<host> --content coffee-run
//        [--interval 500] [--duration 300] [--label e4-replicas2] [--out results/e4.jsonl]
// 부하 시험이 아니다. 한 요청씩 순서대로 보내 성공률·지연·끊긴 구간을 본다 (design.md §11 E4)

import { appendFile, mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { nextSlot } from './policy-probe.mjs';

// 세션·CSRF 쿠키를 직접 들고 다닌다. 웹 앱과 같은 흐름이다 (app-design.md §5)
export class Session {
  constructor(base, timeoutMs = 10000) {
    this.base = base.replace(/\/$/, '');
    this.timeoutMs = timeoutMs;
    this.cookies = new Map();
  }

  async call(method, path, body) {
    const headers = { 'content-type': 'application/json', 'user-agent': 'ott-api-probe/0.1' };
    if (this.cookies.size) headers.cookie = [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ');
    if (method !== 'GET' && this.cookies.has('XSRF-TOKEN')) headers['x-xsrf-token'] = this.cookies.get('XSRF-TOKEN');
    const res = await fetch(this.base + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      redirect: 'manual',
      // Pod를 강제 삭제하면 응답이 오지 않는 연결이 생긴다. 한 요청이 측정 전체를 멈추지 않게 한다
      signal: AbortSignal.timeout(this.timeoutMs)
    });
    for (const setCookie of res.headers.getSetCookie()) {
      const [pair] = setCookie.split(';');
      const index = pair.indexOf('=');
      const name = pair.slice(0, index).trim();
      const value = pair.slice(index + 1).trim();
      if (value) this.cookies.set(name, value);
      else this.cookies.delete(name);
    }
    return res;
  }

  async login(email, password) {
    await this.call('GET', '/api/csrf');
    const res = await this.call('POST', '/api/auth/login', { email, password });
    if (!res.ok) throw new Error(`login failed: ${res.status}`);
    await res.arrayBuffer();
    // 로그인하면 CSRF 토큰이 바뀐다
    await this.call('GET', '/api/csrf');
  }
}

export function percentile(sorted, p) {
  if (sorted.length === 0) return null;
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
}

export function summarize(records) {
  const ok = records.filter((r) => r.ok);
  const latencies = ok.map((r) => r.ms).sort((a, b) => a - b);
  // 연속 실패 구간: Pod가 빠지고 새 Pod가 받기까지 끊긴 시간을 본다
  let longest = null;
  let current = null;
  for (const r of records) {
    if (r.ok) {
      current = null;
      continue;
    }
    current = current ? { ...current, to: r.at, count: current.count + 1 } : { from: r.at, to: r.at, count: 1 };
    if (!longest || current.count > longest.count) longest = current;
  }
  return {
    requests: records.length,
    succeeded: ok.length,
    successRate: records.length ? Math.round((ok.length / records.length) * 10000) / 100 : null,
    p50Ms: percentile(latencies, 50),
    p95Ms: percentile(latencies, 95),
    p99Ms: percentile(latencies, 99),
    statuses: records.reduce((acc, r) => ({ ...acc, [r.status ?? 'network']: (acc[r.status ?? 'network'] ?? 0) + 1 }), {}),
    longestFailure: longest
  };
}

async function startPlayback(session, contentId, base) {
  const started = performance.now();
  try {
    const res = await session.call('POST', `/api/contents/${encodeURIComponent(contentId)}/playback`);
    const text = await res.text();
    let code = null;
    if (!res.ok) {
      try {
        code = JSON.parse(text).code ?? null;
      } catch {
        code = null;
      }
    }
    return {
      ...base,
      at: new Date().toISOString(),
      status: res.status,
      // 재생 시작은 Signed Cookie 4개를 같이 줘야 성공이다
      ok: res.status === 200 && res.headers.getSetCookie().some((c) => c.startsWith('CloudFront-Signature=')),
      code,
      ms: Math.round((performance.now() - started) * 10) / 10
    };
  } catch (err) {
    return {
      ...base,
      at: new Date().toISOString(),
      status: null,
      ok: false,
      code: err?.name === 'TimeoutError' ? 'TIMEOUT' : String(err?.cause?.code ?? err?.message ?? err),
      ms: Math.round((performance.now() - started) * 10) / 10
    };
  }
}

export async function probe({ session, contentId, interval, durationSeconds, maxRequests = Infinity, base, write }) {
  const records = [];
  const started = Date.now();
  let next = started;
  for (let i = 0; i < maxRequests && Date.now() - started < durationSeconds * 1000; i++) {
    const record = await startPlayback(session, contentId, { ...base, seq: i });
    records.push(record);
    await write(record);
    // 늦어진 만큼 몰아서 보내면 E4의 "부하 고정"이 깨진다
    next = nextSlot(next, interval);
    const wait = next - Date.now();
    if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
  }
  return summarize(records);
}

function parseArgs(argv) {
  const args = { interval: 500, duration: 300, timeout: 10000, label: 'unlabeled', out: null };
  for (let i = 2; i < argv.length; i += 2) args[argv[i].replace(/^--/, '')] = argv[i + 1];
  if (!args.base || !args.content) throw new Error('--base and --content are required');
  if (!process.env.OTT_EMAIL || !process.env.OTT_PASSWORD) throw new Error('OTT_EMAIL and OTT_PASSWORD are required');
  return { ...args, interval: Number(args.interval), duration: Number(args.duration), timeout: Number(args.timeout) };
}

async function main() {
  const args = parseArgs(process.argv);
  const session = new Session(args.base, args.timeout);
  await session.login(process.env.OTT_EMAIL, process.env.OTT_PASSWORD);
  if (args.out) await mkdir(dirname(args.out), { recursive: true });
  const base = { probeId: randomUUID(), label: args.label, content: args.content };
  const write = async (record) => {
    if (args.out) await appendFile(args.out, JSON.stringify(record) + '\n');
  };
  const summary = await probe({ session, contentId: args.content, interval: args.interval, durationSeconds: args.duration, base, write });
  const line = JSON.stringify({ type: 'summary', ...base, at: new Date().toISOString(), ...summary });
  if (args.out) await appendFile(args.out, line + '\n');
  console.log(JSON.stringify(summary, null, 2));
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => {
    console.error(err.message);
    process.exit(1);
  });
}
