package dev.gumwoo.ott.jobapi.web;

import java.util.UUID;

import dev.gumwoo.ott.jobapi.FailureCode;
import dev.gumwoo.ott.jobapi.JobReport;
import dev.gumwoo.ott.jobapi.JobStatus;
import dev.gumwoo.ott.jobapi.PublishRequest;
import dev.gumwoo.ott.jobapi.PublishResult;
import dev.gumwoo.ott.jobapi.PublishService;
import dev.gumwoo.ott.jobapi.TranscodeJobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 409 JOB_FAILED를 받으면 Job은 즉시 멈춘다 (app-design.md §8)
@RestController
class InternalJobController {

	private final TranscodeJobService jobs;

	private final PublishService publisher;

	InternalJobController(TranscodeJobService jobs, PublishService publisher) {
		this.jobs = jobs;
		this.publisher = publisher;
	}

	@PatchMapping("/internal/jobs/{jobId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void report(@PathVariable UUID jobId, @Valid @RequestBody Report report) {
		this.jobs.report(jobId, new JobReport(report.status(), report.failureCode(), report.error()));
	}

	@PostMapping("/internal/jobs/{jobId}/heartbeat")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void heartbeat(@PathVariable UUID jobId) {
		this.jobs.heartbeat(jobId);
	}

	@PostMapping("/internal/jobs/{jobId}/publish")
	PublishResult publish(@PathVariable UUID jobId, @Valid @RequestBody Publish request) {
		return this.publisher.publish(jobId, new PublishRequest(request.contentId(), request.version(),
				request.sourceHash(), request.profileVersion()));
	}

	record Report(@NotNull JobStatus status, FailureCode failureCode, @Size(max = 2000) String error) {
	}

	record Publish(@NotBlank String contentId, @Positive int version, @NotBlank String sourceHash,
			@NotBlank String profileVersion) {
	}

}
