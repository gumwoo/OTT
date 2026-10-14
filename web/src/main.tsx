import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router/dom';
import { queryClient } from './app/queryClient';
import { router } from './app/router';
import './index.css';
import { refreshCsrf } from './lib/api/client';

const root = document.getElementById('root');
if (!root) {
  throw new Error('#root not found');
}

// 로그인 화면도 POST를 보내므로 시작할 때 토큰을 받는다 (app-design.md §5)
await refreshCsrf().catch(() => undefined);

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
