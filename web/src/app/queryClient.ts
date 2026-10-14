import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { ApiError } from '../lib/api/client';
import { reloadAt } from '../lib/navigation';

const MAX_RETRIES = 2;

export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status < 500) {
    return false;
  }
  return failureCount < MAX_RETRIES;
}

// 세션이 만료되면 캐시에 남은 내 정보로는 로그인 화면에 갈 수 없으므로 비우고 새로 연다.
// 새로 열면 CSRF 토큰도 다시 받는다 (app-design.md §5 CSRF 흐름 4)
export function handleUnauthenticated(error: unknown): boolean {
  if (!(error instanceof ApiError && error.code === 'UNAUTHENTICATED')) {
    return false;
  }
  queryClient.clear();
  reloadAt('/login');
  return true;
}

export const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError: handleUnauthenticated }),
  mutationCache: new MutationCache({ onError: handleUnauthenticated }),
  defaultOptions: {
    queries: { retry: shouldRetry },
  },
});
