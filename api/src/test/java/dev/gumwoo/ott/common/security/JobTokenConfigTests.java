package dev.gumwoo.ott.common.security;

import java.net.URI;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class JobTokenConfigTests {

	private static final TokenReviewProperties TOKEN_REVIEW_OFF = tokenReview(false, null);

	private static final ObjectProvider<SslBundles> NO_SSL_BUNDLES = new StaticListableBeanFactory()
		.getBeanProvider(SslBundles.class);

	@Test
	void devTokenOutsideDevProfileFailsStartup() {
		assertThatIllegalStateException().isThrownBy(() -> JobTokenConfig.create("dev-token", false))
			.withMessageContaining("ott.internal.dev-token");
	}

	@Test
	void profileDecidesWhetherTheDevTokenIsAllowed() {
		JobTokenConfig config = new JobTokenConfig();
		MockEnvironment local = new MockEnvironment();
		local.setActiveProfiles("local");

		assertThat(config.jobTokenVerifier("dev-token", TOKEN_REVIEW_OFF, NO_SSL_BUNDLES, local).verify("dev-token"))
			.isTrue();
		assertThatIllegalStateException().isThrownBy(
				() -> config.jobTokenVerifier("dev-token", TOKEN_REVIEW_OFF, NO_SSL_BUNDLES, new MockEnvironment()));
	}

	@Test
	void devTokenAndTokenReviewTogetherFailStartup() {
		MockEnvironment local = new MockEnvironment();
		local.setActiveProfiles("local");

		assertThatIllegalStateException()
			.isThrownBy(() -> new JobTokenConfig().jobTokenVerifier("dev-token",
					tokenReview(true, "system:serviceaccount:ott:transcoder"), NO_SSL_BUNDLES, local))
			.withMessageContaining("not both");
	}

	@Test
	void tokenReviewWithoutServiceAccountFailsStartup() {
		assertThatIllegalStateException()
			.isThrownBy(() -> new JobTokenConfig().jobTokenVerifier(null, tokenReview(true, " "), NO_SSL_BUNDLES,
					new MockEnvironment()))
			.withMessageContaining("service-account");
	}

	@Test
	void blankDevTokenFailsStartup() {
		assertThatIllegalStateException().isThrownBy(() -> JobTokenConfig.create(" ", true));
	}

	@Test
	void devTokenMatchesOnlyItself() {
		JobTokenVerifier verifier = JobTokenConfig.create("dev-token", true);

		assertThat(verifier.verify("dev-token")).isTrue();
		assertThat(verifier.verify("dev-token2")).isFalse();
		assertThat(verifier.verify("")).isFalse();
	}

	@Test
	void withoutVerifierEveryTokenIsRejectedInDevProfile() {
		assertThat(JobTokenConfig.create(null, true).verify("anything")).isFalse();
	}

	@Test
	void withoutVerifierOutsideDevProfileFailsStartup() {
		assertThatIllegalStateException().isThrownBy(() -> JobTokenConfig.create(null, false))
			.withMessageContaining("ott.internal.token-review.enabled");
	}

	private static TokenReviewProperties tokenReview(boolean enabled, String serviceAccount) {
		return new TokenReviewProperties(enabled, URI.create("https://kubernetes.default.svc"),
				new ByteArrayResource(new byte[0]), "kubernetes", "ott-api", serviceAccount);
	}

}
