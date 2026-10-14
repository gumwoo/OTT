import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

export const basicAccount = {
  id: 1,
  email: 'viewer@example.com',
  role: 'USER',
  subscription: { plan: 'BASIC', expiresAt: null },
} as const;

export const catalog = [
  { id: 'night-train', title: '밤기차', requiredPlan: 'BASIC', durationSec: 734 },
  { id: 'harbor', title: '항구', requiredPlan: 'PREMIUM', durationSec: 5400 },
];

export function problem(status: number, code: string) {
  return HttpResponse.json({ status, code, detail: code }, { status });
}

// 기본은 로그인한 BASIC 사용자. 테스트마다 server.use로 덮는다
export const server = setupServer(
  http.get('*/api/csrf', () => new HttpResponse(null, { status: 204 })),
  http.get('*/api/me', () => HttpResponse.json(basicAccount)),
  http.get('*/api/contents', () => HttpResponse.json(catalog)),
);
