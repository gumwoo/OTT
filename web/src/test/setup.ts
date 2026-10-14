import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, vi } from 'vitest';
import { queryClient } from '../app/queryClient';
import { reloadAt } from '../lib/navigation';
import { server } from './server';

// jsdom은 페이지 이동을 못 한다. queryClient보다 먼저 바꿔야 하므로 여기서 대체한다
vi.mock('../lib/navigation', () => ({ reloadAt: vi.fn() }));

// 첫 라우트 로딩이 jsdom에서 1초(기본값)를 넘길 때가 있다
configure({ asyncUtilTimeout: 5_000 });

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
  // jsdom에는 미디어 재생이 없다
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
    },
  );
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  queryClient.clear();
  vi.mocked(reloadAt).mockClear();
  localStorage.clear();
});

afterAll(() => server.close());
