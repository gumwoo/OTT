package dev.gumwoo.ott.account.web;

import dev.gumwoo.ott.account.AccountService;
import dev.gumwoo.ott.account.AccountView;
import dev.gumwoo.ott.common.error.ApiException;
import dev.gumwoo.ott.common.error.ErrorCode;
import dev.gumwoo.ott.common.security.AuthenticatedUser;
import dev.gumwoo.ott.common.security.LoginSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuthController {

	private final AccountService accounts;

	private final AuthenticationManager authenticationManager;

	private final LoginSession loginSession;

	AuthController(AccountService accounts, AuthenticationManager authenticationManager, LoginSession loginSession) {
		this.accounts = accounts;
		this.authenticationManager = authenticationManager;
		this.loginSession = loginSession;
	}

	@GetMapping("/api/csrf")
	ResponseEntity<Void> csrf(CsrfToken token) {
		// 지연 토큰이라 읽어야 쿠키가 나간다
		token.getToken();
		return ResponseEntity.noContent().build();
	}

	// role은 DTO에 없어서 보내도 무시된다 (app-design.md §5)
	@PostMapping("/api/auth/signup")
	ResponseEntity<AccountView> signup(@Valid @RequestBody SignupRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(this.accounts.signup(request.email(), request.password()));
	}

	@PostMapping("/api/auth/login")
	AccountView login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) {
		Authentication authentication;
		try {
			authentication = this.authenticationManager
				.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
		}
		catch (AuthenticationException ex) {
			throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
		}
		this.loginSession.establish(authentication, httpRequest, httpResponse);
		return this.accounts.get(((AuthenticatedUser) authentication.getPrincipal()).id());
	}

	record SignupRequest(@NotBlank @Email @Size(max = 320) String email,
			@NotBlank @Size(min = 8, max = 72) String password) {
	}

	record LoginRequest(@NotBlank String email, @NotBlank String password) {
	}

}
