package dev.gumwoo.ott.jobapi;

import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

class OpsCheckTests {

	private final TranscodeJobService jobs = mock(TranscodeJobService.class);

	private final ContentArtifacts artifacts = mock(ContentArtifacts.class);

	// kind에서 transcoder(uid 999)가 만든 폴더를 다른 uid가 지우지 못해 점검 전체가 멈췄다
	@Test
	void oneUndeletableVersionDoesNotStopTheRestAndFailsTheRun() {
		TranscodeJob locked = failed("locked", 1);
		TranscodeJob free = failed("free", 1);
		given(this.jobs.cleanupCandidates(any())).willReturn(List.of(locked, free));
		willThrow(new UncheckedIOException(new AccessDeniedException("/data/content/locked/v1/master.m3u8")))
			.given(this.artifacts)
			.delete("locked", 1);
		given(this.artifacts.delete("free", 1)).willReturn(true);
		OpsCheck opsCheck = new OpsCheck(this.jobs, this.artifacts,
				new OpsCheckProperties(true, Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(2)));

		opsCheck.run(null);

		assertThat(opsCheck.check().cleanupFailed()).containsExactly(locked);
		assertThat(opsCheck.check().deleted()).containsExactly(free);
		assertThat(opsCheck.getExitCode()).isEqualTo(1);
	}

	private static TranscodeJob failed(String contentId, int version) {
		return new TranscodeJob(UUID.randomUUID(), contentId, version, 0, "x.mp4", "a".repeat(64), "ladder-1",
				JobStatus.FAILED, FailureCode.LOST, null, null, "transcode-" + contentId + "-v" + version, null, null,
				Instant.now(), Instant.now());
	}

}
