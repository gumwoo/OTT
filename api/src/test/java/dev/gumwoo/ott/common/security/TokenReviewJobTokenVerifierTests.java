package dev.gumwoo.ott.common.security;

import dev.gumwoo.ott.common.error.ApiException;
import dev.gumwoo.ott.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TokenReviewJobTokenVerifierTests {

	private static final String TRANSCODER = "system:serviceaccount:ott:transcoder";

	private MockRestServiceServer server;

	private TokenReviewJobTokenVerifier verifier;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://kubernetes.test");
		this.server = MockRestServiceServer.bindTo(builder).build();
		this.verifier = new TokenReviewJobTokenVerifier(builder.build(),
				new ByteArrayResource("api-sa-token\n".getBytes()), "ott-api", TRANSCODER);
	}

	@Test
	void acceptsTranscoderTokenForTheAudience() {
		this.server.expect(requestTo("https://kubernetes.test/apis/authentication.k8s.io/v1/tokenreviews"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer api-sa-token"))
			.andExpect(content().json("""
					{"apiVersion":"authentication.k8s.io/v1","kind":"TokenReview",
					 "spec":{"token":"job-token","audiences":["ott-api"]}}
					""", JsonCompareMode.STRICT))
			.andRespond(review(true, TRANSCODER, "[\"ott-api\"]"));

		assertThat(this.verifier.verify("job-token")).isTrue();
		this.server.verify();
	}

	// kind v1.35에서 받은 실제 응답. authenticated가 없다
	@Test
	void rejectsUnauthenticatedToken() {
		this.server.expect(requestTo("https://kubernetes.test/apis/authentication.k8s.io/v1/tokenreviews"))
			.andRespond(withSuccess("""
					{"kind":"TokenReview","apiVersion":"authentication.k8s.io/v1",
					 "spec":{"token":"abc.def.ghi","audiences":["ott-api"]},
					 "status":{"user":{},"error":"invalid bearer token"}}
					""", MediaType.APPLICATION_JSON));

		assertThat(this.verifier.verify("job-token")).isFalse();
	}

	@Test
	void rejectsOtherServiceAccount() {
		this.server.expect(requestTo("https://kubernetes.test/apis/authentication.k8s.io/v1/tokenreviews"))
			.andRespond(review(true, "system:serviceaccount:ott:default", "[\"ott-api\"]"));

		assertThat(this.verifier.verify("job-token")).isFalse();
	}

	@Test
	void rejectsWhenResponseAudiencesDoNotIncludeOurs() {
		this.server.expect(requestTo("https://kubernetes.test/apis/authentication.k8s.io/v1/tokenreviews"))
			.andRespond(review(true, TRANSCODER, "[\"https://kubernetes.default.svc\"]"));

		assertThat(this.verifier.verify("job-token")).isFalse();
	}

	// API 서버는 빈 토큰을 400으로 거절한다(kind v1.35 실측). 호출하지 않고 거부해야 503이 되지 않는다
	@Test
	void blankTokenIsRejectedWithoutReview() {
		assertThat(this.verifier.verify("")).isFalse();
		assertThat(this.verifier.verify(" ")).isFalse();
		this.server.verify();
	}

	@Test
	void rejectsAuthenticatedResponseWithoutUserOrAudiences() {
		this.server.expect(requestTo("https://kubernetes.test/apis/authentication.k8s.io/v1/tokenreviews"))
			.andRespond(withSuccess("""
					{"status":{"authenticated":true,"audiences":["ott-api"]}}
					""", MediaType.APPLICATION_JSON));
		this.server.expect(requestTo("https://kubernetes.test/apis/authentication.k8s.io/v1/tokenreviews"))
			.andRespond(withSuccess("""
					{"status":{"authenticated":true,"user":{"username":"%s"}}}
					""".formatted(TRANSCODER), MediaType.APPLICATION_JSON));

		assertThat(this.verifier.verify("job-token")).isFalse();
		assertThat(this.verifier.verify("job-token")).isFalse();
	}

	@Test
	void reviewFailureIsUnavailableNotRejected() {
		this.server.expect(requestTo("https://kubernetes.test/apis/authentication.k8s.io/v1/tokenreviews"))
			.andRespond(withServerError());

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> this.verifier.verify("job-token"))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo(ErrorCode.TOKEN_REVIEW_UNAVAILABLE));
	}

	@Test
	void missingRbacPermissionIsUnavailableNotRejected() {
		this.server.expect(requestTo("https://kubernetes.test/apis/authentication.k8s.io/v1/tokenreviews"))
			.andRespond(withStatus(HttpStatus.FORBIDDEN));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> this.verifier.verify("job-token"))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo(ErrorCode.TOKEN_REVIEW_UNAVAILABLE));
	}

	private static ResponseCreator review(boolean authenticated, String username, String audiences) {
		return withSuccess("""
				{"apiVersion":"authentication.k8s.io/v1","kind":"TokenReview",
				 "status":{"authenticated":%s,"user":{"username":"%s","uid":"u","groups":["system:serviceaccounts"]},
				 "audiences":%s}}
				""".formatted(authenticated, username, audiences), MediaType.APPLICATION_JSON);
	}

}
