package dev.gumwoo.ott.common.error;

import dev.gumwoo.ott.support.Browser;
import dev.gumwoo.ott.support.Browser.Response;
import dev.gumwoo.ott.support.IntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

// SP-05·06: 어느 경로로 나가는 오류든 ProblemDetail + code
class ErrorResponseContractIntegrationTests extends IntegrationTest {

	@ParameterizedTest
	@CsvSource({ "GET, /api/me, , , 401, UNAUTHENTICATED", "GET, /api/nothing-here, , , 404, NOT_FOUND",
			"GET, /api/auth/login, , , 405, METHOD_NOT_ALLOWED",
			"PUT, /api/me/subscription, not json, application/json, 400, BAD_REQUEST",
			"PUT, /api/me/subscription, plan=PREMIUM, text/plain, 415, UNSUPPORTED_MEDIA_TYPE",
			"GET, /api/test/boom, , , 500, INTERNAL_ERROR" })
	void everyErrorHasCode(String method, String path, String body, String contentType, int status, String code) {
		Browser browser = "/api/me".equals(path) ? new Browser(this.port) : loggedIn(uniqueEmail());

		Response response = browser.send(method, path, body, contentType);

		assertThat(response.status()).isEqualTo(status);
		assertThat(response.contentType()).startsWith("application/problem+json");
		assertThat(response.json().get("status").asInt()).isEqualTo(status);
		assertThat(response.json().get("code").asString()).isEqualTo(code);
		assertThat(response.json().get("detail").asString()).isNotBlank();
	}

}
