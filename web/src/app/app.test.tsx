import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { basicAccount, problem, server } from '../test/server';
import { renderApp } from '../test/renderApp';

describe('authentication', () => {
  it('sends anonymous visitors to the login page', async () => {
    server.use(http.get('*/api/me', () => problem(401, 'UNAUTHENTICATED')));

    const { router } = renderApp('/');

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
  });

  it('shows the message for the problem code on failed login', async () => {
    server.use(
      http.get('*/api/me', () => problem(401, 'UNAUTHENTICATED')),
      http.post('*/api/auth/login', () => problem(401, 'INVALID_CREDENTIALS')),
    );
    renderApp('/login');
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText('이메일'), 'viewer@example.com');
    await user.type(screen.getByLabelText('비밀번호'), 'wrong-password');
    await user.click(screen.getByRole('button', { name: '로그인' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '이메일 또는 비밀번호가 올바르지 않습니다',
    );
  });
});

describe('catalog', () => {
  it('features the first title and keeps premium titles in their own row', async () => {
    renderApp('/');

    expect(await screen.findByRole('heading', { level: 1, name: '밤기차' })).toBeInTheDocument();
    const premiumRow = screen.getByRole('region', { name: 'PREMIUM 작품' });
    expect(within(premiumRow).getByText('항구')).toBeInTheDocument();
    expect(within(premiumRow).getByText('PREMIUM')).toBeInTheDocument();
  });

  it('says so when nothing is published', async () => {
    server.use(http.get('*/api/contents', () => HttpResponse.json([])));

    renderApp('/');

    expect(await screen.findByText('아직 공개된 작품이 없습니다')).toBeInTheDocument();
  });
});

describe('content detail', () => {
  it('offers a plan change instead of play when the plan is too low', async () => {
    server.use(
      http.get('*/api/contents/harbor', () =>
        HttpResponse.json({
          id: 'harbor',
          title: '항구',
          requiredPlan: 'PREMIUM',
          durationSec: 5400,
          description: '',
          playable: false,
        }),
      ),
    );

    renderApp('/contents/harbor');

    expect(await screen.findByRole('link', { name: '구독 바꾸기' })).toHaveAttribute(
      'href',
      '/account',
    );
    expect(screen.queryByRole('link', { name: '재생' })).not.toBeInTheDocument();
  });

  it('shows not found for unknown content', async () => {
    server.use(http.get('*/api/contents/missing', () => problem(404, 'NOT_FOUND')));

    renderApp('/contents/missing');

    expect(await screen.findByText('찾을 수 없습니다')).toBeInTheDocument();
  });
});

describe('account', () => {
  it('labels the subscription as mock and switches the plan', async () => {
    let requested: unknown;
    let plan: 'BASIC' | 'PREMIUM' = 'BASIC';
    server.use(
      http.get('*/api/me', () =>
        HttpResponse.json({ ...basicAccount, subscription: { plan, expiresAt: null } }),
      ),
      http.put('*/api/me/subscription', async ({ request }) => {
        requested = await request.json();
        plan = 'PREMIUM';
        return HttpResponse.json({ plan, expiresAt: '2026-11-13T00:00:00Z' });
      }),
    );
    renderApp('/account');

    expect(await screen.findByText(/모의 구독입니다/)).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'PREMIUM로 바꾸기' }));

    await waitFor(() => expect(requested).toEqual({ plan: 'PREMIUM' }));
    expect(await screen.findByRole('button', { name: 'BASIC로 바꾸기' })).toBeInTheDocument();
  });
});
