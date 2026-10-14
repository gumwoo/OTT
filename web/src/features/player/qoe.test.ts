import { describe, expect, it } from 'vitest';
import { refreshDelay } from './api';
import { createQoeTracker } from './qoe';

describe('createQoeTracker', () => {
  it('measures startup until the first playing event', () => {
    const qoe = createQoeTracker(1_000);
    qoe.waiting(1_200);
    qoe.playing(1_800);

    expect(qoe.snapshot(2_800)).toMatchObject({
      startupMs: 800,
      watchedMs: 1_000,
      rebufferMs: 0,
      rebufferCount: 0,
    });
  });

  it('counts rebuffering after playback started, including an ongoing stall', () => {
    const qoe = createQoeTracker(0);
    qoe.playing(100);
    qoe.waiting(1_000);
    qoe.playing(1_500);
    qoe.waiting(2_000);

    expect(qoe.snapshot(2_300)).toMatchObject({ rebufferMs: 800, rebufferCount: 2 });
  });

  it('counts only actual level changes', () => {
    const qoe = createQoeTracker(0);
    qoe.levelSwitched('720p');
    qoe.levelSwitched('720p');
    qoe.levelSwitched('1080p');

    expect(qoe.snapshot(0).levelSwitches).toBe(1);
  });

  it('caps the number and length of errors', () => {
    const qoe = createQoeTracker(0);
    for (let i = 0; i < 30; i++) {
      qoe.error('x'.repeat(300));
    }

    const { errors } = qoe.snapshot(0);
    expect(errors).toHaveLength(20);
    expect(errors[0]).toHaveLength(200);
  });
});

describe('refreshDelay', () => {
  it('refreshes at half of the remaining time', () => {
    expect(refreshDelay('2026-10-14T00:30:00Z', Date.parse('2026-10-14T00:00:00Z'))).toBe(900_000);
  });

  it('never schedules sooner than five seconds', () => {
    expect(refreshDelay('2026-10-14T00:00:01Z', Date.parse('2026-10-14T00:00:00Z'))).toBe(5_000);
  });
});
