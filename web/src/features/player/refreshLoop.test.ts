import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../lib/api/client';
import type { PlaybackGrant } from './api';
import { startRefreshLoop } from './refreshLoop';

const NOW = Date.parse('2026-10-14T00:00:00Z');

function grantExpiringIn(minutes: number): PlaybackGrant {
  return {
    sessionId: 's1',
    manifestUrl: '/content/a/v1/master.m3u8',
    expiresAt: new Date(NOW + minutes * 60_000).toISOString(),
  };
}

// 타이머를 직접 돌려서 예약 간격과 순서를 본다
function harness(refresh: () => Promise<PlaybackGrant>) {
  const timers: { run: () => void; delay: number }[] = [];
  const onStop = vi.fn();
  const stop = startRefreshLoop({
    grant: grantExpiringIn(30),
    refresh,
    onStop,
    now: () => NOW,
    setTimer: (run, delay) => timers.push({ run, delay }),
    clearTimer: vi.fn(),
  });
  const fire = async () => {
    timers.at(-1)?.run();
    await vi.waitFor(() => expect(refresh).toHaveBeenCalled());
    await Promise.resolve();
    await Promise.resolve();
  };
  return { timers, onStop, stop, fire };
}

describe('startRefreshLoop', () => {
  it('first refresh is at half of the TTL and reschedules from the new expiry', async () => {
    const refresh = vi.fn(() => Promise.resolve(grantExpiringIn(20)));
    const { timers, fire } = harness(refresh);

    expect(timers[0]?.delay).toBe(15 * 60_000);
    await fire();
    expect(timers[1]?.delay).toBe(10 * 60_000);
  });

  it('stops and reports on 4xx', async () => {
    const refresh = vi.fn(() => Promise.reject(new ApiError(403, 'PLAN_REQUIRED')));
    const { timers, onStop, fire } = harness(refresh);

    await fire();

    expect(onStop).toHaveBeenCalledWith(expect.objectContaining({ code: 'PLAN_REQUIRED' }));
    expect(timers).toHaveLength(1);
  });

  it.each([
    ['5xx', new ApiError(503, 'INTERNAL_ERROR')],
    ['network error', new TypeError('Failed to fetch')],
  ])('retries after %s', async (_name, error) => {
    const refresh = vi.fn(() => Promise.reject(error));
    const { timers, onStop, fire } = harness(refresh);

    await fire();

    expect(onStop).not.toHaveBeenCalled();
    expect(timers[1]?.delay).toBe(10_000);
  });

  it('does nothing after it is stopped', async () => {
    const refresh = vi.fn(() => Promise.reject(new ApiError(403, 'PLAN_REQUIRED')));
    const { onStop, stop, fire } = harness(refresh);

    stop();
    await fire();

    expect(onStop).not.toHaveBeenCalled();
  });
});
