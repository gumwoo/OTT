package dev.gumwoo.ott.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

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

}
