package dev.gumwoo.ott.jobapi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import dev.gumwoo.ott.common.error.ApiException;
import dev.gumwoo.ott.common.error.ErrorCode;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 등록·게시·롤백은 contents 행 FOR UPDATE로 직렬화한다. 롤백은 job 행을 잠그지 않는다 (design.md §4)
@Service
public class TranscodeJobService {

	private final JdbcClient jdbc;

	TranscodeJobService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public TranscodeJob register(String contentId, JobRequest request) {
		ContentPointer content = lockContent(contentId);
		int version = content.lastAllocatedVersion() + 1;
		UUID id = UUID.randomUUID();
		this.jdbc.sql("update contents set last_allocated_version = ? where id = ?")
			.params(version, contentId)
			.update();
		this.jdbc.sql("""
				insert into content_versions (content_id, version, profile_version, source_hash)
				values (?, ?, ?, ?)
				""").params(contentId, version, request.profileVersion(), request.sourceHash()).update();
		this.jdbc.sql("""
				insert into transcode_jobs (id, content_id, version, publish_epoch, source_key, source_hash,
				    profile_version, status, k8s_job_name)
				values (?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
				""")
			.params(id, contentId, version, content.publishEpoch(), request.sourceKey(), request.sourceHash(),
					request.profileVersion(), "transcode-" + contentId + "-v" + version)
			.update();
		return find(id);
	}

	@Transactional(readOnly = true)
	public TranscodeJob find(UUID jobId) {
		return this.jdbc.sql("select * from transcode_jobs where id = ?")
			.param(jobId)
			.query(TranscodeJobService::job)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	@Transactional
	public void report(UUID jobId, JobReport report) {
		validate(report);
		JobStatus current = lockJob(jobId).status();
		if (current == JobStatus.FAILED) {
			throw new ApiException(ErrorCode.JOB_FAILED);
		}
		// 응답을 못 받은 Job이 같은 보고를 다시 보낸 경우
		if (current == report.status()) {
			return;
		}
		if (!current.canMoveTo(report.status())) {
			throw new ApiException(ErrorCode.INVALID_JOB_STATE);
		}
		boolean started = report.status() == JobStatus.RUNNING;
		this.jdbc.sql("""
				update transcode_jobs
				set status = ?, failure_code = ?, error = ?, updated_at = now(),
				    started_at = case when ? then now() else started_at end,
				    heartbeat_at = case when ? then now() else heartbeat_at end
				where id = ?
				""")
			.params(report.status().name(), (report.failureCode() != null) ? report.failureCode().name() : null,
					report.error(), started, started, jobId)
			.update();
	}

	@Transactional
	public void heartbeat(UUID jobId) {
		int updated = this.jdbc.sql("""
				update transcode_jobs set heartbeat_at = now()
				where id = ? and status in ('RUNNING', 'VALIDATED', 'UPLOADED')
				""").param(jobId).update();
		if (updated == 1) {
			return;
		}
		JobStatus status = find(jobId).status();
		if (status == JobStatus.FAILED) {
			throw new ApiException(ErrorCode.JOB_FAILED);
		}
		if (status == JobStatus.REQUESTED) {
			throw new ApiException(ErrorCode.INVALID_JOB_STATE);
		}
	}

	// 산출물 확인은 이 트랜잭션 밖에서 끝낸다 (SP-07, PublishService)
	@Transactional
	public PublishResult publish(UUID jobId, boolean artifactsChecked) {
		TranscodeJob job = lockJob(jobId);
		if (job.status().published()) {
			return new PublishResult(job.status(), job.supersededReason(), job.version(),
					currentVersion(job.contentId()));
		}
		if (job.status() == JobStatus.FAILED) {
			throw new ApiException(ErrorCode.JOB_FAILED);
		}
		// 확인 뒤에 UPLOADED가 된 경우. 확인 없이 게시하지 않고 Job이 다시 요청하게 한다
		if (job.status() != JobStatus.UPLOADED || !artifactsChecked) {
			throw new ApiException(ErrorCode.INVALID_JOB_STATE);
		}
		ContentPointer content = lockContent(job.contentId());
		SupersededReason reason = supersededReason(job, content);
		if (reason != null) {
			this.jdbc.sql("""
					update transcode_jobs set status = 'PUBLISHED_SUPERSEDED', superseded_reason = ?, updated_at = now()
					where id = ?
					""").params(reason.name(), jobId).update();
			return new PublishResult(JobStatus.PUBLISHED_SUPERSEDED, reason, job.version(), content.currentVersion());
		}
		this.jdbc.sql("update contents set current_version = ?, max_published_version = ? where id = ?")
			.params(job.version(), job.version(), job.contentId())
			.update();
		this.jdbc.sql("update transcode_jobs set status = 'PUBLISHED', updated_at = now() where id = ?")
			.param(jobId)
			.update();
		return new PublishResult(JobStatus.PUBLISHED, null, job.version(), job.version());
	}

	// 공개된 적 있는 버전이나 게시 검증을 통과한 PUBLISHED_SUPERSEDED 버전만 고를 수 있다
	@Transactional
	public RollbackResult rollback(String contentId, int version) {
		lockContent(contentId);
		boolean eligible = this.jdbc.sql("""
				select exists (select 1 from transcode_jobs
				               where content_id = ? and version = ? and status in ('PUBLISHED', 'PUBLISHED_SUPERSEDED'))
				""").params(contentId, version).query(Boolean.class).single();
		if (!eligible) {
			throw new ApiException(ErrorCode.ROLLBACK_NOT_ALLOWED);
		}
		return this.jdbc.sql("""
				update contents
				set current_version = ?, publish_epoch = publish_epoch + 1,
				    max_published_version = greatest(coalesce(max_published_version, 0), ?)
				where id = ?
				returning id, current_version, max_published_version, publish_epoch
				""")
			.params(version, version, contentId)
			.query((rs, row) -> new RollbackResult(rs.getString("id"), rs.getInt("current_version"),
					rs.getInt("max_published_version"), rs.getInt("publish_epoch")))
			.single();
	}

	private static SupersededReason supersededReason(TranscodeJob job, ContentPointer content) {
		if (job.publishEpoch() != content.publishEpoch()) {
			return SupersededReason.STALE_EPOCH;
		}
		Integer max = content.maxPublishedVersion();
		if (max != null && max >= job.version()) {
			return SupersededReason.OLDER_VERSION;
		}
		return null;
	}

	private static void validate(JobReport report) {
		if (!JobStatus.REPORTABLE.contains(report.status())) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "Job이 보고할 수 없는 상태입니다");
		}
		boolean failed = report.status() == JobStatus.FAILED;
		if (failed != (report.failureCode() != null)
				|| (report.failureCode() != null && !report.failureCode().reportableByJob())) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "failureCode는 FAILED 보고에만, Job이 보고할 수 있는 값으로 보냅니다");
		}
	}

	private ContentPointer lockContent(String contentId) {
		return this.jdbc.sql("""
				select current_version, max_published_version, publish_epoch, last_allocated_version
				from contents where id = ? for update
				""")
			.param(contentId)
			.query((rs, row) -> new ContentPointer(nullableInt(rs, "current_version"),
					nullableInt(rs, "max_published_version"), rs.getInt("publish_epoch"),
					rs.getInt("last_allocated_version")))
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private TranscodeJob lockJob(UUID jobId) {
		return this.jdbc.sql("select * from transcode_jobs where id = ? for update")
			.param(jobId)
			.query(TranscodeJobService::job)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private Integer currentVersion(String contentId) {
		return this.jdbc.sql("select current_version from contents where id = ?")
			.param(contentId)
			.query((rs, row) -> nullableInt(rs, "current_version"))
			.single();
	}

	private static TranscodeJob job(ResultSet rs, int row) throws SQLException {
		return new TranscodeJob(rs.getObject("id", UUID.class), rs.getString("content_id"), rs.getInt("version"),
				rs.getInt("publish_epoch"), rs.getString("source_key"), rs.getString("source_hash"),
				rs.getString("profile_version"), JobStatus.valueOf(rs.getString("status")),
				nullableEnum(FailureCode.class, rs.getString("failure_code")),
				nullableEnum(SupersededReason.class, rs.getString("superseded_reason")), rs.getString("error"),
				rs.getString("k8s_job_name"), instant(rs, "started_at"), instant(rs, "heartbeat_at"),
				instant(rs, "created_at"), instant(rs, "updated_at"));
	}

	private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	private static <E extends Enum<E>> E nullableEnum(Class<E> type, String value) {
		return (value != null) ? Enum.valueOf(type, value) : null;
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return (timestamp != null) ? timestamp.toInstant() : null;
	}

	private record ContentPointer(Integer currentVersion, Integer maxPublishedVersion, int publishEpoch,
			int lastAllocatedVersion) {
	}

}
