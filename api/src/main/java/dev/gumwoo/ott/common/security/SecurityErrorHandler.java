package dev.gumwoo.ott.common.security;

import java.io.IOException;

import dev.gumwoo.ott.common.error.ApiException;
import dev.gumwoo.ott.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

// 필터 단계 401·403도 advice로 보내 같은 ProblemDetail 형식을 쓴다 (app-design.md §5)
@Component
class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final HandlerExceptionResolver resolver;

	SecurityErrorHandler(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
		this.resolver = resolver;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		write(request, response, ErrorCode.UNAUTHENTICATED);
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		write(request, response,
				(accessDeniedException instanceof CsrfException) ? ErrorCode.CSRF_INVALID : ErrorCode.FORBIDDEN);
	}

	private void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
		if (this.resolver.resolveException(request, response, null, new ApiException(code)) == null) {
			response.sendError(code.status().value());
		}
	}

}
