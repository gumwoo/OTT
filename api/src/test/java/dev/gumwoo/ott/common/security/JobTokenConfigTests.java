package dev.gumwoo.ott.common.security;

import org.junit.jupiter.api.Test;

import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class JobTokenConfigTests {

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

		assertThat(config.jobTokenVerifier("dev-token", local).verify("dev-token")).isTrue();
		assertThatIllegalStateException().isThrownBy(() -> config.jobTokenVerifier("dev-token", new MockEnvironment()));
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
	void withoutVerifierEveryTokenIsRejected() {
		assertThat(JobTokenConfig.create(null, false).verify("anything")).isFalse();
	}

}
