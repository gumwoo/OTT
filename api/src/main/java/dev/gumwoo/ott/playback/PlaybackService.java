package dev.gumwoo.ott.playback;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import dev.gumwoo.ott.catalog.CatalogService;
import dev.gumwoo.ott.catalog.PlayableContent;
import dev.gumwoo.ott.common.error.ApiException;
import dev.gumwoo.ott.common.error.ErrorCode;
import dev.gumwoo.ott.subscription.Plan;
import dev.gumwoo.ott.subscription.SubscriptionService;
import dev.gumwoo.ott.subscription.SubscriptionView;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaybackService {

	private final CatalogService catalog;

	private final SubscriptionService subscriptions;

	private final PlaybackSessionRepository sessions;

	private final SignedCookieSigner signer;

	private final PlaybackProperties properties;

	private final Clock clock;

	PlaybackService(CatalogService catalog, SubscriptionService subscriptions, PlaybackSessionRepository sessions,
			SignedCookieSigner signer, PlaybackProperties properties, Clock clock) {
		this.catalog = catalog;
		this.subscriptions = subscriptions;
		this.sessions = sessions;
		this.signer = signer;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public PlaybackGrant start(long userId, String contentId, String country) {
		PlayableContent content = this.catalog.forPlayback(contentId);
		SubscriptionView subscription = requirePlan(userId, content.requiredPlan());
		if (!content.published()) {
			throw new ApiException(ErrorCode.NOT_PUBLISHED);
		}
		Instant now = this.clock.instant();
		PlaybackSession session = this.sessions.save(new PlaybackSession(userId, contentId, content.currentVersion(),
				country, now, expiry(now, content.requiredPlan(), subscription)));
		return grant(session);
	}

	// 남의 세션이면 존재 여부를 알리지 않고 404 (app-design.md §6)
	@Transactional
	public PlaybackGrant refresh(long userId, UUID sessionId) {
		PlaybackSession session = this.sessions.findByIdAndUserId(sessionId, userId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Plan required = this.catalog.forPlayback(session.contentId()).requiredPlan();
		SubscriptionView subscription = requirePlan(userId, required);
		Instant now = this.clock.instant();
		session.refreshed(now, expiry(now, required, subscription));
		return grant(session);
	}

	private SubscriptionView requirePlan(long userId, Plan required) {
		SubscriptionView subscription = this.subscriptions.get(userId);
		if (!subscription.plan().covers(required)) {
			throw new ApiException(ErrorCode.PLAN_REQUIRED);
		}
		return subscription;
	}

	// PREMIUM 콘텐츠만 구독 만료로 자른다. BASIC 콘텐츠는 PREMIUM이 끝나도 계속 볼 수 있다
	private Instant expiry(Instant now, Plan required, SubscriptionView subscription) {
		// 정책의 DateLessThan이 초 단위다
		Instant ttl = now.plus(this.properties.ttl()).truncatedTo(ChronoUnit.SECONDS);
		if (required == Plan.PREMIUM && subscription.expiresAt() != null && subscription.expiresAt().isBefore(ttl)) {
			return subscription.expiresAt().truncatedTo(ChronoUnit.SECONDS);
		}
		return ttl;
	}

	private PlaybackGrant grant(PlaybackSession session) {
		String manifestUrl = SignedCookieSigner.versionPath(session.contentId(), session.version()) + "master.m3u8";
		return new PlaybackGrant(session.getId(), manifestUrl, session.expiresAt(),
				this.signer.sign(session.contentId(), session.version(), session.expiresAt()));
	}

}
