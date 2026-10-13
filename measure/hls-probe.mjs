// HLS 요청 단위 측정 클라이언트 (E2 cold/warm 캐시 실험용).
// 브라우저 없이 master → variant playlist → segment N개를 순서대로 받으며,
// 요청마다 CloudFront 캐시 결과(X-Cache), 처리 POP, TTFB, 전체 시간, 바이트를 JSONL로 기록한다.
//
// 사용법:
//   node measure/hls-probe.mjs --url https://<dist>.cloudfront.net/content/movie-100/runs/run-001/master.m3u8 \
//     --location seoul --label cold --segments 5 --variant highest --out results/e2.jsonl
//
// 주의
// - Node fetch는 연결을 재사용한다. 첫 요청에는 DNS·TLS 시간이 포함되고 이후 요청에는 빠진다.
//   이 차이를 기록하기 위해 요청 순번(seq)을 남긴다.
// - TTFB는 응답 헤더 수신 시점(fetch resolve)까지의 시간이다.
// - 측정 위치(--location)는 클라이언트가 실행된 곳을 사람이 지정한다. CloudFront POP은 응답 헤더에서 따로 기록한다.

import { appendFile, mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';
import { randomUUID } from 'node:crypto';

function parseArgs(argv) {
  const args = { segments: 5, variant: 'highest', label: 'unlabeled', location: 'unknown', out: null };
  for (let i = 2; i < argv.length; i += 2) {
    const key = argv[i].replace(/^--/, '');
    args[key] = argv[i + 1];
  }
  if (!args.url) throw new Error('--url is required');
  args.segments = Number(args.segments);
  return args;
}

function parseMaster(text, baseUrl) {
  const lines = text.split(/\r?\n/);
  const variants = [];
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].startsWith('#EXT-X-STREAM-INF')) {
      const bandwidth = Number(/BANDWIDTH=(\d+)/.exec(lines[i])?.[1] ?? 0);
      const resolution = /RESOLUTION=([\dx]+)/.exec(lines[i])?.[1] ?? null;
      variants.push({ bandwidth, resolution, url: new URL(lines[i + 1].trim(), baseUrl).href });
    }
  }
  return variants.sort((a, b) => a.bandwidth - b.bandwidth);
}

function parseMedia(text, baseUrl) {
  const lines = text.split(/\r?\n/);
  const segments = [];
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].startsWith('#EXTINF:')) segments.push(new URL(lines[i + 1].trim(), baseUrl).href);
  }
  return segments;
}

function pickVariant(variants, which) {
  if (which === 'highest') return variants[variants.length - 1];
  if (which === 'lowest') return variants[0];
  return variants[Number(which)];
}

async function timedGet(url, ctx, kind) {
  const started = performance.now();
  const res = await fetch(url, { headers: { 'user-agent': 'ott-hls-probe/0.1' } });
  const ttfbMs = performance.now() - started;
  const body = await res.arrayBuffer();
  const totalMs = performance.now() - started;

  const record = {
    runId: ctx.runId,
    probeId: ctx.probeId,
    seq: ctx.seq++,
    at: new Date().toISOString(),
    location: ctx.location,
    label: ctx.label,
    kind,
    path: new URL(url).pathname,
    status: res.status,
    bytes: body.byteLength,
    ttfbMs: Math.round(ttfbMs * 10) / 10,
    totalMs: Math.round(totalMs * 10) / 10,
    xCache: res.headers.get('x-cache'),
    pop: res.headers.get('x-amz-cf-pop'),
    age: res.headers.get('age'),
    cfId: res.headers.get('x-amz-cf-id')
  };
  ctx.records.push(record);
  return { res, text: () => new TextDecoder().decode(body) };
}

async function main() {
  const args = parseArgs(process.argv);
  const ctx = {
    runId: args.run ?? new URL(args.url).pathname.split('/').slice(0, -1).join('/'),
    probeId: randomUUID(),
    seq: 0,
    location: args.location,
    label: args.label,
    records: []
  };

  const started = performance.now();
  const master = await timedGet(args.url, ctx, 'master');
  if (master.res.status !== 200) throw new Error(`master returned ${master.res.status}`);

  const variant = pickVariant(parseMaster(master.text(), args.url), args.variant);
  if (!variant) throw new Error(`variant not found: ${args.variant}`);

  const media = await timedGet(variant.url, ctx, 'playlist');
  const segments = parseMedia(media.text(), variant.url).slice(0, args.segments);

  let firstSegmentDoneMs = null;
  for (const seg of segments) {
    await timedGet(seg, ctx, 'segment');
    if (firstSegmentDoneMs === null) firstSegmentDoneMs = performance.now() - started;
  }

  const summary = {
    type: 'summary',
    runId: ctx.runId,
    probeId: ctx.probeId,
    at: new Date().toISOString(),
    location: ctx.location,
    label: ctx.label,
    variant: variant.resolution,
    requests: ctx.records.length,
    // 플레이어 startup의 근사치: master + playlist + 첫 segment 다운로드 완료까지. 디코딩·버퍼 정책은 포함하지 않는다.
    startupProxyMs: firstSegmentDoneMs === null ? null : Math.round(firstSegmentDoneMs),
    hits: ctx.records.filter((r) => /^Hit/i.test(r.xCache ?? '')).length,
    misses: ctx.records.filter((r) => /^Miss/i.test(r.xCache ?? '')).length,
    pops: [...new Set(ctx.records.map((r) => r.pop))]
  };

  const lines = [...ctx.records, summary].map((r) => JSON.stringify(r)).join('\n') + '\n';
  if (args.out) {
    await mkdir(dirname(args.out), { recursive: true });
    await appendFile(args.out, lines);
  }
  console.log(JSON.stringify(summary, null, 2));
}

main().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
