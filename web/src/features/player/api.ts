import { apiFetch, jsonBody } from '../../lib/api/client';

export type PlaybackGrant = {
  sessionId: string;
  manifestUrl: string;
  expiresAt: string;
};

export type QoeEvent = {
  clientEventId: string;
  sessionId: string;
  kind: 'PERIODIC' | 'FINAL';
  startupMs: number | null;
  watchedMs: number;
  positionSec: number;
  rebufferMs: number;
  rebufferCount: number;
  levelSwitches: number;
  errors: string[];
  measuredAt: string;
};

export function startPlayback(contentId: string): Promise<PlaybackGrant> {
  return apiFetch<PlaybackGrant>(`/api/contents/${encodeURIComponent(contentId)}/playback`, {
    method: 'POST',
  });
}

export function refreshPlayback(sessionId: string): Promise<PlaybackGrant> {
  return apiFetch<PlaybackGrant>(`/api/playback/${sessionId}/refresh`, { method: 'POST' });
}

// sendBeacon은 CSRF 헤더를 못 붙여서 keepalive fetch를 쓴다 (FE-10)
export function sendQoe(events: QoeEvent[], keepalive: boolean): Promise<void> {
  return apiFetch<void>('/api/qoe', { ...jsonBody('POST', { events }), keepalive });
}

const MIN_REMAINING_MS = 10_000;
const FALLBACK_DELAY_MS = 60_000;

// TTL 절반마다 갱신한다 (app-design.md §6).
// 클라이언트 시계가 서버보다 TTL 이상 빠르면 남은 시간이 음수로 보여 연달아 부르게 되므로 1분 간격으로 둔다
export function refreshDelay(expiresAt: string, now: number): number {
  const remaining = Date.parse(expiresAt) - now;
  return remaining < MIN_REMAINING_MS ? FALLBACK_DELAY_MS : remaining / 2;
}
