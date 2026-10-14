import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { problem, server } from '../../test/server';
import { renderApp } from '../../test/renderApp';

// 실제 hls.js처럼 destroy()가 미디어를 떼어 내면 재생 위치가 0이 된다
const hls = vi.hoisted(() => {
  const media = { position: 0 };
  return {
    media,
    loadSource: vi.fn(),
    attachMedia: vi.fn(),
    destroy: vi.fn(() => {
      media.position = 0;
    }),
  };
});

vi.mock('hls.js', () => ({
  default: class {
    static isSupported = () => true;
    static Events = { LEVEL_SWITCHED: 'hlsLevelSwitched', ERROR: 'hlsError' };
    levels = [];
    on = vi.fn();
    loadSource = hls.loadSource;
    attachMedia = hls.attachMedia;
    destroy = hls.destroy;
  },
}));

const grant = {
  sessionId: '6f1c1a9e-8d1f-4a55-9a51-1b7a3f0c2d10',
  manifestUrl: '/content/night-train/v3/master.m3u8',
  expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
};

describe('player', () => {
  it('loads the granted manifest and reports a final QoE event on leave', async () => {
    const reports: unknown[] = [];
    server.use(
      http.post('*/api/contents/night-train/playback', () => HttpResponse.json(grant)),
      http.post('*/api/qoe', async ({ request }) => {
        reports.push(await request.json());
        return new HttpResponse(null, { status: 204 });
      }),
    );
    // MSW가 넘겨주는 Request에는 keepalive가 남지 않아 fetch 인자로 확인한다
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const { unmount } = renderApp('/contents/night-train/watch');

    await waitFor(() => expect(hls.loadSource).toHaveBeenCalledWith(grant.manifestUrl));
    Object.defineProperty(screen.getByLabelText('영상'), 'currentTime', {
      get: () => hls.media.position,
    });
    hls.media.position = 42.7;
    unmount();

    expect(hls.destroy).toHaveBeenCalled();
    await waitFor(() =>
      expect(reports).toContainEqual({
        events: [
          expect.objectContaining({ sessionId: grant.sessionId, kind: 'FINAL', positionSec: 42 }),
        ],
      }),
    );
    const qoeCall = fetchSpy.mock.calls.find(
      ([url]) => url instanceof URL && url.pathname === '/api/qoe',
    );
    expect(qoeCall?.[1]).toMatchObject({ keepalive: true });
  }, 15_000);

  it('explains why playback cannot start', async () => {
    server.use(http.post('*/api/contents/harbor/playback', () => problem(403, 'PLAN_REQUIRED')));

    renderApp('/contents/harbor/watch');

    expect(await screen.findByText('PREMIUM 구독이 필요합니다')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '구독 바꾸기' })).toHaveAttribute('href', '/account');
  });
});
