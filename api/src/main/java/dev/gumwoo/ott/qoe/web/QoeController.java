package dev.gumwoo.ott.qoe.web;

import java.util.List;

import dev.gumwoo.ott.common.security.AuthenticatedUser;
import dev.gumwoo.ott.qoe.QoeReport;
import dev.gumwoo.ott.qoe.QoeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class QoeController {

	private final QoeService qoe;

	QoeController(QoeService qoe) {
		this.qoe = qoe;
	}

	@PostMapping("/api/qoe")
	ResponseEntity<Void> report(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody Batch batch) {
		this.qoe.record(user.id(), batch.events());
		return ResponseEntity.noContent().build();
	}

	// keepalive 요청 본문 64KiB 제한 안에 들어오게 개수를 묶는다 (app-design.md §9)
	record Batch(@NotEmpty @Size(max = 50) List<@Valid QoeReport> events) {
	}

}
