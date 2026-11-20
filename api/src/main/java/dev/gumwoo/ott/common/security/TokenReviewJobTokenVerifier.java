package dev.gumwoo.ott.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.gumwoo.ott.common.error.ApiException;
import dev.gumwoo.ott.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// TokenReview가 실패하면 토큰이 틀린 것과 구분해 503으로 돌려준다. Job은 5xx만 재시도한다 (app-design.md §8)
class TokenReviewJobTokenVerifier implements JobTokenVerifier {

	private static final Logger log = LoggerFactory.getLogger(TokenReviewJobTokenVerifier.class);

	private final RestClient client;

	private final Resource credentials;

	private final String audience;

	private final String serviceAccount;

	TokenReviewJobTokenVerifier(RestClient client, Resource credentials, String audience, String serviceAccount) {
		this.client = client;
		this.credentials = credentials;
		this.audience = audience;
		this.serviceAccount = serviceAccount;
	}

	@Override
	public boolean verify(String token) {
		// API 서버는 빈 토큰을 400으로 거절한다. 그러면 호출 실패(503)로 섞이므로 여기서 거부한다
		if (token.isBlank()) {
			return false;
		}
		Status status = review(token);
		if (status == null || !Boolean.TRUE.equals(status.authenticated())) {
			log.info("Job token rejected: not authenticated ({})", (status != null) ? status.error() : "no status");
			return false;
		}
		if (status.audiences() == null || !status.audiences().contains(this.audience)) {
			log.info("Job token rejected: audiences {}", status.audiences());
			return false;
		}
		String username = (status.user() != null) ? status.user().username() : null;
		if (!this.serviceAccount.equals(username)) {
			log.info("Job token rejected: user {}", username);
			return false;
		}
		return true;
	}

	private Status review(String token) {
		try {
			TokenReview response = this.client.post()
				.uri("/apis/authentication.k8s.io/v1/tokenreviews")
				.header(HttpHeaders.AUTHORIZATION,
						"Bearer " + this.credentials.getContentAsString(StandardCharsets.UTF_8).trim())
				.body(new TokenReview("authentication.k8s.io/v1", "TokenReview",
						new Spec(token, List.of(this.audience)), null))
				.retrieve()
				.body(TokenReview.class);
			return (response != null) ? response.status() : null;
		}
		catch (IOException | RestClientException ex) {
			log.warn("TokenReview request failed", ex);
			throw new ApiException(ErrorCode.TOKEN_REVIEW_UNAVAILABLE);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	record TokenReview(String apiVersion, String kind, Spec spec, Status status) {
	}

	record Spec(String token, List<String> audiences) {
	}

	// 인증에 실패하면 authenticated가 빠진 채로 온다. boolean이면 Jackson 3 기본 설정에서 역직렬화가 실패한다 (TS-0008)
	@JsonIgnoreProperties(ignoreUnknown = true)
	record Status(Boolean authenticated, User user, List<String> audiences, String error) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record User(String username) {
	}

}
