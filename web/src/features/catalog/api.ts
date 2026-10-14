import { queryOptions } from '@tanstack/react-query';
import { apiFetch } from '../../lib/api/client';
import type { Plan } from '../auth/api';

export type CatalogItem = {
  id: string;
  title: string;
  requiredPlan: Plan;
  durationSec: number;
};

export type ContentDetail = CatalogItem & {
  description: string;
  playable: boolean;
};

export const catalogKeys = {
  all: ['contents'] as const,
  list: () => [...catalogKeys.all, 'list'] as const,
  detail: (id: string) => [...catalogKeys.all, 'detail', id] as const,
};

export function catalogQueryOptions() {
  return queryOptions({
    queryKey: catalogKeys.list(),
    queryFn: () => apiFetch<CatalogItem[]>('/api/contents'),
  });
}

export function contentQueryOptions(id: string) {
  return queryOptions({
    queryKey: catalogKeys.detail(id),
    queryFn: () => apiFetch<ContentDetail>(`/api/contents/${encodeURIComponent(id)}`),
  });
}

export function formatDuration(seconds: number): string {
  const minutes = Math.max(1, Math.round(seconds / 60));
  if (minutes < 60) {
    return `${minutes}분`;
  }
  const rest = minutes % 60;
  return rest === 0 ? `${minutes / 60}시간` : `${Math.floor(minutes / 60)}시간 ${rest}분`;
}
