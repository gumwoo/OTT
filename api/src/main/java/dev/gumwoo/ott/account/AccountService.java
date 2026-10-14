package dev.gumwoo.ott.account;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import dev.gumwoo.ott.common.error.ApiException;
import dev.gumwoo.ott.common.error.ErrorCode;
import dev.gumwoo.ott.subscription.SubscriptionService;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

	// BCrypt는 72바이트까지만 본다
	private static final int MAX_PASSWORD_BYTES = 72;

	private final UserRepository users;

	private final SubscriptionService subscriptions;

	private final PasswordEncoder passwordEncoder;

	private final Clock clock;

	AccountService(UserRepository users, SubscriptionService subscriptions, PasswordEncoder passwordEncoder,
			Clock clock) {
		this.users = users;
		this.subscriptions = subscriptions;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	@Transactional
	public AccountView signup(String email, String password) {
		String normalized = User.normalizeEmail(email);
		if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "비밀번호가 너무 깁니다");
		}
		if (this.users.existsByEmail(normalized)) {
			throw new ApiException(ErrorCode.EMAIL_TAKEN);
		}
		User user;
		try {
			user = this.users.saveAndFlush(
					new User(normalized, this.passwordEncoder.encode(password), Role.USER, this.clock.instant()));
		}
		catch (DataIntegrityViolationException ex) {
			throw new ApiException(ErrorCode.EMAIL_TAKEN);
		}
		this.subscriptions.startBasic(user.id());
		return view(user);
	}

	@Transactional(readOnly = true)
	public AccountView get(long userId) {
		return view(this.users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED)));
	}

	@Transactional
	public boolean createAdminIfAbsent(String email, String password) {
		String normalized = User.normalizeEmail(email);
		boolean created = this.users.insertAdminIfAbsent(normalized, this.passwordEncoder.encode(password)) > 0;
		User user = this.users.findByEmail(normalized).orElseThrow();
		if (user.role() != Role.ADMIN) {
			throw new IllegalStateException("Admin email is already used by a regular account");
		}
		this.subscriptions.startBasicIfAbsent(user.id());
		return created;
	}

	private AccountView view(User user) {
		return new AccountView(user.id(), user.email(), user.role(), this.subscriptions.get(user.id()));
	}

}
