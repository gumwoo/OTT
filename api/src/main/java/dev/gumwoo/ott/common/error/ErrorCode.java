package dev.gumwoo.ott.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다"),

	BAD_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다"),

	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),

	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"),

	CSRF_INVALID(HttpStatus.FORBIDDEN, "요청을 확인할 수 없습니다. 다시 시도해 주세요"),

	FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),

	PLAN_REQUIRED(HttpStatus.FORBIDDEN, "더 높은 구독 등급이 필요합니다"),

	NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없습니다"),

	NOT_PUBLISHED(HttpStatus.NOT_FOUND, "아직 공개되지 않은 콘텐츠입니다"),

	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다"),

	NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "지원하지 않는 응답 형식입니다"),

	EMAIL_TAKEN(HttpStatus.CONFLICT, "이미 가입된 이메일입니다"),

	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다"),

	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다");

	private final HttpStatus status;

	private final String defaultMessage;

	ErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus status() {
		return this.status;
	}

	public String defaultMessage() {
		return this.defaultMessage;
	}

	static ErrorCode forStatus(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> BAD_REQUEST;
			case 401 -> UNAUTHENTICATED;
			case 403 -> FORBIDDEN;
			case 404 -> NOT_FOUND;
			case 405 -> METHOD_NOT_ALLOWED;
			case 406 -> NOT_ACCEPTABLE;
			case 415 -> UNSUPPORTED_MEDIA_TYPE;
			default -> status.is4xxClientError() ? BAD_REQUEST : INTERNAL_ERROR;
		};
	}

}
