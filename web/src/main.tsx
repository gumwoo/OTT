import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { queryClient } from './app/queryClient';
import { routes } from './app/router';
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css';
import './index.css';
import { refreshCsrf } from './lib/api/client';
import { syncDevModeFromUrl } from './lib/devMode';

syncDevModeFromUrl(window.location.search);

const root = document.getElementById('root');
if (!root) {
  throw new Error('#root not found');
}

// 로그인 화면도 POST를 보내므로 시작할 때 토큰을 받는다 (app-design.md §5).
// 늦게 와도 apiFetch가 CSRF_INVALID를 받으면 다시 받아 한 번 재시도한다
void refreshCsrf().catch(() => undefined);

// 만드는 즉시 첫 이동(loader·middleware)이 돈다. router.tsx에서 만들면 routes를 불러오는 테스트도 실제 요청을 보낸다 (TS-0012)
const router = createBrowserRouter(routes);

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
