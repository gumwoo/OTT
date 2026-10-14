import { ApiError } from '../../lib/api/client';
import { refreshDelay, type PlaybackGrant } from './api';

const RETRY_MS = 10_000;

type Options = {
  grant: Pick<PlaybackGrant, 'sessionId' | 'expiresAt'>;
  refresh: (sessionId: string) => Promise<PlaybackGrant>;
  onStop: (error: ApiError) => void;
  now?: () => number;
  setTimer?: (run: () => void, delay: number) => number;
  clearTimer?: (id: number) => void;
};

// 4xx는 등급·소유 문제라 다시 해도 같다. 네트워크 오류와 5xx만 재시도한다
export function startRefreshLoop({
  grant,
  refresh,
  onStop,
  now = Date.now,
  setTimer = (run, delay) => window.setTimeout(run, delay),
  clearTimer = (id) => window.clearTimeout(id),
}: Options): () => void {
  let stopped = false;
  let timer: number | undefined;

  const schedule = (delay: number) => {
    timer = setTimer(() => {
      refresh(grant.sessionId)
        .then((next) => {
          if (!stopped) schedule(refreshDelay(next.expiresAt, now()));
        })
        .catch((error: unknown) => {
          if (stopped) return;
          if (error instanceof ApiError && error.status < 500) {
            onStop(error);
          } else {
            schedule(RETRY_MS);
          }
        });
    }, delay);
  };

  schedule(refreshDelay(grant.expiresAt, now()));
  return () => {
    stopped = true;
    if (timer !== undefined) clearTimer(timer);
  };
}
