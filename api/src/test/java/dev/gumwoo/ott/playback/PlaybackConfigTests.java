package dev.gumwoo.ott.playback;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class PlaybackConfigTests {

	private static final String BASE_URL = "https://d111111abcdef8.cloudfront.net";

	@Test
	void ephemeralKeyWorksInDevProfile() throws Exception {
		assertThat(PlaybackConfig.create(properties(BASE_URL, true), true)).isNotNull();
	}

	@Test
	void ephemeralKeyIsRefusedOutsideDevProfile() {
		assertThatIllegalStateException().isThrownBy(() -> PlaybackConfig.create(properties(BASE_URL, true), false))
			.withMessageContaining("ephemeral-key");
	}

	@Test
	void missingPrivateKeyFailsStartup() {
		assertThatIllegalStateException().isThrownBy(() -> PlaybackConfig.create(properties(BASE_URL, false), true))
			.withMessageContaining("private-key");
	}

	@Test
	void missingResourceBaseUrlFailsStartup() {
		assertThatIllegalStateException().isThrownBy(() -> PlaybackConfig.create(properties(null, true), true))
			.withMessageContaining("resource-base-url");
	}

	private static PlaybackProperties properties(String baseUrl, boolean ephemeral) {
		return new PlaybackProperties(Duration.ofMinutes(30), baseUrl,
				new PlaybackProperties.Signing("K2JCJMDEHXQW5F", null, ephemeral));
	}

}
