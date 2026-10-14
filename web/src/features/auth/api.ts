import { queryOptions } from '@tanstack/react-query';
import { ApiError, apiFetch, jsonBody, refreshCsrf } from '../../lib/api/client';

export type Plan = 'BASIC' | 'PREMIUM';

export type Subscription = {
  plan: Plan;
  expiresAt: string | null;
};

export type Account = {
  id: number;
  email: string;
  role: 'USER' | 'ADMIN';
  subscription: Subscription;
};

export const authKeys = {
  me: ['auth', 'me'] as const,
};

// 401은 오류가 아니라 로그인 안 한 상태다 (app-design.md §9)
export function meQueryOptions() {
  return queryOptions({
    queryKey: authKeys.me,
    queryFn: async () => {
      try {
        return await apiFetch<Account>('/api/me');
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          return null;
        }
        throw error;
      }
    },
    staleTime: 60_000,
  });
}

export async function login(email: string, password: string): Promise<Account> {
  const account = await apiFetch<Account>('/api/auth/login', jsonBody('POST', { email, password }));
  await refreshCsrf();
  return account;
}

export async function signup(email: string, password: string): Promise<Account> {
  await apiFetch<Account>('/api/auth/signup', jsonBody('POST', { email, password }));
  return login(email, password);
}

export async function logout(): Promise<void> {
  await apiFetch<void>('/api/auth/logout', { method: 'POST' });
  await refreshCsrf();
}

export async function changePlan(plan: Plan): Promise<Subscription> {
  return apiFetch<Subscription>('/api/me/subscription', jsonBody('PUT', { plan }));
}
