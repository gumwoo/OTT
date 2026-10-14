import { useEffect } from 'react';
import { isRouteErrorResponse, Link, useRouteError } from 'react-router';
import { ApiError } from '../lib/api/client';
import { messageFor } from '../lib/api/messages';
import { handleUnauthenticated } from './queryClient';

export function RouteError() {
  const error = useRouteError();
  const message =
    isRouteErrorResponse(error) && error.status === 404 ? '찾을 수 없습니다' : messageFor(error);
  const needsPlan = error instanceof ApiError && error.code === 'PLAN_REQUIRED';

  // 재생 시작처럼 쿼리를 거치지 않는 loader의 401도 로그인 화면으로 보낸다
  useEffect(() => {
    handleUnauthenticated(error);
  }, [error]);

  return (
    <main className="px-6 pt-24 pb-16 md:px-10">
      <p className="text-lg">{message}</p>
      <div className="mt-6 flex gap-4 text-sm">
        {needsPlan && (
          <Link to="/account" className="text-accent underline underline-offset-4">
            구독 바꾸기
          </Link>
        )}
        <Link to="/" className="text-text-muted hover:text-text underline underline-offset-4">
          홈으로
        </Link>
      </div>
    </main>
  );
}
