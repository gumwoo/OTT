package dev.gumwoo.ott.playback;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
class PlaybackConfig {

	private static final Logger log = LoggerFactory.getLogger(PlaybackConfig.class);

	@Bean
	SignedCookieSigner signedCookieSigner(PlaybackProperties properties, Environment environment)
			throws IOException, GeneralSecurityException {
		return create(properties, environment.acceptsProfiles(Profiles.of("local", "test")));
	}

	static SignedCookieSigner create(PlaybackProperties properties, boolean devProfile)
			throws IOException, GeneralSecurityException {
		PlaybackProperties.Signing signing = properties.signing();
		if (properties.resourceBaseUrl() == null || signing == null || signing.keyPairId() == null) {
			throw new IllegalStateException(
					"ott.playback.resource-base-url and ott.playback.signing.key-pair-id are required");
		}
		return new SignedCookieSigner(properties.resourceBaseUrl(), signing.keyPairId(),
				privateKey(signing, devProfile));
	}

	private static PrivateKey privateKey(PlaybackProperties.Signing signing, boolean devProfile)
			throws IOException, GeneralSecurityException {
		if (signing.privateKey() != null) {
			return parsePkcs8(signing.privateKey().getContentAsString(StandardCharsets.US_ASCII));
		}
		if (!signing.ephemeralKey()) {
			throw new IllegalStateException("ott.playback.signing.private-key is required");
		}
		// 운영에서 켜지면 기동은 되는데 모든 쿠키가 거부되므로 여기서 막는다
		if (!devProfile) {
			throw new IllegalStateException(
					"ott.playback.signing.ephemeral-key is allowed only in local or test profile");
		}
		log.warn("Using an ephemeral signing key. CloudFront will reject these cookies");
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		return generator.generateKeyPair().getPrivate();
	}

	// openssl genrsa(OpenSSL 3)·genpkey가 만드는 PKCS#8만 받는다
	static PrivateKey parsePkcs8(String pem) throws GeneralSecurityException {
		if (!pem.contains("-----BEGIN PRIVATE KEY-----")) {
			throw new IllegalStateException("Signing key must be a PKCS#8 PEM (BEGIN PRIVATE KEY)");
		}
		String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
		return KeyFactory.getInstance("RSA")
			.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
	}

}
