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

// TTL 절반마다 갱신한다 (app-design.md §6)
export function refreshDelay(expiresAt: string, now: number): number {
  return Math.max(5_000, (Date.parse(expiresAt) - now) / 2);
}
