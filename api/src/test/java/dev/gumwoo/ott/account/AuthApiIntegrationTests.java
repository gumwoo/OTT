package dev.gumwoo.ott.account;

import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.gumwoo.ott.support.Browser;
import dev.gumwoo.ott.support.Browser.Response;
import dev.gumwoo.ott.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class AuthApiIntegrationTests extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void csrfCookieIsReadableByScriptOnEveryPath() {
		Response response = new Browser(this.port).get("/api/csrf");

		assertThat(response.status()).isEqualTo(204);
		assertThat(response.setCookie("XSRF-TOKEN"))
			.hasValueSatisfying((cookie) -> assertThat(cookie).contains("Path=/;")
				.contains("Secure")
				.contains("SameSite=Lax")
				.doesNotContain("HttpOnly"));
	}

	@Test
	void signupCreatesBasicUserAndIgnoresRequestedRole() {
		String email = uniqueEmail();
		Response response = browser().post("/api/auth/signup", """
				{"email": "%s", "password": "%s", "role": "ADMIN"}
				""".formatted(email, PASSWORD));

		assertThat(response.status()).isEqualTo(201);
		assertThat(response.json().get("role").asString()).isEqualTo("USER");
		assertThat(response.json().get("subscription").get("plan").asString()).isEqualTo("BASIC");
		assertThat(response.json().get("subscription").get("expiresAt").isNull()).isTrue();
		assertThat(this.jdbc.queryForObject("select role from users where email = ?", String.class, email))
			.isEqualTo("USER");
	}

	@Test
	void signupWithoutCsrfHeaderIsRejected() {
		Response response = browser().withoutCsrfHeader()
			.post("/api/auth/signup", credentials(uniqueEmail(), PASSWORD));

		assertThat(response.status()).isEqualTo(403);
		assertThat(response.json().get("code").asString()).isEqualTo("CSRF_INVALID");
	}

	@Test
	void emailIsUniqueIgnoringCase() {
		String email = uniqueEmail();
		signedUp(email);

		Response response = browser().post("/api/auth/signup", credentials(email.toUpperCase(), PASSWORD));

		assertThat(response.status()).isEqualTo(409);
		assertThat(response.json().get("code").asString()).isEqualTo("EMAIL_TAKEN");
	}

	@Test
	void loginStartsSessionScopedToApiPath() {
		String email = uniqueEmail();
		Browser browser = signedUp(email);

		Response login = browser.post("/api/auth/login", credentials(email, PASSWORD));

		assertThat(login.status()).isEqualTo(200);
		assertThat(login.setCookie("SESSION")).hasValueSatisfying((cookie) -> assertThat(cookie).contains("Path=/api;")
			.contains("HttpOnly")
			.contains("Secure")
			.contains("SameSite=Lax"));
		assertThat(browser.get("/api/me").json().get("email").asString()).isEqualTo(email);
	}

	@Test
	void wrongPasswordAndUnknownEmailLookTheSame() {
		String email = uniqueEmail();
		Browser browser = signedUp(email);

		Response wrongPassword = browser.post("/api/auth/login", credentials(email, "wrong-password"));
		Response unknownEmail = browser.post("/api/auth/login", credentials(uniqueEmail(), PASSWORD));

		assertThat(wrongPassword.status()).isEqualTo(401);
		assertThat(wrongPassword.json().get("code").asString()).isEqualTo("INVALID_CREDENTIALS");
		assertThat(unknownEmail.body()).isEqualTo(wrongPassword.body());
	}

	@Test
	void csrfTokenIssuedBeforeLoginIsRejectedAfterLogin() {
		String email = uniqueEmail();
		Browser browser = signedUp(email);
		String tokenBeforeLogin = browser.cookie("XSRF-TOKEN");

		Response login = browser.post("/api/auth/login", credentials(email, PASSWORD));
		assertThat(login.setCookie("XSRF-TOKEN"))
			.hasValueSatisfying((cookie) -> assertThat(cookie).startsWith("XSRF-TOKEN=;").contains("Path=/;"));

		// 쿠키는 지워졌고 앱 메모리에는 옛 토큰이 남은 상황
		Response stale = browser.csrfHeader(tokenBeforeLogin).put("/api/me/subscription", "{\"plan\": \"PREMIUM\"}");
		assertThat(stale.status()).isEqualTo(403);
		assertThat(stale.json().get("code").asString()).isEqualTo("CSRF_INVALID");

		browser.csrfHeader(null);
		browser.get("/api/csrf");
		assertThat(browser.put("/api/me/subscription", "{\"plan\": \"PREMIUM\"}").status()).isEqualTo(200);
	}

	// 수동 JSON 로그인은 세션 ID를 직접 바꿔야 한다 (app-design.md §5)
	@Test
	void loginReplacesExistingSessionId() {
		String first = uniqueEmail();
		String second = uniqueEmail();
		signedUp(second);
		Browser browser = loggedIn(first);
		String sessionBefore = browser.cookie("SESSION");

		browser.post("/api/auth/login", credentials(second, PASSWORD));

		assertThat(browser.cookie("SESSION")).isNotEqualTo(sessionBefore);
		assertThat(browser.get("/api/me").json().get("email").asString()).isEqualTo(second);
		Browser attacker = new Browser(this.port);
		attacker.cookie("SESSION", sessionBefore);
		assertThat(attacker.get("/api/me").status()).isEqualTo(401);
	}

	@Test
	void sessionIsStoredInDatabase() {
		String email = uniqueEmail();
		loggedIn(email);

		assertThat(this.jdbc.queryForObject("select count(*) from spring_session where principal_name = ?",
				Integer.class, email))
			.isEqualTo(1);
	}

	@Test
	void logoutEndsSession() {
		Browser browser = loggedIn(uniqueEmail());
		String session = browser.cookie("SESSION");

		Response logout = browser.post("/api/auth/logout", null);

		assertThat(logout.status()).isEqualTo(204);
		assertThat(logout.setCookie("SESSION"))
			.hasValueSatisfying((cookie) -> assertThat(cookie).contains("Max-Age=0"));
		Browser reused = new Browser(this.port);
		reused.cookie("SESSION", session);
		assertThat(reused.get("/api/me").status()).isEqualTo(401);
	}

	@Test
	void logoutRequiresCsrfAndClearsToken() {
		Browser browser = loggedIn(uniqueEmail());

		Response withoutToken = browser.csrfHeader("").post("/api/auth/logout", null);
		assertThat(withoutToken.status()).isEqualTo(403);
		assertThat(withoutToken.json().get("code").asString()).isEqualTo("CSRF_INVALID");

		Response logout = browser.csrfHeader(null).post("/api/auth/logout", null);
		assertThat(logout.setCookie("XSRF-TOKEN"))
			.hasValueSatisfying((cookie) -> assertThat(cookie).startsWith("XSRF-TOKEN=;").contains("Path=/;"));
	}

	@Test
	void sessionDoesNotHoldPasswordHash() {
		String email = uniqueEmail();
		loggedIn(email);

		List<byte[]> attributes = this.jdbc
			.queryForList("select a.attribute_bytes from spring_session_attributes a join spring_session s "
					+ "on s.primary_id = a.session_primary_id where s.principal_name = ?", byte[].class, email);
		String hash = this.jdbc.queryForObject("select password_hash from users where email = ?", String.class, email);

		assertThat(attributes).isNotEmpty()
			.noneMatch((bytes) -> new String(bytes, StandardCharsets.ISO_8859_1).contains(hash));
	}

	// spring_session.principal_name 길이가 이메일보다 짧으면 로그인이 500이 된다
	@Test
	void longEmailCanLogIn() {
		String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + ".example.com";
		Browser browser = signedUp(email);

		assertThat(browser.post("/api/auth/login", credentials(email, PASSWORD)).status()).isEqualTo(200);
	}

	@Test
	void meRequiresLogin() {
		Response response = new Browser(this.port).get("/api/me");

		assertThat(response.status()).isEqualTo(401);
		assertThat(response.contentType()).startsWith("application/problem+json");
		assertThat(response.json().get("code").asString()).isEqualTo("UNAUTHENTICATED");
		assertThat(response.json().get("instance").asString()).isEqualTo("/api/me");
	}

	@Test
	void anonymousRequestDoesNotCreateSession() {
		Response response = new Browser(this.port).get("/api/me");

		assertThat(response.setCookie("SESSION")).isEmpty();
	}

}
