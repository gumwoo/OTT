package dev.gumwoo.ott.common.error;

import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class ApiException extends ErrorResponseException {

	private final ErrorCode code;

	public ApiException(ErrorCode code) {
		this(code, code.defaultMessage());
	}

	public ApiException(ErrorCode code, String detail) {
		super(code.status(), problem(code, detail), null);
		this.code = code;
	}

	public ErrorCode code() {
		return this.code;
	}

	private static ProblemDetail problem(ErrorCode code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
		problem.setProperty("code", code.name());
		return problem;
	}

}
