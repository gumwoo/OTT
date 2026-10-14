import { act, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { problem, server } from '../../test/server';
import { renderApp } from '../../test/renderApp';

type Handler = (event: string, data: unknown) => void;

// 실제 hls.js처럼 destroy()가 미디어를 떼어 내면 재생 위치가 0이 된다
const hls = vi.hoisted(() => {
  const media = { position: 0 };
  const handlers = new Map<string, Handler>();
  return {
    media,
    handlers,
    loadSource: vi.fn(),
    attachMedia: vi.fn(),
    recoverMediaError: vi.fn(),
    destroy: vi.fn(() => {
      media.position = 0;
    }),
  };
});

vi.mock('hls.js', () => ({
  default: class {
    static isSupported = () => true;
    static Events = { LEVEL_SWITCHED: 'hlsLevelSwitched', ERROR: 'hlsError' };
    static ErrorTypes = { MEDIA_ERROR: 'mediaError', NETWORK_ERROR: 'networkError' };
    levels = [{ height: 360 }, { height: 1080 }];
    on = (event: string, handler: Handler) => hls.handlers.set(event, handler);
    loadSource = hls.loadSource;
    attachMedia = hls.attachMedia;
    recoverMediaError = hls.recoverMediaError;
    destroy = hls.destroy;
  },
}));

const grant = {
  sessionId: '6f1c1a9e-8d1f-4a55-9a51-1b7a3f0c2d10',
  manifestUrl: '/content/night-train/v3/master.m3u8',
  expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
};

let reports: { events: Record<string, unknown>[] }[];
let fetchSpy: MockInstance<typeof fetch>;

beforeEach(() => {
  reports = [];
  hls.handlers.clear();
  server.use(
    http.post('*/api/contents/night-train/playback', () => HttpResponse.json(grant)),
    http.post('*/api/qoe', async ({ request }) => {
      reports.push((await request.json()) as { events: Record<string, unknown>[] });
      return new HttpResponse(null, { status: 204 });
    }),
  );
  // MSW가 넘겨주는 Request에는 keepalive가 남지 않아 fetch 인자로 확인한다
  fetchSpy = vi.spyOn(globalThis, 'fetch');
});

afterEach(() => {
  fetchSpy.mockRestore();
});

function emit(event: string, data: unknown) {
  act(() => hls.handlers.get(event)?.(event, data));
}

function qoeKeepaliveFlags(): unknown[] {
  return fetchSpy.mock.calls
    .filter(([url]) => url instanceof URL && url.pathname === '/api/qoe')
    .map(([, init]) => init?.keepalive);
}

describe('player', () => {
  it('reports a final QoE event with position and level switches on leave', async () => {
    const { unmount } = renderApp('/contents/night-train/watch');

    await waitFor(() => expect(hls.loadSource).toHaveBeenCalledWith(grant.manifestUrl));
    emit('hlsLevelSwitched', { level: 0 });
    emit('hlsLevelSwitched', { level: 1 });
    Object.defineProperty(screen.getByLabelText('영상'), 'currentTime', {
      get: () => hls.media.position,
    });
    hls.media.position = 42.7;
    unmount();

    expect(hls.destroy).toHaveBeenCalled();
    await waitFor(() =>
      expect(reports.at(-1)?.events[0]).toMatchObject({
        sessionId: grant.sessionId,
        kind: 'FINAL',
        positionSec: 42,
        levelSwitches: 1,
      }),
    );
    expect(qoeKeepaliveFlags()).toContain(true);
  }, 15_000);

  it('sends a periodic report when the tab is hidden', async () => {
    renderApp('/contents/night-train/watch');
    await waitFor(() => expect(hls.loadSource).toHaveBeenCalled());

    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });

    await waitFor(() => expect(reports.at(-1)?.events[0]).toMatchObject({ kind: 'PERIODIC' }));
    expect(qoeKeepaliveFlags()).toEqual([true]);
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
  });

  it('recovers a media error once and then tells the viewer', async () => {
    renderApp('/contents/night-train/watch');
    await waitFor(() => expect(hls.loadSource).toHaveBeenCalled());
    const fatalMedia = { type: 'mediaError', details: 'bufferStalledError', fatal: true };

    emit('hlsError', fatalMedia);
    expect(hls.recoverMediaError).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    emit('hlsError', fatalMedia);
    expect(await screen.findByRole('alert')).toHaveTextContent('영상을 불러오지 못했습니다');
  });

  it('tells the viewer when the manifest cannot be loaded', async () => {
    renderApp('/contents/night-train/watch');
    await waitFor(() => expect(hls.loadSource).toHaveBeenCalled());

    emit('hlsError', { type: 'networkError', details: 'manifestLoadError', fatal: true });

    expect(await screen.findByRole('alert')).toHaveTextContent('영상을 불러오지 못했습니다');
  });

  it('explains why playback cannot start', async () => {
    server.use(http.post('*/api/contents/harbor/playback', () => problem(403, 'PLAN_REQUIRED')));

    renderApp('/contents/harbor/watch');

    expect(await screen.findByText('PREMIUM 구독이 필요합니다')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '구독 바꾸기' })).toHaveAttribute('href', '/account');
  });
});
