package dev.gumwoo.ott.common.security;

import java.util.concurrent.atomic.AtomicReference;

import dev.gumwoo.ott.common.error.ApiException;
import dev.gumwoo.ott.common.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.ModelAndView;

import static org.assertj.core.api.Assertions.assertThat;

class JobTokenAuthenticationFilterTests {

	private final AtomicReference<Exception> resolved = new AtomicReference<>();

	private final SecurityErrorHandler errorHandler = new SecurityErrorHandler((request, response, handler, ex) -> {
		this.resolved.set(ex);
		return new ModelAndView();
	});

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void verifiedTokenAuthenticatesTranscoder() throws Exception {
		MockFilterChain chain = new MockFilterChain();

		new JobTokenAuthenticationFilter((token) -> token.equals("ok"), this.errorHandler).doFilter(bearer("ok"),
				new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNotNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities()).extracting(Object::toString)
			.containsExactly("ROLE_TRANSCODER");
	}

	@Test
	void reviewFailureStopsTheChainWithItsOwnError() throws Exception {
		MockFilterChain chain = new MockFilterChain();

		new JobTokenAuthenticationFilter((token) -> {
			throw new ApiException(ErrorCode.TOKEN_REVIEW_UNAVAILABLE);
		}, this.errorHandler).doFilter(bearer("any"), new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNull();
		assertThat(this.resolved.get()).isInstanceOfSatisfying(ApiException.class,
				(ex) -> assertThat(ex.code()).isEqualTo(ErrorCode.TOKEN_REVIEW_UNAVAILABLE));
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	private static MockHttpServletRequest bearer(String token) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/jobs/1/heartbeat");
		request.addHeader("Authorization", "Bearer " + token);
		return request;
	}

}
