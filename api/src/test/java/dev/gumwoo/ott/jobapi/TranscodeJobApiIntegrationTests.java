package dev.gumwoo.ott.jobapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import dev.gumwoo.ott.account.AccountService;
import dev.gumwoo.ott.common.error.ApiException;
import dev.gumwoo.ott.support.Browser;
import dev.gumwoo.ott.support.Browser.Response;
import dev.gumwoo.ott.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class TranscodeJobApiIntegrationTests extends IntegrationTest {

	private static final String SOURCE_HASH = "a".repeat(64);

	private static final Path CONTENT_ROOT = Path.of("build/test-content");

	@Autowired
	AccountService accounts;

	@Autowired
	TranscodeJobService jobs;

	@Autowired
	JdbcTemplate jdbc;

	private Browser admin;

	private String contentId;

	@BeforeEach
	void registerContent() {
		String email = uniqueEmail();
		this.accounts.createAdminIfAbsent(email, PASSWORD);
		this.admin = browser();
		this.admin.post("/api/auth/login", credentials(email, PASSWORD));
		this.admin.get("/api/csrf");
		this.contentId = "c-" + UUID.randomUUID().toString().substring(0, 12);
		Response created = this.admin.post("/api/admin/contents", """
				{"id": "%s", "title": "Spring", "requiredPlan": "BASIC", "durationSec": 464}
				""".formatted(this.contentId));
		assertThat(created.status()).isEqualTo(201);
	}

	@Test
	void registeringJobsAllocatesIncreasingVersions() {
		JsonNode first = register();
		JsonNode second = register();

		assertThat(first.get("version").asInt()).isEqualTo(1);
		assertThat(second.get("version").asInt()).isEqualTo(2);
		assertThat(first.get("status").asString()).isEqualTo("REQUESTED");
		assertThat(first.get("publishEpoch").asInt()).isZero();
		assertThat(first.get("k8sJobName").asString()).isEqualTo("transcode-" + this.contentId + "-v1");
		assertThat(this.jdbc.queryForObject("select count(*) from content_versions where content_id = ?", Integer.class,
				this.contentId))
			.isEqualTo(2);
	}

	@Test
	void concurrentRegistrationsGetDifferentVersions() {
		List<TranscodeJob> registered = concurrently(
				() -> this.jobs.register(this.contentId, new JobRequest("source.mp4", SOURCE_HASH, "ladder-1")),
				() -> this.jobs.register(this.contentId, new JobRequest("source.mp4", SOURCE_HASH, "ladder-1")));

		assertThat(registered).extracting(TranscodeJob::version).containsExactlyInAnyOrder(1, 2);
	}

	@Test
	void contentIsRegisteredOnceAndOnlyByAdmin() {
		Response duplicate = this.admin.post("/api/admin/contents", """
				{"id": "%s", "title": "Again", "requiredPlan": "BASIC", "durationSec": 1}
				""".formatted(this.contentId));
		Response asUser = loggedIn(uniqueEmail()).post("/api/admin/contents/" + this.contentId + "/transcode-jobs",
				jobRequest());

		assertThat(duplicate.status()).isEqualTo(409);
		assertThat(duplicate.json().get("code").asString()).isEqualTo("CONTENT_EXISTS");
		assertThat(asUser.status()).isEqualTo(403);
		assertThat(asUser.json().get("code").asString()).isEqualTo("FORBIDDEN");
	}

	@Test
	void internalEndpointsAcceptOnlyTheJobToken() {
		String path = "/internal/jobs/" + register().get("id").asString() + "/heartbeat";

		Response noToken = new Browser(this.port).post(path, "");
		Response wrongToken = new Browser(this.port).header("Authorization", "Bearer wrong").post(path, "");
		Response adminSession = this.admin.post(path, "");
		Response tokenOnApi = jobClient().get("/api/me");

		assertThat(List.of(noToken, wrongToken, adminSession, tokenOnApi)).allSatisfy((response) -> {
			assertThat(response.status()).isEqualTo(401);
			assertThat(response.json().get("code").asString()).isEqualTo("UNAUTHENTICATED");
		});
	}

	@Test
	void jobReportsMoveForwardOneStepAtATime() {
		String id = register().get("id").asString();

		assertThat(report(id, "UPLOADED").status()).isEqualTo(409);
		assertThat(report(id, "RUNNING").status()).isEqualTo(204);
		assertThat(report(id, "RUNNING").status()).isEqualTo(204);
		assertThat(this.jdbc.queryForMap("select started_at, heartbeat_at from transcode_jobs where id = ?::uuid", id))
			.doesNotContainValue(null);
		assertThat(report(id, "VALIDATED").status()).isEqualTo(204);
		assertThat(report(id, "UPLOADED").status()).isEqualTo(204);
		assertThat(job(id).get("status").asString()).isEqualTo("UPLOADED");
	}

	@Test
	void failedJobIsToldToStop() {
		String failed = register().get("id").asString();
		String lost = register().get("id").asString();
		String notStarted = register().get("id").asString();
		report(failed, "RUNNING");
		report(lost, "RUNNING");

		Response reportFailed = jobClient().send("PATCH", "/internal/jobs/" + failed,
				"{\"status\": \"FAILED\", \"failureCode\": \"VERSION_DIRTY\", \"error\": \"v1 not empty\"}",
				"application/json");
		// 운영 CronJob이 heartbeat 끊김으로 확정한 경우
		this.jdbc.update("update transcode_jobs set status = 'FAILED', failure_code = 'LOST' where id = ?::uuid", lost);
		this.jdbc.update("update transcode_jobs set status = 'FAILED', failure_code = 'NOT_STARTED' where id = ?::uuid",
				notStarted);

		assertThat(reportFailed.status()).isEqualTo(204);
		assertThat(report(notStarted, "RUNNING").json().get("code").asString()).isEqualTo("JOB_FAILED");
		for (String id : List.of(failed, lost)) {
			Response heartbeat = jobClient().post("/internal/jobs/" + id + "/heartbeat", "");
			assertThat(heartbeat.status()).isEqualTo(409);
			assertThat(heartbeat.json().get("code").asString()).isEqualTo("JOB_FAILED");
			assertThat(report(id, "VALIDATED").json().get("code").asString()).isEqualTo("JOB_FAILED");
		}
	}

	@Test
	void jobCannotDeclareItselfLost() {
		String id = register().get("id").asString();

		Response response = jobClient().send("PATCH", "/internal/jobs/" + id,
				"{\"status\": \"FAILED\", \"failureCode\": \"LOST\"}", "application/json");

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.json().get("code").asString()).isEqualTo("VALIDATION_FAILED");
	}

	@Test
	void firstPublishSwitchesThePointer() throws IOException {
		JsonNode job = uploaded();

		Response response = publish(job);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.json().get("status").asString()).isEqualTo("PUBLISHED");
		assertThat(pointer()).containsEntry("current_version", 1).containsEntry("max_published_version", 1);
		assertThat(loggedIn(uniqueEmail()).get("/api/contents").body()).contains(this.contentId);
	}

	@Test
	void repeatedPublishDoesNotMoveThePointerBack() throws IOException {
		JsonNode first = uploaded();
		JsonNode second = uploaded();
		publish(first);
		publish(second);

		Response again = publish(first);

		assertThat(again.status()).isEqualTo(200);
		assertThat(again.json().get("status").asString()).isEqualTo("PUBLISHED");
		assertThat(again.json().get("currentVersion").asInt()).isEqualTo(2);
		assertThat(pointer()).containsEntry("current_version", 2);
	}

	@Test
	void olderVersionFinishingLateIsSuperseded() throws IOException {
		JsonNode older = uploaded();
		JsonNode newer = uploaded();
		publish(newer);

		Response late = publish(older);

		assertThat(late.json().get("status").asString()).isEqualTo("PUBLISHED_SUPERSEDED");
		assertThat(late.json().get("reason").asString()).isEqualTo("OLDER_VERSION");
		assertThat(pointer()).containsEntry("current_version", 2).containsEntry("max_published_version", 2);
	}

	// v1·v2 공개 → v3 실행 중 → v1로 롤백 → v3 완료. 롤백 전에 등록된 v3은 롤백을 덮지 않는다
	@Test
	void rollbackIsNotOverriddenByJobsRegisteredBeforeIt() throws IOException {
		publish(uploaded());
		publish(uploaded());
		JsonNode running = register();
		report(running.get("id").asString(), "RUNNING");

		Response rollback = this.admin.post("/api/admin/contents/" + this.contentId + "/rollback", "{\"version\": 1}");
		upload(running);
		Response stale = publish(running);
		Response afterRollback = publish(uploaded());

		assertThat(rollback.status()).isEqualTo(200);
		assertThat(rollback.json().get("publishEpoch").asInt()).isEqualTo(1);
		assertThat(stale.json().get("status").asString()).isEqualTo("PUBLISHED_SUPERSEDED");
		assertThat(stale.json().get("reason").asString()).isEqualTo("STALE_EPOCH");
		assertThat(afterRollback.json().get("status").asString()).isEqualTo("PUBLISHED");
		assertThat(pointer()).containsEntry("current_version", 4).containsEntry("max_published_version", 4);
	}

	@Test
	void rollbackTargetsOnlyVersionsThatPassedPublish() throws IOException {
		publish(uploaded());
		JsonNode superseded = uploaded();
		this.admin.post("/api/admin/contents/" + this.contentId + "/rollback", "{\"version\": 1}");
		assertThat(publish(superseded).json().get("reason").asString()).isEqualTo("STALE_EPOCH");
		register();

		Response toUnpublished = this.admin.post("/api/admin/contents/" + this.contentId + "/rollback",
				"{\"version\": 3}");
		Response toSuperseded = this.admin.post("/api/admin/contents/" + this.contentId + "/rollback",
				"{\"version\": 2}");

		assertThat(toUnpublished.status()).isEqualTo(409);
		assertThat(toUnpublished.json().get("code").asString()).isEqualTo("ROLLBACK_NOT_ALLOWED");
		assertThat(toSuperseded.status()).isEqualTo(200);
		assertThat(pointer()).containsEntry("current_version", 2)
			.containsEntry("max_published_version", 2)
			.containsEntry("publish_epoch", 2);
	}

	@Test
	void incompleteArtifactsAreNotPublished() throws IOException {
		JsonNode job = uploaded();
		Files.delete(versionDir(job).resolve("360p_00001.ts"));

		Response response = publish(job);

		assertThat(response.status()).isEqualTo(409);
		assertThat(response.json().get("code").asString()).isEqualTo("ARTIFACTS_MISSING");
		assertThat(response.json().get("detail").asString()).contains("360p_00001.ts");
		assertThat(job(job.get("id").asString()).get("status").asString()).isEqualTo("UPLOADED");
		assertThat(pointer()).containsEntry("current_version", null);
	}

	@Test
	void publishMustMatchTheRegisteredJobAndFinishedUpload() throws IOException {
		JsonNode notUploaded = register();
		JsonNode job = uploaded();
		String id = job.get("id").asString();

		Response mismatch = jobClient().post("/internal/jobs/" + id + "/publish", """
				{"contentId": "%s", "version": 2, "sourceHash": "%s", "profileVersion": "ladder-1"}
				""".formatted(this.contentId, "b".repeat(64)));
		Response early = publish(notUploaded);

		assertThat(mismatch.json().get("code").asString()).isEqualTo("JOB_MISMATCH");
		assertThat(early.json().get("code").asString()).isEqualTo("INVALID_JOB_STATE");
		assertThat(pointer()).containsEntry("current_version", null);
	}

	@Test
	void concurrentPublishesKeepTheHighestVersion() throws IOException {
		JsonNode older = uploaded();
		JsonNode newer = uploaded();

		List<Response> responses = concurrently(() -> publish(older), () -> publish(newer));

		assertThat(responses).extracting(Response::status).containsOnly(200);
		assertThat(pointer()).containsEntry("current_version", 2).containsEntry("max_published_version", 2);
		assertThat(job(older.get("id").asString()).get("status").asString()).isIn("PUBLISHED", "PUBLISHED_SUPERSEDED");
	}

	// 어느 쪽이 먼저 잠그든 롤백이 남고, v2의 결과는 그 순서와 맞는다
	@Test
	void rollbackAndPublishAtTheSameTimeSettleInOneOrder() throws IOException {
		publish(uploaded());
		JsonNode pending = uploaded();

		concurrently(() -> this.admin.post("/api/admin/contents/" + this.contentId + "/rollback", "{\"version\": 1}"),
				() -> publish(pending));

		JsonNode result = job(pending.get("id").asString());
		assertThat(pointer()).containsEntry("current_version", 1).containsEntry("publish_epoch", 1);
		if ("PUBLISHED".equals(result.get("status").asString())) {
			assertThat(pointer()).containsEntry("max_published_version", 2);
		}
		else {
			assertThat(result.get("supersededReason").asString()).isEqualTo("STALE_EPOCH");
			assertThat(pointer()).containsEntry("max_published_version", 1);
		}
	}

	// 산출물을 확인할 때는 아직 UPLOADED가 아니었고, 잠근 뒤에는 UPLOADED인 경우
	@Test
	void jobThatBecameUploadedAfterTheCheckIsNotPublished() throws IOException {
		JsonNode job = uploaded();
		UUID id = UUID.fromString(job.get("id").asString());

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> this.jobs.publish(id, false))
			.satisfies((ex) -> assertThat(ex.code().name()).isEqualTo("INVALID_JOB_STATE"));
		assertThat(pointer()).containsEntry("current_version", null);
	}

	// 등록이 먼저 잠그면 이전 세대를 기록해 STALE_EPOCH, 롤백이 먼저면 새 세대로 정상 게시된다
	@Test
	void registrationAndRollbackAtTheSameTimeSettleInOneOrder() throws IOException {
		publish(uploaded());

		List<Object> results = concurrently(
				() -> this.jobs.register(this.contentId, new JobRequest("spring.mp4", SOURCE_HASH, "ladder-1")),
				() -> this.admin.post("/api/admin/contents/" + this.contentId + "/rollback", "{\"version\": 1}"));
		TranscodeJob registered = (TranscodeJob) results.get(0);
		JsonNode job = job(registered.id().toString());
		upload(job);
		Response published = publish(job);

		if (registered.publishEpoch() == 0) {
			assertThat(published.json().get("reason").asString()).isEqualTo("STALE_EPOCH");
			assertThat(pointer()).containsEntry("current_version", 1);
		}
		else {
			assertThat(published.json().get("status").asString()).isEqualTo("PUBLISHED");
			assertThat(pointer()).containsEntry("current_version", 2);
		}
	}

	private JsonNode register() {
		Response response = this.admin.post("/api/admin/contents/" + this.contentId + "/transcode-jobs", jobRequest());
		assertThat(response.status()).isEqualTo(201);
		return response.json();
	}

	private static String jobRequest() {
		return """
				{"sourceKey": "spring.mp4", "sourceHash": "%s", "profileVersion": "ladder-1"}
				""".formatted(SOURCE_HASH);
	}

	private JsonNode uploaded() throws IOException {
		JsonNode job = register();
		upload(job);
		return job;
	}

	private void upload(JsonNode job) throws IOException {
		String id = job.get("id").asString();
		writeArtifacts(versionDir(job));
		for (String status : List.of("RUNNING", "VALIDATED", "UPLOADED")) {
			if (!status.equals(job(id).get("status").asString())) {
				assertThat(report(id, status).status()).isEqualTo(204);
			}
		}
	}

	private Response publish(JsonNode job) {
		return jobClient().post("/internal/jobs/" + job.get("id").asString() + "/publish", """
				{"contentId": "%s", "version": %d, "sourceHash": "%s", "profileVersion": "ladder-1"}
				""".formatted(this.contentId, job.get("version").asInt(), SOURCE_HASH));
	}

	private Response report(String id, String status) {
		return jobClient().send("PATCH", "/internal/jobs/" + id, "{\"status\": \"" + status + "\"}",
				"application/json");
	}

	private JsonNode job(String id) {
		return this.admin.get("/api/admin/transcode-jobs/" + id).json();
	}

	private Map<String, Object> pointer() {
		return this.jdbc.queryForMap(
				"select current_version, max_published_version, publish_epoch from contents where id = ?",
				this.contentId);
	}

	private Browser jobClient() {
		return new Browser(this.port).header("Authorization", "Bearer test-job-token");
	}

	private Path versionDir(JsonNode job) {
		return CONTENT_ROOT.resolve(this.contentId).resolve("v" + job.get("version").asInt());
	}

	private static void writeArtifacts(Path dir) throws IOException {
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("master.m3u8"), """
				#EXTM3U
				#EXT-X-STREAM-INF:BANDWIDTH=1105941,RESOLUTION=640x268
				360p.m3u8
				""");
		Files.writeString(dir.resolve("360p.m3u8"), """
				#EXTM3U
				#EXT-X-TARGETDURATION:6
				#EXTINF:6.0,
				360p_00000.ts
				#EXTINF:4.0,
				360p_00001.ts
				#EXT-X-ENDLIST
				""");
		for (String segment : Set.of("360p_00000.ts", "360p_00001.ts")) {
			Files.write(dir.resolve(segment), new byte[] { 0x47 });
		}
	}

	@SafeVarargs
	private static <T> List<T> concurrently(Supplier<T>... tasks) {
		CyclicBarrier start = new CyclicBarrier(tasks.length);
		List<CompletableFuture<T>> futures = List.of(tasks).stream().map((task) -> CompletableFuture.supplyAsync(() -> {
			try {
				start.await(10, TimeUnit.SECONDS);
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			return task.get();
		}).orTimeout(30, TimeUnit.SECONDS)).toList();
		return futures.stream().map(CompletableFuture::join).toList();
	}

}
