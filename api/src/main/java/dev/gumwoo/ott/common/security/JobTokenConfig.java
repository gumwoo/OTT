package dev.gumwoo.ott.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
class JobTokenConfig {

	private static final Logger log = LoggerFactory.getLogger(JobTokenConfig.class);

	@Bean
	JobTokenVerifier jobTokenVerifier(@Value("${ott.internal.dev-token:#{null}}") String devToken,
			Environment environment) {
		return create(devToken, environment.acceptsProfiles(Profiles.of("local", "test")));
	}

	static JobTokenVerifier create(String devToken, boolean devProfile) {
		if (devToken == null) {
			// TokenReview 구현은 kind 단계에서 붙인다. 그 전까지 /internal/*은 모두 401이다
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

}
