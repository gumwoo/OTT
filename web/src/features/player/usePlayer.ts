import Hls from 'hls.js';
import { useEffect, useRef, useState, type RefObject } from 'react';
import { ApiError } from '../../lib/api/client';
import { refreshPlayback, sendQoe, type PlaybackGrant, type QoeEvent } from './api';
import { createQoeTracker, type QoeTracker } from './qoe';
import { startRefreshLoop } from './refreshLoop';

const REPORT_INTERVAL_MS = 30_000;

export type PlayerGrant = PlaybackGrant & {
  // performance.now() 기준. Startup은 재생 요청부터 잰다 (design.md §9)
  requestedAt: number;
};

export type PlayerProblem = { kind: 'refresh'; error: ApiError } | { kind: 'media' };

export function usePlayer(
  video: RefObject<HTMLVideoElement | null>,
  grant: PlayerGrant,
): PlayerProblem | null {
  const tracker = useRef<QoeTracker | null>(null);
  const [mediaFailed, setMediaFailed] = useState(false);
  // 정리는 선언 순서대로 돈다. hls.destroy()가 재생 위치를 0으로 돌리기 전에 FINAL을 보내야 한다
  useQoeReports(video, grant.sessionId, tracker);
  useHls(video, grant, tracker, setMediaFailed);
  const refreshError = usePlaybackRefresh(grant);

  if (refreshError) return { kind: 'refresh', error: refreshError };
  if (mediaFailed) return { kind: 'media' };
  return null;
}

// FE-09: 인스턴스는 effect 안에서 만들고 cleanup에서 destroy. StrictMode의 두 번 생성은 정상이다
function useHls(
  videoRef: RefObject<HTMLVideoElement | null>,
  grant: PlayerGrant,
  trackerRef: RefObject<QoeTracker | null>,
  onFatal: (failed: boolean) => void,
) {
  const { manifestUrl, requestedAt } = grant;
  useEffect(() => {
    const video = videoRef.current;
    if (!video) {
      return;
    }
    const tracker = createQoeTracker(requestedAt);
    trackerRef.current = tracker;
    const onPlaying = () => tracker.playing(performance.now());
    const onWaiting = () => tracker.waiting(performance.now());
    video.addEventListener('playing', onPlaying);
    video.addEventListener('waiting', onWaiting);

    let hls: Hls | null = null;
    if (Hls.isSupported()) {
      const instance = new Hls();
      let mediaRecovered = false;
      instance.on(Hls.Events.LEVEL_SWITCHED, (_event, data) => {
        const level = instance.levels[data.level];
        tracker.levelSwitched(level ? `${level.height}p` : String(data.level));
      });
      instance.on(Hls.Events.ERROR, (_event, data) => {
        tracker.error(`${data.type}:${data.details}${data.fatal ? ':fatal' : ''}`);
        if (!data.fatal) return;
        // hls.js 권장 복구: 미디어 오류는 한 번 되살려 보고, 그 밖의 치명 오류는 멈춘다
        if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !mediaRecovered) {
          mediaRecovered = true;
          instance.recoverMediaError();
          return;
        }
        onFatal(true);
      });
      instance.loadSource(manifestUrl);
      instance.attachMedia(video);
      hls = instance;
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      // Safari 네이티브 HLS. 화질 전환은 못 잰다
      video.src = manifestUrl;
    } else {
      tracker.error('hls-unsupported');
      onFatal(true);
    }
    video.play().catch((error: unknown) => {
      tracker.error(`play:${error instanceof Error ? error.name : 'unknown'}`);
    });

    return () => {
      video.removeEventListener('playing', onPlaying);
      video.removeEventListener('waiting', onWaiting);
      hls?.destroy();
    };
  }, [videoRef, manifestUrl, requestedAt, trackerRef, onFatal]);
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

function usePlaybackRefresh(grant: PlaybackGrant): ApiError | null {
  const [error, setError] = useState<ApiError | null>(null);
  const { sessionId, expiresAt } = grant;
  useEffect(
    () =>
      startRefreshLoop({
        grant: { sessionId, expiresAt },
        refresh: refreshPlayback,
        onStop: setError,
      }),
    [sessionId, expiresAt],
  );
  return error;
}
