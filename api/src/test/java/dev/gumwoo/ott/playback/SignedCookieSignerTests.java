package dev.gumwoo.ott.playback;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.ResponseCookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

// SDK가 SHA-256으로 서명하고 CloudFront-Hash-Algorithm 쿠키를 주는지 확인한다 (app-design.md §7)
class SignedCookieSignerTests {

	private static final Instant EXPIRES_AT = Instant.parse("2026-10-14T01:30:00Z");

	private static KeyPair keys;

	private static Map<String, ResponseCookie> cookies;

	@BeforeAll
	static void sign() throws GeneralSecurityException {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		keys = generator.generateKeyPair();
		SignedCookieSigner signer = new SignedCookieSigner("https://d111111abcdef8.cloudfront.net/", "K2JCJMDEHXQW5F",
				keys.getPrivate());
		cookies = signer.sign("movie-100", 3, EXPIRES_AT)
			.stream()
			.collect(Collectors.toMap(ResponseCookie::getName, Function.identity()));
	}

	@Test
	void issuesFourCookiesIncludingHashAlgorithm() {
		assertThat(cookies).containsOnlyKeys("CloudFront-Policy", "CloudFront-Signature", "CloudFront-Key-Pair-Id",
				"CloudFront-Hash-Algorithm");
		assertThat(cookies.get("CloudFront-Hash-Algorithm").getValue()).isEqualTo("SHA256");
		assertThat(cookies.get("CloudFront-Key-Pair-Id").getValue()).isEqualTo("K2JCJMDEHXQW5F");
	}

	@Test
	void cookiesAreScopedToVersionPathWithoutExpiry() {
		assertThat(cookies.values()).allSatisfy((cookie) -> {
			assertThat(cookie.getPath()).isEqualTo("/content/movie-100/v3/");
			assertThat(cookie.isHttpOnly()).isTrue();
			assertThat(cookie.isSecure()).isTrue();
			assertThat(cookie.getSameSite()).isEqualTo("Lax");
			assertThat(cookie.getMaxAge().isNegative()).isTrue();
			assertThat(cookie.toString()).doesNotContain("Expires", "Max-Age");
		});
	}

	@Test
	void policyLimitsResourceToVersionAndExpiry() {
		JsonNode statement = JsonMapper.builder().build().readTree(policy()).get("Statement").get(0);

		assertThat(statement.get("Resource").asString())
			.isEqualTo("https://d111111abcdef8.cloudfront.net/content/movie-100/v3/*");
		assertThat(statement.get("Condition").get("DateLessThan").get("AWS:EpochTime").asLong())
			.isEqualTo(EXPIRES_AT.getEpochSecond());
	}

	@Test
	void signatureIsSha256WithRsa() throws GeneralSecurityException {
		byte[] signature = decode(cookies.get("CloudFront-Signature").getValue());

		assertThat(verify("SHA256withRSA", keys.getPublic(), signature)).isTrue();
		assertThat(verify("SHA1withRSA", keys.getPublic(), signature)).isFalse();
	}

	@Test
	void onlyPkcs8KeysAreAccepted() {
		assertThatIllegalStateException().isThrownBy(() -> PlaybackConfig
			.parsePkcs8("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"));
	}

	@Test
	void pkcs8KeyIsParsed() throws GeneralSecurityException {
		String pem = "-----BEGIN PRIVATE KEY-----\n"
				+ Base64.getMimeEncoder().encodeToString(keys.getPrivate().getEncoded())
				+ "\n-----END PRIVATE KEY-----\n";

		assertThat(PlaybackConfig.parsePkcs8(pem)).isEqualTo(keys.getPrivate());
	}

	private static String policy() {
		return new String(decode(cookies.get("CloudFront-Policy").getValue()), StandardCharsets.UTF_8);
	}

	private static boolean verify(String algorithm, PublicKey key, byte[] signature) throws GeneralSecurityException {
		Signature verifier = Signature.getInstance(algorithm);
		verifier.initVerify(key);
		verifier.update(policy().getBytes(StandardCharsets.UTF_8));
		return verifier.verify(signature);
	}

	// CloudFront용 base64: + = / 를 - _ ~ 로 바꿔 쓴다
	private static byte[] decode(String value) {
		return Base64.getDecoder().decode(value.replace('-', '+').replace('_', '=').replace('~', '/'));
	}

}
