package dev.gumwoo.ott.qoe;

import java.util.UUID;

import dev.gumwoo.ott.support.Browser;
import dev.gumwoo.ott.support.Browser.Response;
import dev.gumwoo.ott.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class QoeApiIntegrationTests extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void reportIsStoredWithMeasuredAndReceivedTimes() {
		Browser browser = loggedIn(uniqueEmail());
		UUID session = startPlayback(browser);
		UUID event = UUID.randomUUID();

		Response response = browser.post("/api/qoe", batch(event(event, session, "FINAL")));

		assertThat(response.status()).isEqualTo(204);
		assertThat(this.jdbc.queryForMap(
				"select kind, startup_ms, errors::text as errors, measured_at < received_at as ordered from qoe_events where client_event_id = ?",
				event))
			.containsEntry("kind", "FINAL")
			.containsEntry("startup_ms", 812)
			.containsEntry("errors", "[\"networkError:fragLoadError\"]")
			.containsEntry("ordered", true);
	}

	@Test
	void resentEventIsStoredOnce() {
		Browser browser = loggedIn(uniqueEmail());
		UUID session = startPlayback(browser);
		UUID event = UUID.randomUUID();

		browser.post("/api/qoe", batch(event(event, session, "PERIODIC")));
		Response resent = browser.post("/api/qoe", batch(event(event, session, "PERIODIC")));

		assertThat(resent.status()).isEqualTo(204);
		assertThat(count(session)).isEqualTo(1);
	}

	@Test
	void reportForAnotherUsersSessionLooksMissingAndStoresNothing() {
		UUID session = startPlayback(loggedIn(uniqueEmail()));
		Browser other = loggedIn(uniqueEmail());
		UUID ownSession = startPlayback(other);

		Response response = other.post("/api/qoe",
				batch(event(UUID.randomUUID(), ownSession, "PERIODIC"), event(UUID.randomUUID(), session, "PERIODIC")));

		assertThat(response.status()).isEqualTo(404);
		assertThat(response.json().get("code").asString()).isEqualTo("NOT_FOUND");
		assertThat(count(session) + count(ownSession)).isZero();
	}

	@Test
	void invalidReportIsRejected() {
		Browser browser = loggedIn(uniqueEmail());
		UUID session = startPlayback(browser);
		String negative = event(UUID.randomUUID(), session, "PERIODIC").replace("\"rebufferMs\": 0",
				"\"rebufferMs\": -1");

		Response response = browser.post("/api/qoe", batch(negative));

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.json().get("code").asString()).isEqualTo("VALIDATION_FAILED");
	}

	@Test
	void emptyBatchIsRejected() {
		Response response = loggedIn(uniqueEmail()).post("/api/qoe", "{\"events\": []}");

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.json().get("code").asString()).isEqualTo("VALIDATION_FAILED");
	}

	@Test
	void reportRequiresLogin() {
		Response response = browser().post("/api/qoe", batch(event(UUID.randomUUID(), UUID.randomUUID(), "FINAL")));

		assertThat(response.status()).isEqualTo(401);
	}

	private UUID startPlayback(Browser browser) {
		String id = "movie-" + UUID.randomUUID();
		this.jdbc.update(
				"insert into contents (id, title, required_plan, duration_sec, current_version) values (?, ?, 'BASIC', 600, 1)",
				id, id);
		Response response = browser.post("/api/contents/" + id + "/playback", null);
		return UUID.fromString(response.json().get("sessionId").asString());
	}

	private int count(UUID session) {
		return this.jdbc.queryForObject("select count(*) from qoe_events where session_id = ?", Integer.class, session);
	}

	private static String batch(String... events) {
		return "{\"events\": [" + String.join(",", events) + "]}";
	}

	private static String event(UUID clientEventId, UUID sessionId, String kind) {
		return """
				{"clientEventId": "%s", "sessionId": "%s", "kind": "%s", "startupMs": 812, "watchedMs": 60000,
				 "positionSec": 60, "rebufferMs": 0, "rebufferCount": 0, "levelSwitches": 1,
				 "errors": ["networkError:fragLoadError"], "measuredAt": "2026-10-14T00:00:00Z"}
				""".formatted(clientEventId, sessionId, kind);
	}

}
