package dev.gumwoo.ott.jobapi;

import java.util.List;
import java.util.UUID;

import dev.gumwoo.ott.common.error.ApiException;
import dev.gumwoo.ott.common.error.ErrorCode;

import org.springframework.stereotype.Service;

// 산출물 확인(원격 I/O)을 트랜잭션 밖에서 먼저 한다 (SP-07). 버전 경로는 불변이라 확인 뒤에 바뀌지 않는다
@Service
public class PublishService {

	private static final int REPORTED_PROBLEMS = 5;

	private final TranscodeJobService jobs;

	private final ContentArtifacts artifacts;

	PublishService(TranscodeJobService jobs, ContentArtifacts artifacts) {
		this.jobs = jobs;
		this.artifacts = artifacts;
	}

	public PublishResult publish(UUID jobId, PublishRequest request) {
		TranscodeJob job = this.jobs.find(jobId);
		if (!job.matches(request)) {
			throw new ApiException(ErrorCode.JOB_MISMATCH);
		}
		boolean checked = job.status() == JobStatus.UPLOADED;
		if (checked) {
			List<String> problems = this.artifacts.problems(job.contentId(), job.version());
			if (!problems.isEmpty()) {
				throw new ApiException(ErrorCode.ARTIFACTS_MISSING,
						String.join(", ", problems.subList(0, Math.min(REPORTED_PROBLEMS, problems.size()))));
			}
		}
		return this.jobs.publish(jobId, checked);
	}

}
