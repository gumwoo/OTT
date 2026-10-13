// E2 cold/warm 측정용. master → playlist → segment N개를 받아 요청별로 JSONL에 남긴다.
// usage: node measure/hls-probe.mjs --url <master.m3u8> [--location seoul] [--label cold]
//        [--segments 5] [--variant highest|lowest|N] [--out results/e2.jsonl]

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

class ProbeFailure extends Error {
  constructor(reason, kind, path, status) {
    super(`${reason}: ${kind} ${path}${status === null ? '' : ` -> ${status}`}`);
    this.reason = reason;
    this.kind = kind;
    this.path = path;
    this.status = status;
  }
}

const EXPECTED_STATUS = 200;

async function timedGet(url, ctx, kind) {
  const path = new URL(url).pathname;
  const started = performance.now();
  let res;
  try {
    res = await fetch(url, { headers: { 'user-agent': 'ott-hls-probe/0.1' } });
  } catch (err) {
    ctx.records.push({
      runId: ctx.runId,
      probeId: ctx.probeId,
      seq: ctx.seq++,
      at: new Date().toISOString(),
      location: ctx.location,
      label: ctx.label,
      kind,
      path,
      status: null,
      ok: false,
      error: String(err?.cause?.code ?? err?.message ?? err),
      totalMs: Math.round((performance.now() - started) * 10) / 10
    });
    throw new ProbeFailure('NETWORK_ERROR', kind, path, null);
  }
  const ttfbMs = performance.now() - started;
  // 본문 수신 중 끊김도 실패로 남긴다
  let body = null;
  let bodyError = false;
  try {
    body = await res.arrayBuffer();
  } catch {
    bodyError = true;
  }
  const totalMs = performance.now() - started;

  const record = {
    runId: ctx.runId,
    probeId: ctx.probeId,
    seq: ctx.seq++, // 첫 요청만 DNS·TLS 포함
    at: new Date().toISOString(),
    location: ctx.location,
    label: ctx.label,
    kind,
    path,
    status: res.status,
    ok: res.status === EXPECTED_STATUS && !bodyError,
    bytes: body === null ? null : body.byteLength,
    ttfbMs: Math.round(ttfbMs * 10) / 10,
    totalMs: Math.round(totalMs * 10) / 10,
    xCache: res.headers.get('x-cache'),
    pop: res.headers.get('x-amz-cf-pop'),
    age: res.headers.get('age'),
    cfId: res.headers.get('x-amz-cf-id')
  };
  ctx.records.push(record);
  if (bodyError) throw new ProbeFailure('BODY_READ_ERROR', kind, path, res.status);
  if (!record.ok) throw new ProbeFailure('UNEXPECTED_STATUS', kind, path, res.status);
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
  let variant = null;
  let firstSegmentDoneMs = null;
  let failure = null;

  try {
    const master = await timedGet(args.url, ctx, 'master');

    variant = pickVariant(parseMaster(master.text(), args.url), args.variant) ?? null;
    if (!variant) throw new ProbeFailure('VARIANT_NOT_FOUND', 'master', new URL(args.url).pathname, null);

    const media = await timedGet(variant.url, ctx, 'playlist');
    const segments = parseMedia(media.text(), variant.url).slice(0, args.segments);
    if (segments.length === 0) throw new ProbeFailure('NO_SEGMENTS', 'playlist', new URL(variant.url).pathname, null);

    for (const seg of segments) {
      await timedGet(seg, ctx, 'segment');
      if (firstSegmentDoneMs === null) firstSegmentDoneMs = performance.now() - started;
    }
  } catch (err) {
    failure =
      err instanceof ProbeFailure
        ? { reason: err.reason, kind: err.kind, path: err.path, status: err.status }
        : { reason: 'UNEXPECTED_ERROR', kind: null, path: null, status: null, message: String(err?.message ?? err) };
  }

  const ok = failure === null;
  const succeeded = ctx.records.filter((r) => r.ok);
  const summary = {
    type: 'summary',
    runId: ctx.runId,
    probeId: ctx.probeId,
    at: new Date().toISOString(),
    location: ctx.location,
    label: ctx.label,
    ok,
    failure,
    variant: variant?.resolution ?? null,
    requests: ctx.records.length,
    failedRequests: ctx.records.length - succeeded.length,
    // master + playlist + 첫 segment까지. 실패한 측정은 null
    startupProxyMs: ok && firstSegmentDoneMs !== null ? Math.round(firstSegmentDoneMs) : null,
    hits: succeeded.filter((r) => /^Hit/i.test(r.xCache ?? '')).length,
    misses: succeeded.filter((r) => /^Miss/i.test(r.xCache ?? '')).length,
    pops: [...new Set(ctx.records.map((r) => r.pop))]
  };

  const lines = [...ctx.records, summary].map((r) => JSON.stringify(r)).join('\n') + '\n';
  if (args.out) {
    await mkdir(dirname(args.out), { recursive: true });
    await appendFile(args.out, lines);
  }
  console.log(JSON.stringify(summary, null, 2));
  if (!ok) process.exitCode = 1;
}

main().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
