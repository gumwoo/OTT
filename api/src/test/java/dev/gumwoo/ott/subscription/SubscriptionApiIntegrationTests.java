package dev.gumwoo.ott.subscription;

import java.time.Duration;
import java.time.Instant;

import dev.gumwoo.ott.support.Browser;
import dev.gumwoo.ott.support.Browser.Response;
import dev.gumwoo.ott.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SubscriptionApiIntegrationTests extends IntegrationTest {

	@Test
	void premiumLastsForMockPeriodAndBasicHasNoExpiry() {
		Browser browser = loggedIn(uniqueEmail());

		Response premium = browser.put("/api/me/subscription", "{\"plan\": \"PREMIUM\"}");

		assertThat(premium.status()).isEqualTo(200);
		assertThat(premium.json().get("plan").asString()).isEqualTo("PREMIUM");
		assertThat(Instant.parse(premium.json().get("expiresAt").asString()))
			.isCloseTo(Instant.now().plus(Duration.ofDays(30)), within(Duration.ofMinutes(1)));
		assertThat(browser.get("/api/me").json().get("subscription").get("plan").asString()).isEqualTo("PREMIUM");

		Response basic = browser.put("/api/me/subscription", "{\"plan\": \"BASIC\"}");

		assertThat(basic.json().get("plan").asString()).isEqualTo("BASIC");
		assertThat(basic.json().get("expiresAt").isNull()).isTrue();
	}

	@Test
	void unknownPlanIsRejected() {
		Response response = loggedIn(uniqueEmail()).put("/api/me/subscription", "{\"plan\": \"GOLD\"}");

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.json().get("code").asString()).isEqualTo("BAD_REQUEST");
	}

	@Test
	void missingPlanIsValidationError() {
		Response response = loggedIn(uniqueEmail()).put("/api/me/subscription", "{}");

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.json().get("code").asString()).isEqualTo("VALIDATION_FAILED");
		assertThat(response.json().get("errors").get(0).get("field").asString()).isEqualTo("plan");
	}

	@Test
	void changingPlanRequiresLogin() {
		Response response = browser().put("/api/me/subscription", "{\"plan\": \"PREMIUM\"}");

		assertThat(response.status()).isEqualTo(401);
		assertThat(response.json().get("code").asString()).isEqualTo("UNAUTHENTICATED");
	}

}
