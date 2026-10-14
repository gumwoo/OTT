package dev.gumwoo.ott.account;

import dev.gumwoo.ott.common.security.AuthenticatedUser;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountUserDetailsService implements UserDetailsService {

	private final UserRepository users;

	AccountUserDetailsService(UserRepository users) {
		this.users = users;
	}

	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String email) {
		return this.users.findByEmail(User.normalizeEmail(email))
			.map((user) -> new AuthenticatedUser(user.id(), user.email(), user.role().name(), user.passwordHash()))
			.orElseThrow(() -> new UsernameNotFoundException("unknown email"));
	}

}
