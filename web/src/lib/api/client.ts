export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly detail: string | undefined;

  constructor(status: number, code: string, detail?: string) {
    super(`${status} ${code}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.detail = detail;
  }
}

const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

function csrfToken(): string | undefined {
  const match = /(?:^|;\s*)XSRF-TOKEN=([^;]+)/.exec(document.cookie);
  return match?.[1] ? decodeURIComponent(match[1]) : undefined;
}

export function jsonBody(method: 'POST' | 'PUT', body: unknown): RequestInit {
  return { method, body: JSON.stringify(body) };
}

// 로그인·로그아웃 뒤에는 서버가 토큰 쿠키를 지우므로 다시 받는다 (app-design.md §5)
export async function refreshCsrf(): Promise<void> {
  await apiFetch<void>('/api/csrf');
}

export async function apiFetch<T>(
  path: string,
  init: RequestInit = {},
  retried = false,
): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const token = csrfToken();
  if (UNSAFE_METHODS.has(method) && token) {
    headers.set('X-XSRF-TOKEN', token);
  }

  const res = await fetch(new URL(path, window.location.origin), {
    ...init,
    method,
    headers,
    credentials: 'same-origin',
  });
  if (!res.ok) {
    const problem = (await res.json().catch(() => ({}))) as { code?: string; detail?: string };
    const error = new ApiError(res.status, problem.code ?? 'UNKNOWN', problem.detail);
    if (error.code === 'CSRF_INVALID' && UNSAFE_METHODS.has(method) && !retried) {
      await refreshCsrf();
      return apiFetch<T>(path, init, true);
    }
    throw error;
  }
  if (res.status === 204) {
    return undefined as T;
  }
  return (await res.json()) as T;
}
