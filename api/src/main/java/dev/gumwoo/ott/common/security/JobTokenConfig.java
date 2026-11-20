package dev.gumwoo.ott.common.security;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class JobTokenConfig {

	private static final Logger log = LoggerFactory.getLogger(JobTokenConfig.class);

	@Bean
	JobTokenVerifier jobTokenVerifier(@Value("${ott.internal.dev-token:#{null}}") String devToken,
			TokenReviewProperties tokenReview, ObjectProvider<SslBundles> sslBundles, Environment environment) {
		if (tokenReview.enabled()) {
			if (devToken != null) {
				throw new IllegalStateException(
						"Set either ott.internal.dev-token or ott.internal.token-review.enabled, not both");
			}
			return tokenReview(tokenReview, sslBundles);
		}
		return create(devToken, environment.acceptsProfiles(Profiles.of("local", "test")));
	}

	static JobTokenVerifier create(String devToken, boolean devProfile) {
		if (devToken == null) {
			// 운영에서 설정을 빠뜨리면 Job이 모두 401을 받기 전까지 드러나지 않으므로 기동을 막는다
			if (!devProfile) {
				throw new IllegalStateException(
						"ott.internal.token-review.enabled is required outside local or test profile");
			}
			log.warn("No job token verifier configured. /internal/* rejects every request");
			return (token) -> false;
		}
		if (!devProfile) {
			throw new IllegalStateException("ott.internal.dev-token is allowed only in local or test profile");
		}
		if (devToken.isBlank()) {
			throw new IllegalStateException("ott.internal.dev-token must not be blank");
		}
		byte[] expected = devToken.getBytes(StandardCharsets.UTF_8);
		return (token) -> MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8));
	}

	private static JobTokenVerifier tokenReview(TokenReviewProperties properties,
			ObjectProvider<SslBundles> sslBundles) {
		if (properties.serviceAccount() == null || properties.serviceAccount().isBlank()) {
			throw new IllegalStateException("ott.internal.token-review.service-account is required");
		}
		HttpClient httpClient = HttpClient.newBuilder()
			.sslContext(sslBundles.getObject().getBundle(properties.sslBundle()).createSslContext())
			.connectTimeout(Duration.ofSeconds(1))
			.build();
		// Job의 요청 타임아웃(5초) 안에 401·503 중 하나로 답해야 Job이 재시도 여부를 판단할 수 있다
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(2));
		RestClient client = RestClient.builder()
			.baseUrl(properties.apiServer().toString())
			.requestFactory(requestFactory)
			.build();
		return new TokenReviewJobTokenVerifier(client, properties.credentials(), properties.audience(),
				properties.serviceAccount());
	}

}
