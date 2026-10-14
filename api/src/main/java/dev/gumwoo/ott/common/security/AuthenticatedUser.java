package dev.gumwoo.ott.common.security;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

// 세션 DB에 직렬화되므로 인증 직후 해시를 지운다
public final class AuthenticatedUser implements UserDetails, CredentialsContainer {

	private final long id;

	private final String email;

	private final String role;

	private String passwordHash;

	public AuthenticatedUser(long id, String email, String role, String passwordHash) {
		this.id = id;
		this.email = email;
		this.role = role;
		this.passwordHash = passwordHash;
	}

	public long id() {
		return this.id;
	}

	public String email() {
		return this.email;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority("ROLE_" + this.role));
	}

	@Override
	public String getPassword() {
		return this.passwordHash;
	}

	@Override
	public String getUsername() {
		return this.email;
	}

	@Override
	public void eraseCredentials() {
		this.passwordHash = null;
	}

}
