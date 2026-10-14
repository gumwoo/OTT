import type Hls from 'hls.js';
import { useEffect, useState, type RefObject } from 'react';
import type { EdgeHeaders, PlayerDiagnostics } from './usePlayer';

const POLL_MS = 1_000;
const NEEDS_INTEGRATION = '연동 필요';

type Row = { label: string; value: string; pending?: boolean };

// 개발자 모드 전용. 플레이어가 실제로 잰 값만 보여주고, 없는 값은 만들어 내지 않는다
export function QoePanel({
  video,
  diagnostics,
  sessionId,
  manifestUrl,
}: {
  video: RefObject<HTMLVideoElement | null>;
  diagnostics: RefObject<PlayerDiagnostics>;
  sessionId: string;
  manifestUrl: string;
}) {
  const [rows, setRows] = useState<Row[]>([]);

  useEffect(() => {
    const read = () => setRows(collect(video.current, diagnostics.current, sessionId, manifestUrl));
    read();
    const timer = window.setInterval(read, POLL_MS);
    return () => window.clearInterval(timer);
  }, [video, diagnostics, sessionId, manifestUrl]);

  return (
    <section
      aria-label="재생 진단"
      className="bg-scrim absolute top-16 right-4 w-76 rounded-md p-4 text-xs"
    >
      <h2 className="mb-2 text-sm font-semibold">재생 진단</h2>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
        {rows.map((row) => (
          <div key={row.label} className="contents">
            <dt className="text-text-muted">{row.label}</dt>
            <dd
              className={`truncate text-right font-mono tabular-nums ${row.pending ? 'text-text-muted font-sans' : 'text-text'}`}
            >
              {row.value}
            </dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

function collect(
  video: HTMLVideoElement | null,
  diagnostics: PlayerDiagnostics,
  sessionId: string,
  manifestUrl: string,
): Row[] {
  const { hls, tracker, edge, bandwidthMeasured } = diagnostics;
  const level = hls && hls.currentLevel >= 0 ? hls.levels[hls.currentLevel] : undefined;
  const snapshot = tracker?.snapshot(performance.now());
  const quality = video?.getVideoPlaybackQuality?.();

  return [
    {
      label: '해상도',
      value: video?.videoHeight ? `${video.videoWidth}×${video.videoHeight}` : '-',
    },
    { label: '렌디션', value: level ? `${level.height}p · ${kbps(level.bitrate)}` : '-' },
    {
      label: '대역폭 추정',
      value:
        hls && bandwidthMeasured && Number.isFinite(hls.bandwidthEstimate)
          ? kbps(hls.bandwidthEstimate)
          : '-',
    },
    { label: '버퍼', value: video ? `${bufferAhead(video).toFixed(1)}초` : '-' },
    {
      label: '끊긴 프레임',
      value: quality ? `${quality.droppedVideoFrames} / ${quality.totalVideoFrames}` : '-',
    },
    { label: '시작 시간', value: snapshot?.startupMs == null ? '-' : `${snapshot.startupMs}ms` },
    {
      label: '재버퍼링',
      value: snapshot ? `${snapshot.rebufferCount}회 · ${snapshot.rebufferMs}ms` : '-',
    },
    { label: '화질 전환', value: snapshot ? `${snapshot.levelSwitches}회` : '-' },
    { label: '버전 경로', value: manifestUrl.replace(/master\.m3u8$/, '') },
    // 세션 ID 전체는 QoE 보고를 위조하는 데 쓸 수 있어 앞부분만 보인다
    { label: '세션', value: `${sessionId.slice(0, 8)}…` },
    edgeRow('CDN POP', hls, edge, edge?.pop),
    edgeRow('캐시', hls, edge, edge?.cache),
  ];
}

// 세그먼트를 받기 전은 측정 전이고, 받았는데 헤더가 없으면 CloudFront를 거치지 않은 것이다
function edgeRow(
  label: string,
  hls: Hls | null,
  edge: EdgeHeaders | null,
  value: string | null | undefined,
): Row {
  // 브라우저 자체 HLS(Safari)는 응답 헤더를 읽을 수 없다
  if (!hls) return { label, value: '측정 불가', pending: true };
  if (!edge) return { label, value: '-' };
  return value ? { label, value } : { label, value: NEEDS_INTEGRATION, pending: true };
}

function kbps(bitsPerSecond: number): string {
  return `${Math.round(bitsPerSecond / 1000).toLocaleString('ko-KR')} kbps`;
}

function bufferAhead(video: HTMLVideoElement): number {
  const { buffered, currentTime } = video;
  for (let i = 0; i < buffered.length; i++) {
    if (buffered.start(i) <= currentTime && currentTime <= buffered.end(i)) {
      return buffered.end(i) - currentTime;
    }
  }
  return 0;
}
