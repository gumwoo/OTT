package dev.gumwoo.ott.account;

import dev.gumwoo.ott.subscription.SubscriptionView;

public record AccountView(long id, String email, Role role, SubscriptionView subscription) {
}
