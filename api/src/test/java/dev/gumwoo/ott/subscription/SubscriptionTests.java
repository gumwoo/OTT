package dev.gumwoo.ott.subscription;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionTests {

	private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

	@Test
	void premiumIsEffectiveUntilExpiry() {
		Subscription subscription = Subscription.basic(1L, NOW);
		subscription.change(Plan.PREMIUM, NOW);

		assertThat(subscription.effectivePlan(NOW.plus(Subscription.PREMIUM_PERIOD).minusSeconds(1)))
			.isEqualTo(Plan.PREMIUM);
		assertThat(subscription.effectivePlan(NOW.plus(Subscription.PREMIUM_PERIOD))).isEqualTo(Plan.BASIC);
	}

	@Test
	void expiredPremiumIsShownAsBasicWithoutExpiry() {
		Subscription subscription = Subscription.basic(1L, NOW);
		subscription.change(Plan.PREMIUM, NOW);

		SubscriptionView view = subscription.view(NOW.plus(Duration.ofDays(31)));

		assertThat(view.plan()).isEqualTo(Plan.BASIC);
		assertThat(view.expiresAt()).isNull();
	}

	@Test
	void premiumCoversBasicButNotTheOtherWayAround() {
		assertThat(Plan.PREMIUM.covers(Plan.BASIC)).isTrue();
		assertThat(Plan.BASIC.covers(Plan.PREMIUM)).isFalse();
		assertThat(Plan.BASIC.covers(Plan.BASIC)).isTrue();
	}

}
