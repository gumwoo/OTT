import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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
    static Events = {
      LEVEL_SWITCHED: 'hlsLevelSwitched',
      FRAG_LOADED: 'hlsFragLoaded',
      FRAG_BUFFERED: 'hlsFragBuffered',
      ERROR: 'hlsError',
    };
    static ErrorTypes = { MEDIA_ERROR: 'mediaError', NETWORK_ERROR: 'networkError' };
    levels = [{ height: 360 }, { height: 1080 }];
    currentLevel = -1;
    // hls.js는 샘플이 없을 때 설정 기본값(500 kbps)을 돌려준다
    bandwidthEstimate = 500_000;
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

// hls.js 기본 로더(XhrLoader)는 networkDetails로 XMLHttpRequest를 넘긴다
function xhrWithHeaders(headers: Record<string, string>): XMLHttpRequest {
  const xhr = Object.create(XMLHttpRequest.prototype) as XMLHttpRequest;
  const lower = Object.fromEntries(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v]));
  Object.defineProperty(xhr, 'getResponseHeader', {
    value: (name: string) => lower[name.toLowerCase()] ?? null,
  });
  return xhr;
}

function denied403(networkDetails: unknown) {
  return {
    type: 'networkError',
    details: 'fragLoadError',
    fatal: true,
    response: { code: 403 },
    networkDetails,
  };
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

  it('shows the edge decision when the region is blocked', async () => {
    renderApp('/contents/night-train/watch');
    await waitFor(() => expect(hls.loadSource).toHaveBeenCalled());

    emit('hlsError', {
      type: 'networkError',
      details: 'manifestLoadError',
      fatal: true,
      response: { code: 403 },
      networkDetails: new Response(null, {
        status: 403,
        headers: { 'X-Playback-Denied': 'GEO_BLOCKED' },
      }),
    });

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('현재 계신 국가에서는 볼 수 없는 작품입니다');
    expect(alert).toHaveTextContent('사유 코드 GEO_BLOCKED');
  });

  it('treats a 403 without a reason as a load failure, not a region block', async () => {
    renderApp('/contents/night-train/watch');
    await waitFor(() => expect(hls.loadSource).toHaveBeenCalled());

    emit('hlsError', {
      type: 'networkError',
      details: 'manifestLoadError',
      fatal: true,
      response: { code: 403 },
      networkDetails: new Response(null, { status: 403 }),
    });

    expect(await screen.findByRole('alert')).toHaveTextContent('영상을 불러오지 못했습니다');
  });

  it.each([
    ['NOT_AVAILABLE', '지금은 볼 수 있는 기간이 아닙니다'],
    ['POLICY_UNAVAILABLE', '재생 정책을 확인하지 못했습니다'],
  ])('reads the %s reason from the XHR response', async (code, message) => {
    renderApp('/contents/night-train/watch');
    await waitFor(() => expect(hls.loadSource).toHaveBeenCalled());

    emit('hlsError', denied403(xhrWithHeaders({ 'X-Playback-Denied': code })));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(message);
    expect(alert).toHaveTextContent(`사유 코드 ${code}`);
  });

  it('does not trust an unknown reason value', async () => {
    renderApp('/contents/night-train/watch');
    await waitFor(() => expect(hls.loadSource).toHaveBeenCalled());

    emit('hlsError', denied403(xhrWithHeaders({ 'X-Playback-Denied': '<b>ALLOWED</b>' })));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('영상을 불러오지 못했습니다');
    expect(alert).not.toHaveTextContent('ALLOWED');
  });

  it('keeps the diagnostics panel away from regular viewers', async () => {
    renderApp('/contents/night-train/watch');
    await waitFor(() => expect(hls.loadSource).toHaveBeenCalled());

    await userEvent.setup().keyboard('{Shift>}D{/Shift}');

    expect(screen.queryByRole('region', { name: '재생 진단' })).not.toBeInTheDocument();
  });

  it('opens the diagnostics panel in developer mode without exposing the full session', async () => {
    localStorage.setItem('stillcut.devtools', '1');
    renderApp('/contents/night-train/watch');
    await waitFor(() => expect(hls.loadSource).toHaveBeenCalled());

    emit('hlsFragLoaded', { networkDetails: xhrWithHeaders({}) });
    await userEvent.setup().keyboard('{Shift>}D{/Shift}');

    const panel = await screen.findByRole('region', { name: '재생 진단' });
    expect(panel).toHaveTextContent('6f1c1a9e…');
    expect(panel).not.toHaveTextContent(grant.sessionId);
    expect(panel).toHaveTextContent('연동 필요');
    expect(panel).toHaveTextContent('/content/night-train/v3/');
  });

  it('shows only measured values in the diagnostics panel', async () => {
    localStorage.setItem('stillcut.devtools', '1');
    renderApp('/contents/night-train/watch');
    await waitFor(() => expect(hls.loadSource).toHaveBeenCalled());
    const user = userEvent.setup();

    await user.keyboard('{Shift>}D{/Shift}');
    const before = await screen.findByRole('region', { name: '재생 진단' });
    expect(before).not.toHaveTextContent('kbps');
    expect(before).not.toHaveTextContent('연동 필요');

    emit('hlsFragBuffered', {});
    emit('hlsFragLoaded', {
      networkDetails: xhrWithHeaders({
        'x-amz-cf-pop': 'ICN57-P1',
        'x-cache': 'Hit from cloudfront',
      }),
    });
    await user.keyboard('{Shift>}D{/Shift}');
    await user.keyboard('{Shift>}D{/Shift}');

    const after = await screen.findByRole('region', { name: '재생 진단' });
    expect(after).toHaveTextContent('500 kbps');
    expect(after).toHaveTextContent('ICN57-P1');
    expect(after).toHaveTextContent('Hit from cloudfront');
  });
});
