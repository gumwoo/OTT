import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch } from './client';

function mockFetch(status: number, body: unknown) {
  const fn = vi.fn(() => Promise.resolve(Response.json(body, { status })));
  vi.stubGlobal('fetch', fn);
  return fn;
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
});

describe('apiFetch', () => {
  it('returns the body on 2xx', async () => {
    mockFetch(200, { id: 'movie-100' });
    await expect(apiFetch('/api/contents/movie-100')).resolves.toEqual({ id: 'movie-100' });
  });

  it('throws ApiError with the problem code', async () => {
    mockFetch(403, { code: 'PLAN_REQUIRED', detail: 'PREMIUM 구독이 필요합니다' });
    const error = await apiFetch('/api/contents/movie-100/playback').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 403, code: 'PLAN_REQUIRED' });
  });

  it('sends the CSRF header only on unsafe methods', async () => {
    document.cookie = 'XSRF-TOKEN=abc';
    const fn = mockFetch(200, {});
    await apiFetch('/api/me');
    await apiFetch('/api/auth/logout', { method: 'POST' });
    const headers = fn.mock.calls.map(
      (call) => (call as unknown as [string, RequestInit])[1].headers as Headers,
    );
    expect(headers[0]?.get('X-XSRF-TOKEN')).toBeNull();
    expect(headers[1]?.get('X-XSRF-TOKEN')).toBe('abc');
  });
});
