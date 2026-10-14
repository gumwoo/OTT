package dev.gumwoo.ott.subscription.web;

import dev.gumwoo.ott.common.security.AuthenticatedUser;
import dev.gumwoo.ott.subscription.Plan;
import dev.gumwoo.ott.subscription.SubscriptionService;
import dev.gumwoo.ott.subscription.SubscriptionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SubscriptionController {

	private final SubscriptionService subscriptions;

	SubscriptionController(SubscriptionService subscriptions) {
		this.subscriptions = subscriptions;
	}

	@PutMapping("/api/me/subscription")
	SubscriptionView change(@AuthenticationPrincipal AuthenticatedUser user,
			@Valid @RequestBody ChangeRequest request) {
		return this.subscriptions.change(user.id(), request.plan());
	}

	record ChangeRequest(@NotNull Plan plan) {
	}

}
