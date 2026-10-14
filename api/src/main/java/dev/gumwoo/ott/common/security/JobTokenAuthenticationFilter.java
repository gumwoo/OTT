package dev.gumwoo.ott.common.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// 토큰이 틀리면 인증만 비워 두고, 401은 인가 단계의 entry point가 쓴다
class JobTokenAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	private final JobTokenVerifier verifier;

	JobTokenAuthenticationFilter(JobTokenVerifier verifier) {
		this.verifier = verifier;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith(BEARER) && this.verifier.verify(header.substring(BEARER.length()))) {
			SecurityContextHolder.getContext()
				.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("transcoder", null,
						List.of(new SimpleGrantedAuthority("ROLE_TRANSCODER"))));
		}
		chain.doFilter(request, response);
	}

}
