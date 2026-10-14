package dev.gumwoo.ott.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

// formLogin이 해 주던 세션 처리를 JSON 로그인에서 직접 한다 (app-design.md §5)
public class LoginSession {

	private final SessionAuthenticationStrategy sessionStrategy;

	private final SecurityContextRepository contextRepository;

	private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

	LoginSession(SessionAuthenticationStrategy sessionStrategy, SecurityContextRepository contextRepository) {
		this.sessionStrategy = sessionStrategy;
		this.contextRepository = contextRepository;
	}

	public void establish(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
		this.sessionStrategy.onAuthentication(authentication, request, response);
		SecurityContext context = this.holder.createEmptyContext();
		context.setAuthentication(authentication);
		this.holder.setContext(context);
		this.contextRepository.saveContext(context, request, response);
	}

}
