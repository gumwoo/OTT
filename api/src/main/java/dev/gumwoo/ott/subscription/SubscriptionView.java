package dev.gumwoo.ott.subscription;

import java.time.Instant;

public record SubscriptionView(Plan plan, Instant expiresAt) {
}
