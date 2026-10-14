import { ApiError } from './client';

// 화면은 code로만 분기한다. detail은 서버 문구라 여기 없는 코드일 때만 쓴다 (app-design.md §6)
const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: '이메일 또는 비밀번호가 올바르지 않습니다',
  EMAIL_TAKEN: '이미 가입된 이메일입니다',
  VALIDATION_FAILED: '입력값을 확인해 주세요',
  PLAN_REQUIRED: 'PREMIUM 구독이 필요합니다',
  NOT_PUBLISHED: '아직 공개되지 않은 작품입니다',
  NOT_FOUND: '찾을 수 없습니다',
  UNAUTHENTICATED: '다시 로그인해 주세요',
};

export function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status >= 500) {
      return '서버에 문제가 생겼습니다. 잠시 후 다시 시도해 주세요';
    }
    return MESSAGES[error.code] ?? error.detail ?? '요청을 처리하지 못했습니다';
  }
  return '연결에 문제가 있습니다. 잠시 후 다시 시도해 주세요';
}
