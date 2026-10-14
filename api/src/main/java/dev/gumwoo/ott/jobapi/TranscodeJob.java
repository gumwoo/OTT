package dev.gumwoo.ott.jobapi;

import java.time.Instant;
import java.util.UUID;

public record TranscodeJob(UUID id, String contentId, int version, int publishEpoch, String sourceKey,
		String sourceHash, String profileVersion, JobStatus status, FailureCode failureCode,
		SupersededReason supersededReason, String error, String k8sJobName, Instant startedAt, Instant heartbeatAt,
		Instant createdAt, Instant updatedAt) {

	boolean matches(PublishRequest request) {
		return this.contentId.equals(request.contentId()) && this.version == request.version()
				&& this.sourceHash.equals(request.sourceHash()) && this.profileVersion.equals(request.profileVersion());
	}

}
