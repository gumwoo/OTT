package dev.gumwoo.ott.jobapi;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 운영 CronJob이 api 이미지로 한 번 실행한다. 순서는 design.md §10 1단계: 생존 확인 → 공개 버전 무결성 → 미완성 버전 정리
@Component
@ConditionalOnProperty(name = "ott.ops-check.run", havingValue = "true")
class OpsCheck implements ApplicationRunner, ExitCodeGenerator {

	private static final Logger log = LoggerFactory.getLogger(OpsCheck.class);

	private final TranscodeJobService jobs;

	private final ContentArtifacts artifacts;

	private final OpsCheckProperties properties;

	private int exitCode;

	OpsCheck(TranscodeJobService jobs, ContentArtifacts artifacts, OpsCheckProperties properties) {
		this.jobs = jobs;
		this.artifacts = artifacts;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		Result result = check();
		this.exitCode = (result.incomplete().isEmpty() && result.cleanupFailed().isEmpty()) ? 0 : 1;
	}

	@Override
	public int getExitCode() {
		return this.exitCode;
	}

	Result check() {
		List<TranscodeJob> lost = this.jobs.failLost(this.properties.lostAfter());
		lost.forEach((job) -> log.warn("Job {} ({} v{}) marked LOST: {}", job.id(), job.contentId(), job.version(),
				job.error()));
		List<TranscodeJob> notStarted = this.jobs.failNotStarted(this.properties.startDeadline());
		notStarted.forEach((job) -> log.warn("Job {} ({} v{}) marked NOT_STARTED: {}", job.id(), job.contentId(),
				job.version(), job.error()));
		List<TranscodeJobService.ContentVersion> incomplete = new ArrayList<>();
		for (TranscodeJobService.ContentVersion current : this.jobs.currentVersions()) {
			List<String> problems;
			try {
				problems = this.artifacts.problems(current.contentId(), current.version());
			}
			catch (RuntimeException ex) {
				problems = List.of("unreadable: " + ex.getMessage());
			}
			if (!problems.isEmpty()) {
				incomplete.add(current);
				log.error("Current version {} v{} is incomplete: {}", current.contentId(), current.version(), problems);
			}
		}
		List<TranscodeJob> deleted = new ArrayList<>();
		List<TranscodeJob> cleanupFailed = new ArrayList<>();
		for (TranscodeJob job : this.jobs.cleanupCandidates(this.properties.cleanupAfter())) {
			try {
				if (this.artifacts.delete(job.contentId(), job.version())) {
					deleted.add(job);
					log.info("Deleted unfinished version {} v{} (job {} {})", job.contentId(), job.version(), job.id(),
							job.failureCode());
				}
			}
			catch (RuntimeException ex) {
				cleanupFailed.add(job);
				log.error("Could not delete unfinished version {} v{} (job {})", job.contentId(), job.version(),
						job.id(), ex);
			}
		}
		log.info("Ops check finished: lost={}, notStarted={}, incomplete={}, deleted={}, cleanupFailed={}", lost.size(),
				notStarted.size(), incomplete.size(), deleted.size(), cleanupFailed.size());
		return new Result(lost, notStarted, incomplete, deleted, cleanupFailed);
	}

	record Result(List<TranscodeJob> lost, List<TranscodeJob> notStarted,
			List<TranscodeJobService.ContentVersion> incomplete, List<TranscodeJob> deleted,
			List<TranscodeJob> cleanupFailed) {
	}

}
