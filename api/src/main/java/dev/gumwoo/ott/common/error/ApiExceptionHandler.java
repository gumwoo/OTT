package dev.gumwoo.ott.common.error;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
		return handle(new ApiException(ErrorCode.FORBIDDEN), request);
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
		return handle(new ApiException(ErrorCode.UNAUTHENTICATED), request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
		log.error("Unhandled exception", ex);
		return handle(new ApiException(ErrorCode.INTERNAL_ERROR), request);
	}

	private ResponseEntity<Object> handle(ApiException ex, WebRequest request) {
		return handleExceptionInternal(ex, ex.getBody(), new HttpHeaders(), ex.getStatusCode(), request);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
		if (response != null && response.getBody() instanceof ProblemDetail problem) {
			Map<String, Object> properties = problem.getProperties();
			if (properties == null || !properties.containsKey("code")) {
				problem.setProperty("code", codeFor(ex, statusCode).name());
			}
			if (ex instanceof MethodArgumentNotValidException invalid) {
				problem.setProperty("errors", fieldErrors(invalid));
			}
		}
		return response;
	}

	private static ErrorCode codeFor(Exception ex, HttpStatusCode status) {
		if (ex instanceof MethodArgumentNotValidException || ex instanceof HandlerMethodValidationException) {
			return ErrorCode.VALIDATION_FAILED;
		}
		return ErrorCode.forStatus(status);
	}

	private static List<Map<String, String>> fieldErrors(MethodArgumentNotValidException ex) {
		return ex.getFieldErrors()
			.stream()
			.map((error) -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
			.toList();
	}

}
