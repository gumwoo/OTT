import { expect, it } from 'vitest';
import { queryClient } from './queryClient';
import { routes } from './router';

// routes를 불러오는 것만으로 요청이 나가면 MSW가 켜지기 전이라 실제 네트워크로 간다 (TS-0012)
it('importing the routes starts no request', () => {
  expect(routes.length).toBeGreaterThan(0);
  expect(queryClient.getQueryCache().getAll()).toEqual([]);
});
