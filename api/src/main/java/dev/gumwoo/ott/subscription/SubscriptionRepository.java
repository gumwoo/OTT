package dev.gumwoo.ott.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

	@Modifying
	@Query(value = "insert into subscriptions (user_id, plan) values (:userId, 'BASIC') on conflict (user_id) do nothing",
			nativeQuery = true)
	int insertBasicIfAbsent(@Param("userId") long userId);

}
