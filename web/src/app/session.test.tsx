import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { catalogKeys } from '../features/catalog/api';
import { reloadAt } from '../lib/navigation';
import { basicAccount, catalog, problem, server } from '../test/server';
import { renderApp } from '../test/renderApp';
import { queryClient } from './queryClient';

describe('session', () => {
  it('goes to a fresh login page when the session expired', async () => {
    server.use(http.get('*/api/contents', () => problem(401, 'UNAUTHENTICATED')));

    renderApp('/');

    await waitFor(() => expect(reloadAt).toHaveBeenCalledWith('/login'));
    expect(queryClient.getQueryData(catalogKeys.list())).toBeUndefined();
  });

  it('drops the previous viewer data after logging in', async () => {
    let anonymous = true;
    let catalogRequests = 0;
    server.use(
      http.get('*/api/me', () =>
        anonymous ? problem(401, 'UNAUTHENTICATED') : HttpResponse.json(basicAccount),
      ),
      http.post('*/api/auth/login', () => {
        anonymous = false;
        return HttpResponse.json(basicAccount);
      }),
      http.get('*/api/contents', () => {
        catalogRequests += 1;
        return HttpResponse.json(catalog);
      }),
    );
    queryClient.setQueryData(catalogKeys.list(), [
      { id: 'stale', title: '이전 사용자', requiredPlan: 'PREMIUM', durationSec: 60 },
    ]);
    renderApp('/login');
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText('이메일'), basicAccount.email);
    await user.type(screen.getByLabelText('비밀번호'), 'correct-horse-1');
    await user.click(screen.getByRole('button', { name: '로그인' }));

    expect(await screen.findByRole('heading', { level: 1, name: '밤기차' })).toBeInTheDocument();
    expect(screen.queryByText('이전 사용자')).not.toBeInTheDocument();
    expect(catalogRequests).toBe(1);
  });

  it('shows a server error message for 5xx', async () => {
    server.use(http.get('*/api/contents', () => problem(500, 'INTERNAL_ERROR')));

    renderApp('/');

    expect(
      await screen.findByText('서버에 문제가 생겼습니다. 잠시 후 다시 시도해 주세요'),
    ).toBeInTheDocument();
  });
});
