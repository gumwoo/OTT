package dev.gumwoo.ott.jobapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import dev.gumwoo.ott.common.error.ApiException;
import dev.gumwoo.ott.common.error.ErrorCode;
import dev.gumwoo.ott.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

// 테스트 DB를 다른 테스트와 같이 쓰므로 결과는 이 테스트의 콘텐츠로만 거른다
class OpsCheckIntegrationTests extends IntegrationTest {

	private static final Path CONTENT_ROOT = Path.of("build/test-content");

	@Autowired
	TranscodeJobService jobs;

	@Autowired
	ContentArtifacts artifacts;

	@Autowired
	JdbcTemplate jdbc;

	private OpsCheck opsCheck;

	private String contentId;

	@BeforeEach
	void setUp() {
		this.opsCheck = new OpsCheck(this.jobs, this.artifacts,
				new OpsCheckProperties(false, Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(2)));
		this.contentId = "ops-" + UUID.randomUUID().toString().substring(0, 12);
		this.jdbc.update("insert into contents (id, title, required_plan, duration_sec) values (?, 'Ops', 'BASIC', 60)",
				this.contentId);
	}

	@Test
	void runningJobWithoutHeartbeatBecomesLostAndIsToldToStop() {
		TranscodeJob silent = running();
		TranscodeJob alive = running();
		this.jdbc.update("update transcode_jobs set heartbeat_at = now() - interval '5 minutes' where id = ?",
				silent.id());

		OpsCheck.Result result = this.opsCheck.check();

		assertThat(result.lost()).extracting(TranscodeJob::id).contains(silent.id()).doesNotContain(alive.id());
		TranscodeJob lost = this.jobs.find(silent.id());
		assertThat(lost.status()).isEqualTo(JobStatus.FAILED);
		assertThat(lost.failureCode()).isEqualTo(FailureCode.LOST);
		assertThat(lost.error()).startsWith("no heartbeat since");
		assertThat(this.jobs.find(alive.id()).status()).isEqualTo(JobStatus.RUNNING);
		assertJobFailed(() -> this.jobs.heartbeat(silent.id()));
		assertJobFailed(() -> this.jobs.report(silent.id(), new JobReport(JobStatus.VALIDATED, null, null)));
	}

	@Test
	void requestedJobPastStartDeadlineBecomesNotStarted() {
		TranscodeJob late = register();
		TranscodeJob fresh = register();
		this.jdbc.update("update transcode_jobs set created_at = now() - interval '20 minutes' where id = ?",
				late.id());

		OpsCheck.Result result = this.opsCheck.check();

		assertThat(result.notStarted()).extracting(TranscodeJob::id).contains(late.id()).doesNotContain(fresh.id());
		assertThat(this.jobs.find(late.id()).failureCode()).isEqualTo(FailureCode.NOT_STARTED);
		assertThat(this.jobs.find(fresh.id()).status()).isEqualTo(JobStatus.REQUESTED);
		assertJobFailed(() -> this.jobs.report(late.id(), new JobReport(JobStatus.RUNNING, null, null)));
	}

	@Test
	void onlyLongFailedVersionsThatWereNeverCurrentAreDeleted() throws IOException {
		TranscodeJob old = failed("3 hours");
		TranscodeJob recent = failed("10 minutes");
		TranscodeJob current = failed("3 hours");
		this.jdbc.update("update contents set current_version = ? where id = ?", current.version(), this.contentId);
		for (TranscodeJob job : new TranscodeJob[] { old, recent, current }) {
			Files.createDirectories(versionDir(job));
			Files.write(versionDir(job).resolve("360p_00000.ts"), new byte[] { 0x47 });
		}

		OpsCheck.Result result = this.opsCheck.check();

		assertThat(result.deleted()).extracting(TranscodeJob::id)
			.contains(old.id())
			.doesNotContain(recent.id(), current.id());
		assertThat(versionDir(old)).doesNotExist();
		assertThat(versionDir(recent)).isDirectory();
		assertThat(versionDir(current)).isDirectory();
	}

	@Test
	void publishedAndSeedVersionsAreNeverDeleted() throws IOException {
		TranscodeJob published = register();
		this.jdbc.update(
				"update transcode_jobs set status = 'PUBLISHED', updated_at = now() - interval '3 hours' where id = ?",
				published.id());
		Path seed = CONTENT_ROOT.resolve(this.contentId).resolve("v99");
		for (Path dir : new Path[] { versionDir(published), seed }) {
			Files.createDirectories(dir);
			Files.write(dir.resolve("360p_00000.ts"), new byte[] { 0x47 });
		}

		this.opsCheck.check();

		assertThat(versionDir(published)).isDirectory();
		assertThat(seed).isDirectory();
	}

	@Test
	void incompleteCurrentVersionIsReported() {
		this.jdbc.update("update contents set current_version = 7 where id = ?", this.contentId);

		OpsCheck.Result result = this.opsCheck.check();

		assertThat(result.incomplete()).contains(new TranscodeJobService.ContentVersion(this.contentId, 7));
	}

	private TranscodeJob register() {
		return this.jobs.register(this.contentId, new JobRequest("ops.mp4", "b".repeat(64), "ladder-1"));
	}

	private TranscodeJob running() {
		TranscodeJob job = register();
		this.jobs.report(job.id(), new JobReport(JobStatus.RUNNING, null, null));
		return job;
	}

	private TranscodeJob failed(String ago) {
		TranscodeJob job = register();
		this.jdbc.update(
				"update transcode_jobs set status = 'FAILED', failure_code = 'LOST', updated_at = now() - ?::interval where id = ?",
				ago, job.id());
		return job;
	}

	private Path versionDir(TranscodeJob job) {
		return CONTENT_ROOT.resolve(this.contentId).resolve("v" + job.version());
	}

	private static void assertJobFailed(Runnable call) {
		assertThatExceptionOfType(ApiException.class).isThrownBy(call::run)
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo(ErrorCode.JOB_FAILED));
	}

}
