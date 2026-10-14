package dev.gumwoo.ott.common.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	// Job은 쿠키 없이 Bearer 토큰만 쓴다. 세션 쿠키로는 /internal/*에 들어올 수 없다 (design.md §4)
	@Bean
	@Order(1)
	SecurityFilterChain internalFilterChain(HttpSecurity http, JobTokenVerifier verifier,
			SecurityErrorHandler errorHandler) {
		http.securityMatcher("/internal/**")
			.csrf((csrf) -> csrf.disable())
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.securityContext(
					(context) -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
			.requestCache((cache) -> cache.disable())
			.addFilterBefore(new JobTokenAuthenticationFilter(verifier), AnonymousAuthenticationFilter.class)
			.authorizeHttpRequests((authorize) -> authorize.anyRequest().hasRole("TRANSCODER"))
			.exceptionHandling((exceptions) -> exceptions.authenticationEntryPoint(errorHandler)
				.accessDeniedHandler(errorHandler));
		return http.build();
	}

	@Bean
	SecurityFilterChain apiFilterChain(HttpSecurity http, CookieCsrfTokenRepository csrfTokenRepository,
			SecurityContextRepository securityContextRepository, SecurityErrorHandler errorHandler) {
		// spa()가 저장소를 덮어쓰므로 그 뒤에 지정한다
		http.csrf((csrf) -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
			.securityContext((context) -> context.securityContextRepository(securityContextRepository))
			.requestCache((cache) -> cache.disable())
			.authorizeHttpRequests((authorize) -> authorize.requestMatchers(HttpMethod.GET, "/api/csrf")
				.permitAll()
				.requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login")
				.permitAll()
				.requestMatchers("/actuator/health", "/actuator/health/**", "/error")
				.permitAll()
				.requestMatchers("/api/admin/**")
				.hasRole("ADMIN")
				.requestMatchers("/api/**")
				.authenticated()
				.anyRequest()
				.denyAll())
			.exceptionHandling(
					(exceptions) -> exceptions.authenticationEntryPoint(errorHandler).accessDeniedHandler(errorHandler))
			.logout((logout) -> logout.logoutUrl("/api/auth/logout")
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));
		return http.build();
	}

	@Bean
	CookieCsrfTokenRepository csrfTokenRepository() {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		// 화면 경로에서도 JS가 읽어야 하므로 Path=/ (app-design.md §5 쿠키 경로)
		repository.setCookieCustomizer((cookie) -> cookie.path("/").secure(true).sameSite("Lax"));
		return repository;
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new DelegatingSecurityContextRepository(new RequestAttributeSecurityContextRepository(),
				new HttpSessionSecurityContextRepository());
	}

	@Bean
	LoginSession loginSession(CookieCsrfTokenRepository csrfTokenRepository,
			SecurityContextRepository securityContextRepository) {
		CsrfAuthenticationStrategy csrfStrategy = new CsrfAuthenticationStrategy(csrfTokenRepository);
		csrfStrategy.setRequestHandler(new CsrfTokenRequestAttributeHandler());
		return new LoginSession(new CompositeSessionAuthenticationStrategy(
				List.of(new ChangeSessionIdAuthenticationStrategy(), csrfStrategy)), securityContextRepository);
	}

	@Bean
	AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
			PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
		provider.setPasswordEncoder(passwordEncoder);
		return new ProviderManager(provider);
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

}
