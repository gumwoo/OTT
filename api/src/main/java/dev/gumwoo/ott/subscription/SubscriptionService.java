package dev.gumwoo.ott.subscription;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {

	private final SubscriptionRepository subscriptions;

	private final Clock clock;

	SubscriptionService(SubscriptionRepository subscriptions, Clock clock) {
		this.subscriptions = subscriptions;
		this.clock = clock;
	}

	@Transactional
	public void startBasic(long userId) {
		this.subscriptions.save(Subscription.basic(userId, this.clock.instant()));
	}

	@Transactional
	public void startBasicIfAbsent(long userId) {
		this.subscriptions.insertBasicIfAbsent(userId);
	}

	@Transactional(readOnly = true)
	public SubscriptionView get(long userId) {
		return find(userId).view(this.clock.instant());
	}

	// 모의 구독 (app-design.md §6)
	@Transactional
	public SubscriptionView change(long userId, Plan plan) {
		Subscription subscription = find(userId);
		subscription.change(plan, this.clock.instant());
		return subscription.view(this.clock.instant());
	}

	private Subscription find(long userId) {
		return this.subscriptions.findById(userId)
			.orElseThrow(() -> new IllegalStateException("subscription missing for user " + userId));
	}

}
