package dev.gumwoo.ott.support;

import java.util.UUID;

import dev.gumwoo.ott.TestcontainersConfiguration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 설정이 같아야 컨텍스트와 컨테이너를 재사용한다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, IntegrationTest.FailingEndpoint.class })
public abstract class IntegrationTest {

	protected static final String PASSWORD = "correct-horse-1";

	@LocalServerPort
	protected int port;

	protected Browser browser() {
		Browser browser = new Browser(this.port);
		browser.get("/api/csrf");
		return browser;
	}

	protected static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	protected Browser signedUp(String email) {
		Browser browser = browser();
		browser.post("/api/auth/signup", credentials(email, PASSWORD));
		return browser;
	}

	protected Browser loggedIn(String email) {
		Browser browser = signedUp(email);
		browser.post("/api/auth/login", credentials(email, PASSWORD));
		browser.get("/api/csrf");
		return browser;
	}

	protected static String credentials(String email, String password) {
		return """
				{"email": "%s", "password": "%s"}
				""".formatted(email, password);
	}

	@TestConfiguration(proxyBeanMethods = false)
	@RestController
	static class FailingEndpoint {

		@GetMapping("/api/test/boom")
		void boom() {
			throw new IllegalStateException("boom");
		}

	}

}
