package dev.gumwoo.ott.subscription;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "subscriptions")
class Subscription {

	// 모의 구독이라 결제 주기 대신 고정 기간을 준다
	static final Duration PREMIUM_PERIOD = Duration.ofDays(30);

	@Id
	@Column(name = "user_id")
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Plan plan;

	private Instant expiresAt;

	@Column(nullable = false)
	private Instant updatedAt;

	protected Subscription() {
	}

	static Subscription basic(long userId, Instant now) {
		Subscription subscription = new Subscription();
		subscription.userId = userId;
		subscription.plan = Plan.BASIC;
		subscription.updatedAt = now;
		return subscription;
	}

	void change(Plan plan, Instant now) {
		this.plan = plan;
		this.expiresAt = (plan == Plan.PREMIUM) ? now.plus(PREMIUM_PERIOD) : null;
		this.updatedAt = now;
	}

	Plan effectivePlan(Instant now) {
		if (this.plan == Plan.PREMIUM && this.expiresAt.isAfter(now)) {
			return Plan.PREMIUM;
		}
		return Plan.BASIC;
	}

	SubscriptionView view(Instant now) {
		Plan effective = effectivePlan(now);
		return new SubscriptionView(effective, (effective == Plan.PREMIUM) ? this.expiresAt : null);
	}

}
