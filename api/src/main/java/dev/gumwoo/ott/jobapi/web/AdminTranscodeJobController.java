package dev.gumwoo.ott.jobapi.web;

import java.util.UUID;

import dev.gumwoo.ott.jobapi.JobRequest;
import dev.gumwoo.ott.jobapi.RollbackResult;
import dev.gumwoo.ott.jobapi.TranscodeJob;
import dev.gumwoo.ott.jobapi.TranscodeJobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// API는 작업 등록과 버전 할당만 한다. Kubernetes Job은 운영자 CLI가 만든다 (app-design.md §6)
@RestController
class AdminTranscodeJobController {

	private final TranscodeJobService jobs;

	AdminTranscodeJobController(TranscodeJobService jobs) {
		this.jobs = jobs;
	}

	@PostMapping("/api/admin/contents/{contentId}/transcode-jobs")
	@ResponseStatus(HttpStatus.CREATED)
	TranscodeJob register(@PathVariable String contentId, @Valid @RequestBody Request request) {
		return this.jobs.register(contentId,
				new JobRequest(request.sourceKey(), request.sourceHash(), request.profileVersion()));
	}

	@GetMapping("/api/admin/transcode-jobs/{jobId}")
	TranscodeJob find(@PathVariable UUID jobId) {
		return this.jobs.find(jobId);
	}

	@PostMapping("/api/admin/contents/{contentId}/rollback")
	RollbackResult rollback(@PathVariable String contentId, @Valid @RequestBody Rollback request) {
		return this.jobs.rollback(contentId, request.version());
	}

	record Request(@NotBlank @Size(max = 512) String sourceKey,
			@NotNull @Pattern(regexp = "[0-9a-f]{64}") String sourceHash,
			@NotNull @Pattern(regexp = "[a-z0-9][a-z0-9.-]{0,31}") String profileVersion) {
	}

	record Rollback(@Positive int version) {
	}

}
