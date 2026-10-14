package dev.gumwoo.ott.playback;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import dev.gumwoo.ott.support.Browser;
import dev.gumwoo.ott.support.Browser.Response;
import dev.gumwoo.ott.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PlaybackApiIntegrationTests extends IntegrationTest {

	private static final List<String> SIGNED_COOKIES = List.of("CloudFront-Policy", "CloudFront-Signature",
			"CloudFront-Key-Pair-Id", "CloudFront-Hash-Algorithm");

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void catalogListsOnlyPublishedContent() {
		String published = content("BASIC", 1);
		String unpublished = content("BASIC", null);

		List<String> ids = loggedIn(uniqueEmail()).get("/api/contents")
			.json()
			.valueStream()
			.map((item) -> item.get("id").asString())
			.toList();

		assertThat(ids).contains(published).doesNotContain(unpublished);
	}

	@Test
	void detailTellsWhetherViewerCanPlay() {
		String premium = content("PREMIUM", 1);
		Browser browser = loggedIn(uniqueEmail());

		assertThat(browser.get("/api/contents/" + premium).json().get("playable").asBoolean()).isFalse();
		browser.put("/api/me/subscription", "{\"plan\": \"PREMIUM\"}");
		assertThat(browser.get("/api/contents/" + premium).json().get("playable").asBoolean()).isTrue();
	}

	@Test
	void unpublishedContentHasNoDetail() {
		Response response = loggedIn(uniqueEmail()).get("/api/contents/" + content("BASIC", null));

		assertThat(response.status()).isEqualTo(404);
		assertThat(response.json().get("code").asString()).isEqualTo("NOT_FOUND");
	}

	@Test
	void playbackIssuesFourCookiesScopedToCurrentVersion() {
		String id = content("BASIC", 3);
		Browser browser = loggedIn(uniqueEmail());

		Response response = browser.post("/api/contents/" + id + "/playback", null);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.json().get("manifestUrl").asString()).isEqualTo("/content/" + id + "/v3/master.m3u8");
		assertThat(SIGNED_COOKIES).allSatisfy((name) -> assertThat(response.setCookie(name))
			.hasValueSatisfying((cookie) -> assertThat(cookie).contains("Path=/content/" + id + "/v3/;")
				.contains("HttpOnly")
				.contains("Secure")
				.contains("SameSite=Lax")
				.doesNotContain("Max-Age", "Expires")));
		assertThat(Instant.parse(response.json().get("expiresAt").asString()))
			.isCloseTo(Instant.now().plus(Duration.ofMinutes(30)), within(Duration.ofMinutes(1)));
	}

	@Test
	void basicSubscriberCannotPlayPremiumContent() {
		Response response = loggedIn(uniqueEmail()).post("/api/contents/" + content("PREMIUM", 1) + "/playback", null);

		assertThat(response.status()).isEqualTo(403);
		assertThat(response.json().get("code").asString()).isEqualTo("PLAN_REQUIRED");
		assertThat(response.setCookie("CloudFront-Signature")).isEmpty();
	}

	@Test
	void unpublishedAndMissingContentAreDistinguished() {
		Browser browser = loggedIn(uniqueEmail());

		Response unpublished = browser.post("/api/contents/" + content("BASIC", null) + "/playback", null);
		Response missing = browser.post("/api/contents/no-such-movie/playback", null);

		assertThat(unpublished.status()).isEqualTo(404);
		assertThat(unpublished.json().get("code").asString()).isEqualTo("NOT_PUBLISHED");
		assertThat(missing.json().get("code").asString()).isEqualTo("NOT_FOUND");
	}

	@Test
	void premiumCookiesDoNotOutliveSubscription() {
		String email = uniqueEmail();
		String id = content("PREMIUM", 1);
		Browser browser = loggedIn(email);
		browser.put("/api/me/subscription", "{\"plan\": \"PREMIUM\"}");
		Instant subscriptionEnds = Instant.now().plus(Duration.ofMinutes(10));
		expireSubscriptionAt(email, subscriptionEnds);

		Response response = browser.post("/api/contents/" + id + "/playback", null);

		Instant expiresAt = Instant.parse(response.json().get("expiresAt").asString());
		assertThat(expiresAt).isCloseTo(subscriptionEnds, within(Duration.ofSeconds(1)));
		assertThat(policyExpiry(response)).isEqualTo(expiresAt);
	}

	@Test
	void basicContentIsNotCutBySubscriptionExpiry() {
		String email = uniqueEmail();
		Browser browser = loggedIn(email);
		browser.put("/api/me/subscription", "{\"plan\": \"PREMIUM\"}");
		expireSubscriptionAt(email, Instant.now().plus(Duration.ofMinutes(10)));

		Response response = browser.post("/api/contents/" + content("BASIC", 1) + "/playback", null);

		assertThat(Instant.parse(response.json().get("expiresAt").asString()))
			.isCloseTo(Instant.now().plus(Duration.ofMinutes(30)), within(Duration.ofMinutes(1)));
	}

	@Test
	void expiryIsWholeSecondsMatchingPolicy() {
		Response response = loggedIn(uniqueEmail()).post("/api/contents/" + content("BASIC", 1) + "/playback", null);

		Instant expiresAt = Instant.parse(response.json().get("expiresAt").asString());
		assertThat(expiresAt.getNano()).isZero();
		assertThat(policyExpiry(response)).isEqualTo(expiresAt);
	}

	@Test
	void countryIsRecordedOnlyWhenWellFormed() {
		String id = content("BASIC", 1);
		Browser browser = loggedIn(uniqueEmail());

		UUID valid = sessionId(
				browser.header("CloudFront-Viewer-Country", "KR").post("/api/contents/" + id + "/playback", null));
		UUID invalid = sessionId(
				browser.header("CloudFront-Viewer-Country", "Korea").post("/api/contents/" + id + "/playback", null));

		assertThat(country(valid)).isEqualTo("KR");
		assertThat(country(invalid)).isNull();
	}

	@Test
	void refreshKeepsSessionVersionAfterNewVersionIsPublished() {
		String id = content("BASIC", 1);
		Browser browser = loggedIn(uniqueEmail());
		UUID session = sessionId(browser.post("/api/contents/" + id + "/playback", null));
		this.jdbc.update("update contents set current_version = 2 where id = ?", id);

		Response refreshed = browser.post("/api/playback/" + session + "/refresh", null);

		assertThat(refreshed.status()).isEqualTo(200);
		assertThat(refreshed.json().get("manifestUrl").asString()).isEqualTo("/content/" + id + "/v1/master.m3u8");
		assertThat(refreshed.setCookie("CloudFront-Policy"))
			.hasValueSatisfying((cookie) -> assertThat(cookie).contains("Path=/content/" + id + "/v1/;"));
		assertThat(this.jdbc.queryForObject("select last_refreshed_at is not null from playback_sessions where id = ?",
				Boolean.class, session))
			.isTrue();
		assertThat(this.jdbc
			.queryForObject("select expires_at from playback_sessions where id = ?", Timestamp.class, session)
			.toInstant()).isEqualTo(Instant.parse(refreshed.json().get("expiresAt").asString()));
	}

	@Test
	void refreshOfAnotherUsersSessionLooksMissing() {
		String id = content("BASIC", 1);
		UUID session = sessionId(loggedIn(uniqueEmail()).post("/api/contents/" + id + "/playback", null));

		Response response = loggedIn(uniqueEmail()).post("/api/playback/" + session + "/refresh", null);

		assertThat(response.status()).isEqualTo(404);
		assertThat(response.json().get("code").asString()).isEqualTo("NOT_FOUND");
	}

	@Test
	void refreshStopsAfterDowngrade() {
		String id = content("PREMIUM", 1);
		Browser browser = loggedIn(uniqueEmail());
		browser.put("/api/me/subscription", "{\"plan\": \"PREMIUM\"}");
		UUID session = sessionId(browser.post("/api/contents/" + id + "/playback", null));
		browser.put("/api/me/subscription", "{\"plan\": \"BASIC\"}");

		Response response = browser.post("/api/playback/" + session + "/refresh", null);

		assertThat(response.status()).isEqualTo(403);
		assertThat(response.json().get("code").asString()).isEqualTo("PLAN_REQUIRED");
		assertThat(response.setCookie("CloudFront-Signature")).isEmpty();
	}

	@Test
	void refreshStopsAfterSubscriptionExpires() {
		String email = uniqueEmail();
		String id = content("PREMIUM", 1);
		Browser browser = loggedIn(email);
		browser.put("/api/me/subscription", "{\"plan\": \"PREMIUM\"}");
		UUID session = sessionId(browser.post("/api/contents/" + id + "/playback", null));
		expireSubscriptionAt(email, Instant.now().minusSeconds(1));

		Response response = browser.post("/api/playback/" + session + "/refresh", null);

		assertThat(response.status()).isEqualTo(403);
		assertThat(response.json().get("code").asString()).isEqualTo("PLAN_REQUIRED");
	}

	@Test
	void playbackRequiresLogin() {
		Response response = browser().post("/api/contents/" + content("BASIC", 1) + "/playback", null);

		assertThat(response.status()).isEqualTo(401);
	}

	private String content(String requiredPlan, Integer currentVersion) {
		String id = "movie-" + UUID.randomUUID();
		this.jdbc.update(
				"insert into contents (id, title, required_plan, duration_sec, current_version) values (?, ?, ?, ?, ?)",
				id, "Title " + id, requiredPlan, 734, currentVersion);
		return id;
	}

	private void expireSubscriptionAt(String email, Instant expiresAt) {
		this.jdbc.update(
				"update subscriptions set expires_at = ? where user_id = (select id from users where email = ?)",
				Timestamp.from(expiresAt), email);
	}

	private static Instant policyExpiry(Response response) {
		String cookie = response.setCookie("CloudFront-Policy").orElseThrow();
		String value = cookie.substring("CloudFront-Policy=".length(), cookie.indexOf(';'));
		byte[] policy = Base64.getDecoder().decode(value.replace('-', '+').replace('_', '=').replace('~', '/'));
		JsonNode condition = JsonMapper.builder().build().readTree(policy).get("Statement").get(0).get("Condition");
		return Instant.ofEpochSecond(condition.get("DateLessThan").get("AWS:EpochTime").asLong());
	}

	private String country(UUID session) {
		return this.jdbc.queryForObject("select country from playback_sessions where id = ?", String.class, session);
	}

	private static UUID sessionId(Response response) {
		return UUID.fromString(response.json().get("sessionId").asString());
	}

}
