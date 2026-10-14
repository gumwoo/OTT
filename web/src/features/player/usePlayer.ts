import Hls from 'hls.js';
import { useEffect, useRef, useState, type RefObject } from 'react';
import { ApiError } from '../../lib/api/client';
import { refreshDelay, refreshPlayback, sendQoe, type PlaybackGrant, type QoeEvent } from './api';
import { createQoeTracker, type QoeTracker } from './qoe';

const REPORT_INTERVAL_MS = 30_000;
const RETRY_MS = 10_000;

export function usePlayer(video: RefObject<HTMLVideoElement | null>, grant: PlaybackGrant) {
  const tracker = useRef<QoeTracker | null>(null);
  // 정리는 선언 순서대로 돈다. hls.destroy()가 재생 위치를 0으로 돌리기 전에 FINAL을 보내야 한다
  useQoeReports(video, grant.sessionId, tracker);
  useHls(video, grant.manifestUrl, tracker);
  return usePlaybackRefresh(grant);
}

// FE-09: 인스턴스는 effect 안에서 만들고 cleanup에서 destroy. StrictMode의 두 번 생성은 정상이다
function useHls(
  videoRef: RefObject<HTMLVideoElement | null>,
  manifestUrl: string,
  trackerRef: RefObject<QoeTracker | null>,
) {
  useEffect(() => {
    const video = videoRef.current;
    if (!video) {
      return;
    }
    const tracker = createQoeTracker(performance.now());
    trackerRef.current = tracker;
    const onPlaying = () => tracker.playing(performance.now());
    const onWaiting = () => tracker.waiting(performance.now());
    video.addEventListener('playing', onPlaying);
    video.addEventListener('waiting', onWaiting);

    let hls: Hls | null = null;
    if (Hls.isSupported()) {
      const instance = new Hls();
      instance.on(Hls.Events.LEVEL_SWITCHED, (_event, data) => {
        const level = instance.levels[data.level];
        tracker.levelSwitched(level ? `${level.height}p` : String(data.level));
      });
      instance.on(Hls.Events.ERROR, (_event, data) => {
        tracker.error(`${data.type}:${data.details}${data.fatal ? ':fatal' : ''}`);
      });
      instance.loadSource(manifestUrl);
      instance.attachMedia(video);
      hls = instance;
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      // Safari 네이티브 HLS. 화질 전환은 못 잰다
      video.src = manifestUrl;
    } else {
      tracker.error('hls-unsupported');
    }
    video.play().catch((error: unknown) => {
      tracker.error(`play:${error instanceof Error ? error.name : 'unknown'}`);
    });

    return () => {
      video.removeEventListener('playing', onPlaying);
      video.removeEventListener('waiting', onWaiting);
      hls?.destroy();
    };
  }, [videoRef, manifestUrl, trackerRef]);
}

// 재생 중에는 주기적으로, 떠날 때 FINAL을 한 번 보낸다 (app-design.md §4 qoe_events)
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
    const timer = window.setInterval(() => report('PERIODIC', false), REPORT_INTERVAL_MS);
    document.addEventListener('visibilitychange', onVisibilityChange);
    window.addEventListener('pagehide', final);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibilityChange);
      window.removeEventListener('pagehide', final);
      final();
    };
  }, [videoRef, sessionId, trackerRef]);
}

function usePlaybackRefresh(grant: PlaybackGrant): ApiError | null {
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    let cancelled = false;
    let timer: number | undefined;
    const schedule = (delay: number) => {
      timer = window.setTimeout(() => {
        refreshPlayback(grant.sessionId)
          .then((next) => {
            if (!cancelled) schedule(refreshDelay(next.expiresAt, Date.now()));
          })
          .catch((reason: unknown) => {
            if (cancelled) return;
            // 4xx는 등급·소유 문제라 다시 시도해도 같다. 네트워크·5xx만 재시도
            if (reason instanceof ApiError && reason.status < 500) {
              setError(reason);
            } else {
              schedule(RETRY_MS);
            }
          });
      }, delay);
    };
    schedule(refreshDelay(grant.expiresAt, Date.now()));
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [grant.sessionId, grant.expiresAt]);

  return error;
}
