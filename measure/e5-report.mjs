// E5·E6 지표 계산. 시각 정의는 design.md §11 "E5의 시간 기준"
// usage: node measure/e5-report.mjs --t0 <PR merge ISO> --t1 <UpdateKeys 성공 ISO> [--window 60] [--budget 60]
//        [--codes GEO_BLOCKED,NOT_AVAILABLE] results/e5-*.jsonl

import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const seconds = (ms) => (ms === null ? null : Math.round(ms / 100) / 10);

// records: policy-probe.mjs가 남긴 한 위치의 기록
export function locationReport(records, { t0, t1, windowSeconds, budgetSeconds, codes }) {
  const sorted = records
    .map((r) => ({ ...r, time: Date.parse(r.at) }))
    .sort((a, b) => a.time - b.time);
  const after = sorted.filter((r) => r.time >= t1);
  const policyBlock = (r) => r.outcome === 'block' && codes.has(r.denied);

  const firstBlock = after.find(policyBlock) ?? null;
  const lastAllow = [...after].reverse().find((r) => r.outcome === 'allow') ?? null;

  // T4: 차단만 이어진 구간 안에서 W가 지난 시점의 차단까지 관측한 첫 시점.
  // 관측 간격이 요청 간격의 두 배를 넘으면 그 사이를 봤다고 할 수 없어 구간을 끊는다
  const maxGap = (r) => (r.intervalMs ? r.intervalMs * 2 : Infinity);
  let converged = null;
  for (let i = 0; i < after.length && converged === null; i++) {
    if (!policyBlock(after[i])) continue;
    const until = after[i].time + windowSeconds * 1000;
    for (let j = i; j < after.length && policyBlock(after[j]); j++) {
      if (j > i && after[j].time - after[j - 1].time > maxGap(after[j])) break;
      if (after[j].time >= until) {
        converged = until;
        break;
      }
    }
  }

  return {
    requests: sorted.length,
    requestsAfterT1: after.length,
    clients: new Set(sorted.map((r) => r.probeId)).size,
    // 클라이언트를 합쳐 재면 간격이 짧게 나온다. 클라이언트별 중앙값의 중앙값
    medianIntervalMs: median(perClient(sorted).map((list) => median(gaps(list)))),
    errorsAfterT1: after.filter((r) => r.outcome === 'error').length,
    // 정책 사유 코드가 있지만 codes에 없는 차단(예: 키 삭제로 인한 POLICY_UNAVAILABLE)
    otherBlocksAfterT1: after.filter((r) => r.outcome === 'block' && !codes.has(r.denied)).length,
    pipelineDelayS: seconds(t1 - t0),
    firstBlockDelayS: seconds(firstBlock ? firstBlock.time - t1 : null),
    endToEndRevocationDelayS: seconds(firstBlock ? firstBlock.time - t0 : null),
    lastAllow: lastAllow ? lastAllow.at : null,
    observedConvergenceS: seconds(converged === null ? null : converged - t1),
    // 전파 예산은 T1(UpdateKeys 성공)부터 잰다. 그 뒤에도 허용된 요청 수. E6에서는 False Allow
    sloViolations: after.filter((r) => r.outcome === 'allow' && r.time > t1 + budgetSeconds * 1000).length,
    firstBlockRev: firstBlock?.rev ?? null,
    pops: [...new Set(after.map((r) => r.pop).filter(Boolean))]
  };
}

function perClient(records) {
  const byProbe = new Map();
  for (const r of records) {
    if (!byProbe.has(r.probeId)) byProbe.set(r.probeId, []);
    byProbe.get(r.probeId).push(r);
  }
  return [...byProbe.values()];
}

function gaps(records) {
  return records.slice(1).map((r, i) => r.time - records[i].time);
}

function median(values) {
  const sorted = values.filter((v) => v !== null).sort((a, b) => a - b);
  return sorted.length ? sorted[Math.floor(sorted.length / 2)] : null;
}

export function report(records, options) {
  const byLocation = new Map();
  for (const r of records) {
    if (!byLocation.has(r.location)) byLocation.set(r.location, []);
    byLocation.get(r.location).push(r);
  }
  return Object.fromEntries([...byLocation].map(([location, list]) => [location, locationReport(list, options)]));
}

function parseArgs(argv) {
  const args = { window: 60, budget: 60, codes: 'GEO_BLOCKED,NOT_AVAILABLE', files: [] };
  for (let i = 2; i < argv.length; i++) {
    if (argv[i].startsWith('--')) args[argv[i].slice(2)] = argv[++i];
    else args.files.push(argv[i]);
  }
  if (!args.t0 || !args.t1 || args.files.length === 0) throw new Error('--t0, --t1 and at least one file are required');
  for (const name of ['t0', 't1']) {
    if (Number.isNaN(Date.parse(args[name]))) throw new Error(`--${name} is not an ISO 8601 time: ${args[name]}`);
  }
  return args;
}

async function main() {
  const args = parseArgs(process.argv);
  const records = [];
  for (const file of args.files) {
    for (const line of (await readFile(file, 'utf8')).split('\n')) if (line.trim()) records.push(JSON.parse(line));
  }
  const options = {
    t0: Date.parse(args.t0),
    t1: Date.parse(args.t1),
    windowSeconds: Number(args.window),
    budgetSeconds: Number(args.budget),
    codes: new Set(args.codes.split(','))
  };
  // 결과에는 요청 간격·W·위치별 클라이언트 수를 같이 남긴다 (design.md §11)
  console.log(JSON.stringify({ t0: args.t0, t1: args.t1, windowSeconds: options.windowSeconds, budgetSeconds: options.budgetSeconds, locations: report(records, options) }, null, 2));
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => {
    console.error(err.message);
    process.exit(1);
  });
}
