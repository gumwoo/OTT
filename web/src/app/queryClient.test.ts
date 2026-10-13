import { describe, expect, it } from 'vitest';
import { ApiError } from '../lib/api/client';
import { shouldRetry } from './queryClient';

describe('shouldRetry', () => {
  it('does not retry 4xx', () => {
    expect(shouldRetry(0, new ApiError(403, 'PLAN_REQUIRED'))).toBe(false);
  });

  it('retries 5xx and network errors up to the limit', () => {
    expect(shouldRetry(0, new ApiError(503, 'API_MAINTENANCE'))).toBe(true);
    expect(shouldRetry(1, new TypeError('Failed to fetch'))).toBe(true);
    expect(shouldRetry(2, new TypeError('Failed to fetch'))).toBe(false);
  });
});
