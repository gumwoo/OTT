import Hls, { PlaylistLevelType } from 'hls.js';
import { useEffect, useRef, useState, type RefObject } from 'react';
import { ApiError } from '../../lib/api/client';
import { refreshPlayback, sendQoe, type PlaybackGrant, type QoeEvent } from './api';
import { createQoeTracker, type QoeTracker } from './qoe';
import { startRefreshLoop } from './refreshLoop';

const REPORT_INTERVAL_MS = 30_000;

// Edge가 정책으로 거부할 때 403과 함께 주는 사유 (design.md §7). 사유 없는 403은 쿠키 문제다
const DENIED_HEADER = 'X-Playback-Denied';
const DENIED_CODES = new Set(['GEO_BLOCKED', 'NOT_AVAILABLE', 'POLICY_UNAVAILABLE']);

export type PlayerGrant = PlaybackGrant & {
  // performance.now() 기준. Startup은 재생 요청부터 잰다 (design.md §9)
  requestedAt: number;
};

export type PlayerProblem =
  { kind: 'refresh'; error: ApiError } | { kind: 'denied'; code: string } | { kind: 'media' };

export type EdgeHeaders = { pop: string | null; cache: string | null };

export type PlayerDiagnostics = {
  hls: Hls | null;
  tracker: QoeTracker | null;
  edge: EdgeHeaders | null;
  // 첫 세그먼트가 버퍼에 붙기 전 bandwidthEstimate는 측정값이 아니라 설정 기본값이다
  bandwidthMeasured: boolean;
};

export function usePlayer(video: RefObject<HTMLVideoElement | null>, grant: PlayerGrant) {
  const tracker = useRef<QoeTracker | null>(null);
  const diagnostics = useRef<PlayerDiagnostics>({
    hls: null,
    tracker: null,
    edge: null,
    bandwidthMeasured: false,
  });
  const [mediaProblem, setMediaProblem] = useState<PlayerProblem | null>(null);
  // 정리는 선언 순서대로 돈다. hls.destroy()가 재생 위치를 0으로 돌리기 전에 FINAL을 보내야 한다
  useQoeReports(video, grant.sessionId, tracker);
  useHls(video, grant, tracker, diagnostics, setMediaProblem);
  const denied = mediaProblem?.kind === 'denied';
  const refreshError = usePlaybackRefresh(grant, !denied);

  const problem: PlayerProblem | null =
    refreshError && !denied ? { kind: 'refresh', error: refreshError } : mediaProblem;
  return { problem, diagnostics };
}

function header(details: unknown, name: string): string | null {
  if (details instanceof XMLHttpRequest) return details.getResponseHeader(name);
  if (details instanceof Response) return details.headers.get(name);
  return null;
}

// FE-09: 인스턴스는 effect 안에서 만들고 cleanup에서 destroy. StrictMode의 두 번 생성은 정상이다
function useHls(
  videoRef: RefObject<HTMLVideoElement | null>,
  grant: PlayerGrant,
  trackerRef: RefObject<QoeTracker | null>,
  diagnosticsRef: RefObject<PlayerDiagnostics>,
  onProblem: (problem: PlayerProblem) => void,
) {
  const { manifestUrl, requestedAt } = grant;
  useEffect(() => {
    const video = videoRef.current;
    if (!video) {
      return;
    }
    const tracker = createQoeTracker(requestedAt);
    trackerRef.current = tracker;
    diagnosticsRef.current = { hls: null, tracker, edge: null, bandwidthMeasured: false };
    const onPlaying = () => tracker.playing(performance.now());
    const onWaiting = () => tracker.waiting(performance.now());
    video.addEventListener('playing', onPlaying);
    video.addEventListener('waiting', onWaiting);

    let hls: Hls | null = null;
    const onNativeError = () => {
      tracker.error('native:error');
      onProblem({ kind: 'media' });
    };
    if (Hls.isSupported()) {
      const instance = new Hls();
      let mediaRecovered = false;
      instance.on(Hls.Events.LEVEL_SWITCHED, (_event, data) => {
        const level = instance.levels[data.level];
        tracker.levelSwitched(level ? `${level.height}p` : String(data.level));
      });
      // CloudFront 응답 헤더. 로컬 content-server에는 없다
      instance.on(Hls.Events.FRAG_LOADED, (_event, data) => {
        diagnosticsRef.current.edge = {
          pop: header(data.networkDetails, 'x-amz-cf-pop'),
          cache: header(data.networkDetails, 'x-cache'),
        };
      });
      // hls.js도 main 이외의 조각과 init 조각은 대역폭 샘플로 쓰지 않는다
      instance.on(Hls.Events.FRAG_BUFFERED, (_event, data) => {
        if (data.frag.type === PlaylistLevelType.MAIN && data.frag.sn !== 'initSegment') {
          diagnosticsRef.current.bandwidthMeasured = true;
        }
      });
      instance.on(Hls.Events.ERROR, (_event, data) => {
        tracker.error(`${data.type}:${data.details}${data.fatal ? ':fatal' : ''}`);
        if (!data.fatal) return;
        const reason =
          data.response?.code === 403 ? header(data.networkDetails, DENIED_HEADER) : null;
        if (reason && DENIED_CODES.has(reason)) {
          onProblem({ kind: 'denied', code: reason });
          return;
        }
        // hls.js 권장 복구: 미디어 오류는 한 번 되살려 보고, 그 밖의 치명 오류는 멈춘다
        if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !mediaRecovered) {
          mediaRecovered = true;
          instance.recoverMediaError();
          return;
        }
        onProblem({ kind: 'media' });
      });
      instance.loadSource(manifestUrl);
      instance.attachMedia(video);
      hls = instance;
      diagnosticsRef.current.hls = instance;
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      // Safari 네이티브 HLS. 화질 전환과 정책 거부 사유는 못 읽어서 일반 오류로만 알린다
      video.addEventListener('error', onNativeError);
      video.src = manifestUrl;
    } else {
      tracker.error('hls-unsupported');
      onProblem({ kind: 'media' });
    }
    video.play().catch((error: unknown) => {
      tracker.error(`play:${error instanceof Error ? error.name : 'unknown'}`);
    });

    return () => {
      video.removeEventListener('playing', onPlaying);
      video.removeEventListener('waiting', onWaiting);
      video.removeEventListener('error', onNativeError);
      hls?.destroy();
      diagnosticsRef.current.hls = null;
    };
  }, [videoRef, manifestUrl, requestedAt, trackerRef, diagnosticsRef, onProblem]);
}

function useQoeReports(
  videoRef: RefObject<HTMLVideoElement | null>,
  sessionId: string,
  trackerRef: RefObject<QoeTracker | null>,
) {
  useEffect(() => {
    // 언마운트 때는 ref가 먼저 비워지므로 요소를 미리 잡아 둔다
    const video = videoRef.current;
    let finalSent = false;
    const report = (kind: QoeEvent['kind'], keepalive: boolean) => {
      const tracker = trackerRef.current;
      if (!tracker) {
        return;
      }
      const event: QoeEvent = {
        clientEventId: crypto.randomUUID(),
        sessionId,
        kind,
        ...tracker.snapshot(performance.now()),
        positionSec: Math.floor(video?.currentTime ?? 0),
        measuredAt: new Date().toISOString(),
      };
      sendQoe([event], keepalive).catch(() => undefined);
    };
    const final = () => {
      if (!finalSent) {
        finalSent = true;
        report('FINAL', true);
      }
    };
    const onVisibilityChange = () => {
      if (document.visibilityState === 'hidden') {
        report('PERIODIC', true);
      }
    };
    // 뒤로가기 캐시(bfcache)에서 돌아오면 나중에 떠날 때 FINAL을 다시 보낸다
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) finalSent = false;
    };
    const timer = window.setInterval(() => report('PERIODIC', false), REPORT_INTERVAL_MS);
    document.addEventListener('visibilitychange', onVisibilityChange);
    window.addEventListener('pagehide', final);
    window.addEventListener('pageshow', onPageShow);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibilityChange);
      window.removeEventListener('pagehide', final);
      window.removeEventListener('pageshow', onPageShow);
      final();
    };
  }, [videoRef, sessionId, trackerRef]);
}

// Edge가 거부한 작품은 쿠키를 갱신해도 볼 수 없으므로 멈춘다
function usePlaybackRefresh(grant: PlaybackGrant, active: boolean): ApiError | null {
  const [error, setError] = useState<ApiError | null>(null);
  const { sessionId, expiresAt } = grant;
  useEffect(() => {
    if (!active) return;
    return startRefreshLoop({
      grant: { sessionId, expiresAt },
      refresh: refreshPlayback,
      onStop: setError,
    });
  }, [sessionId, expiresAt, active]);
  return error;
}
