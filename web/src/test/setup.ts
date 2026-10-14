import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, vi } from 'vitest';
import { queryClient } from '../app/queryClient';
import { server } from './server';

// 첫 라우트 로딩이 jsdom에서 1초(기본값)를 넘길 때가 있다
configure({ asyncUtilTimeout: 5_000 });

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
  // jsdom에는 미디어 재생이 없다
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  queryClient.clear();
});

afterAll(() => server.close());
