package dev.gumwoo.ott.account.web;

import dev.gumwoo.ott.account.AccountService;
import dev.gumwoo.ott.account.AccountView;
import dev.gumwoo.ott.common.security.AuthenticatedUser;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController {

	private final AccountService accounts;

	MeController(AccountService accounts) {
		this.accounts = accounts;
	}

	@GetMapping("/api/me")
	AccountView me(@AuthenticationPrincipal AuthenticatedUser user) {
		return this.accounts.get(user.id());
	}

}
